import org.bson.Document;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.MongoActuator;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandParser;
import java.util.List;

/**
 * Live probe: drives aggregate and the write commands through the real Mongo
 * driver, so the parser/actuator wiring is proven end to end rather than only
 * at the parse layer.
 *
 * Requires a reachable MongoDB. Point it with MONGO_HOST / MONGO_PORT
 * (defaults 127.0.0.1:27017). It works in a scratch database and drops the
 * collection it creates.
 */
public class TestMongoWriteExecution {
    static int failures = 0;

    private static final String DB = "t_a1_write_" + java.util.UUID.randomUUID().toString().replace("-", "");
    private static final String COLL = "order";

    public static void main(String[] args) throws Exception {
        String host = System.getenv().getOrDefault("MONGO_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("MONGO_PORT", "27017"));

        DBConnectInfo info = new DBConnectInfo();
        info.setDbType("mongodb");
        info.setHost(host);
        info.setPort(port);
        info.setDb(DB);

        if (args.length > 0 && args[0].equals("--fixture")) {
            info = TestConnectionTlsIntegration.info("mongodb");
        }
        MongoConnectionManager cm = new MongoConnectionManager(info, null);
        cm.setDatabaseContext(DB);
        MongoActuator actuator = new MongoActuator(cm);
        MongoCommandParser parser = new MongoCommandParser();

        try {
            cm.getDatabase(DB).getCollection(COLL).drop();

            insertsReportInsertedCount(parser, actuator);
            findSeesInsertedRows(parser, actuator);
            updateManyReportsModifiedCount(parser, actuator);
            deleteOneReportsDeletedCount(parser, actuator);
            aggregateGroupsRows(parser, actuator);
            aggregateWithoutLimitIsCapped(parser, actuator);
            aggregateKeepsAuthorsOwnLimit(parser, actuator);
            aggregateTerminalWriteAndExpansion(parser, actuator, cm);
            complexDocumentsRemainComplete(parser, actuator, cm);
            dropRemovesCollection(parser, actuator, cm);
        } finally {
            try {
                cm.getDatabase(DB).drop();
            } catch (RuntimeException ignore) {
            }
            cm.close();
        }

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void insertsReportInsertedCount(MongoCommandParser parser, MongoActuator actuator) {
        SQLQueryResult one = run(parser, actuator,
                "db." + COLL + ".insertOne({sku: \"A-1\", status: \"new\", qty: 2})");
        report("insertOne affected", one.getUpdateCount() == 1, 1, one.getUpdateCount());
        report("insertOne has no result set", !one.isHasResultSet(), false, one.isHasResultSet());
        report("insertOne output", "Query OK, 1 rows affected".equals(one.getOutput()),
                "Query OK, 1 rows affected", one.getOutput());

        SQLQueryResult many = run(parser, actuator,
                "db." + COLL + ".insertMany([{sku: \"A-2\", status: \"new\", qty: 5},"
                        + " {sku: \"A-3\", status: \"done\", qty: 7}])");
        report("insertMany affected", many.getUpdateCount() == 2, 2, many.getUpdateCount());
    }

    private static void findSeesInsertedRows(MongoCommandParser parser, MongoActuator actuator) {
        SQLQueryResult result = run(parser, actuator, "db." + COLL + ".find({})");
        report("find sees 3 rows", result.getData().size() == 3, 3, result.getData().size());
        report("find has result set", result.isHasResultSet(), true, result.isHasResultSet());
    }

    private static void updateManyReportsModifiedCount(MongoCommandParser parser, MongoActuator actuator) {
        SQLQueryResult result = run(parser, actuator,
                "db." + COLL + ".updateMany({status: \"new\"}, {$set: {status: \"done\"}})");
        report("updateMany modified 2", result.getUpdateCount() == 2, 2, result.getUpdateCount());

        SQLQueryResult remaining = run(parser, actuator, "db." + COLL + ".find({status: \"new\"})");
        report("no 'new' rows remain", remaining.getData().isEmpty(), 0, remaining.getData().size());
    }

    private static void deleteOneReportsDeletedCount(MongoCommandParser parser, MongoActuator actuator) {
        SQLQueryResult result = run(parser, actuator, "db." + COLL + ".deleteOne({sku: \"A-3\"})");
        report("deleteOne deleted 1", result.getUpdateCount() == 1, 1, result.getUpdateCount());

        SQLQueryResult left = run(parser, actuator, "db." + COLL + ".find({})");
        report("2 rows left after delete", left.getData().size() == 2, 2, left.getData().size());
    }

    private static void aggregateGroupsRows(MongoCommandParser parser, MongoActuator actuator) {
        SQLQueryResult result = run(parser, actuator,
                "db." + COLL + ".aggregate([{$group: {_id: \"$status\", total: {$sum: \"$qty\"}}}])");
        report("aggregate returns one group", result.getData().size() == 1, 1, result.getData().size());
        report("aggregate has result set", result.isHasResultSet(), true, result.isHasResultSet());

        int totalIdx = fieldIndex(result, "total");
        report("aggregate exposes computed column", totalIdx >= 0, ">=0", totalIdx);
        if (totalIdx >= 0) {
            Object total = result.getData().get(0).get(totalIdx);
            report("aggregate $sum is 7", total instanceof Number && ((Number) total).intValue() == 7,
                    7, total);
        }
    }

    private static void aggregateWithoutLimitIsCapped(MongoCommandParser parser, MongoActuator actuator) {
        String bulk = "db." + COLL + "_bulk";
        StringBuilder docs = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            docs.append(i > 0 ? "," : "").append("{n: ").append(i).append("}");
        }
        docs.append("]");
        run(parser, actuator, bulk + ".insertMany(" + docs + ")");

        // Console limit 5, pipeline declares no $limit -> the actuator appends one.
        SQLQueryResult capped = execute(parser, actuator, bulk + ".aggregate([{$match: {}}])", 5);
        report("unbounded aggregate capped to console limit", capped.getData().size() == 5,
                5, capped.getData().size());

        run(parser, actuator, bulk + ".drop()");
    }

    private static void aggregateKeepsAuthorsOwnLimit(MongoCommandParser parser, MongoActuator actuator) {
        String bulk = "db." + COLL + "_bulk2";
        StringBuilder docs = new StringBuilder("[");
        for (int i = 0; i < 20; i++) {
            docs.append(i > 0 ? "," : "").append("{n: ").append(i).append("}");
        }
        docs.append("]");
        run(parser, actuator, bulk + ".insertMany(" + docs + ")");

        // Pipeline declares $limit 3 while the console asks for 5 -> the
        // pipeline's own limit wins, mirroring the manual-LIMIT rule.
        SQLQueryResult own = execute(parser, actuator,
                bulk + ".aggregate([{$match: {}}, {$limit: 3}])", 5);
        report("pipeline $limit wins over console limit", own.getData().size() == 3,
                3, own.getData().size());
        SQLQueryResult chained = execute(parser, actuator,
                bulk + ".aggregate([{$limit: 10}]).limit(2)", 5);
        report("aggregate cursor limit is not ignored", chained.getData().size() == 2,
                2, chained.getData().size());

        run(parser, actuator, bulk + ".drop()");
    }

    private static void dropRemovesCollection(MongoCommandParser parser, MongoActuator actuator,
                                              MongoConnectionManager cm) {
        SQLQueryResult result = run(parser, actuator, "db." + COLL + ".drop()");
        report("drop has no result set", !result.isHasResultSet(), false, result.isHasResultSet());

        boolean stillThere = false;
        for (String name : cm.getDatabase(DB).listCollectionNames()) {
            if (COLL.equals(name)) {
                stillThere = true;
            }
        }
        report("collection is gone after drop", !stillThere, false, stillThere);
    }

    private static void aggregateTerminalWriteAndExpansion(MongoCommandParser parser, MongoActuator actuator,
                                                            MongoConnectionManager cm) {
        var collection = cm.getDatabase(DB).getCollection("bounds");
        List<Integer> values = new java.util.ArrayList<>();
        for (int i = 0; i < 1500; i++) values.add(i);
        collection.insertOne(new Document("items", values));
        var expanded = execute(parser, actuator,
                "db.bounds.aggregate([{$limit: 1}, {$unwind: '$items'}])", 5);
        report("limit before unwind cannot exhaust GUI memory", expanded.getData().size() == 1000,
                1000, expanded.getData().size());
        report("expanded result reports truncation", expanded.isTruncated(), true, expanded.isTruncated());

        var out = execute(parser, actuator, "db.bounds.aggregate([{$unwind: '$items'}, {$project: {_id: 0}}, {$out: 'bounds_out'}])", 5);
        long stored = cm.getDatabase(DB).getCollection("bounds_out").countDocuments();
        report("terminal $out is not followed by injected limit", stored == 1500, 1500L, stored);
        var zero = execute(parser, actuator, "db.bounds_out.find({}).limit(0)", 5);
        report("limit zero is bounded on GUI path", zero.getData().size() == 1000, 1000, zero.getData().size());
    }

    private static void complexDocumentsRemainComplete(MongoCommandParser parser, MongoActuator actuator,
                                                       MongoConnectionManager cm) {
        Document document = new Document();
        for (int i = 0; i < 600; i++) document.put("f" + i, "中");
        List<Document> items = new java.util.ArrayList<>();
        for (int i = 0; i < 300; i++) items.add(new Document("city", "北京"));
        document.put("items", items);
        cm.getDatabase(DB).getCollection("complete").insertOne(document);
        var result = execute(parser, actuator, "db.complete.find({}, {_id: 0})", 5);
        report("driver result retains all wide fields", result.getFields().size() == 601, 601, result.getFields().size());
        int arrayIndex = fieldIndex(result, "items");
        int count = arrayIndex < 0 ? -1 : Document.parse("{v:" + result.getData().get(0).get(arrayIndex) + "}")
                .getList("v", Document.class).size();
        report("driver result retains all array elements", count == 300, 300, count);
        report("driver result counts all UTF-8 leaves", result.getStreamedSizeBytes() == 3600, 3600L, result.getStreamedSizeBytes());
    }

    private static int fieldIndex(SQLQueryResult result, String name) {
        for (int i = 0; i < result.getFields().size(); i++) {
            if (name.equals(result.getFields().get(i).getName())) {
                return i;
            }
        }
        return -1;
    }

    private static SQLQueryResult run(MongoCommandParser parser, MongoActuator actuator, String command) {
        return execute(parser, actuator, command, 100);
    }

    private static SQLQueryResult execute(MongoCommandParser parser, MongoActuator actuator,
                                          String command, int limit) {
        return actuator.execute(parser.parse(command), 0, limit);
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-35s actual=%-35s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
