import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.driver.DriverClassLoader;
import org.jumpserver.chen.modules.postgresql.PostgresqlConnectionManager;
import org.jumpserver.chen.modules.mysql.MysqlConnectionManager;
import org.jumpserver.chen.modules.sqlserver.SQLServerConnectionManager;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.net.*;
import java.sql.*;

/** Real drivers/servers; run only through scripts/test-s02b-integration.sh. */
public class TestConnectionTlsIntegration {
    static Path fixture = Path.of("/fixtures");
    static int passed;
    static final String[] TYPES = {"postgresql", "mysql", "sqlserver", "mongodb"};
    static String pem(String name) throws Exception { return Files.readString(fixture.resolve(name)); }
    static DBConnectInfo info(String type) throws Exception {
        DBConnectInfo info = new DBConnectInfo(); info.setDbType(type);
        info.setHost(switch(type){case "postgresql" -> "pg.fixture";case "sqlserver" -> "sql.fixture";case "mongodb" -> "mongo.fixture";default -> "mysql.fixture";});
        info.setPort(switch(type){case "postgresql" -> 5432;case "sqlserver" -> 1433;case "mongodb" -> 27017;default -> 3306;});
        info.setDb(type.equals("mongodb") ? "admin" : type.equals("sqlserver") ? "master" : "fixture");
        info.setUser(type.equals("sqlserver") ? "sa" : "fixture");
        info.setPassword(type.equals("mongodb") ? "Fixture + /?@Pass9" : "FixturePass9!");
        info.getOptions().put("useSSL", true);
        info.getOptions().put("caCert", pem("ca.crt"));
        if(type.equals("postgresql") || type.equals("mysql")) {
            info.getOptions().put("clientCert", pem("client.crt") + pem("ca.crt"));
            info.getOptions().put("clientKey", pem("client.key"));
        }
        return info;
    }
    static ConnectionManager manager(DBConnectInfo info) {
        Datasource ds = (Datasource) Proxy.newProxyInstance(Datasource.class.getClassLoader(), new Class[]{Datasource.class},
                (p,m,a) -> m.getName().equals("getDruidDbType") ? DbType.of(info.getDbType()) : null);
        return switch(info.getDbType()) {
            case "postgresql" -> new PostgresqlConnectionManager(info, ds);
            case "mysql" -> new MysqlConnectionManager(info, ds);
            case "sqlserver" -> new SQLServerConnectionManager(info, ds);
            default -> new MongoConnectionManager(info, ds);
        };
    }
    static void check(String label, DBConnectInfo info, boolean success) throws Exception {
        ConnectionManager manager = manager(info);
        Exception failure = null;
        try {
            manager.ping();
            if(manager instanceof BaseConnectionManager) {
                try(var connection = manager.getConnection();var stmt = connection.createStatement();var result = stmt.executeQuery("SELECT 1")) {
                    if(!result.next() || result.getInt(1)!=1) throw new AssertionError("wrong query result");
                }
            }
        } catch(Exception e) { failure=e; }
        finally { manager.close(); }
        if(success && failure!=null) throw new AssertionError(info.getDbType()+" "+label+" unexpectedly failed", failure);
        if(!success && failure==null) throw new AssertionError(info.getDbType()+" "+label+" unexpectedly succeeded");
        if(!success && label.contains("wrong") && !hasTlsCause(failure)) throw new AssertionError("negative case did not fail for TLS: "+label, failure);
        System.out.println("PASS "+info.getDbType()+" "+label);passed++;
    }
    static boolean hasTlsCause(Throwable e) {
        for(;e!=null;e=e.getCause()) {
            if(e instanceof javax.net.ssl.SSLException || e instanceof java.security.cert.CertificateException || e instanceof java.security.cert.CertPathValidatorException) return true;
            String message=String.valueOf(e.getMessage()).toLowerCase();
            if(message.contains("certificate") || message.contains("ssl") || message.contains("pkix") || message.contains("hostname")) return true;
        }
        return false;
    }
    static void register(String type,String jar) throws Exception {
        var path=Path.of(jar);
        org.jumpserver.chen.framework.driver.DriverManager.registerDriver(type,new DriverClassLoader(path.getFileName().toString(),path.toUri().toURL()));
    }
    static void specialDatabase(String type) throws Exception {
        DBConnectInfo info=info(type); ConnectionManager manager=manager(info);
        String name = type.equals("sqlserver") ? "space;db}x" : "space?db";
        try {
            manager.ping();
            try(var connection=manager.getConnection();var stmt=connection.createStatement()) {
                String quoted=type.equals("mysql") ? "`"+name+"`" : type.equals("sqlserver") ? "["+name+"]" : "\""+name+"\"";
                stmt.execute("CREATE DATABASE "+quoted);
            }
            manager.setDatabaseContext(name);
            try(var connection=manager.getConnection();var stmt=connection.createStatement();var result=stmt.executeQuery(
                    type.equals("postgresql") ? "SELECT current_database()" : type.equals("mysql") ? "SELECT DATABASE()" : "SELECT DB_NAME()")) {
                if(!result.next() || !name.equals(result.getString(1))) throw new AssertionError("database was parsed as URL options");
            }
            System.out.println("PASS "+type+" special database name and pool context");passed++;
        } finally {manager.close();}
    }
    static String quote(String type,String name) {
        String end=type.equals("mysql")?"`":type.equals("sqlserver")?"]":"\"";
        return (type.equals("sqlserver")?"[":end)+name.replace(end,end+end)+end;
    }
    static void resourceNames(String type) throws Exception {
        var manager=manager(info(type));
        String schema=type.equals("postgresql")?"public":type.equals("mysql")?"fixture":"dbo";
        String name="probe'`\"]$?\\;--";
        String qualified=quote(type,schema)+"."+quote(type,name);
        try {
            manager.ping();
            try(var connection=manager.getConnection();var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE "+qualified+" (id INT)");
                statement.execute("INSERT INTO "+qualified+" VALUES (7)");
            }
            var actuator=manager.getSqlActuator();
            var plan=actuator.createPlan(schema,name,null);
            var rows=plan.execute();
            if(rows.getData().size()!=1 || ((Number)rows.getData().get(0).get(0)).intValue()!=7)
                throw new AssertionError(type+" quoted preview selected wrong table");
            var params=new org.jumpserver.chen.framework.datasource.sql.SQLQueryParams();params.setLimit(1);
            var paged=actuator.createPlan(schema,name,params).execute();
            if(paged.getData().size()!=1)throw new AssertionError(type+" paged special identifier failed");
            var query=org.jumpserver.chen.framework.datasource.sql.SQL.bound(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",schema,name);
            var tables=actuator.getObjects(query,org.jumpserver.chen.framework.datasource.entity.resource.Table.class,Map.of("name",1));
            if(tables.size()!=1 || !name.equals(tables.get(0).getName()))throw new AssertionError(type+" bound metadata lost special name");
            query=org.jumpserver.chen.framework.datasource.sql.SQL.bound(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?", "x' OR 1=1 --");
            if(!actuator.getObjects(query,org.jumpserver.chen.framework.datasource.entity.resource.Table.class,Map.of("name",1)).isEmpty())
                throw new AssertionError(type+" metadata parameter changed predicate");
            var result=actuator.execute(org.jumpserver.chen.framework.datasource.sql.SQL.bound(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",schema,name));
            if(result.getData().size()!=1 || !name.equals(result.getData().get(0).get(0)))throw new AssertionError(type+" property binding failed");
            System.out.println("PASS "+type+" special resource identifiers, bound metadata/property and injection rejection");passed++;
        } finally {manager.close();}
    }
    static void renewedPhysicalConnection(String type) throws Exception {
        var info=info(type);
        var now=new java.util.concurrent.atomic.AtomicLong(java.time.Instant.now().getEpochSecond());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        java.time.Clock clock=new java.time.Clock() {
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            public java.time.Instant instant(){return java.time.Instant.ofEpochSecond(now.get());}
        };
        String fresh="RenewedFixturePass9!";
        info.getOptions().put("token_expires_at",now.get()+3600);
        info.setTokenProvider(new SessionTokenProvider(new SessionTokenProvider.Credential(info.getPassword(),now.get()+3600),
                ()->{calls.incrementAndGet();return new SessionTokenProvider.Credential(fresh,now.get()+3600);},()->true,clock));
        var manager=(BaseConnectionManager)manager(info);
        String alter=type.equals("postgresql")?"ALTER USER fixture WITH PASSWORD '%s'":type.equals("mysql")?
                "ALTER USER 'fixture'@'%%' IDENTIFIED BY '%s'":"ALTER LOGIN sa WITH PASSWORD = '%s'";
        try {
            manager.ping();
            try(var held=manager.getConnection();var stmt=held.createStatement()) {
                stmt.execute(String.format(alter,fresh));
                held.setAutoCommit(false);
                now.addAndGet(3601);
                try(var physical=manager.getPhysicalConnection();var query=physical.createStatement();var rows=query.executeQuery("SELECT 1")) {
                    if(!rows.next() || rows.getInt(1)!=1 || calls.get()!=1)throw new AssertionError("renewal did not reach real driver");
                }
                // The existing transaction/connection stays usable. No automatic SQL replay.
                try(var rows=stmt.executeQuery("SELECT 1")){if(!rows.next())throw new AssertionError("held connection replaced");}
                held.rollback();held.setAutoCommit(true);
                stmt.execute(String.format(alter,info.getPassword()));
            }
            System.out.println("PASS "+type+" renewed credential on new physical connection; held transaction survives");passed++;
        } finally {manager.close();}
    }
    static void expiryOnNewConnection() throws Exception {
        var info=info("postgresql");
        info.getOptions().put("token_expires_at", java.time.Instant.now().getEpochSecond()+3600);
        var manager=(BaseConnectionManager)manager(info);
        try {
            manager.ping();try(var connection=manager.getConnection()) {}
            info.getOptions().put("token_expires_at",1);
            try(var connection=manager.getPhysicalConnection()) {throw new AssertionError("expired token opened physical connection");}
            catch(IllegalStateException expected) {if(!expected.getMessage().contains("expired"))throw expected;}
            System.out.println("PASS pooled new physical connection rejects expired credential");passed++;
        } finally {manager.close();}
    }
    static void poolBorrowTimeout() throws Exception {
        var manager=(BaseConnectionManager)manager(info("postgresql"));
        try {
            manager.ping();
            var pool=manager.getOrInitDataSource("fixture");
            if(pool.getMaxWait()!=15000)throw new AssertionError("pool borrow is unbounded");
            pool.setMaxActive(1);
            try(var held=manager.getConnection()) {
                long start=System.nanoTime();
                try(var extra=manager.getConnection()) {throw new AssertionError("exhausted pool did not time out");}
                catch(com.alibaba.druid.pool.GetConnectionTimeoutException expected) {}
                long seconds=java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start);
                if(seconds<14 || seconds>18)throw new AssertionError("pool timeout outside bound: "+seconds);
                System.out.println("PASS exhausted pool borrow bounded ("+seconds+"s)");passed++;
            }
        } finally {manager.close();}
    }
    static void stalledLogin(String type) throws Exception {
        try(var server=new ServerSocket(0)) {
            var accepted=new java.util.concurrent.atomic.AtomicReference<Socket>();
            Thread accept=new Thread(()->{try{accepted.set(server.accept());}catch(Exception ignored){}});accept.setDaemon(true);accept.start();
            var info=info(type);info.setHost("127.0.0.1");info.setPort(server.getLocalPort());
            var manager=manager(info);long start=System.nanoTime();
            try {
                try{manager.ping();throw new AssertionError("stalled server connected");}catch(Exception expected){}
                long seconds=java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start);
                if(seconds>16)throw new AssertionError(type+" login exceeded bound: "+seconds);
                System.out.println("PASS "+type+" stalled login bounded ("+seconds+"s)");passed++;
            } finally {manager.close();if(accepted.get()!=null)accepted.get().close();}
        }
    }
    public static void main(String[] args) throws Exception {
        register("postgresql","drivers/postgresql/postgresql-42.6.0.jar");
        register("mysql","drivers/mysql/mysql-connector-java-8.0.30.jar");
        register("sqlserver","drivers/sqlserver/mssql-jdbc-12.8.1.jre11.jar");
        for(String type:TYPES) {
            check("CA/direct/default hostname verification",info(type),true);
            var bundle=info(type);bundle.getOptions().put("caCert",pem("wrong-ca.crt")+pem("ca.crt"));check("CA bundle",bundle,true);
            var system=info(type);system.getOptions().remove("caCert");check("JVM trust store",system,true);
            var gateway=info(type);gateway.setProxyHost(gateway.getHost());gateway.setProxyPort(gateway.getPort());gateway.setHost("ghost.fixture");check("gateway preserves logical certificate hostname",gateway,true);
            gateway.setHost("wrong.fixture");check("wrong gateway hostname",gateway,false);
            var wrong=info(type);wrong.getOptions().put("caCert",pem("wrong-ca.crt"));check("wrong CA",wrong,false);
            wrong.getOptions().put("verifyServerCertificate",false);check("explicit allow invalid certificate",wrong,true);
            if(type.equals("postgresql") || type.equals("mysql")) {
                var missing=info(type);missing.getOptions().remove("clientCert");missing.getOptions().remove("clientKey");check("required client certificate missing",missing,false);
                var mismatch=info(type);mismatch.getOptions().put("clientKey",pem("wrong-ca.key"));check("mismatched client key",mismatch,false);
            }
            if(type.equals("mongodb")) {
                var mutual=info(type);mutual.getOptions().put("clientCert",pem("client.crt"));mutual.getOptions().put("clientKey",pem("client.key"));check("client certificate supplied",mutual,true);
                mutual.getOptions().put("clientKey",pem("wrong-ca.key"));check("mismatched client key",mutual,false);
            }
            if(type.equals("sqlserver")) {
                var mutual=info(type);mutual.getOptions().put("clientCert",pem("client.crt"));mutual.getOptions().put("clientKey",pem("client.key"));check("unsupported TLS client identity explicitly rejected",mutual,false);
            }
            var incomplete=info(type);incomplete.getOptions().put("clientCert",pem("client.crt"));incomplete.getOptions().remove("clientKey");check("incomplete certificate pair rejected",incomplete,false);
            if(!type.equals("mongodb")){specialDatabase(type); resourceNames(type);}
        }
        try(var generator=new org.jumpserver.chen.framework.ssl.JKSGenerator(pem("client.crt")+pem("ca.crt"),pem("client.key"))) {
            var store=java.security.KeyStore.getInstance("JKS");
            try(var input=Files.newInputStream(generator.generateClientJKS())){store.load(input,org.jumpserver.chen.framework.ssl.JKSGenerator.JSK_PASS.toCharArray());}
            if(store.getCertificateChain("client").length!=2)throw new AssertionError("client chain truncated");
            System.out.println("PASS full client certificate chain retained");passed++;
        }
        for(String type:List.of("postgresql","mysql","sqlserver"))renewedPhysicalConnection(type);
        expiryOnNewConnection();
        poolBorrowTimeout();
        for(String type:TYPES)stalledLogin(type);
        System.out.println("S02-B real-driver cases: "+passed+" passed");
    }
}
