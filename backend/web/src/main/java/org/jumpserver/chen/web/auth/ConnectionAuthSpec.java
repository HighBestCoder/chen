package org.jumpserver.chen.web.auth;

import java.util.Locale;
import java.util.Map;

public record ConnectionAuthSpec(
        String authType,
        String authSource,
        String authFlowVersion,
        String scope
) {
    public static final String AUTH_TYPE_KEY = "auth_type";
    public static final String AUTH_SOURCE_KEY = "auth_source";
    public static final String AUTH_FLOW_VERSION_KEY = "auth_flow_version";
    public static final String SCOPE_KEY = "scope";

    public static ConnectionAuthSpec fromSettings(Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) {
            return new ConnectionAuthSpec("", "", "", "");
        }
        return new ConnectionAuthSpec(
                normalize(settings.get(AUTH_TYPE_KEY)),
                normalize(settings.get(AUTH_SOURCE_KEY)),
                normalize(settings.get(AUTH_FLOW_VERSION_KEY)),
                normalize(settings.get(SCOPE_KEY))
        );
    }

    public boolean hasAuthContext() {
        return !(authType.isEmpty() && authSource.isEmpty() && authFlowVersion.isEmpty() && scope.isEmpty());
    }

    public boolean isLegacy() {
        return authFlowVersion.isEmpty();
    }

    public boolean isCorePocToken() {
        return "core_poc_token".equals(authSource.toLowerCase(Locale.ROOT));
    }

    /**
     * True when Core replaced {@code account.secret} with an Entra bearer
     * token before handing the connection to chen. Core stamps either
     * {@code auth_source=core_poc_token} or an {@code entra_*} auth type
     * ({@code entra_sp} / {@code entra_cert} / {@code entra_mi}); a
     * plain-password asset carries neither, so it is never re-routed.
     */
    public boolean hasEntraToken() {
        return isCorePocToken() || authType.toLowerCase(Locale.ROOT).startsWith("entra_");
    }

    public String normalizedFlowVersion() {
        if (isLegacy()) {
            return "legacy";
        }
        return authFlowVersion.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}