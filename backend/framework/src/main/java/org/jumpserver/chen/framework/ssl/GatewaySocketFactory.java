package org.jumpserver.chen.framework.ssl;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.*;
import java.util.Properties;

/** Route TCP through the existing forward while JDBC retains the asset's TLS hostname. */
public class GatewaySocketFactory extends SocketFactory {
    private final InetSocketAddress forward;
    public GatewaySocketFactory(Properties properties) {
        this(properties.getProperty("chenGateway"));
    }
    public GatewaySocketFactory(String endpoint) {
        URI uri = URI.create("tcp://" + endpoint);
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid gateway endpoint");
        forward = new InetSocketAddress(uri.getHost(), uri.getPort());
    }
    @Override public Socket createSocket() {
        return new Socket() {
            @Override public void connect(SocketAddress endpoint, int timeout) throws IOException { super.connect(forward, timeout); }
            @Override public void connect(SocketAddress endpoint) throws IOException { connect(endpoint, 5000); }
        };
    }
    private Socket connected(InetAddress local, int localPort) throws IOException {
        Socket socket = createSocket();
        try { if (local != null) socket.bind(new InetSocketAddress(local,localPort)); socket.connect(forward,5000); return socket; }
        catch (IOException e) { socket.close(); throw e; }
    }
    @Override public Socket createSocket(String host,int port) throws IOException { return connected(null,0); }
    @Override public Socket createSocket(InetAddress host,int port) throws IOException { return connected(null,0); }
    @Override public Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException { return connected(local,localPort); }
    @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException { return connected(local,localPort); }
}
