import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.SqlExecutionStatsBuilder;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Probe that {@link SqlExecutionStatsBuilder#fromSuccess} consumes the
 * streaming size/row stats carried on {@link SQLQueryResult} rather than
 * recomputing over the (possibly capped) retained {@code data}, and that the
 * legacy path still works when the streaming fields are absent (Mongo-style
 * defaults).
 *
 * <p>Run via:</p>
 * <pre>
 *   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
 *   mvn -pl backend/framework -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q -o
 *   java -cp backend/framework/target/classes:backend/framework/target/test-classes:$(cat /tmp/cp.txt) \
 *        TestStreamingStatsBuilder
 * </pre>
 */
public class TestStreamingStatsBuilder {

    static int failures = 0;

    public static void main(String[] args) {
        streamedPathUsesCarriedStats();
        legacyPathFallsBackToCompute();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    /**
     * Streamed result: only 2 rows retained (display cap) but the full result
     * had 1_000_000 rows and a streamed size of 987654 bytes. The builder must
     * report those full numbers, NOT data.size()/recomputed-size.
     */
    private static void streamedPathUsesCarriedStats() {
        SQLQueryResult result = new SQLQueryResult("SELECT name, age FROM big");
        result.setHasResultSet(true);
        result.setFields(fields("name", "age"));
        // retained (capped) rows — deliberately tiny and NOT matching streamed size
        List<List<Object>> retained = new ArrayList<>();
        retained.add(row("a", 1));
        retained.add(row("b", 2));
        result.setData(retained);
        result.setTotal(1_000_000);

        result.setTrueReturnedRows(1_000_000L);
        result.setStreamedSizeBytes(987654L);
        result.setSizeStatsStatus("ok");
        result.setTruncated(true);

        ExecutionStats stats = SqlExecutionStatsBuilder.fromSuccess(null, "SELECT name, age FROM big", result);

        report("streamed returnedRows == trueReturnedRows",
                eq(stats.getReturnedRows(), 1_000_000L), 1_000_000L, stats.getReturnedRows());
        report("streamed sizeBytes == streamedSizeBytes",
                eq(stats.getSizeBytes(), 987654L), 987654L, stats.getSizeBytes());
        report("size_stats_status propagated",
                "ok".equals(stats.getExtras().get("size_stats_status")),
                "ok", stats.getExtras().get("size_stats_status"));
        report("result_truncated recorded",
                Boolean.TRUE.equals(stats.getExtras().get("result_truncated")),
                true, stats.getExtras().get("result_truncated"));
        report("totalRows from total",
                eq(stats.getTotalRows(), 1_000_000L), 1_000_000L, stats.getTotalRows());
    }

    /**
     * Legacy/Mongo-style result: streamedSizeBytes==-1 and trueReturnedRows==-1
     * (SQLQueryResult defaults) -> builder recomputes over data.
     * rows: ("ab",30)("cd",40) over fields[name,age]:
     *   "ab"=2 "30"=2 "cd"=2 "40"=2  => 8 bytes; returnedRows == data.size() == 2.
     */
    private static void legacyPathFallsBackToCompute() {
        SQLQueryResult result = new SQLQueryResult("SELECT name, age FROM t");
        result.setHasResultSet(true);
        result.setFields(fields("name", "age"));
        List<List<Object>> data = new ArrayList<>();
        data.add(row("ab", 30));
        data.add(row("cd", 40));
        result.setData(data);
        result.setTotal(2);
        // streamed fields left at defaults (-1) => legacy branch

        ExecutionStats stats = SqlExecutionStatsBuilder.fromSuccess(null, "SELECT name, age FROM t", result);

        report("legacy returnedRows == data.size()",
                eq(stats.getReturnedRows(), 2L), 2L, stats.getReturnedRows());
        report("legacy sizeBytes recomputed == 8",
                eq(stats.getSizeBytes(), 8L), 8L, stats.getSizeBytes());
    }

    private static boolean eq(Long actual, long expected) {
        return actual != null && actual == expected;
    }

    private static List<Field> fields(String... names) {
        List<Field> fs = new ArrayList<>();
        for (String n : names) {
            Field f = new Field();
            f.setName(n);
            fs.add(f);
        }
        return fs;
    }

    private static List<Object> row(Object... vals) {
        return new ArrayList<>(Arrays.asList(vals));
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-12s actual=%-12s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
