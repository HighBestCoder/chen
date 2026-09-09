package org.jumpserver.chen.framework.session.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.SqlExecutionStatsBuilder;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.ACLFilter;
import org.jumpserver.chen.framework.jms.CommandHandler;
import org.jumpserver.chen.framework.jms.ReplayHandler;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.jms.impl.CommandHandlerImpl;
import org.jumpserver.chen.framework.jms.impl.ReplayHandlerImpl;
import org.jumpserver.chen.framework.session.QueryAuditFunction;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.dialog.Dialog;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.session.exception.SessionException;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.List;

@Slf4j
public class JMSSession extends BaseSession {

    @Getter
    private final Common.Session jmsSession;

    private ACLFilter aclFilter;
    private CommandHandler commandHandler;
    private ReplayHandler replayHandler;
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;

    private final List<Common.CommandACL> commandACLs;
    private final long maxIdleTimeDelta;
    private final long expireTime;
    private volatile long lastActiveTime;
    private long startedAt;

    private int maxSessionTime;
    private Thread waitIdleTimeThread;
    @Setter
    private String gatewayId;

    private volatile boolean locked = false;

    private boolean canUpload = false;
    private boolean canDownload = false;

    private boolean canCopy = false;
    private boolean canPaste = false;



    public void lockSession(String creator) {
        String previous = SessionManager.getContextToken();
        SessionManager.setContext(this.getWebToken());
        this.locked = true;
        try {
            this.getController().showMessage(MessageLevel.ERROR, MessageUtils.get("msg.dialog.session_locked", creator));

        } finally { SessionManager.setContext(previous); }

    }

    public void unloadSession(String creator) {
        String previous = SessionManager.getContextToken();
        SessionManager.setContext(this.getWebToken());
        this.locked = false;
        try {
            this.getController().showMessage(MessageLevel.SUCCESS, MessageUtils.get("msg.dialog.session_unlocked", creator));

        } finally { SessionManager.setContext(previous); }

    }


    public JMSSession(Common.Session session,
                      Datasource datasource,
                      String remoteAddr,
                      ServiceGrpc.ServiceBlockingStub serviceBlockingStub,
                      ServiceOuterClass.TokenResponse tokenResp) {
        super(datasource, remoteAddr);
        this.jmsSession = session;
        this.serviceBlockingStub = serviceBlockingStub;
        this.commandACLs = tokenResp.getData().getFilterRulesList();
        this.expireTime = tokenResp.getData().getExpireInfo().getExpireAt();
        this.maxIdleTimeDelta = tokenResp.getData().getSetting().getMaxIdleTime();
        this.maxSessionTime = tokenResp.getData().getSetting().getMaxSessionTime();
        this.canUpload = tokenResp.getData().getPermission().getEnableUpload();
        this.canDownload = tokenResp.getData().getPermission().getEnableDownload();
        this.canCopy = tokenResp.getData().getPermission().getEnableCopy();
        this.canPaste = tokenResp.getData().getPermission().getEnablePaste();
    }

    public boolean allowsCredentialRenewal() {
        long now = System.currentTimeMillis();
        long start = this.jmsSession.getDateStart() * 1000;
        long last = this.lastActiveTime > 0 ? this.lastActiveTime : start;
        return !isClosed() && !locked && now < this.expireTime * 1000
                && now - start < (long) this.maxSessionTime * 3600000
                && now - last < this.maxIdleTimeDelta * 60000;
    }

    @Override
    public void recordCommand(String command) {
        CommandRecord commandRecord = new CommandRecord(command);
        this.recordCommand(commandRecord);
    }

    @Override
    public ACLResult checkACL(String command) {
        return checkACL(command, null);
    }

    public ACLResult checkACL(String command, Connection connection) {
        return checkACLBatch(command, java.util.List.of(), connection);
    }

    public boolean allowsExecution() { return isActive() && allowsCredentialRenewal(); }

    @Override
    public ACLResult checkACLBatch(String command, java.util.List<String> statements, Connection connection) {
        if (!allowsExecution()) return deniedExecution();
        var result = this.aclFilter.commandACLFilterBatch(command, statements, connection);
        return allowsExecution() ? result : deniedExecution();
    }

    private static ACLResult deniedExecution() {
        var result = new ACLResult();
        result.setRiskLevel(Common.RiskLevel.Reject);
        result.setRiskAction("session_unavailable");
        return result;
    }

    @Override
    public void recordCommand(CommandRecord commandRecord) {
        if (allowsExecution()) this.lastActiveTime = System.currentTimeMillis();
        this.commandHandler.recordCommand(commandRecord);
    }

    @Override
    public boolean canUpload() {
        return this.canUpload;
    }

    @Override
    public boolean canDownload() {
        return this.canDownload;
    }

    @Override
    public boolean canCopy() {
        return this.canCopy;
    }

    @Override
    public boolean canPaste() {
        return this.canPaste;
    }

    @Override
    public String getUsername() {
        return this.jmsSession.getUser();
    }

    public String getDatasourceName() {
        return this.jmsSession.getAsset();
    }

    @Override
    public synchronized void activeSession(PacketIO packetIO) {
        if (isClosed()) throw new IllegalStateException("Session is closed");
        this.commandHandler = new CommandHandlerImpl(this.jmsSession, this.serviceBlockingStub);
        this.replayHandler = new ReplayHandlerImpl(this.jmsSession, this.serviceBlockingStub);
        this.aclFilter = new ACLFilterImpl(this.jmsSession, this.serviceBlockingStub, this.commandACLs);
        this.replayHandler.init();
        super.activeSession(packetIO);
        this.startWaitIdleTime();
        this.recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType.AssetConnectSuccess, "");
    }


    private void recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType eventType, String reason) {
        var req = ServiceOuterClass.SessionLifecycleLogRequest.newBuilder()
                .setSessionId(this.jmsSession.getId())
                .setEvent(eventType)
                .setReason(reason)
                .build();
        var resp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).recordSessionLifecycleLog(req);
        if (!resp.getStatus().getOk()) {
            log.error("recordLifecycle error: {}", resp.getStatus().getErr());
        }
    }

    private void startWaitIdleTime() {
        this.startedAt = this.jmsSession.getDateStart() > 0
                ? this.jmsSession.getDateStart() * 1000 : System.currentTimeMillis();
        this.lastActiveTime = System.currentTimeMillis();
        this.waitIdleTimeThread = new Thread(() -> {
            while (this.isActive()) {
                try {
                    Thread.sleep(5000);
                    synchronized (this) {
                        long now = System.currentTimeMillis();
                        var expireTime = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss").format(this.expireTime * 1000);
                        if (now > this.expireTime * 1000) {
                            this.close("msg.error.perms_expired", "permission_expired",expireTime);
                            return;
                        }
                        if (now - this.lastActiveTime > this.maxIdleTimeDelta * 1000 * 60) {
                            this.close("msg.error.over_max_idle_time","idle_disconnect", this.maxIdleTimeDelta);
                            return;
                        }

                        if (now - this.startedAt > (long) this.maxSessionTime * 1000 * 60 * 60) {
                            this.close("msg.error.over_max_session_time", "max_session_timeout",this.maxSessionTime);
                            return;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        this.waitIdleTimeThread.setDaemon(true);
        this.waitIdleTimeThread.start();
    }

    @Override
    public void close() { closeInternal("connect_disconnect"); }

    private void closeInternal(String reason) {
        if (!beginClose()) return;
        if (waitIdleTimeThread != null && waitIdleTimeThread != Thread.currentThread()) waitIdleTimeThread.interrupt();
        try {
            cleanup("replay", () -> { if (replayHandler != null) replayHandler.release(); });
            cleanup("Core session", this::finishedJmsSession);
            cleanup("gateway", this::closeGateway);
            cleanup("lifecycle", () -> recordLifecycle(ServiceOuterClass.SessionLifecycleLogRequest.EventType.AssetConnectFinished, reason));
        } finally {
            closeResources();
        }
    }

    public void close(String message, String reason, Object... args) {
        String previous = SessionManager.getContextToken();
        SessionManager.setContext(this.getWebToken());
        try {
            if (isClosed()) return;
            if (getPacketIO() != null && isActive()) {
                getPacketIO().sendPacket("session_close", null);
                var dialog = new Dialog(MessageUtils.get("msg.dialog.title.session_finished"));
                dialog.setBody(MessageUtils.get(message, args));
                getController().showDialog(dialog);
            }
        } finally {
            try { closeInternal(reason); }
            finally { SessionManager.setContext(previous); }
        }
    }

    private void finishedJmsSession() {
        var req = ServiceOuterClass.SessionFinishRequest
                .newBuilder()
                .setId(this.jmsSession.getId())
                .setDateEnd(Instant.now().getEpochSecond())
                .build();
        var resp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).finishSession(req);
        if (!resp.getStatus().getOk()) {
            throw new SessionException(this.getUsername(), resp.getStatus().getErr());
        }
    }


    private void closeGateway() {
        if (this.gatewayId == null) {
            return;
        }

        var req = ServiceOuterClass.ForwardDeleteRequest
                .newBuilder()
                .setId(this.gatewayId)
                .build();
        var resp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).deleteForward(req);
        if (!resp.getStatus().getOk()) {
            log.error("close gateway error: {}", resp.getStatus().getErr());
        }
    }


    @Override
    public SQLQueryResult withAudit(String command, QueryAuditFunction queryAuditFunction) throws SQLException, CommandRejectException {
        synchronized (this) {
            if (!allowsExecution()) throw new CommandRejectException(MessageUtils.get("msg.error.session_unavailable"));
            this.lastActiveTime = System.currentTimeMillis();
        }

        CommandRecord commandRecord = new CommandRecord(command);

        try {
            this.replayHandler.writeInput(commandRecord.getInput());

            var result = queryAuditFunction.run();
            commandRecord.setOutput(result);

            commandRecord.applyACL(result.getAclResult());

            try {
                ExecutionStats stats = SqlExecutionStatsBuilder.fromSuccess(this.getDatasource(), command, result);
                commandRecord.setExecutionStats(stats);
            } catch (Throwable statsErr) {
                log.warn("withAudit: failed to build success ExecutionStats, continuing without it", statsErr);
            }

            this.replayHandler.writeOutput(result.getOutput());
            return result;

        } catch (SQLException e) {
            commandRecord.setError(e.getMessage());
            try {
                ExecutionStats stats = SqlExecutionStatsBuilder.fromFailure(this.getDatasource(), command, e);
                commandRecord.setExecutionStats(stats);
            } catch (Throwable statsErr) {
                log.warn("withAudit: failed to build failure ExecutionStats, continuing without it", statsErr);
            }
            this.replayHandler.writeOutput(e.getMessage());
            throw e;
        } finally {
            this.commandHandler.recordCommand(commandRecord);
        }
    }
}
