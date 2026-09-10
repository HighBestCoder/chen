package org.jumpserver.chen.framework.utils;

import org.jumpserver.chen.framework.session.SessionManager;

import java.util.concurrent.*;

public class ThreadUtils {

    public static class SessionCtxRunnable implements Runnable {
        private final Runnable runnable;
        private final String token;

        public SessionCtxRunnable(Runnable runnable, String sessionToken) {
            this.runnable = runnable;
            this.token = sessionToken;
        }

        @Override
        public void run() {
            String previous = SessionManager.getContextToken();
            SessionManager.setContext(token);
            try { runnable.run(); }
            finally { SessionManager.setContext(previous); }
        }
    }


    public static void runWithTimeout(SessionCtxRunnable runnable, int timeout) {
        if (timeout < 0) {
            runnable.run();
            return;
        }
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<?> future = null;
        try {
            future = executorService.submit(runnable);
            future.get(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        } finally {
            if (future != null && !future.isDone()) future.cancel(true);
            executorService.shutdownNow();
        }
    }
}
