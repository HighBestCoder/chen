import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mysql.MysqlConnectionManager;
import org.jumpserver.chen.modules.postgresql.PostgresqlConnectionManager;
import org.jumpserver.chen.modules.sqlserver.SQLServerConnectionManager;

import com.alibaba.druid.DbType;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Probe for the per-driver SSL property derivation. Each driver family
 * has a different TLS contract (MySQL useSSL, PostgreSQL sslmode,
 * SQL Server encrypt/trustServerCertificate), so setSSLProps must map
 * the generic use_ssl / allow_invalid_cert / caCert options to the
 * right driver-specific properties.
 *
 * <pre>
 *   mvn -pl backend/modules -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q
 *   java -cp backend/modules/target/classes:backend/modules/target/test-classes:\
 *           backend/framework/target/classes:$(cat /tmp/cp.txt) \
 *        TestSslPropsDerivation
 * </pre>
 */
public class TestSslPropsDerivation {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        mysql();
        postgresql();
        sqlserver();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void mysql() throws Exception {
        Properties off = sslProps(new MysqlConnectionManager(info(Map.of()), ds(DbType.mysql)));
        report("mysql no-ssl -> useSSL=false", "false".equals(off.getProperty("useSSL")), "false", off.getProperty("useSSL"));

        Properties on = sslProps(new MysqlConnectionManager(
                info(Map.of("useSSL", Boolean.TRUE, "verifyServerCertificate", Boolean.FALSE)), ds(DbType.mysql)));
        report("mysql ssl -> useSSL=true", "true".equals(on.getProperty("useSSL")), "true", on.getProperty("useSSL"));
        report("mysql ssl -> requireSSL=true", "true".equals(on.getProperty("requireSSL")), "true", on.getProperty("requireSSL"));

        MysqlConnectionManager mgr = new MysqlConnectionManager(info(Map.of()), ds(DbType.mysql));
        report("mysql url drops useSSL=false", !mgr.getDisplayJDBCUrl().contains("useSSL=false"),
                "no useSSL=false", mgr.getDisplayJDBCUrl());
    }

    private static void postgresql() throws Exception {
        Properties prefer = sslProps(new PostgresqlConnectionManager(info(Map.of()), ds(DbType.postgresql)));
        report("pg no-ssl -> sslmode=prefer", "prefer".equals(prefer.getProperty("sslmode")), "prefer", prefer.getProperty("sslmode"));

        Properties require = sslProps(new PostgresqlConnectionManager(
                info(Map.of("useSSL", Boolean.TRUE, "verifyServerCertificate", Boolean.FALSE)), ds(DbType.postgresql)));
        report("pg ssl+no-verify -> sslmode=require", "require".equals(require.getProperty("sslmode")), "require", require.getProperty("sslmode"));

        Properties verifyNoCa = sslProps(new PostgresqlConnectionManager(
                info(Map.of("useSSL", Boolean.TRUE, "verifyServerCertificate", Boolean.TRUE)), ds(DbType.postgresql)));
        report("pg verify no-ca -> sslmode=verify-full", "verify-full".equals(verifyNoCa.getProperty("sslmode")), "verify-full", verifyNoCa.getProperty("sslmode"));
        report("pg verify no-ca -> PemSslSocketFactory",
                "org.jumpserver.chen.framework.ssl.PemSslSocketFactory".equals(verifyNoCa.getProperty("sslfactory")),
                "PemSslSocketFactory", verifyNoCa.getProperty("sslfactory"));

        Properties verifyCa = sslProps(new PostgresqlConnectionManager(info(Map.of(
                "useSSL", Boolean.TRUE, "verifyServerCertificate", Boolean.TRUE,
                "caCert", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----")), ds(DbType.postgresql)));
        report("pg verify+ca -> sslmode=verify-full", "verify-full".equals(verifyCa.getProperty("sslmode")), "verify-full", verifyCa.getProperty("sslmode"));
        report("pg verify+ca -> sslrootcert set", verifyCa.getProperty("sslrootcert") != null, "set", verifyCa.getProperty("sslrootcert"));
        report("pg no-ssl drops MySQL useSSL", prefer.getProperty("useSSL") == null, "null", prefer.getProperty("useSSL"));
    }

    private static void sqlserver() throws Exception {
        Properties off = sslProps(new SQLServerConnectionManager(info(Map.of()), ds(DbType.sqlserver)));
        report("mssql no-ssl -> encrypt=false", "false".equals(off.getProperty("encrypt")), "false", off.getProperty("encrypt"));

        Properties trust = sslProps(new SQLServerConnectionManager(
                info(Map.of("useSSL", Boolean.TRUE, "verifyServerCertificate", Boolean.FALSE)), ds(DbType.sqlserver)));
        report("mssql ssl+no-verify -> encrypt=true", "true".equals(trust.getProperty("encrypt")), "true", trust.getProperty("encrypt"));
        report("mssql ssl+no-verify -> trustServerCertificate=true",
                "true".equals(trust.getProperty("trustServerCertificate")), "true", trust.getProperty("trustServerCertificate"));

        Properties verify = sslProps(new SQLServerConnectionManager(
                info(Map.of("useSSL", Boolean.TRUE, "verifyServerCertificate", Boolean.TRUE)), ds(DbType.sqlserver)));
        report("mssql verify -> trustServerCertificate=false",
                "false".equals(verify.getProperty("trustServerCertificate")), "false", verify.getProperty("trustServerCertificate"));
        report("mssql no-ssl drops MySQL useSSL", off.getProperty("useSSL") == null, "null", off.getProperty("useSSL"));
    }

    private static Datasource ds(DbType dbType) {
        return (Datasource) Proxy.newProxyInstance(
                Datasource.class.getClassLoader(),
                new Class<?>[]{Datasource.class},
                (proxy, method, methodArgs) -> "getDruidDbType".equals(method.getName()) ? dbType : null);
    }

    private static DBConnectInfo info(Map<String, Object> options) {
        DBConnectInfo info = new DBConnectInfo();
        info.setHost("localhost");
        info.setPort(1234);
        info.setDb("testdb");
        info.setUser("u");
        info.setPassword("p");
        info.getOptions().putAll(new HashMap<>(options));
        return info;
    }

    private static Properties sslProps(Object manager) throws Exception {
        Properties props = new Properties();
        Method m = findSetSSLProps(manager.getClass());
        m.setAccessible(true);
        m.invoke(manager, props);
        return props;
    }

    private static Method findSetSSLProps(Class<?> c) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredMethod("setSSLProps", Properties.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("setSSLProps");
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-26s actual=%-26s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
