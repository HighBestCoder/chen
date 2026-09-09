package org.jumpserver.chen.framework.datasource;

import java.time.Clock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One immutable credential snapshot and one in-flight refresh per GUI session. */
public final class SessionTokenProvider {
    public record Credential(String token, long expiresAt) {
        @Override public String toString() { return "Credential[redacted]"; }
    }
    private Credential credential;
    private final Supplier<Credential> refresh;
    private final BooleanSupplier authorized;
    private final Clock clock;
    private long retryAfter;

    public SessionTokenProvider(Credential initial, Supplier<Credential> refresh, BooleanSupplier authorized) {
        this(initial, refresh, authorized, Clock.systemUTC());
    }

    public SessionTokenProvider(Credential initial, Supplier<Credential> refresh, BooleanSupplier authorized, Clock clock) {
        this.credential = initial;
        this.refresh = refresh;
        this.authorized = authorized;
        this.clock = clock;
    }

    public synchronized Credential current() {
        checkSession();
        long now = clock.instant().getEpochSecond();
        if (credential.expiresAt() > now + 60) return credential;
        if (now < retryAfter) throw new IllegalStateException("Entra credential renewal temporarily unavailable; retry later");
        try {
            Credential next = refresh.get();
            checkSession();
            if (next == null || next.token() == null || next.token().isBlank()
                    || next.expiresAt() <= clock.instant().getEpochSecond() + 60)
                throw new IllegalStateException("Invalid renewed credential");
            credential = next;
            return next;
        } catch (RuntimeException failure) {
            retryAfter = clock.instant().getEpochSecond() + 5;
            // RPC errors may contain upstream response bodies. Never expose them.
            throw new IllegalStateException("Entra credential renewal failed; check session authorization and identity configuration");
        }
    }

    private void checkSession() {
        if (!authorized.getAsBoolean()) throw new IllegalStateException("Session no longer permits credential renewal");
    }
}
