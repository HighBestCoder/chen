package org.jumpserver.chen.framework.ws;

import com.alibaba.fastjson.JSON;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.console.DataViewConsole;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ConsoleWebSocketHandler extends TextWebSocketHandler {
    private final ExecutorService executor = Executors.newFixedThreadPool(10, runnable -> {
        Thread thread = new Thread(runnable, "chen-console");
        thread.setDaemon(true);
        return thread;
    });
    private static final class Queue {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean running;
    }
    private final ConcurrentHashMap<String, Queue> queues = new ConcurrentHashMap<>();

    // Database context belongs to a Session's datasource, so serialize all its
    // consoles, not just each socket. Dialog responses use the session channel.
    private void enqueue(String token, Runnable task) {
        queues.compute(token, (key, current) -> {
            Queue queue = current == null ? new Queue() : current;
            synchronized (queue) {
                if (queue.tasks.size() >= 256) throw new IllegalStateException("Too many pending console messages");
                queue.tasks.add(task);
                if (!queue.running) {
                    queue.running = true;
                    executor.execute(() -> drain(token, queue));
                }
            }
            return queue;
        });
    }

    private void drain(String token, Queue queue) {
        while (true) {
            Runnable task;
            synchronized (queue) {
                task = queue.tasks.poll();
                if (task == null) { queue.running = false; break; }
            }
            try { task.run(); }
            catch (Exception e) { log.error("Console task failed", e); }
        }
        queues.computeIfPresent(token, (key, current) -> {
            synchronized (current) {
                return current == queue && !current.running && current.tasks.isEmpty() ? null : current;
            }
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        if (socket instanceof org.springframework.web.socket.adapter.NativeWebSocketSession ns) {
            var nativeSession = ns.getNativeSession(jakarta.websocket.Session.class);
            if (nativeSession != null) nativeSession.getUserProperties().put(
                    "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 90_000L);
        }
    }

    @Override
    public void handleMessage(WebSocketSession socket, WebSocketMessage<?> message) throws Exception {
        String token = (String) socket.getAttributes().get("token");
        var session = SessionManager.getSession(token);
        if (session == null || !session.isActive()) {
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        Packet packet;
        boolean cancel;
        try {
            packet = JSON.parseObject(message.getPayload().toString(), Packet.class);
            if (packet == null || packet.getType() == null) throw new IllegalArgumentException("Missing packet type");
            cancel = "query_console_action".equals(packet.getType()) && packet.getData() != null
                && "cancel".equals(JSON.parseObject(JSON.toJSONString(packet.getData())).getString("action"));
        } catch (RuntimeException e) {
            socket.close(CloseStatus.BAD_DATA);
            return;
        }

        if (cancel) {
            // Cancellation must not wait behind the query it needs to stop.
            dispatch(socket, token, packet, true);
        } else {
            try { enqueue(token, () -> dispatch(socket, token, packet, false)); }
            catch (RuntimeException e) { socket.close(CloseStatus.POLICY_VIOLATION); }
        }
    }

    private void dispatch(WebSocketSession socket, String token, Packet packet, boolean cancel) {
        SessionManager.setContext(token);
        try {
            var session = SessionManager.getCurrentSession();
            if (!socket.isOpen() || session == null || !session.isActive()) return;
            if (Packet.TYPE_CONNECT.equals(packet.getType())) {
                onConnectPacket(socket, packet);
            } else {
                var console = session.getConsoles().get(socket.getId());
                if (console != null) {
                    if (!cancel) session.getDatasource().getConnectionManager().setDatabaseContext(
                            TreeUtils.getValue(console.getNodeKey(), "database"));
                    console.handle(packet);
                }
            }
        } catch (Exception e) {
            log.error("Console message failed", e);
        } finally {
            SessionManager.setContext(null);
        }
    }

    private void onConnectPacket(WebSocketSession socket, Packet packet) {
        Connect connect = JSON.parseObject(JSON.toJSONString(packet.getData()), Connect.class);
        var session = SessionManager.getCurrentSession();
        Console console;
        synchronized (socket) {
            if (!socket.isOpen() || session.getConsoles().containsKey(socket.getId())) return;
            if (Connect.CONSOLE_TYPE_QUERY.equals(connect.getType())) {
                console = session.getDatasource().createQueryConsole(socket, connect.getNodeKey());
            } else if (Connect.CONSOLE_TYPE_DATA_VIEW.equals(connect.getType())) {
                console = new DataViewConsole(session.getDatasource(), socket, connect.getNodeKey());
            } else {
                throw new IllegalArgumentException("Unknown console type");
            }
            session.getConsoles().put(socket.getId(), console);
        }
        try {
            session.getDatasource().getConnectionManager().setDatabaseContext(TreeUtils.getValue(console.getNodeKey(), "database"));
            console.onInit(connect);
        } catch (RuntimeException e) {
            session.getConsoles().remove(socket.getId(), console);
            console.close();
            throw e;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        SessionManager.setContext((String) socket.getAttributes().get("token"));
        try {
            var session = SessionManager.getCurrentSession();
            if (session == null) return;
            Console console;
            synchronized (socket) { console = session.getConsoles().remove(socket.getId()); }
            if (console != null) console.close();
        } finally {
            SessionManager.setContext(null);
        }
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
