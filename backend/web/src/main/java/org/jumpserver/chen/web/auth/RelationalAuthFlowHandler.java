package org.jumpserver.chen.web.auth;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Set;

/**
 * task-07 slice A: per-protocol auth-flow decision for relational
 * databases (PostgreSQL / MySQL / SQL Server).
 *
 * <p>This class is a pure function of
 * ({@link ConnectionAuthSpec}, {@link AuthFlowDispatcher.Route}, dbType)
 * and produces a {@link Decision} that downstream code can act on
 * without re-parsing the auth context. Keeping the decision logic
 * isolated from {@code JmsSessionService} lets us unit-test it in
 * full and lets slice C ({@code SQL Server AccessToken}) and the
 * future v1 native flow plug in without touching the call site.</p>
 *
 * <p>Scope of slice A (this commit):</p>
 * <ul>
 *   <li>Recognise that {@code core_poc_token} legacy and v1 routes for
 *       PostgreSQL / MySQL collapse to the same wire behaviour
 *       (token-as-password) — only the version tag differs.</li>
 *   <li>Detect that v1 + SQL Server requires a true AccessToken
 *       (driver hand-off owned by slice C) and emit a clearly tagged
 *       decision the caller can guard on instead of silently
 *       degrading to plain-password.</li>
 *   <li>Default plain-password / legacy paths remain bit-compatible
 *       with the v3.10.17 baseline so existing POC deployments do not
 *       see a behaviour change.</li>
 * </ul>
 */
@Slf4j
public final class RelationalAuthFlowHandler {

    /** dbType strings that natively accept token-as-password. */
    private static final Set<String> TOKEN_AS_PASSWORD_DB_TYPES = Set.of(
            "postgresql",
            "mysql",
            "mariadb"
    );

    /** dbType strings that require a driver-level AccessToken hand-off. */
    private static final Set<String> ACCESS_TOKEN_DB_TYPES = Set.of(
            "sqlserver",
            "mssql"
    );

    /** dbType strings that authenticate via the MongoDB OIDC callback. */
    private static final Set<String> OIDC_DB_TYPES = Set.of(
            "mongodb"
    );

    private RelationalAuthFlowHandler() {
    }

    /**
     * Per-protocol auth flow decision.
     *
     * <p>Order matters: callers must read {@link #decision()} first; the
     * other accessors are diagnostic only.</p>
     *
     * @param decision        what the connection layer should do.
     * @param normalizedDbType lower-cased dbType the decision was made for.
     * @param routeName        normalised flow route ({@code legacy / v1 / v2 / unknown}).
     * @param reason           short, machine-stable reason for logging / audit.
     */
    public record Decision(
            Outcome decision,
            String normalizedDbType,
            String routeName,
            String reason
    ) {
        public boolean requiresAccessToken() {
            return decision == Outcome.V1_ACCESS_TOKEN_REQUIRED;
        }

        public boolean requiresOidcToken() {
            return decision == Outcome.V2_OIDC_TOKEN_REQUIRED;
        }

        public boolean isUnsupported() {
            return decision == Outcome.UNSUPPORTED;
        }
    }

    /** All possible outcomes; expand here when a new flow lands. */
    public enum Outcome {
        /** No Entra context. Use {@code account.secret} verbatim. */
        LEGACY_PASSWORD,
        /** Legacy + core_poc_token. core already injected the token; use it as password. */
        LEGACY_TOKEN_AS_PASSWORD,
        /** v1 + PG/MySQL. Use the token as password; record version tag. */
        V1_TOKEN_AS_PASSWORD,
        /** v1 + SQL Server. Driver-level AccessToken required (slice C). */
        V1_ACCESS_TOKEN_REQUIRED,
        /** v2 + MongoDB (Cosmos vCore). Driver-level OIDC callback required;
         *  password carries the Core-minted bearer token. */
        V2_OIDC_TOKEN_REQUIRED,
        /** Auth flow / dbType combination not yet handled. Caller should log. */
        UNSUPPORTED
    }

    public static Decision decide(
            ConnectionAuthSpec authSpec,
            AuthFlowDispatcher.Route route,
            String dbType
    ) {
        String normalizedDbType = normalize(dbType);
        String routeName = (route == null ? AuthFlowDispatcher.Route.UNKNOWN : route).name().toLowerCase(Locale.ROOT);

        if (authSpec == null) {
            return new Decision(Outcome.LEGACY_PASSWORD, normalizedDbType, routeName, "auth_spec_absent");
        }
        if (route == null) {
            return new Decision(Outcome.LEGACY_PASSWORD, normalizedDbType, routeName, "null_route");
        }

        if (route != AuthFlowDispatcher.Route.UNKNOWN && !authSpec.hasEntraToken()
                && ("password".equalsIgnoreCase(authSpec.authType()) || "direct_password".equalsIgnoreCase(authSpec.authSource()))) {
            return new Decision(Outcome.LEGACY_PASSWORD, normalizedDbType, routeName, "explicit_password_account");
        }
        switch (route) {
            case LEGACY -> {
                if (authSpec.hasEntraToken() && !TOKEN_AS_PASSWORD_DB_TYPES.contains(normalizedDbType)) {
                    return new Decision(Outcome.UNSUPPORTED, normalizedDbType, routeName, "legacy_token_not_supported");
                }
                if (authSpec.hasEntraToken()) {
                    return new Decision(Outcome.LEGACY_TOKEN_AS_PASSWORD, normalizedDbType, routeName, "legacy_core_poc_token");
                }
                return new Decision(Outcome.LEGACY_PASSWORD, normalizedDbType, routeName, "legacy_no_token");
            }
            case V1 -> {
                if (TOKEN_AS_PASSWORD_DB_TYPES.contains(normalizedDbType)) {
                    return new Decision(Outcome.V1_TOKEN_AS_PASSWORD, normalizedDbType, routeName, "v1_token_as_password");
                }
                if (ACCESS_TOKEN_DB_TYPES.contains(normalizedDbType)) {
                    return new Decision(Outcome.V1_ACCESS_TOKEN_REQUIRED, normalizedDbType, routeName, "v1_access_token_required");
                }
                return new Decision(Outcome.UNSUPPORTED, normalizedDbType, routeName, "v1_unsupported_db_type");
            }
            case V2 -> {
                // v2 is the Mongo Entra flow (task-08). Only MongoDB is
                // wired here; the token (carried in password) is handed to
                // the driver via the MONGODB-OIDC callback by the chen
                // MongoConnectionManager. Other dbTypes under v2 stay
                // UNSUPPORTED so the caller logs and falls back.
                if (OIDC_DB_TYPES.contains(normalizedDbType)) {
                    return new Decision(Outcome.V2_OIDC_TOKEN_REQUIRED, normalizedDbType, routeName, "v2_oidc_token_required");
                }
                return new Decision(Outcome.UNSUPPORTED, normalizedDbType, routeName, "v2_unsupported_db_type");
            }
            case UNKNOWN -> {
                return new Decision(Outcome.UNSUPPORTED, normalizedDbType, routeName, "unknown_route");
            }
        }
        return new Decision(Outcome.UNSUPPORTED, normalizedDbType, routeName, "unreachable");
    }

    private static String normalize(String dbType) {
        if (dbType == null) {
            return "";
        }
        return dbType.trim().toLowerCase(Locale.ROOT);
    }
}
