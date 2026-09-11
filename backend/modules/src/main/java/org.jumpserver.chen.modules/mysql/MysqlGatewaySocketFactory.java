package org.jumpserver.chen.modules.mysql;

import com.mysql.cj.protocol.StandardSocketFactory;
import com.mysql.cj.conf.PropertySet;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

/** Bounds the login handshake and preserves the logical TLS hostname through a gateway. */
public class MysqlGatewaySocketFactory extends StandardSocketFactory {
    @Override
    public <T extends Closeable> T connect(String host, int port, PropertySet properties, int loginTimeout) throws IOException {
        var gateway = properties.getStringProperty("chenGateway");
        if (gateway != null && gateway.getValue() != null) {
            URI forward = URI.create("tcp://" + gateway.getValue());
            host = forward.getHost();
            port = forward.getPort();
        }
        return super.connect(host, port, properties, loginTimeout > 0 ? loginTimeout : 10000);
    }
}
