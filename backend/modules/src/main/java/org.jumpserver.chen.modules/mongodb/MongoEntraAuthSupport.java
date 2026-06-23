package org.jumpserver.chen.modules.mongodb;

import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;

import java.util.Map;

/**
 * task-08: MongoDB (Cosmos vCore) Entra OIDC bridge.
 *
 * <p>The chen-web {@code RelationalAuthFlowHandler} records its decision
 * for every connection in {@link DBConnectInfo#getOptions()}. For a Mongo
 * Entra asset (auth_flow_version=v2) the decision is
 * {@code V2_OIDC_TOKEN_REQUIRED}, signalling that
 * {@link DBConnectInfo#getPassword()} carries a Core-minted Entra OAuth2
 * bearer token rather than a SCRAM password.</p>
 *
 * <p>The mongodb-driver-sync driver consumes that token through the
 * MONGODB-OIDC mechanism callback; user / password must not appear in the
 * connection URI in that mode. This helper centralises the detection and
 * option-key naming so {@link MongoConnectionManager} has a single place
 * to read. It deliberately mirrors
 * {@code sqlserver.SqlServerAccessTokenSupport} — chen only RELAYS the
 * Core-provided token and never contacts Azure itself.</p>
 */
public final class MongoEntraAuthSupport {

    /**
     * Option key written by {@code RelationalAuthFlowHandler} on the
     * chen-web side; mirrors {@code Outcome.V2_OIDC_TOKEN_REQUIRED}.
     */
    public static final String OPT_DECISION = "relationalAuthDecision";
    public static final String DECISION_OIDC_TOKEN_REQUIRED = "V2_OIDC_TOKEN_REQUIRED";

    private MongoEntraAuthSupport() {
    }

    /**
     * Returns true when the chen-web layer asked us to authenticate via
     * the Mongo OIDC callback instead of SCRAM password.
     */
    public static boolean isOidcMode(DBConnectInfo connectInfo) {
        if (connectInfo == null) {
            return false;
        }
        Map<String, Object> options = connectInfo.getOptions();
        if (options == null || options.isEmpty()) {
            return false;
        }

        Object decision = options.get(OPT_DECISION);
        return decision instanceof String s
                && DECISION_OIDC_TOKEN_REQUIRED.equalsIgnoreCase(s.trim());
    }

    /**
     * Extracts the Entra access token. The token is always carried in
     * {@link DBConnectInfo#getPassword()} (the entra-patch already put it
     * there); we accept an explicit option override only for tests.
     */
    public static String resolveToken(DBConnectInfo connectInfo) {
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
}
