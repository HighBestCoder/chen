import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.command.*;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import java.util.*;

/** Lossless display/export payloads and independent full-document size checks. */
public class TestMongoDocumentCompleteness {
    private static int failures;

    public static void main(String[] args) {
        check("wide documents", TestMongoDocumentCompleteness::wideDocuments);
        check("long arrays", TestMongoDocumentCompleteness::longArrays);
        check("heterogeneous shapes", TestMongoDocumentCompleteness::heterogeneousShapes);
        check("deep documents", TestMongoDocumentCompleteness::deepDocuments);
        check("array structure", TestMongoDocumentCompleteness::arrayStructure);
        check("large integer precision", () -> {
            var result = result(List.of(new Document("n", 9007199254740993L)));
            require("9007199254740993".equals(row(result, 0).get("n")), "int64 would round in browser");
            require(result.getStreamedSizeBytes() == 16, "int64 size changed");
        });
        if (failures != 0) throw new AssertionError(failures + " completeness cases failed");
        System.out.println("OK: all completeness cases passed");
    }

    private static SQLQueryResult result(List<Document> documents) {
        return new MongoResultTableAdapter().toResult("db.order.find({})", "order", documents, 1, 2, documents.size(), false);
    }

    private static Map<String, Object> row(SQLQueryResult result, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < result.getFields().size(); i++) {
            row.put(result.getFields().get(i).getName(), result.getData().get(index).get(i));
        }
        return row;
    }

    private static Map<?, ?> sizes(SQLQueryResult result) {
        var command = new MongoCommandParser().parse("db.order.find({})");
        var stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);
        require(Objects.equals(stats.getSizeBytes(), result.getStreamedSizeBytes()), "display/audit size differs");
        require(Objects.equals(stats.getExtras().get("size_by_column"), stats.getExtras().get("column_size")), "column_size alias differs");
        return (Map<?, ?>) stats.getExtras().get("size_by_column");
    }

    private static void wideDocuments() {
        Document first = new Document();
        Document second = new Document();
        for (int i = 0; i < 700; i++) first.put("f" + i, "中");
        second.put("late", "尾");
        var result = result(List.of(first, second));
        require(result.getFields().size() == 701, "fields after 512 or in later documents were dropped");
        require("中".equals(row(result, 0).get("f699")) && "尾".equals(row(result, 1).get("late")), "tail values missing");
        require(result.getStreamedSizeBytes() == 2103, "expected 701 UTF-8 Chinese values at 3 bytes each");
        require(sizes(result).size() == 701 && Long.valueOf(3).equals(sizes(result).get("order.f699")), "wide per-column statistics incomplete");
    }

    private static void longArrays() {
        List<Document> items = new ArrayList<>();
        for (int i = 0; i < 300; i++) items.add(new Document("city", "北京"));
        items.get(299).put("tail", "末");
        var result = result(List.of(new Document("items", items)));
        Object cell = row(result, 0).get("items");
        require(cell instanceof String, "object array is not preserved as a JSON array");
        List<?> decoded = Document.parse("{v:" + cell + "}").getList("v", Object.class);
        require(decoded.size() == 300 && ((Document) decoded.get(299)).getString("tail").equals("末"), "array tail lost");
        require(result.getStreamedSizeBytes() == 1803, "array size stopped at 256 elements");
        require(Long.valueOf(1800).equals(sizes(result).get("order.items.city")), "array leaf sizes incomplete");
        require(Long.valueOf(3).equals(sizes(result).get("order.items.tail")), "late array field missing");
    }

    private static void heterogeneousShapes() {
        var result = result(List.of(new Document("a", "scalar"), new Document("a", new Document("b", "nested"))));
        require("scalar".equals(row(result, 0).get("a")), "scalar overwritten by another row's nested path");
        require(Document.parse(row(result, 1).get("a").toString()).getString("b").equals("nested"), "nested object lost");
        require(result.getStreamedSizeBytes() == 12, "heterogeneous leaves double-counted or omitted");
        require(Long.valueOf(6).equals(sizes(result).get("order.a")) && Long.valueOf(6).equals(sizes(result).get("order.a.b")), "heterogeneous statistics missing");
        var dotted = result(List.of(new Document("a.b", "literal").append("a", new Document("b", "nested"))));
        require("literal".equals(row(dotted, 0).get("a.b")) && row(dotted, 0).containsKey("a"), "literal dotted key collides with nested object");
        require(dotted.getStreamedSizeBytes() == 13, "literal/nested path collision lost bytes");
    }

    private static void deepDocuments() {
        Document root = new Document();
        Document node = root;
        for (int i = 0; i < 48; i++) {
            Document child = new Document(); node.put("l" + i, child); node = child;
        }
        node.put("value", "深");
        var result = result(List.of(root));
        require(row(result, 0).get("l0").toString().contains("深"), "deep leaf not rendered");
        require(result.getStreamedSizeBytes() == 3, "deep leaf not counted exactly once");
    }

    private static void arrayStructure() {
        List<Object> source = Arrays.asList(new Document("n", 1), null, List.of(2, 3), "逗,号", new Document());
        var result = result(List.of(new Document("items", source).append("empty", new Document())));
        Object cell = row(result, 0).get("items");
        require(cell.toString().startsWith("["), "array wrapped in synthetic object");
        require(Document.parse("{v:" + cell + "}").get("v").equals(source), "array boundaries/nulls/values changed");
        require("{}".equals(row(result, 0).get("empty")), "empty object omitted");
    }

    private static void check(String name, Runnable test) {
        try { test.run(); System.out.println("ok " + name); }
        catch (RuntimeException | AssertionError e) { failures++; System.out.println("FAIL " + name + ": " + e.getMessage()); }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
