import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.datasource.error.ConnectionFailureReason;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import javax.net.ssl.SSLHandshakeException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.sql.SQLException;

/** RUN-261002 OBS-08 (TLS reason behind a wrapped driver error) and OBS-11 (out-of-int display limit). */
public class TestObsMessages {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        // OBS-08: MySQL wraps an expired server certificate as "Communications link failure".
        var expired = new CertificateExpiredException("NotAfter: Sat Jan 04 00:00:00 UTC 2020");
        var handshake = new SSLHandshakeException("PKIX path validation failed");
        handshake.initCause(new CertificateException("validity check failed", expired));
        var wrapped = new SQLException("Communications link failure\n\nThe last packet sent successfully to the server was 0 ms ago.",
                "08S01", handshake);
        String text = ConnectionFailureReason.describe(wrapped);
        require(text.startsWith("Communications link failure"), "original driver text kept: " + text);
        require(text.contains("(TLS: server certificate expired: NotAfter: Sat Jan 04 00:00:00 UTC 2020)"), "expiry reason added: " + text);

        var plainHandshake = new SQLException("connect failed", new SSLHandshakeException("No subject alternative names present"));
        require(ConnectionFailureReason.describe(plainHandshake).endsWith("(TLS: No subject alternative names present)"),
                "handshake reason added: " + ConnectionFailureReason.describe(plainHandshake));

        var alreadySaid = new SQLException("PKIX path validation failed", new SSLHandshakeException("PKIX path validation failed"));
        require(ConnectionFailureReason.describe(alreadySaid).equals("PKIX path validation failed"), "no duplicate when message already has it");
        var network = new SQLException("Connection refused", new java.net.ConnectException("Connection refused"));
        require(ConnectionFailureReason.describe(network).equals("Connection refused"), "non-TLS failure unchanged");

        // OBS-11: 2147483648 arrives as Long and used to die on an (int) cast with no reply.
        PacketIO packets = new PacketIO(null) { @Override public void sendPacket(String type, Object data) { } };
        DataView view = new DataView("limit", packets, new Logger(packets));
        for (Object bad : new Object[]{2147483648L, -2147483649L, 100.5, "100", new java.math.BigInteger("99999999999999999999"), null}) {
            DataViewAction action = new DataViewAction();
            action.setAction(DataViewAction.ACTION_CHANGE_LIMIT);
            action.setData(bad);
            try {
                view.doAction(action);
                throw new AssertionError("limit " + bad + " accepted");
            } catch (SQLException expected) {
                require(expected.getMessage().equals("Display limit exceeds the configured maximum"), "same message for " + bad);
            }
        }
        System.out.println("OK: TLS reason behind wrapped driver errors; out-of-int and non-integer limits rejected with the standard message");
    }
}
