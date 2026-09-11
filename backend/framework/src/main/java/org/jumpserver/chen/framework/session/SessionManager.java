package org.jumpserver.chen.framework.session;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
public class SessionManager {
    private final static SessionManager instance = new SessionManager();
    private final static ThreadLocal<String> token = new ThreadLocal<>();
    private final Map<String, Session> store = new ConcurrentHashMap<>();
    private final Map<String, Long> pending = new ConcurrentHashMap<>();
    private static final long PENDING_TIMEOUT_MS = 300_000;
    static {
        var reaper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "chen-pending-sessions");
            thread.setDaemon(true);
            return thread;
        });
        reaper.scheduleWithFixedDelay(() -> expirePendingSessions(System.currentTimeMillis()),
                30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    static void expirePendingSessions(long now) {
        instance.pending.forEach((key, createdAt) -> {
            if (now - createdAt < PENDING_TIMEOUT_MS) return;
            var session = instance.store.get(key);
            if (session == null) { instance.pending.remove(key); return; }
            synchronized (session) {
                instance.pending.remove(key);
                if (session.getPacketIO() == null && instance.store.remove(key, session)) {
                    try { session.close(); }
                    catch (Exception e) { log.error("Pending session cleanup failed", e); }
                }
            }
        });
    }

    public static void markActive(String token) {
        if (token != null) instance.pending.remove(token);
    }

    public static String registerSession(Session session) {
        String token = createToken();
        session.setWebToken(token);
        instance.store.put(token, session);
        instance.pending.put(token, System.currentTimeMillis());
        log.info("new session created, current session count {}", instance.getCurrentSessionCount());
        return token;
    }

    public static void unregisterSession(String token) {
        if (token == null) return;
        instance.store.remove(token);
        instance.pending.remove(token);
        log.info("session unregistered, current session count {}", instance.getCurrentSessionCount());
    }

    public int getCurrentSessionCount() {
        return instance.store.size();
    }

    public static void setContext(String token) {
        if (token == null) {
            SessionManager.token.remove();
        } else {
            SessionManager.token.set(token);
        }
    }

    public static String getContextToken() {
        return token.get();
    }

    public static SessionManager getInstance() {
        return instance;
    }

    public static Session getCurrentSession() {
        return getSession(token.get());
    }

    public static Map<String, Session> getStore() {
        return instance.store;
    }

    public static Session getSession(String token) {
        return token == null ? null : instance.store.get(token);
    }


    private static String createToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }


}
