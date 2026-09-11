package org.jumpserver.chen.modules.mongodb;

import java.util.Locale;

/** Endpoint routing only: a suffix is not proof of product/region availability or authorization. */
public final class MongoAzureEndpoint {
    private MongoAzureEndpoint() { }

    public static boolean isDocumentDb(String host) {
        return matches(host, ".mongocluster.cosmos.azure.com") || matches(host, ".mongocluster.cosmos.azure.cn");
    }

    public static boolean isAzureMongo(String host) {
        return isDocumentDb(host) || matches(host, ".mongo.cosmos.azure.com") || matches(host, ".mongo.cosmos.azure.cn");
    }

    private static boolean matches(String host, String suffix) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized.endsWith(suffix) && normalized.length() > suffix.length();
    }
}
