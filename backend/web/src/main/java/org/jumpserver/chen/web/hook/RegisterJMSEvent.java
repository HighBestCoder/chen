package org.jumpserver.chen.web.hook;

import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.web.config.MockConfig;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Slf4j
public class RegisterJMSEvent {

    @GrpcClient("wisp")
    private ServiceGrpc.ServiceBlockingStub serviceBlockingStub;
    @Autowired
    private MockConfig mockConfig;

    @PostConstruct
    public void clearZombieSession() {
        if (this.mockConfig.isEnable()) {
            return;
        }

        var currentDir = System.getProperty("user.dir");
        var filePath = Path.of(currentDir, "data/replay");

        log.info("Scan remain replay: {}", filePath);

        var req = ServiceOuterClass.RemainReplayRequest
                .newBuilder()
                .setReplayDir(filePath.toString())
                .build();
        var resp = this.serviceBlockingStub.scanRemainReplays(req);
        if (!resp.getStatus().getOk()) {
            log.error("Scan remain replay error: {}", resp.getStatus().getErr());
        } else {
            log.info("Scan remain replay success");
        }
    }


    @Async
    @PostConstruct
    public void startSessionKiller() {
        if (this.mockConfig.isEnable()) {
            return;
        }
        this.waitForKillSessionMessage();
    }


    private volatile StreamObserver<ServiceOuterClass.FinishedTaskRequest> requestObserver;
    private final java.util.concurrent.ScheduledExecutorService reconnect = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "chen-session-tasks");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopped;
    private java.util.concurrent.ScheduledFuture<?> retry;

    private synchronized void scheduleReconnect() {
        if (stopped || (retry != null && !retry.isDone())) return;
        retry = reconnect.schedule(() -> {
            synchronized (this) { retry = null; }
            waitForKillSessionMessage();
        }, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    protected StreamObserver<ServiceOuterClass.FinishedTaskRequest> openTaskStream(StreamObserver<ServiceOuterClass.TaskResponse> observer) {
        return ServiceGrpc.newStub(this.serviceBlockingStub.getChannel()).dispatchTask(observer);
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stopSessionTasks() {
        if (stopped) return;
        stopped = true;
        if (retry != null) retry.cancel(false);
        reconnect.shutdownNow();
        if (requestObserver != null) {
            try { requestObserver.onCompleted(); }
            catch (RuntimeException e) { log.warn("Cannot complete session task stream", e); }
        }
    }

    private synchronized void waitForKillSessionMessage() {
        if (stopped) return;
        try {
        requestObserver = openTaskStream(new StreamObserver<>() {
                    @Override
                    public void onNext(ServiceOuterClass.TaskResponse taskResponse) {
                        synchronized (RegisterJMSEvent.this) {
                        if (stopped) return;
                        JMSSession targetSession = null;
                        for (var session : SessionManager.getStore().values()) {
                            if (session instanceof JMSSession) {
                                if (((JMSSession) session).getJmsSession().getId().equals(taskResponse.getTask().getSessionId())) {
                                    targetSession = (JMSSession) session;
                                    break;
                                }
                            }
                        }
                        if (targetSession != null) {
                            switch (taskResponse.getTask().getAction()) {
                                case KillSession ->
                                        targetSession.close("msg.error.session_closed_by","admin_terminate", taskResponse.getTask().getTerminatedBy());

                                case LockSession -> targetSession.lockSession(taskResponse.getTask().getCreatedBy());
                                case UnlockSession ->
                                        targetSession.unloadSession(taskResponse.getTask().getCreatedBy());
                            }
                            var req = ServiceOuterClass.FinishedTaskRequest
                                    .newBuilder()
                                    .setTaskId(taskResponse.getTask().getId())
                                    .build();
                            requestObserver.onNext(req);
                        }
                        }

                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.error("Session task stream disconnected", throwable);
                        scheduleReconnect();

                    }

                    @Override
                    public void onCompleted() {
                        log.info("Session task stream completed; reconnecting");
                        scheduleReconnect();
                    }
                });
        } catch (RuntimeException e) {
            log.error("Cannot open session task stream", e);
            scheduleReconnect();
        }
    }
}
