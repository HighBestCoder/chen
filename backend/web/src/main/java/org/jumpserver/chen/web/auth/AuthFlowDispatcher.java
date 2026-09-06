package org.jumpserver.chen.web.auth;

import java.util.Locale;

public final class AuthFlowDispatcher {
    private AuthFlowDispatcher() {
    }

    /**
     * Picks the auth flow for one connection.
     *
     * <p>When the asset declares an {@code auth_flow_version} we honour it
     * verbatim. When it does not — the default, since the field is an
     * asset-only admin override that account-level Entra config cannot even
     * set — we infer the flow from the dbType, but only for connections
     * where Core actually injected an Entra bearer token.</p>
     *
     * <p>The inference is required, not cosmetic: SQL Server needs the
     * driver-level {@code accessToken} hand-off and MongoDB needs the
     * MONGODB-OIDC callback, and both bridges key off the decision that
     * this route produces. Falling back to {@link Route#LEGACY} instead
     * sends the ~1-2 KB JWT as a plain password, which mssql-jdbc rejects
     * outright (128-char cap on the password property) and Mongo rejects as
     * a bad SCRAM credential.</p>
     *
     * <p>PostgreSQL / MySQL / MariaDB stay on {@link Route#LEGACY}: their
     * token-as-password wire behaviour is identical either way, so there is
     * nothing to gain from re-routing a path that already works.</p>
     */
    public static Route resolve(ConnectionAuthSpec authSpec, String dbType) {
        if (authSpec == null) {
            return Route.LEGACY;
        }
        if (!authSpec.isLegacy()) {
            return switch (authSpec.normalizedFlowVersion()) {
                case "legacy" -> Route.LEGACY;
                case "v1" -> Route.V1;
                case "v2" -> Route.V2;
                default -> Route.UNKNOWN;
            };
        }
        if (!authSpec.hasEntraToken()) {
            return Route.LEGACY;
        }
        return switch (normalizeDbType(dbType)) {
            case "sqlserver", "mssql" -> Route.V1;
            case "mongodb" -> Route.V2;
            default -> Route.LEGACY;
        };
    }

    /** {@code declared} when the asset pinned a version, {@code auto} otherwise. */
    public static String routeSource(ConnectionAuthSpec authSpec) {
        return (authSpec == null || authSpec.isLegacy()) ? "auto" : "declared";
    }

    private static String normalizeDbType(String dbType) {
        if (dbType == null) {
            return "";
        }
        return dbType.trim().toLowerCase(Locale.ROOT);
    }

    public enum Route {
        LEGACY,
        V1,
        V2,
        UNKNOWN,
    }
}
