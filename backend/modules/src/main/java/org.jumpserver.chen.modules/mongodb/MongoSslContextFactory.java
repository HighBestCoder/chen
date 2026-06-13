package org.jumpserver.chen.modules.mongodb;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.ssl.JKSGenerator;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Builds a javax.net.ssl.SSLContext for the Mongo driver from the same
 * option keys the relational engines use (useSSL / verifyServerCertificate
 * / caCert / clientCert / clientKey), so a Mongo asset's TLS config behaves
 * identically to PG/MySQL/SQLServer.
 *
 * Security contract:
 * - verifyServerCertificate=true (default unless explicitly false):
 *     - caCert present -> trust ONLY that CA;
 *     - caCert blank   -> JVM default trust store (public/corporate CA),
 *       matching the relational engines' blank-CA behavior;
 *     - hostname verification stays ON.
 * - verifyServerCertificate=false -> trust-all + hostname check disabled,
 *   matching the relational "allow invalid cert" path. Insecure by design,
 *   only reached when the admin explicitly allows invalid certs.
 */
final class MongoSslContextFactory {

    private MongoSslContextFactory() {
    }

    static boolean sslEnabled(Map<String, Object> options) {
        return Boolean.TRUE.equals(options.get("useSSL"));
    }

    static boolean verifyServerCertificate(Map<String, Object> options) {
        // Fail-closed: only an explicit false disables verification, so a
        // missing/mistyped option keeps certificate validation on.
        return !Boolean.FALSE.equals(options.get("verifyServerCertificate"));
    }

    static SSLContext build(Map<String, Object> options) {
        try {
            String caCert = (String) options.get("caCert");
            String clientCert = (String) options.get("clientCert");
            String clientKey = (String) options.get("clientKey");
            boolean verify = verifyServerCertificate(options);

            KeyManager[] keyManagers = buildKeyManagers(clientCert, clientKey);
            TrustManager[] trustManagers = verify
                    ? buildVerifyingTrustManagers(caCert)
                    : TRUST_ALL;

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, new SecureRandom());
            return context;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("failed to build Mongo SSL context: " + e.getMessage(), e);
        }
    }

    private static KeyManager[] buildKeyManagers(String clientCert, String clientKey) throws Exception {
        if (StringUtils.isBlank(clientCert) || StringUtils.isBlank(clientKey)) {
            return null;
        }
        JKSGenerator generator = new JKSGenerator();
        generator.setClientCert(clientCert);
        generator.setClientKey(clientKey);
        Path path = generator.generateClientJKS();
        try {
            KeyStore clientStore = KeyStore.getInstance("JKS");
            try (var in = Files.newInputStream(path)) {
                clientStore.load(in, JKSGenerator.JSK_PASS.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(clientStore, JKSGenerator.JSK_PASS.toCharArray());
            return kmf.getKeyManagers();
        } finally {
            deleteTempStore(path);
        }
    }

    private static TrustManager[] buildVerifyingTrustManagers(String caCert) throws Exception {
        if (StringUtils.isBlank(caCert)) {
            return null;
        }
        JKSGenerator generator = new JKSGenerator(caCert);
        Path path = generator.generateCaJKS();
        try {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (var in = Files.newInputStream(path)) {
                trustStore.load(in, JKSGenerator.JSK_PASS.toCharArray());
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            return tmf.getTrustManagers();
        } finally {
            deleteTempStore(path);
        }
    }

    // The JKS holds the client private key in plaintext on disk; delete it
    // (and its temp dir) immediately after loading into the in-memory store.
    private static void deleteTempStore(Path path) {
        try {
            Files.deleteIfExists(path);
            Path parent = path.getParent();
            if (parent != null) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException ignored) {
        }
    }

    private static final TrustManager[] TRUST_ALL = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };
}
