import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.mongodb.MongoEntraAuthSupport;

import java.util.Map;

/**
 * task-08 probe: validate the Mongo Entra OIDC decision helper. Mirrors
 * TestSqlServerAccessTokenBridge.
 *
 * <p>Run with:</p>
 * <pre>
 *   mvn -pl backend/modules -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q
 *   java -cp backend/modules/target/classes:backend/modules/target/test-classes:\
 *           backend/framework/target/classes:$(cat /tmp/cp.txt) \
 *        TestMongoEntraAuthSupport
 * </pre>
 */
public class TestMongoEntraAuthSupport {

    static int failures = 0;

    public static void main(String[] args) {
        // Decision tag from RelationalAuthFlowHandler triggers OIDC mode.
        check("decision tag",
                info(Map.of("relationalAuthDecision", "V2_OIDC_TOKEN_REQUIRED"), "fake-bearer"),
                true, "fake-bearer");

        // Decision tag is case-insensitive.
        check("decision tag lowercase",
                info(Map.of("relationalAuthDecision", "v2_oidc_token_required"), "fake-bearer"),
                true, "fake-bearer");

        // The SQL Server decision must NOT trip the Mongo helper.
        check("sqlserver decision ignored",
                info(Map.of("relationalAuthDecision", "V1_ACCESS_TOKEN_REQUIRED"), "fake-bearer"),
                false, null);

        // Plain legacy decision -> no OIDC.
        check("legacy decision",
                info(Map.of("relationalAuthDecision", "LEGACY_PASSWORD"), "scram-pass"),
                false, null);

        // No options at all -> no OIDC (plain SCRAM Mongo).
        check("empty options",
                info(Map.of(), "scram-pass"),
                false, null);

        // accessToken option override beats password (test fixture only).
        DBConnectInfo override = info(Map.of(
                "relationalAuthDecision", "V2_OIDC_TOKEN_REQUIRED",
                "accessToken", "explicit-token"
        ), "fake-bearer");
        check("explicit accessToken option", override, true, "explicit-token");

        // OIDC mode but token blank -> mode true but resolveToken null.
        DBConnectInfo blank = info(Map.of("relationalAuthDecision", "V2_OIDC_TOKEN_REQUIRED"), "");
        boolean mode = MongoEntraAuthSupport.isOidcMode(blank);
        String tk = MongoEntraAuthSupport.resolveToken(blank);
        report("blank token still in OIDC-mode", mode, true, mode);
        report("blank token resolves to null", tk == null, true, tk == null);

        // Null connect info is safe.
        report("null connectInfo not OIDC", !MongoEntraAuthSupport.isOidcMode(null), true, true);
        report("null connectInfo token null", MongoEntraAuthSupport.resolveToken(null) == null, true, true);

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static DBConnectInfo info(Map<String, Object> options, String password) {
        DBConnectInfo info = new DBConnectInfo();
        info.setDbType("mongodb");
        info.setUser("ignored");
        info.setPassword(password);
        info.getOptions().putAll(options);
        return info;
    }

    private static void check(String label, DBConnectInfo info, boolean expectMode, String expectToken) {
        boolean actualMode = MongoEntraAuthSupport.isOidcMode(info);
        String actualToken = MongoEntraAuthSupport.resolveToken(info);
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
