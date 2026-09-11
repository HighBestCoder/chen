import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mysql.MysqlConnectionManager;
import org.jumpserver.chen.modules.postgresql.PostgresqlConnectionManager;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.web.auth.*;
import com.mongodb.MongoClientSettings;
import java.lang.reflect.*;
import java.util.*;

/** These cases compile against pre-S02-B code for reproducible before/after comparison. */
public class TestConnectionConfigurationBaseline {
    interface Case { void run() throws Exception; }
    static int failures;
    static void check(boolean value) { if(!value)throw new AssertionError("regression reproduced"); }
    static void run(String label, Case test) {
        try {test.run();System.out.println("PASS "+label);}
        catch(Throwable failure) {failures++;System.out.println("FAIL "+label+" ("+failure.getClass().getSimpleName()+")");}
    }
    static DBConnectInfo info(String type) {
        var info=new DBConnectInfo();info.setDbType(type);info.setHost("original.fixture");info.setPort(1234);info.setDb("fixture");info.setUser("fixture");info.setPassword("space + /?@password");return info;
    }
    static org.jumpserver.chen.framework.datasource.Datasource datasource(String type) {
        return (org.jumpserver.chen.framework.datasource.Datasource) Proxy.newProxyInstance(
            TestConnectionConfigurationBaseline.class.getClassLoader(),new Class[]{org.jumpserver.chen.framework.datasource.Datasource.class},
            (p,m,a)->m.getName().equals("getDruidDbType")?com.alibaba.druid.DbType.of(type):null);
    }
    static Properties ssl(Object manager)throws Exception {
        Method method=null;
        for(Class<?> type=manager.getClass();type!=null && method==null;type=type.getSuperclass()) {
            try{method=type.getDeclaredMethod("setSSLProps",Properties.class);}catch(NoSuchMethodException ignored){}
        }
        if(method==null)throw new NoSuchMethodException("setSSLProps");
        method.setAccessible(true);
        var props=new Properties();method.invoke(manager,props);return props;
    }
    public static void main(String[] args) throws Exception {
        run("B40 explicit password on Entra-version asset",()->{
            var spec=ConnectionAuthSpec.fromSettings(Map.of("auth_type","password","auth_source","direct_password","auth_flow_version","v1"));
            check(RelationalAuthFlowHandler.decide(spec,AuthFlowDispatcher.resolve(spec,"sqlserver"),"sqlserver").decision()==RelationalAuthFlowHandler.Outcome.LEGACY_PASSWORD);
        });
        run("B41 PostgreSQL hostname verification",()->{
            var info=info("postgresql");info.getOptions().putAll(Map.of("useSSL",true,"verifyServerCertificate",true));
            var manager=new PostgresqlConnectionManager(info,datasource("postgresql"));
            try{check("verify-full".equals(ssl(manager).getProperty("sslmode")));}finally{manager.close();}
        });
        run("B41 MySQL hostname verification by default",()->{
            var info=info("mysql");info.getOptions().put("useSSL",true);
            var manager=new MysqlConnectionManager(info,datasource("mysql"));
            try{check("VERIFY_IDENTITY".equals(ssl(manager).getProperty("sslMode")));}finally{manager.close();}
        });
        run("B41 gateway retains logical TLS hostname",()->{
            var info=info("postgresql");info.setProxyHost("127.0.0.1");info.setProxyPort(2345);
            check(info.toJDBCUrl("jdbc:postgresql://${host}:${port}/${db}").startsWith("jdbc:postgresql://original.fixture:1234/"));
        });
        run("B43 database name cannot inject URL parameters",()->{
            var info=info("postgresql");info.setDb("fixture?sslmode=disable");
            check(!info.toJDBCUrl("jdbc:postgresql://${host}:${port}/${db}").contains("?sslmode"));
        });
        run("B43 Mongo password preserves spaces and reserved characters",()->{
            var info=info("mongodb");var manager=new MongoConnectionManager(info,null);
            try {
                var method=MongoConnectionManager.class.getDeclaredMethod("buildSettings");method.setAccessible(true);
                var settings=(MongoClientSettings)method.invoke(manager);
                check(Arrays.equals(settings.getCredential().getPassword(),info.getPassword().toCharArray()));
            }finally{manager.close();}
        });
        if(failures>0)throw new AssertionError(failures+" baseline regressions");
        System.out.println("OK: 6 baseline-compatible connection regressions");
    }
}
