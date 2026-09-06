import org.jumpserver.chen.web.auth.AuthFlowDispatcher;
import org.jumpserver.chen.web.auth.ConnectionAuthSpec;
import org.jumpserver.chen.web.auth.RelationalAuthFlowHandler;
import org.jumpserver.chen.web.auth.RelationalAuthFlowHandler.Outcome;

import java.util.Map;

/**
 * Probe-style regression test for {@link RelationalAuthFlowHandler}.
 *
 * <p>Run with:</p>
 * <pre>
 *   mvn -pl backend/web test-compile exec:java \
 *       -Dexec.mainClass=TestRelationalAuthFlowHandler \
 *       -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Or directly via {@code java -cp ...}. The class follows the same
 * minimal pattern as {@code TestSqlServerTop} so it can run without
 * pulling JUnit into the framework module.</p>
 */
public class TestRelationalAuthFlowHandler {

    static int failures = 0;

    public static void main(String[] args) {
        // Legacy: no auth context at all -> plain password.
        check(
                spec(Map.of()),
                AuthFlowDispatcher.Route.LEGACY,
                "postgresql",
                Outcome.LEGACY_PASSWORD
        );

        // Legacy + core_poc_token -> token-as-password (POC behaviour).
        check(
                spec(Map.of("auth_source", "core_poc_token")),
                AuthFlowDispatcher.Route.LEGACY,
                "postgresql",
                Outcome.LEGACY_TOKEN_AS_PASSWORD
        );

        // v1 + PostgreSQL -> token-as-password with version tag.
        check(
                spec(Map.of(
                        "auth_flow_version", "v1",
                        "auth_source", "core_poc_token",
                        "auth_type", "entra_sp"
                )),
                AuthFlowDispatcher.Route.V1,
                "postgresql",
                Outcome.V1_TOKEN_AS_PASSWORD
        );

        // v1 + MySQL / MariaDB -> same outcome.
        check(
                spec(Map.of("auth_flow_version", "v1")),
                AuthFlowDispatcher.Route.V1,
                "mysql",
                Outcome.V1_TOKEN_AS_PASSWORD
        );
        check(
                spec(Map.of("auth_flow_version", "v1")),
                AuthFlowDispatcher.Route.V1,
                "mariadb",
                Outcome.V1_TOKEN_AS_PASSWORD
        );

        // v1 + SQL Server -> AccessToken required (slice C).
        check(
                spec(Map.of("auth_flow_version", "v1")),
                AuthFlowDispatcher.Route.V1,
                "sqlserver",
                Outcome.V1_ACCESS_TOKEN_REQUIRED
        );
        check(
                spec(Map.of("auth_flow_version", "v1")),
                AuthFlowDispatcher.Route.V1,
                "mssql",
                Outcome.V1_ACCESS_TOKEN_REQUIRED
        );

        // v1 + unknown dbType -> UNSUPPORTED (caller logs and falls back).
        check(
                spec(Map.of("auth_flow_version", "v1")),
                AuthFlowDispatcher.Route.V1,
                "oracle",
                Outcome.UNSUPPORTED
        );

        // v2 + MongoDB -> OIDC token required (task-08 Mongo Entra).
        check(
                spec(Map.of("auth_flow_version", "v2")),
                AuthFlowDispatcher.Route.V2,
                "mongodb",
                Outcome.V2_OIDC_TOKEN_REQUIRED
        );

        // v2 / unknown route -> UNSUPPORTED at this handler.
        check(
                spec(Map.of("auth_flow_version", "v2")),
                AuthFlowDispatcher.Route.V2,
                "postgresql",
                Outcome.UNSUPPORTED
        );
        check(
                spec(Map.of("auth_flow_version", "v9")),
                AuthFlowDispatcher.Route.UNKNOWN,
                "postgresql",
                Outcome.UNSUPPORTED
        );

        // dbType case-insensitivity.
        check(
                spec(Map.of("auth_flow_version", "v1")),
                AuthFlowDispatcher.Route.V1,
                "PostgreSQL",
                Outcome.V1_TOKEN_AS_PASSWORD
        );

        // Null auth spec -> safe default (legacy plain password).
        var nullSpecDecision = RelationalAuthFlowHandler.decide(null, AuthFlowDispatcher.Route.LEGACY, "postgresql");
        report("null spec",
                nullSpecDecision.decision() == Outcome.LEGACY_PASSWORD,
                Outcome.LEGACY_PASSWORD,
                nullSpecDecision.decision()
        );

        // ---- AuthFlowDispatcher routing ----

        // No declared version + Core-injected Entra token: the flow is
        // inferred from dbType. This is the path every SP asset actually
        // takes, because auth_flow_version is an asset-only override that
        // account-level Entra config cannot set.
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), "sqlserver", AuthFlowDispatcher.Route.V1);
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), "mssql", AuthFlowDispatcher.Route.V1);
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), "mongodb", AuthFlowDispatcher.Route.V2);
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), "SQLServer", AuthFlowDispatcher.Route.V1);

        // An entra_* auth type is the other tell that Core swapped the
        // secret for a bearer token.
        checkRoute(spec(Map.of("auth_type", "entra_sp")), "sqlserver", AuthFlowDispatcher.Route.V1);
        checkRoute(spec(Map.of("auth_type", "entra_mi")), "mongodb", AuthFlowDispatcher.Route.V2);

        // PG / MySQL keep the legacy token-as-password wire behaviour.
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), "postgresql", AuthFlowDispatcher.Route.LEGACY);
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), "mysql", AuthFlowDispatcher.Route.LEGACY);

        // Plain-password assets must never be re-routed.
        checkRoute(spec(Map.of("auth_source", "direct_password")), "sqlserver", AuthFlowDispatcher.Route.LEGACY);
        checkRoute(spec(Map.of("auth_type", "password")), "mongodb", AuthFlowDispatcher.Route.LEGACY);
        checkRoute(spec(Map.of()), "sqlserver", AuthFlowDispatcher.Route.LEGACY);

        // An explicit version is always honoured. "legacy" is a value the
        // asset dropdown offers, and it used to fall through to UNKNOWN.
        checkRoute(spec(Map.of("auth_flow_version", "legacy",
                "auth_source", "core_poc_token")), "sqlserver", AuthFlowDispatcher.Route.LEGACY);
        checkRoute(spec(Map.of("auth_flow_version", "v1")), "sqlserver", AuthFlowDispatcher.Route.V1);
        checkRoute(spec(Map.of("auth_flow_version", "v2")), "mongodb", AuthFlowDispatcher.Route.V2);
        checkRoute(spec(Map.of("auth_flow_version", "v9")), "sqlserver", AuthFlowDispatcher.Route.UNKNOWN);

        // Missing dbType must not invent a route.
        checkRoute(spec(Map.of("auth_source", "core_poc_token")), null, AuthFlowDispatcher.Route.LEGACY);

        // ---- dispatcher + handler end to end ----
        // These are the decisions the SQL Server AccessToken bridge and the
        // Mongo OIDC bridge key off; anything else sends the JWT as a
        // password.
        checkEndToEnd(spec(Map.of("auth_source", "core_poc_token", "auth_type", "entra_sp")),
                "sqlserver", Outcome.V1_ACCESS_TOKEN_REQUIRED);
        checkEndToEnd(spec(Map.of("auth_source", "core_poc_token", "auth_type", "entra_sp")),
                "mongodb", Outcome.V2_OIDC_TOKEN_REQUIRED);
        checkEndToEnd(spec(Map.of("auth_source", "core_poc_token", "auth_type", "entra_sp")),
                "postgresql", Outcome.LEGACY_TOKEN_AS_PASSWORD);
        checkEndToEnd(spec(Map.of("auth_source", "direct_password")),
                "sqlserver", Outcome.LEGACY_PASSWORD);

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void checkRoute(ConnectionAuthSpec authSpec,
                                   String dbType,
                                   AuthFlowDispatcher.Route expected) {
        var actual = AuthFlowDispatcher.resolve(authSpec, dbType);
        boolean ok = actual == expected;
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-30s actual=%-30s  route dbType=%s flow='%s' source=%s%n",
                ok ? "ok" : "FAIL", expected, actual, dbType,
                authSpec.authFlowVersion(), authSpec.authSource());
    }

    private static void checkEndToEnd(ConnectionAuthSpec authSpec,
                                      String dbType,
                                      Outcome expected) {
        var route = AuthFlowDispatcher.resolve(authSpec, dbType);
        var decision = RelationalAuthFlowHandler.decide(authSpec, route, dbType);
        report(String.format("end-to-end dbType=%s route=%s", dbType, route),
                decision.decision() == expected, expected, decision.decision());
    }

    private static ConnectionAuthSpec spec(Map<String, String> settings) {
        return ConnectionAuthSpec.fromSettings(settings);
    }

    private static void check(ConnectionAuthSpec authSpec,
                              AuthFlowDispatcher.Route route,
                              String dbType,
                              Outcome expected) {
        var decision = RelationalAuthFlowHandler.decide(authSpec, route, dbType);
        boolean ok = decision.decision() == expected;
        String label = String.format("dbType=%s route=%s flow=%s source=%s",
                dbType, route, authSpec.normalizedFlowVersion(), authSpec.authSource());
        report(label, ok, expected, decision.decision());
    }

    private static void report(String label, boolean ok, Outcome expected, Outcome actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-30s actual=%-30s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
