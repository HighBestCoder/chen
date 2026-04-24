package org.jumpserver.chen.web.auth;

public final class AuthFlowDispatcher {
    private AuthFlowDispatcher() {
    }

    public static Route resolve(ConnectionAuthSpec authSpec) {
        if (authSpec == null || authSpec.isLegacy()) {
            return Route.LEGACY;
        }
        return switch (authSpec.normalizedFlowVersion()) {
            case "v1" -> Route.V1;
            case "v2" -> Route.V2;
            default -> Route.UNKNOWN;
        };
    }

    public enum Route {
        LEGACY,
        V1,
        V2,
        UNKNOWN,
    }
}