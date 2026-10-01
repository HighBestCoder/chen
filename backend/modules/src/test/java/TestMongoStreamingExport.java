import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.RowConsumer;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.modules.mongodb.command.MongoResultTableAdapter;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/** DEF-27: Mongo export-all streams in two passes instead of retaining the result. */
public class TestMongoStreamingExport {
    public static void main(String[] args) throws Exception {
        MongoResultTableAdapter adapter = new MongoResultTableAdapter();
        List<Document> docs = List.of(new Document("_id", 1).append("a", "x"),
                new Document("_id", 2).append("a", null).append("b", 9_007_199_254_740_993L));

        Sink sink = new Sink();
        int[] passes = {0};
        SQLQueryResult result = adapter.export("db.c.find({})", "c", null, cursors(() -> docs, passes), 2, sink, 1L);
        require(passes[0] == 2, "export must read the source twice");
        require(sink.header.equals(List.of("_id", "a", "b")) && sink.finished, "header must be the union of keys");
        require(sink.rows.equals(List.of(Arrays.asList(1, "x", null), Arrays.asList(2, null, "9007199254740993"))),
                "rows must use the console rendering: " + sink.rows);
        require(result.getTrueReturnedRows() == 2 && result.getData().isEmpty(), "rows must be counted, not retained");
        require(result.getFields().get(1).getType().equals("string"), "type comes from the first non-null value");
        SQLQueryResult inMemory = adapter.toResult("db.c.find({})", "c", docs, 1L, 2L, 2, false);
        require(inMemory.getStreamedSizeBytes() == result.getStreamedSizeBytes()
                && inMemory.getStreamedSizeByColumn().equals(result.getStreamedSizeByColumn()),
                "size statistics must match the in-memory path");

        Sink over = new Sink();
        expectFailure(() -> adapter.export("q", "c", null, cursors(() -> docs, new int[1]), 1, over, 1L),
                MongoResultTableAdapter.EXPORT_LIMIT_MESSAGE);
        require(over.header == null && over.rows.isEmpty(), "an over-limit export must fail before writing");

        int[] changed = {0};
        Supplier<List<Document>> growing = () -> changed[0]++ == 0 ? docs.subList(0, 1)
                : List.of(new Document("_id", 1).append("a", "x").append("late", true));
        expectFailure(() -> adapter.export("q", "c", null, cursors(growing, new int[1]), 5, new Sink(), 1L),
                "Result changed during export");

        Sink empty = new Sink();
        adapter.export("q", "c", new Document("name", 1), cursors(List::of, new int[1]), 5, empty, 1L);
        require(empty.header.equals(List.of("name")) && empty.rows.isEmpty(), "empty export keeps projected columns");
        System.out.println("TestMongoStreamingExport: OK");
    }

    @SuppressWarnings("unchecked")
    private static Supplier<MongoCursor<Document>> cursors(Supplier<List<Document>> data, int[] passes) {
        return () -> {
            passes[0]++;
            Iterator<Document> rows = new ArrayList<>(data.get()).iterator();
            return (MongoCursor<Document>) Proxy.newProxyInstance(MongoCursor.class.getClassLoader(),
                    new Class[]{MongoCursor.class}, (proxy, method, values) -> switch (method.getName()) {
                        case "hasNext" -> rows.hasNext();
                        case "next" -> rows.next();
                        case "close" -> null;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        };
    }

    private interface Action { void run() throws Exception; }

    private static void expectFailure(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (SQLException expected) {
            require(expected.getMessage().startsWith(message), "unexpected failure: " + expected.getMessage());
            return;
        }
        throw new AssertionError("expected failure: " + message);
    }

    private static final class Sink implements RowConsumer {
        List<String> header;
        final List<List<Object>> rows = new ArrayList<>();
        boolean finished;
        public void begin(List<Field> fields) { header = fields.stream().map(Field::getName).toList(); }
        public void accept(List<Object> row) { rows.add(row); }
        public void finish() { finished = true; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
