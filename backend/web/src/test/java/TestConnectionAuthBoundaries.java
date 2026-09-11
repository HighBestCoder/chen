import com.alibaba.druid.pool.DruidDataSource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.sqlserver.SQLServerConnectionManager;
import org.jumpserver.chen.web.auth.*;
import org.jumpserver.chen.web.service.impl.JmsSessionService;
import java.lang.reflect.*;
import java.util.*;

public class TestConnectionAuthBoundaries {
    static class SqlServer extends SQLServerConnectionManager {
        SqlServer(DBConnectInfo info) { super(info, (org.jumpserver.chen.framework.datasource.Datasource) Proxy.newProxyInstance(
                TestConnectionAuthBoundaries.class.getClassLoader(),
                new Class<?>[]{org.jumpserver.chen.framework.datasource.Datasource.class},
                (proxy, method, args) -> method.getName().equals("getDruidDbType") ? com.alibaba.druid.DbType.sqlserver : null)); }
        void direct(Properties properties) { applyAuthProps(properties); }
        void pooled(DruidDataSource ds, Properties properties) { applyAuthOnDataSource(ds, properties); }
    }
    static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException e) { return; }
        throw new AssertionError("invalid authentication continued as password");
    }
    public static void main(String[] args) throws Exception {
        Method apply = JmsSessionService.class.getDeclaredMethod("applyAuthFlow", DBConnectInfo.class, ConnectionAuthSpec.class, AuthFlowDispatcher.Route.class);
        apply.setAccessible(true);
        int failures = 0;
        for (String flow : List.of("v2", "unknown")) {
            var info = new DBConnectInfo(); info.setDbType("postgresql"); info.setPassword("fixture-password");
            var spec = ConnectionAuthSpec.fromSettings(Map.of("auth_flow_version", flow));
            try { apply.invoke(new JmsSessionService(), info, spec, AuthFlowDispatcher.resolve(spec, "postgresql")); failures++; }
            catch (InvocationTargetException e) { if (!(e.getCause() instanceof IllegalArgumentException)) throw e; }
        }
        var info = new DBConnectInfo(); info.setUser("fixture-user"); info.setPassword("");
        info.getOptions().put("relationalAuthDecision", "V1_ACCESS_TOKEN_REQUIRED");
        var manager = new SqlServer(info);
        try { rejected(() -> manager.direct(new Properties())); } catch (AssertionError e) { failures++; }
        try (var ds = new DruidDataSource()) {
            try { rejected(() -> manager.pooled(ds, new Properties())); } catch (AssertionError e) { failures++; }
        }
        info.setPassword("fixture-token");
        var properties = new Properties(); properties.setProperty("user", "stale"); properties.setProperty("password", "stale");
        manager.direct(properties);
        if (!"fixture-token".equals(properties.getProperty("accessToken")) || properties.containsKey("user") || properties.containsKey("password")) failures++;
        try (var ds = new DruidDataSource()) {
            manager.pooled(ds, properties);
            if (ds.getUsername() != null || ds.getPassword() != null || !"fixture-token".equals(ds.getConnectProperties().getProperty("accessToken"))) failures++;
        }
        info.getOptions().clear(); info.setPassword("fixture-password"); properties.clear(); manager.direct(properties);
        if (!"fixture-password".equals(properties.getProperty("password"))) failures++;
        if (failures != 0) throw new AssertionError(failures + " auth boundary failures");
        System.out.println("OK: unsupported routes and empty SQL Server tokens rejected; actual direct/pool token properties verified");
    }
}
