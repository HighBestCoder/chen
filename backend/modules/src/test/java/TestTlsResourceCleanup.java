import org.jumpserver.chen.framework.ssl.JKSGenerator;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mysql.MysqlConnectionManager;
import org.jumpserver.chen.modules.postgresql.PostgresqlConnectionManager;
import org.jumpserver.chen.modules.sqlserver.SQLServerConnectionManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import com.alibaba.druid.DbType;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public class TestTlsResourceCleanup {
    static int failures;
    static void check(boolean condition) { if(!condition)failures++; }
    static Path workDir(JKSGenerator generator) throws Exception {
        Field f=JKSGenerator.class.getDeclaredField("workDir"); f.setAccessible(true); return (Path)f.get(generator);
    }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator keys=KeyPairGenerator.getInstance("RSA");keys.initialize(2048);var pair=keys.generateKeyPair();
        var name=new X500Name("CN=fixture.invalid");
        var holder=new JcaX509v3CertificateBuilder(name,BigInteger.ONE,new Date(0),new Date(System.currentTimeMillis()+86400000),name,pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()));
        String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(holder.getEncoded())+"\n-----END CERTIFICATE-----\n";
        String key="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";
        JKSGenerator generator=new JKSGenerator(pem,pem,key);Path store=generator.generateCaJKS();Path clientStore=generator.generateClientJKS();
        try { generator.destroy();generator.destroy(); } catch(RuntimeException e) { failures++; }
        check(!Files.exists(store));check(!Files.exists(clientStore));
        // Independent cleanup keeps the old-version test from leaving its fixture behind.
        Files.deleteIfExists(clientStore);Files.deleteIfExists(store);Files.deleteIfExists(store.getParent());
        JKSGenerator broken=new JKSGenerator("invalid fixture certificate");
        try { broken.generateCaJKS();failures++; } catch(RuntimeException expected) { }
        Path dir=workDir(broken);check(dir==null||!Files.exists(dir));
        if(dir!=null){Files.deleteIfExists(dir.resolve("ca.jks"));Files.deleteIfExists(dir);}
        for(DbType type: List.of(DbType.mysql,DbType.postgresql,DbType.sqlserver)){
            var info=new DBConnectInfo();info.setDbType(type.name());
            info.getOptions().putAll(Map.of("useSSL",true,"verifyServerCertificate",true,"caCert",pem));
            var ds=(Datasource)Proxy.newProxyInstance(TestTlsResourceCleanup.class.getClassLoader(),new Class<?>[]{Datasource.class},(p,m,a)->m.getName().equals("getDruidDbType")?type:null);
            BaseConnectionManager manager=switch(type){case mysql->new MysqlConnectionManager(info,ds);case postgresql->new PostgresqlConnectionManager(info,ds);default->new SQLServerConnectionManager(info,ds);};
            Method method=null;for(Class<?> c=manager.getClass();c!=null;c=c.getSuperclass())try{method=c.getDeclaredMethod("setSSLProps",Properties.class);break;}catch(NoSuchMethodException ignored){}
            method.setAccessible(true);Properties props=new Properties();method.invoke(manager,props);
            String path=type==DbType.mysql?props.getProperty("trustCertificateKeyStoreUrl").substring(5):type==DbType.postgresql?props.getProperty("sslrootcert"):props.getProperty("trustStore");
            Path file=Path.of(path);check(Files.exists(file));manager.close();check(!Files.exists(file));
            Files.deleteIfExists(file);if(type!=DbType.postgresql)Files.deleteIfExists(file.getParent());
        }
        if(failures>0)throw new AssertionError(failures+" TLS resource cleanup failures");
        System.out.println("OK: TLS stores survive until connection-manager close, then disappear; malformed input and repeated destroy leave no files");
    }
}
