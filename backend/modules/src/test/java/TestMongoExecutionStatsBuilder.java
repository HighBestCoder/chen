import org.bson.Document;
import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.modules.mongodb.command.MongoCommand;
import org.jumpserver.chen.modules.mongodb.command.MongoExecutionStatsBuilder;
import org.jumpserver.chen.modules.mongodb.command.MongoResultTableAdapter;

import java.util.List;
import java.util.Map;

public class TestMongoExecutionStatsBuilder {
    static int failures = 0;

    public static void main(String[] args) {
        projectedFindOmitsExcludedIdAndCarriesRawCommand();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void projectedFindOmitsExcludedIdAndCarriesRawCommand() {
        String raw = "db.t02_size_rows.find({}, {name:1, city:1, _id:0}).limit(5)";
        MongoCommand command = MongoCommand.find(raw, "t02_size_rows", new Document(),
                new Document("name", 1).append("city", 1).append("_id", 0), null, 5);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "t02_size_rows", List.of(
                new Document("name", "alice").append("city", "北京"),
                new Document("name", "bob").append("city", "上海")
        ), System.currentTimeMillis(), System.currentTimeMillis(), 2, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        report("rawCommand carried", raw.equals(stats.getRawCommand()), raw, stats.getRawCommand());
        report("impactColumns omit _id", stats.getImpactColumns().equals(List.of("name", "city")),
                List.of("name", "city"), stats.getImpactColumns());
        report("sizeBytes exact", Long.valueOf(20L).equals(stats.getSizeBytes()), 20L, stats.getSizeBytes());
        Object byColumn = stats.getExtras().get("size_by_column");
        report("size_by_column exact", Map.of("t02_size_rows.name", 8L, "t02_size_rows.city", 12L).equals(byColumn),
                Map.of("t02_size_rows.name", 8L, "t02_size_rows.city", 12L), byColumn);
        Object columnSize = stats.getExtras().get("column_size");
        report("column_size alias exact", Map.of("t02_size_rows.name", 8L, "t02_size_rows.city", 12L).equals(columnSize),
                Map.of("t02_size_rows.name", 8L, "t02_size_rows.city", 12L), columnSize);
        report("column_size alias is independent map", byColumn != columnSize, true, byColumn == columnSize);
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-45s actual=%-45s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
