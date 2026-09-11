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
        rejectsIgnoredModifiersAndEmptyArguments();
        parsesCursorModifiersInEitherOrder();
        parsesEscapedBackslashBeforeArgumentBoundary();
        validatesDecodedOperatorsWithoutRejectingLiteralText();

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

    private static void validatesDecodedOperatorsWithoutRejectingLiteralText() {
        // Assemble escapes at runtime: Java itself decodes Unicode escapes in source.
        String escapedDollar = "\\" + "u0024";
        expectFailure("escaped $where rejected",
                "db.order.find({\"" + escapedDollar + "where\": \"true\"})", "not allowed");
        expectFailure("escaped nested $function rejected",
                "db.order.aggregate([{$project: {x: {\"" + escapedDollar + "function\": {body: 'f'}}}}])", "not allowed");
        try {
            var command = new MongoCommandParser().parse("db.order.find({note: 'eval $where mapReduce'})");
            report("ordinary text is not executable code",
                    "eval $where mapReduce".equals(command.getFilter().getString("note")), "literal retained", command.getFilter());
        } catch (RuntimeException e) {
            report("ordinary text accepted", false, "parsed", e.getMessage());
        }
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

    private static void rejectsIgnoredModifiersAndEmptyArguments() {
        for (String command : new String[] {
                "db.order.deleteMany({}).limit(1)",
                "db.order.updateMany({}, {$set: {x: 1}}).sort({x: 1})",
                "db.order.drop().limit(1)",
                "db.order.aggregate([]).sort({x: 1})",
                "db.order.aggregate([{$out: 'other'}]).limit(1)",
                "db.order.aggregate([null])",
                "db.order.deleteMany(,{})",
                "db.order.updateMany({},,{$set: {x: 1}})",
                "db.order.find({},)",
                "db.order.find({}).limit(1).limit(2)"
        }) {
            try {
                new MongoCommandParser().parse(command);
                report("invalid command must not reach driver", false, "MongoCommandException", command);
            } catch (org.jumpserver.chen.modules.mongodb.command.MongoCommandException expected) {
                report("invalid command rejected", true, "MongoCommandException", command);
            }
        }
        try {
            new MongoCommandParser().parse("db.order.find({}).limit(9999999999999999999)");
            report("overflow is a parse failure", false, "MongoCommandException", "accepted");
        } catch (RuntimeException e) {
            report("overflow is an audited parse failure",
                    e instanceof org.jumpserver.chen.modules.mongodb.command.MongoCommandException,
                    "MongoCommandException", e.getClass().getSimpleName());
        }
    }

    private static void parsesCursorModifiersInEitherOrder() {
        for (String suffix : new String[] {".sort({x: -1}).limit(3)", ".limit(3).sort({x: -1})"}) {
            try {
                var command = new MongoCommandParser().parse("db.order.find({note: '.sort({x:1})'})" + suffix);
                report("cursor order preserves limit", Integer.valueOf(3).equals(command.getLimit()), 3, command.getLimit());
                report("cursor order preserves sort", Integer.valueOf(-1).equals(command.getSort().get("x")), -1, command.getSort());
            } catch (RuntimeException e) {
                report("valid cursor order accepted", false, "parsed", e.getMessage());
            }
        }
    }

    private static void parsesEscapedBackslashBeforeArgumentBoundary() {
        // BSON filter value ends with one literal backslash, encoded as two.
        String command = "db.order.updateOne({path: \"C:\\\\\"}, {$set: {ok: true}})";
        try {
            var parsed = new MongoCommandParser().parse(command);
            report("escaped backslash does not swallow next argument",
                    "C:\\".equals(parsed.getFilter().getString("path")) && parsed.getUpdate().containsKey("$set"),
                    "filter and update", parsed.getFilter());
        } catch (RuntimeException e) {
            report("escaped backslash accepted", false, "parsed", e.getMessage());
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
