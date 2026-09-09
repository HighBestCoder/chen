import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import com.alibaba.druid.pool.DruidDataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/** Real Druid pools with a controlled JDBC driver; no external database. */
public class TestConnectionManagerLifecycle {
    static class Manager extends BaseConnectionManager {
        final AtomicInteger opened = new AtomicInteger(), released = new AtomicInteger();
        final CountDownLatch firstConnect = new CountDownLatch(1), releaseConnect = new CountDownLatch(1);
        Manager() { super(info(), null); }
        static DBConnectInfo info() {
            var info = new DBConnectInfo(); info.setDbType("mysql"); info.setDb("fixture"); info.setUser("fixture"); info.setPassword("fixture"); return info;
        }
        @Override public Driver getDriver() {
            return new Driver() {
                public Connection connect(String url, Properties props) throws SQLException {
                    opened.incrementAndGet(); firstConnect.countDown();
                    try { if (!releaseConnect.await(5, TimeUnit.SECONDS)) throw new SQLException("fixture latch timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                    AtomicBoolean closed = new AtomicBoolean();
                    return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> switch(m.getName()) {
                        case "close", "abort" -> { if (closed.compareAndSet(false,true)) released.incrementAndGet(); yield null; }
                        case "isClosed" -> closed.get();
                        case "isValid", "getAutoCommit" -> true;
                        case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                        case "getHoldability" -> ResultSet.HOLD_CURSORS_OVER_COMMIT;
                        case "getMetaData" -> Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(q,n,b)-> switch(n.getName()) {
                            case "getURL" -> "jdbc:mysql://fixture/fixture";
                            case "getDatabaseProductName" -> "MySQL";
                            case "getDatabaseProductVersion", "getDriverVersion" -> "8.0";
                            default -> zero(n.getReturnType());
                        });
                        default -> zero(m.getReturnType());
                    });
                }
                public boolean acceptsURL(String url) { return true; }
                public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
                public int getMajorVersion() { return 8; } public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return true; } public Logger getParentLogger() { return Logger.getGlobal(); }
            };
        }
        @Override public String getDriverClassName() { return "fixture"; }
        @Override public String getJDBCUrl() { return "jdbc:mysql://fixture/fixture"; }
        @Override public String getJDBCUrl(String db) { return getJDBCUrl(); }
        @Override public String getDisplayJDBCUrl() { return getJDBCUrl(); }
        @Override public String getVersion() { return "fixture"; }
        @Override public void ping() throws SQLException { ping(getJDBCUrl()); }
    }
    static class FailingManager extends Manager {
        DruidDataSource allocated;
        FailingManager() { releaseConnect.countDown(); }
        @Override protected void applyAuthOnDataSource(DruidDataSource ds, Properties properties) {
            allocated=ds;super.applyAuthOnDataSource(ds,properties);ds.setInitialSize(1);
            try { ds.init(); } catch(SQLException e) { throw new IllegalStateException(e); }
            throw new IllegalStateException("injected failure after pool allocation");
        }
    }
    static Object zero(Class<?> type) {
        if(type==boolean.class)return false; if(type==int.class)return 0; if(type==long.class)return 0L; return null;
    }
    public static void main(String[] args) throws Exception {
        int failures=0; ExecutorService workers=Executors.newFixedThreadPool(2);
        Manager manager=new Manager(); DruidDataSource first=null, second=null;
        try {
            Future<DruidDataSource> a=workers.submit(()->manager.getOrInitDataSource("fixture"));
            if(!manager.firstConnect.await(5,TimeUnit.SECONDS))throw new AssertionError("initial connection not started");
            CountDownLatch secondStarted=new CountDownLatch(1);
            Future<DruidDataSource> b=workers.submit(()->{secondStarted.countDown();return manager.getOrInitDataSource("fixture");});
            secondStarted.await();
            // Give the second request an opportunity to race while initialization is blocked.
            try { b.get(200,TimeUnit.MILLISECONDS); } catch(TimeoutException expected) { }
            manager.releaseConnect.countDown(); first=a.get(8,TimeUnit.SECONDS);second=b.get(8,TimeUnit.SECONDS);
            if(first!=second)failures++;
            manager.close();manager.close();
            if(manager.opened.get()!=manager.released.get())failures++;
            try { manager.getOrInitDataSource("another-db"); failures++; } catch(SQLException expected) { }
            try { manager.ping(); failures++; } catch(SQLException expected) { }
        } finally {
            manager.releaseConnect.countDown();manager.close();if(first!=null)first.close();if(second!=null)second.close();workers.shutdownNow();
        }
        Manager closing=new Manager();ExecutorService race=Executors.newFixedThreadPool(2);DruidDataSource racedPool=null;
        try {
            Future<DruidDataSource> opening=race.submit(()->closing.getOrInitDataSource("fixture"));
            if(!closing.firstConnect.await(5,TimeUnit.SECONDS))throw new AssertionError("close race did not start");
            CountDownLatch closeStarted=new CountDownLatch(1);
            Future<?> shutdown=race.submit(()->{closeStarted.countDown();closing.close();});
            closeStarted.await();
            try { shutdown.get(150,TimeUnit.MILLISECONDS); } catch(TimeoutException expected) { }
            closing.releaseConnect.countDown();racedPool=opening.get(8,TimeUnit.SECONDS);shutdown.get(8,TimeUnit.SECONDS);
            if(!racedPool.isClosed() || closing.opened.get()!=closing.released.get())failures++;
        } finally {
            closing.releaseConnect.countDown();closing.close();if(racedPool!=null)racedPool.close();race.shutdownNow();
        }
        FailingManager failing=new FailingManager();
        try {
            try { failing.getOrInitDataSource("fixture"); failures++; } catch(IllegalStateException expected) { }
            if(failing.opened.get()!=1 || failing.released.get()!=1 || !failing.allocated.isClosed())failures++;
        } finally { failing.close();if(failing.allocated!=null)failing.allocated.close(); }
        var info=Manager.info(); info.setHost("127.0.0.1");info.setPort(1);
        MongoConnectionManager mongo=new MongoConnectionManager(info,null);
        mongo.close();
        try { mongo.getClient(); failures++; } catch(IllegalStateException expected) { } finally { mongo.close(); }
        if(failures>0)throw new AssertionError(failures+" lifecycle failures (duplicate pools, leaked connections, or reopen after close)");
        System.out.println("OK: one pool per database under concurrent initialization; close releases all connections and is terminal for SQL/Mongo");
    }
}
