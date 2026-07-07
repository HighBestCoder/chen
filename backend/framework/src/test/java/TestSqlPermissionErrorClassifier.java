import org.jumpserver.chen.framework.datasource.error.SqlPermissionErrorClassifier;

import java.sql.SQLException;

/**
 * GUI-SQL-006 probe: verify {@link SqlPermissionErrorClassifier} recognizes a
 * write-permission denial per engine (PostgreSQL SQLState / MySQL+SQLServer
 * vendor code), and does NOT misclassify authentication failures or ordinary
 * syntax errors.
 *
 * <p>Run via:</p>
 * <pre>
 *   mvn -pl backend/framework -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q
 *   java -cp backend/framework/target/classes:backend/framework/target/test-classes:$(cat /tmp/cp.txt) \
 *        TestSqlPermissionErrorClassifier
 * </pre>
 */
public class TestSqlPermissionErrorClassifier {

    static int failures = 0;

    public static void main(String[] args) {
        // ---- Positive: PostgreSQL (SQLState 42501 = insufficient_privilege) ----
        report("pg 42501 permission denied",
                SqlPermissionErrorClassifier.isPermissionDenied(
                        new SQLException("ERROR: permission denied for table orders", "42501")),
                true, true);

        // ---- Positive: MySQL/MariaDB (vendor code) ----
        report("mysql 1142 table-level denied",
                SqlPermissionErrorClassifier.isPermissionDenied(
                        new SQLException("INSERT command denied to user", "42000", 1142)),
                true, true);
        report("mysql 1143 column-level denied",
                SqlPermissionErrorClassifier.isPermissionDenied(
                        new SQLException("UPDATE command denied to user for column", "42000", 1143)),
                true, true);
        report("mysql 1044 db-level denied",
                SqlPermissionErrorClassifier.isPermissionDenied(
                        new SQLException("Access denied for user to database", "42000", 1044)),
                true, true);

        // ---- Positive: SQL Server (error number) ----
        report("mssql 229 object-level denied",
                SqlPermissionErrorClassifier.isPermissionDenied(
                        new SQLException("The INSERT permission was denied on the object", "S0005", 229)),
                true, true);
        report("mssql 230 column-level denied",
                SqlPermissionErrorClassifier.isPermissionDenied(
                        new SQLException("The SELECT permission was denied on the column", "S0005", 230)),
                true, true);

        // ---- Positive: permission error wrapped one level deep in the cause chain ----
        SQLException pgPerm = new SQLException("permission denied for table t", "42501");
        SQLException wrapped = new SQLException("execution failed", null, 0, pgPerm);
        report("wrapped cause still detected",
                SqlPermissionErrorClassifier.isPermissionDenied(wrapped), true, true);

        // ---- Negative: authentication failures must NOT be classified as write-deny ----
        boolean pgAuth = SqlPermissionErrorClassifier.isPermissionDenied(
                new SQLException("password authentication failed", "28P01"));
        report("pg 28P01 auth failure not denied", !pgAuth, false, pgAuth);
        boolean myAuth = SqlPermissionErrorClassifier.isPermissionDenied(
                new SQLException("Access denied for user (using password: YES)", "28000", 1045));
        report("mysql 1045 auth failure not denied", !myAuth, false, myAuth);

        // ---- Negative: ordinary syntax error must NOT be classified ----
        boolean pgSyntax = SqlPermissionErrorClassifier.isPermissionDenied(
                new SQLException("syntax error at or near", "42601"));
        report("pg 42601 syntax error not denied", !pgSyntax, false, pgSyntax);

        // ---- Negative: null ----
        boolean nullDenied = SqlPermissionErrorClassifier.isPermissionDenied(null);
        report("null not denied", !nullDenied, false, nullDenied);

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-8s actual=%-8s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
