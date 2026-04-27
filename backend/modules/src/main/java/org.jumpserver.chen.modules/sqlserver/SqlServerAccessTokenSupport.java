package org.jumpserver.chen.modules.sqlserver;

import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

import java.util.Locale;
import java.util.Map;

/**
 * task-07 slice C: SQL Server Entra AccessToken bridge.
 *
 * <p>The chen-side {@code RelationalAuthFlowHandler} (slice A) records
 * its decision on every relational connection in
 * {@link DBConnectInfo#getOptions()}. For SQL Server v1 the decision is
 * {@code V1_ACCESS_TOKEN_REQUIRED}, signalling that
 * {@link DBConnectInfo#getPassword()} carries an Entra OAuth2 bearer
 * token rather than a password.</p>
 *
 * <p>The mssql-jdbc driver consumes the token through the
 * {@code accessToken} connection property; password (and, in practice,
 * user) must not be sent in that mode. This helper centralises the
 * detection and option-key naming so the connection manager only has
 * one place to read.</p>
 */
public final class SqlServerAccessTokenSupport {

    /**
     * Option key written by {@link RelationalAuthFlowHandler} on the
     * chen-web side; mirrors {@code Outcome.V1_ACCESS_TOKEN_REQUIRED}.
     */
    public static final String OPT_DECISION = "relationalAuthDecision";
    public static final String OPT_REQUIRES_ACCESS_TOKEN = "requiresAccessToken";
    public static final String DECISION_ACCESS_TOKEN_REQUIRED = "V1_ACCESS_TOKEN_REQUIRED";

    /** mssql-jdbc connection-property name for the bearer token. */
    public static final String JDBC_PROP_ACCESS_TOKEN = "accessToken";

    private SqlServerAccessTokenSupport() {
    }

    /**
     * Returns true when the chen-web layer asked us to authenticate via
     * an Entra AccessToken instead of password. We recognise either the
     * structured decision tag (preferred) or the legacy boolean flag
     * (kept for forward compatibility with mid-rollout deployments).
     */
    public static boolean isAccessTokenMode(DBConnectInfo connectInfo) {
        if (connectInfo == null) {
            return false;
        }
        Map<String, Object> options = connectInfo.getOptions();
        if (options == null || options.isEmpty()) {
            return false;
        }

        Object decision = options.get(OPT_DECISION);
        if (decision instanceof String s
                && DECISION_ACCESS_TOKEN_REQUIRED.equalsIgnoreCase(s.trim())) {
            return true;
        }

        Object flag = options.get(OPT_REQUIRES_ACCESS_TOKEN);
        if (flag instanceof Boolean b) {
            return b;
        }
        if (flag instanceof String fs) {
            return "true".equalsIgnoreCase(fs.trim()) || "1".equals(fs.trim());
        }
        return false;
    }

    /**
     * Extracts the Entra access token. The token is always carried in
     * {@link DBConnectInfo#getPassword()} (the entra-patch already put
     * it there); we accept an explicit option override only for tests.
     */
    public static String resolveAccessToken(DBConnectInfo connectInfo) {
        if (connectInfo == null) {
            return null;
        }
        Map<String, Object> options = connectInfo.getOptions();
        if (options != null) {
            Object overridden = options.get("accessToken");
            if (overridden instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        String password = connectInfo.getPassword();
        if (password == null || password.isBlank()) {
            return null;
        }
        return password;
    }

    /**
     * Best-effort dbType normalisation; mirrors the chen-web handler.
     */
    public static String normalizeDbType(String dbType) {
        if (dbType == null) {
            return "";
        }
        return dbType.trim().toLowerCase(Locale.ROOT);
    }
}
