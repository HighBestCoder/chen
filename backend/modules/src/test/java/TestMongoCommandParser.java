import org.jumpserver.chen.modules.mongodb.command.MongoCommandParser;

public class TestMongoCommandParser {
    static int failures = 0;

    public static void main(String[] args) {
        parsesContractIsoDateFind();
        rejectsUnsupportedCommandsClearly();

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

    private static void rejectsUnsupportedCommandsClearly() {
        var parser = new MongoCommandParser();
        try {
            parser.parse("db.order.drop()");
            report("drop rejected", false, "exception", "accepted");
        } catch (RuntimeException e) {
            report("drop rejected", e.getMessage().contains("Unsupported command"), "Unsupported command", e.getMessage());
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
