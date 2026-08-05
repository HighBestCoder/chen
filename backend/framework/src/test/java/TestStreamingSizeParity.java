import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Streaming size-stat parity probe: the incremental {@link SizeCalculator#addRowBytes}
 * accumulated row-by-row (as the streaming fetch loop does) MUST equal the
 * legacy whole-result {@link SizeCalculator#compute} over the same rows, across
 * nulls, CJK, hex-like strings and ragged (short) rows.
 *
 * <p>Run via:</p>
 * <pre>
 *   JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
 *   mvn -pl backend/framework -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q -o
 *   java -cp backend/framework/target/classes:backend/framework/target/test-classes:$(cat /tmp/cp.txt) \
 *        TestStreamingSizeParity
 * </pre>
 */
public class TestStreamingSizeParity {

    static int failures = 0;

    public static void main(String[] args) {
        List<Field> fields = fields("name", "age", "note");

        // ---- Fixed cases ----
        parity("basic ascii", fields, Arrays.asList(
                row("alice", 30, "hello"),
                row("bob", 25, "world")));

        parity("with nulls", fields, Arrays.asList(
                row("alice", null, null),
                row(null, 25, "x")));

        parity("cjk / utf-8", fields, Arrays.asList(
                row("张三", 30, "备注：数据量"),
                row("李四", 25, "日本語テスト")));

        parity("ragged short rows", fields, Arrays.asList(
                Arrays.asList((Object) "onlyname"),
                Arrays.asList((Object) "n", (Object) 1)));

        parity("empty data", fields, new ArrayList<>());

        // ---- A known hand value: "ab"(2) + "cd"(2) over one row, 1 field only counts col0 ----
        long hand = "ab".getBytes(StandardCharsets.UTF_8).length;
        List<Field> oneField = fields("c0");
        List<List<Object>> oneRow = Arrays.asList(row("ab", "cd"));
        long streamed = 0;
        for (List<Object> r : oneRow) {
            streamed += SizeCalculator.addRowBytes(oneField, r);
        }
        report("impact-column bound to fields.size()", streamed == hand, hand, streamed);

        Map<String, Long> byColumn = new LinkedHashMap<>();
        SizeCalculator.addRowBytesByColumn(Arrays.asList("user.name", "user.note"),
                row("张三", "abc", "ignored"), byColumn);
        report("per-column cjk byte size",
                Long.valueOf(6L).equals(byColumn.get("user.name")), 6L, byColumn.get("user.name"));
        report("per-column ascii byte size",
                Long.valueOf(3L).equals(byColumn.get("user.note")), 3L, byColumn.get("user.note"));

        // ---- Randomized fuzz ----
        Random rnd = new Random(42);
        for (int t = 0; t < 200; t++) {
            int cols = 1 + rnd.nextInt(5);
            List<Field> fs = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                fs.add(field("f" + c));
            }
            int rows = rnd.nextInt(50);
            List<List<Object>> data = new ArrayList<>();
            for (int r = 0; r < rows; r++) {
                List<Object> row = new ArrayList<>();
                int rowCols = rnd.nextInt(cols + 2); // sometimes ragged, sometimes wider
                for (int c = 0; c < rowCols; c++) {
                    int k = rnd.nextInt(4);
                    switch (k) {
                        case 0 -> row.add(null);
                        case 1 -> row.add(rnd.nextInt(1_000_000));
                        case 2 -> row.add("值" + rnd.nextInt(999));
                        default -> row.add("s" + rnd.nextInt(999));
                    }
                }
                data.add(row);
            }
            parity("fuzz#" + t, fs, data);
        }

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void parity(String label, List<Field> fields, List<List<Object>> data) {
        long streamed = 0;
        for (List<Object> row : data) {
            streamed += SizeCalculator.addRowBytes(fields, row);
        }
        long whole = SizeCalculator.compute(fields, data).sizeBytes;
        report(label, streamed == whole, whole, streamed);
    }

    private static List<Field> fields(String... names) {
        List<Field> fs = new ArrayList<>();
        for (String n : names) {
            fs.add(field(n));
        }
        return fs;
    }

    private static Field field(String name) {
        Field f = new Field();
        f.setName(name);
        return f;
    }

    private static List<Object> row(Object... vals) {
        return new ArrayList<>(Arrays.asList(vals));
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-10s actual=%-10s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
