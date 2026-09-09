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
        nestedDocumentsEmitDottedImpactColumnsAndSizes();
        arraysUseStableDottedPathsWithoutIndexes();
        emptyInclusionProjectionRetainsDottedImpactColumns();
        literalDottedTopLevelKeysStillContributeValues();
        parentProjectionDoesNotDoubleCountNestedDocument();
        deeplyNestedDocumentsAreBoundedSafely();

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

    private static void nestedDocumentsEmitDottedImpactColumnsAndSizes() {
        String raw = "db.users.find({}, {\"user.idNo\":1, \"user.phone\":1, \"user.country.address\":1, _id:0}).limit(1)";
        MongoCommand command = MongoCommand.find(raw, "users", new Document(),
                new Document("user.idNo", 1).append("user.phone", 1).append("user.country.address", 1).append("_id", 0),
                null, 1);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "users", List.of(
                new Document("user", new Document("idNo", "110101199001011234")
                        .append("phone", "13800000000")
                        .append("country", new Document("address", "北京")))
        ), System.currentTimeMillis(), System.currentTimeMillis(), 1, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        List<String> expectedImpact = List.of("user.idNo", "user.phone", "user.country.address");
        report("nested impactColumns dotted", expectedImpact.equals(stats.getImpactColumns()),
                expectedImpact, stats.getImpactColumns());
        Object byColumn = stats.getExtras().get("size_by_column");
        Map<String, Long> expectedSize = Map.of(
                "users.user.idNo", 18L,
                "users.user.phone", 11L,
                "users.user.country.address", 6L);
        report("nested size_by_column dotted", expectedSize.equals(byColumn), expectedSize, byColumn);
        Object columnSize = stats.getExtras().get("column_size");
        report("nested column_size alias dotted", expectedSize.equals(columnSize), expectedSize, columnSize);
        report("nested sizeBytes leaf total", Long.valueOf(35L).equals(stats.getSizeBytes()), 35L, stats.getSizeBytes());
    }

    private static void arraysUseStableDottedPathsWithoutIndexes() {
        String raw = "db.users.find({}).limit(1)";
        MongoCommand command = MongoCommand.find(raw, "users", new Document(), null, null, 1);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "users", List.of(
                new Document("orders", List.of(
                        new Document("amount", 100).append("city", "北京"),
                        new Document("amount", 200).append("city", "上海")))
                        .append("tags", List.of("vip", "risk"))
        ), System.currentTimeMillis(), System.currentTimeMillis(), 1, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        List<String> expectedImpact = List.of("orders.amount", "orders.city", "tags");
        report("array impactColumns stable", expectedImpact.equals(stats.getImpactColumns()),
                expectedImpact, stats.getImpactColumns());
        report("array impactColumns no indexes", stats.getImpactColumns().stream().noneMatch(c -> c.contains(".0.") || c.contains(".1.")),
                "no indexed paths", stats.getImpactColumns());
        Object byColumn = stats.getExtras().get("size_by_column");
        Map<String, Long> expectedSize = Map.of(
                "users.orders.amount", 6L,
                "users.orders.city", 12L,
                // Only the actual values "vip" + "risk", not the old
                // synthetic {"_": [...]} display wrapper or JSON separators.
                "users.tags", 7L);
        report("array size_by_column stable", expectedSize.equals(byColumn), expectedSize, byColumn);
    }

    private static void emptyInclusionProjectionRetainsDottedImpactColumns() {
        String raw = "db.users.find({\"user.phone\":\"not-found\"}, {\"user.phone\":1, _id:0}).limit(10)";
        Document projection = new Document("user.phone", 1).append("_id", 0);
        MongoCommand command = MongoCommand.find(raw, "users", new Document("user.phone", "not-found"), projection, null, 10);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "users", List.of(), projection,
                System.currentTimeMillis(), System.currentTimeMillis(), 0, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        report("empty projection dotted impactColumns", List.of("user.phone").equals(stats.getImpactColumns()),
                List.of("user.phone"), stats.getImpactColumns());
        report("empty projection returned rows", Long.valueOf(0L).equals(stats.getReturnedRows()), 0L, stats.getReturnedRows());
        report("empty projection omits _id", !stats.getImpactColumns().contains("_id"), "no _id", stats.getImpactColumns());
    }

    private static void literalDottedTopLevelKeysStillContributeValues() {
        String raw = "db.users.find({}).limit(1)";
        MongoCommand command = MongoCommand.find(raw, "users", new Document(), null, null, 1);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "users", List.of(new Document("a.b", "x")),
                System.currentTimeMillis(), System.currentTimeMillis(), 1, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        report("literal dotted impactColumns", List.of("a.b").equals(stats.getImpactColumns()),
                List.of("a.b"), stats.getImpactColumns());
        report("literal dotted sizeBytes", Long.valueOf(1L).equals(stats.getSizeBytes()), 1L, stats.getSizeBytes());
        Object byColumn = stats.getExtras().get("size_by_column");
        report("literal dotted size_by_column", Map.of("users.a.b", 1L).equals(byColumn),
                Map.of("users.a.b", 1L), byColumn);
    }

    private static void parentProjectionDoesNotDoubleCountNestedDocument() {
        String raw = "db.users.find({}, {user:1, _id:0}).limit(1)";
        Document projection = new Document("user", 1).append("_id", 0);
        MongoCommand command = MongoCommand.find(raw, "users", new Document(), projection, null, 1);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "users", List.of(
                new Document("user", new Document("idNo", "110").append("phone", "138"))
        ), projection, System.currentTimeMillis(), System.currentTimeMillis(), 1, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        List<String> expectedImpact = List.of("user.idNo", "user.phone");
        report("parent projection emits leaves only", expectedImpact.equals(stats.getImpactColumns()),
                expectedImpact, stats.getImpactColumns());
        report("parent projection omits parent", !stats.getImpactColumns().contains("user"), "no parent", stats.getImpactColumns());
    }

    private static void deeplyNestedDocumentsAreBoundedSafely() {
        String raw = "db.users.find({}).limit(1)";
        Document root = new Document();
        Document cursor = root;
        for (int i = 0; i < 48; i++) {
            Document child = new Document();
            cursor.append("l" + i, child);
            cursor = child;
        }
        cursor.append("value", "x");
        MongoCommand command = MongoCommand.find(raw, "users", new Document(), null, null, 1);
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        var result = adapter.toResult(raw, "users", List.of(root),
                System.currentTimeMillis(), System.currentTimeMillis(), 1, false);

        ExecutionStats stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);

        report("deep nesting bounded produces a field", !stats.getImpactColumns().isEmpty(),
                "non-empty impactColumns", stats.getImpactColumns());
        report("deep nesting bounded status ok", "ok".equals(stats.getExtras().get("size_by_column_source_status")),
                "ok", stats.getExtras().get("size_by_column_source_status"));
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-45s actual=%-45s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
