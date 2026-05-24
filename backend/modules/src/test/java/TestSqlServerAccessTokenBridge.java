import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.sqlserver.SqlServerAccessTokenSupport;

import java.util.Map;
import java.util.Properties;

/**
 * task-07 slice C probe: validate the SQL Server AccessToken decision
 * helper and the connection-property shape the connection manager will
 * push into mssql-jdbc.
 *
 * <p>Run with the same recipe as TestRelationalAuthFlowHandler:</p>
 * <pre>
 *   mvn -pl backend/modules -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q
 *   java -cp backend/modules/target/classes:backend/modules/target/test-classes:\
 *           backend/framework/target/classes:$(cat /tmp/cp.txt) \
 *        TestSqlServerAccessTokenBridge
 * </pre>
 */
public class TestSqlServerAccessTokenBridge {

    static int failures = 0;

    public static void main(String[] args) {
        // Decision tag from RelationalAuthFlowHandler triggers AccessToken mode.
        check("decision tag",
                info(Map.of("relationalAuthDecision", "V1_ACCESS_TOKEN_REQUIRED"), "fake-bearer"),
                true, "fake-bearer");

        // Decision tag is case-insensitive.
        check("decision tag lowercase",
                info(Map.of("relationalAuthDecision", "v1_access_token_required"), "fake-bearer"),
                true, "fake-bearer");

        // Boolean fallback flag also triggers AccessToken mode.
        check("requiresAccessToken=true",
                info(Map.of("requiresAccessToken", Boolean.TRUE), "fake-bearer"),
                true, "fake-bearer");

        // String "true" fallback.
        check("requiresAccessToken='true'",
                info(Map.of("requiresAccessToken", "true"), "fake-bearer"),
                true, "fake-bearer");

        // Plain legacy decision -> no AccessToken.
        check("legacy decision",
                info(Map.of("relationalAuthDecision", "LEGACY_PASSWORD"), "p@ss"),
                false, null);

        // No options at all -> no AccessToken.
        check("empty options",
                info(Map.of(), "p@ss"),
                false, null);

        // AccessToken option override beats password (test fixture only).
        DBConnectInfo info = info(Map.of(
                "relationalAuthDecision", "V1_ACCESS_TOKEN_REQUIRED",
                "accessToken", "explicit-token"
        ), "fake-bearer");
        check("explicit accessToken option", info, true, "explicit-token");

        // AccessToken mode but token blank -> mode true but resolveAccessToken null.
        DBConnectInfo blank = info(Map.of("relationalAuthDecision", "V1_ACCESS_TOKEN_REQUIRED"), "");
        boolean mode = SqlServerAccessTokenSupport.isAccessTokenMode(blank);
        String tk = SqlServerAccessTokenSupport.resolveAccessToken(blank);
        report("blank token still in token-mode", mode, true, mode);
        report("blank token resolves to null", tk == null, true, tk == null);

        // dbType normalisation is null-safe.
        report("normalize null", "".equals(SqlServerAccessTokenSupport.normalizeDbType(null)), true, true);
        report("normalize SQLSERVER", "sqlserver".equals(SqlServerAccessTokenSupport.normalizeDbType("SQLSERVER")), true, true);

        // Property-shape sanity check: simulating the connection-manager
        // path. After applying AccessToken mode, user/password must be
        // gone and accessToken must be present.
        Properties props = new Properties();
        props.setProperty("user", "should-be-removed");
        props.setProperty("password", "should-be-removed");
        DBConnectInfo tokenInfo = info(Map.of("relationalAuthDecision", "V1_ACCESS_TOKEN_REQUIRED"), "the-token");
        if (SqlServerAccessTokenSupport.isAccessTokenMode(tokenInfo)) {
            props.remove("user");
            props.remove("password");
            props.setProperty(SqlServerAccessTokenSupport.JDBC_PROP_ACCESS_TOKEN,
                    SqlServerAccessTokenSupport.resolveAccessToken(tokenInfo));
        }
        report("user removed", !props.containsKey("user"), true, !props.containsKey("user"));
        report("password removed", !props.containsKey("password"), true, !props.containsKey("password"));
        report("accessToken set",
                "the-token".equals(props.getProperty(SqlServerAccessTokenSupport.JDBC_PROP_ACCESS_TOKEN)),
                true, props.containsKey(SqlServerAccessTokenSupport.JDBC_PROP_ACCESS_TOKEN));

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static DBConnectInfo info(Map<String, Object> options, String password) {
        DBConnectInfo info = new DBConnectInfo();
        info.setDbType("sqlserver");
        info.setUser("ignored");
        info.setPassword(password);
        info.getOptions().putAll(options);
        return info;
    }

    private static void check(String label, DBConnectInfo info, boolean expectMode, String expectToken) {
        boolean actualMode = SqlServerAccessTokenSupport.isAccessTokenMode(info);
        String actualToken = SqlServerAccessTokenSupport.resolveAccessToken(info);
        boolean ok = actualMode == expectMode
                && java.util.Objects.equals(expectToken, actualMode ? actualToken : null);
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expectMode=%-5s actualMode=%-5s expectToken=%s actualToken=%s  %s%n",
                ok ? "ok" : "FAIL", expectMode, actualMode, mask(expectToken), mask(actualToken), label);
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-30s actual=%-30s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }

        private static String mask(String value) {
                if (value == null) {
                        return "null";
                }
                return "<redacted>";
        }
}
