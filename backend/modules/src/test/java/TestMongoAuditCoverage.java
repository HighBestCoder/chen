import org.jumpserver.chen.framework.audit.ExecutionStatsEnvelope;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.modules.mongodb.command.MongoCommand;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandException;
import org.jumpserver.chen.modules.mongodb.command.MongoExecutionStatsBuilder;
import org.jumpserver.chen.modules.mongodb.command.MongoResultTableAdapter;

import org.bson.Document;

import java.util.List;

public class TestMongoAuditCoverage {
    static int failures = 0;

    public static void main(String[] args) {
        successStatsCarryResultAndNamespace();
        failureStatsCarryRawCommandAndError();
        useCommandStatsCarryDatabaseSwitch();
        envelopeRoundTripPreservesAuditFields();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void successStatsCarryResultAndNamespace() {
        MongoCommand command = MongoCommand.find(
                "db.order.find({marker:\"t12-one\"}).limit(1)",
                "order",
                new Document("marker", "t12-one"),
                null,
                null,
                1);
        SQLQueryResult result = new MongoResultTableAdapter().toResult(
                command.getRawText(),
                "order",
                List.of(new Document("marker", "t12-one").append("city", "北京")),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                1,
                false);

        var stats = MongoExecutionStatsBuilder.fromSuccess(null, command, result);
        report("success db type", "mongodb".equals(stats.getDbType()), "mongodb", stats.getDbType());
        report("success op type", "FIND".equals(stats.getOpType()), "FIND", stats.getOpType());
        report("success raw command", command.getRawText().equals(stats.getRawCommand()), command.getRawText(), stats.getRawCommand());
        report("success returned rows", Long.valueOf(1).equals(stats.getReturnedRows()), 1L, stats.getReturnedRows());
        report("success has result output", result.isHasResultSet(), true, result.isHasResultSet());
    }

    private static void failureStatsCarryRawCommandAndError() {
        String raw = "db.order.drop()";
        var stats = MongoExecutionStatsBuilder.fromFailure(null, raw, new MongoCommandException("Unsupported command"));
        report("failure db type", "mongodb".equals(stats.getDbType()), "mongodb", stats.getDbType());
        report("failure op type", "OTHER".equals(stats.getOpType()), "OTHER", stats.getOpType());
        report("failure raw command", raw.equals(stats.getRawCommand()), raw, stats.getRawCommand());
        report("failure success false", Boolean.FALSE.equals(stats.getSuccess()), false, stats.getSuccess());
        report("failure error message", "Unsupported command".equals(stats.getErrorMessage()), "Unsupported command", stats.getErrorMessage());
    }

    private static void useCommandStatsCarryDatabaseSwitch() {
        MongoCommand command = MongoCommand.useDb("use t12audit", "t12audit");
        var stats = MongoExecutionStatsBuilder.fromSuccess(null, command, null);
        report("use op type", "USE".equals(stats.getOpType()), "USE", stats.getOpType());
        report("use raw command", "use t12audit".equals(stats.getRawCommand()), "use t12audit", stats.getRawCommand());
        report("use success", Boolean.TRUE.equals(stats.getSuccess()), true, stats.getSuccess());
    }

    private static void envelopeRoundTripPreservesAuditFields() {
        String raw = "db.order.drop()";
        var stats = MongoExecutionStatsBuilder.fromFailure(null, raw, new MongoCommandException("Command rejected by ACL"));
        var decoded = ExecutionStatsEnvelope.tryDecode(ExecutionStatsEnvelope.appendTo("Error: Command rejected by ACL", stats))
                .orElseThrow();
        report("envelope db type", "mongodb".equals(decoded.getDbType()), "mongodb", decoded.getDbType());
        report("envelope raw command", raw.equals(decoded.getRawCommand()), raw, decoded.getRawCommand());
        report("envelope error", "Command rejected by ACL".equals(decoded.getErrorMessage()), "Command rejected by ACL", decoded.getErrorMessage());
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-45s actual=%-45s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
