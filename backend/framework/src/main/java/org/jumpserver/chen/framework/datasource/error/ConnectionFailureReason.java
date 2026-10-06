package org.jumpserver.chen.framework.datasource.error;

import javax.net.ssl.SSLException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Connection failure text for the user. Drivers often wrap the TLS cause: MySQL reports an expired server
 * certificate only as "Communications link failure", with the CertificateExpiredException further down the
 * cause chain, so the user could not tell a certificate problem from a network one (OBS-08).
 */
public final class ConnectionFailureReason {
    private ConnectionFailureReason() {
    }

    /** The exception's own message, followed by the TLS reason from its cause chain when the message lacks it. */
    public static String describe(Throwable error) {
        String message = String.valueOf(error.getMessage());
        String tls = tlsReason(error);
        return tls == null || message.contains(tls) ? message : message + " (TLS: " + tls + ")";
    }

    static String tlsReason(Throwable error) {
        Throwable handshake = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof CertificateExpiredException) return "server certificate expired: " + t.getMessage();
            if (t instanceof CertificateNotYetValidException) return "server certificate not yet valid: " + t.getMessage();
            if (handshake == null && (t instanceof CertPathValidatorException || t instanceof SSLException)) handshake = t;
        }
        return handshake == null ? null : handshake.getMessage();
    }
}
