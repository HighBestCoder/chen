package org.jumpserver.chen.framework.ssl;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.Properties;

/** pgJDBC TLS factory: system CAs when absent, explicit CA bundle and optional client identity otherwise. */
public class PemSslSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    public PemSslSocketFactory(Properties properties) throws Exception {
        TrustManager[] trust = null; KeyManager[] keys = null;
        String ca = properties.getProperty("sslrootcert");
        if (ca != null) {
            KeyStore store = KeyStore.getInstance("JKS"); store.load(null,null);
            try (var input=Files.newInputStream(Path.of(ca))) {
                int index=0;
                for (var cert:CertificateFactory.getInstance("X.509").generateCertificates(input)) store.setCertificateEntry("ca-"+index++,cert);
                if(index==0)throw new IllegalArgumentException("Empty CA bundle");
            }
            var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(store);trust=factory.getTrustManagers();
        }
        if ("true".equals(properties.getProperty("chenTrustAll"))) trust = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] certificates,String auth) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] certificates,String auth) {}
            public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
        }};
        String client=properties.getProperty("chenClientKeyStore");
        if(client!=null) {
            KeyStore store=KeyStore.getInstance("JKS");
            try(var input=Files.newInputStream(Path.of(client))){store.load(input,JKSGenerator.JSK_PASS.toCharArray());}
            var factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(store,JKSGenerator.JSK_PASS.toCharArray());keys=factory.getKeyManagers();
        }
        javax.net.ssl.SSLContext context=javax.net.ssl.SSLContext.getInstance("TLS");context.init(keys,trust,null);delegate=context.getSocketFactory();
    }
    @Override public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
    @Override public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
    @Override public java.net.Socket createSocket(java.net.Socket socket,String host,int port,boolean close) throws IOException{return delegate.createSocket(socket,host,port,close);}
    @Override public java.net.Socket createSocket(String host,int port) throws IOException{return delegate.createSocket(host,port);}
    @Override public java.net.Socket createSocket(InetAddress host,int port) throws IOException{return delegate.createSocket(host,port);}
    @Override public java.net.Socket createSocket(String host,int port,InetAddress local,int localPort) throws IOException{return delegate.createSocket(host,port,local,localPort);}
    @Override public java.net.Socket createSocket(InetAddress host,int port,InetAddress local,int localPort) throws IOException{return delegate.createSocket(host,port,local,localPort);}
}
