import org.jumpserver.chen.modules.mongodb.command.MongoCommand;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandParser;

public class TestMongoCommandParser {
    static int failures = 0;

    public static void main(String[] args) {
        parsesContractIsoDateFind();
        parsesAggregatePipeline();
        parsesWriteCommands();
        rejectsUnsupportedOperationsClearly();
        rejectsServerSideJavaScript();
        rejectsMalformedWrites();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void parsesContractIsoDateFind() {
        var parser = new MongoCommandParser();
        var command = parser.parse("db.order.find({createTime: {$gt: ISODate(\"2024-01-01\")}}).limit(10)");

        report("contract collection", "order".equals(command.getCollection()), "order", command.getCollection());
        report("contract limit", Integer.valueOf(10).equals(command.getLimit()), 10, command.getLimit());
        Object date = command.getFilter().get("createTime", org.bson.Document.class).get("$gt");
        report("ISODate becomes BSON date", date instanceof java.util.Date, "java.util.Date", date.getClass().getName());
    }

    private static void parsesAggregatePipeline() {
        var parser = new MongoCommandParser();
        var command = parser.parse(
                "db.order.aggregate([{$match: {status: \"paid\"}}, {$group: {_id: \"$day\", n: {$sum: 1}}}])");

        report("aggregate type", command.getType() == MongoCommand.Type.AGGREGATE,
                MongoCommand.Type.AGGREGATE, command.getType());
        report("aggregate collection", "order".equals(command.getCollection()), "order", command.getCollection());
        report("aggregate stage count", command.getPipeline().size() == 2, 2, command.getPipeline().size());
        report("aggregate first stage is $match", command.getPipeline().get(0).containsKey("$match"),
                "$match", command.getPipeline().get(0).keySet());
        report("aggregate second stage is $group", command.getPipeline().get(1).containsKey("$group"),
                "$group", command.getPipeline().get(1).keySet());
    }

    private static void parsesWriteCommands() {
        var parser = new MongoCommandParser();

        var insertOne = parser.parse("db.order.insertOne({sku: \"A-1\", qty: 3})");
        report("insertOne type", insertOne.getType() == MongoCommand.Type.INSERT,
                MongoCommand.Type.INSERT, insertOne.getType());
        report("insertOne document count", insertOne.getDocuments().size() == 1, 1, insertOne.getDocuments().size());

        var insertMany = parser.parse("db.order.insertMany([{sku: \"A-1\"}, {sku: \"A-2\"}])");
        report("insertMany document count", insertMany.getDocuments().size() == 2, 2, insertMany.getDocuments().size());

        var updateOne = parser.parse("db.order.updateOne({sku: \"A-1\"}, {$set: {qty: 9}})");
        report("updateOne type", updateOne.getType() == MongoCommand.Type.UPDATE,
                MongoCommand.Type.UPDATE, updateOne.getType());
        report("updateOne is not multi", !updateOne.isMulti(), false, updateOne.isMulti());
        report("updateOne carries update doc", updateOne.getUpdate().containsKey("$set"),
                "$set", updateOne.getUpdate().keySet());

        var updateMany = parser.parse("db.order.updateMany({status: \"new\"}, {$set: {status: \"done\"}})");
        report("updateMany is multi", updateMany.isMulti(), true, updateMany.isMulti());

        var deleteOne = parser.parse("db.order.deleteOne({sku: \"A-1\"})");
        report("deleteOne type", deleteOne.getType() == MongoCommand.Type.DELETE,
                MongoCommand.Type.DELETE, deleteOne.getType());
        report("deleteOne is not multi", !deleteOne.isMulti(), false, deleteOne.isMulti());

        var deleteMany = parser.parse("db.order.deleteMany({status: \"stale\"})");
        report("deleteMany is multi", deleteMany.isMulti(), true, deleteMany.isMulti());

        // The contract acceptance command: it must now parse, so that an
        // ACL-approved drop actually reaches the driver instead of being
        // refused by the console after the reviewer already approved it.
        var drop = parser.parse("db.order.drop()");
        report("drop type", drop.getType() == MongoCommand.Type.DROP_COLLECTION,
                MongoCommand.Type.DROP_COLLECTION, drop.getType());
        report("drop collection", "order".equals(drop.getCollection()), "order", drop.getCollection());
    }

    private static void rejectsUnsupportedOperationsClearly() {
        expectFailure("unknown operation names the operation",
                "db.order.renameCollection(\"other\")", "Unsupported operation");
        expectFailure("bare junk is rejected", "select * from order", "Unsupported command");
    }

    private static void rejectsServerSideJavaScript() {
        expectFailure("$where stays banned",
                "db.order.find({$where: \"this.qty > 1\"})", "not allowed");
        expectFailure("mapReduce stays banned",
                "db.order.mapReduce(m, r, {out: \"x\"})", "not allowed");
        expectFailure("$function stays banned",
                "db.order.aggregate([{$addFields: {x: {$function: {body: \"f\"}}}}])", "not allowed");
    }

    private static void rejectsMalformedWrites() {
        expectFailure("updateOne without update doc",
                "db.order.updateOne({sku: \"A-1\"})", "requires both");
        expectFailure("empty update doc",
                "db.order.updateOne({sku: \"A-1\"}, {})", "non-empty");
        expectFailure("deleteMany without filter",
                "db.order.deleteMany()", "requires a filter");
        expectFailure("drop takes no arguments",
                "db.order.drop({force: true})", "does not take arguments");
        expectFailure("aggregate needs a pipeline",
                "db.order.aggregate()", "requires a pipeline");
        expectFailure("aggregate pipeline must be an array",
                "db.order.aggregate({$match: {}})", "Invalid pipeline array");
    }

    private static void expectFailure(String label, String command, String expectedFragment) {
        try {
            new MongoCommandParser().parse(command);
            report(label, false, expectedFragment, "accepted");
        } catch (RuntimeException e) {
            report(label, e.getMessage() != null && e.getMessage().contains(expectedFragment),
                    expectedFragment, e.getMessage());
        }
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-45s actual=%-45s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
