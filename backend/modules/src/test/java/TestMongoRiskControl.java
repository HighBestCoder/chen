import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.ExecutionStatsEnvelope;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandParser;
import org.jumpserver.chen.wisp.Common;

import java.util.List;

public class TestMongoRiskControl {
    static int failures = 0;

    public static void main(String[] args) {
        rejectsConfiguredDropCommand();
        rejectsConfiguredUpdateCommand();
        doesNotMatchSafeFindFieldNames();
        carriesAclMetadataForAudit();
        carriesRiskExtrasInAuditEnvelope();
        approvedCommandHashIsExact();
        parserStillRejectsUnsupportedWrites();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void rejectsConfiguredDropCommand() {
        ACLResult result = filter(Common.CommandACL.Action.Reject)
                .commandACLFilter("db.order.drop()", null);

        report("drop matches reject ACL",
                result.getRiskLevel() == Common.RiskLevel.Reject,
                Common.RiskLevel.Reject, result.getRiskLevel());
        report("drop records reject action",
                "reject".equals(result.getRiskAction()),
                "reject", result.getRiskAction());
    }

    private static void rejectsConfiguredUpdateCommand() {
        ACLResult result = filter(Common.CommandACL.Action.Reject)
                .commandACLFilter("db.order.updateMany({status: 'new'}, {$set: {status: 'done'}})", null);

        report("updateMany matches reject ACL",
                result.getRiskLevel() == Common.RiskLevel.Reject,
                Common.RiskLevel.Reject, result.getRiskLevel());
    }

    private static void doesNotMatchSafeFindFieldNames() {
        ACLResult result = filter(Common.CommandACL.Action.Reject)
                .commandACLFilter("db.order.find({dropZone: 'A'}).limit(10)", null);

        report("safe find field name does not match drop keyword",
                result.getRiskLevel() == Common.RiskLevel.Normal,
                Common.RiskLevel.Normal, result.getRiskLevel());
    }

    private static void carriesAclMetadataForAudit() {
        ACLResult result = filter(Common.CommandACL.Action.Reject)
                .commandACLFilter("db.order.drop()", null);
        CommandRecord record = new CommandRecord("db.order.drop()");
        record.applyACL(result);

        report("audit risk matched",
                record.isRiskMatched(), true, record.isRiskMatched());
        report("audit ACL id",
                "acl-t11".equals(record.getCmdAclId()), "acl-t11", record.getCmdAclId());
        report("audit command group id",
                "group-t11".equals(record.getCmdGroupId()), "group-t11", record.getCmdGroupId());
        report("audit risk action",
                "reject".equals(record.getRiskAction()), "reject", record.getRiskAction());
    }

    private static void carriesRiskExtrasInAuditEnvelope() {
        ACLResult result = filter(Common.CommandACL.Action.Reject)
                .commandACLFilter("db.order.drop()", null);
        CommandRecord record = new CommandRecord("db.order.drop()");
        record.applyACL(result);

        ExecutionStats stats = new ExecutionStats();
        if (record.isRiskMatched() || record.getTicketId() != null) {
            stats.putExtra("risk_matched", record.isRiskMatched());
            stats.putExtra("risk_action", record.getRiskAction());
            stats.putExtra("risk_rule_id", record.getCmdAclId());
            stats.putExtra("risk_group_id", record.getCmdGroupId());
        }
        ExecutionStats decoded = ExecutionStatsEnvelope.tryDecode(
                ExecutionStatsEnvelope.appendTo(record.getOutput(), stats)).orElseThrow();

        report("audit envelope risk_matched",
                Boolean.TRUE.equals(decoded.getExtras().get("risk_matched")), true,
                decoded.getExtras().get("risk_matched"));
        report("audit envelope risk_action",
                "reject".equals(decoded.getExtras().get("risk_action")), "reject",
                decoded.getExtras().get("risk_action"));
        report("audit envelope risk_rule_id",
                "acl-t11".equals(decoded.getExtras().get("risk_rule_id")), "acl-t11",
                decoded.getExtras().get("risk_rule_id"));
        report("audit envelope risk_group_id",
                "group-t11".equals(decoded.getExtras().get("risk_group_id")), "group-t11",
                decoded.getExtras().get("risk_group_id"));
    }

    private static void approvedCommandHashIsExact() {
        String approved = "db.order.drop()";
        String changed = "db.order.dropDatabase()";
        String approvedHash = ACLFilterImpl.commandHash(approved);

        report("approved hash is deterministic",
                approvedHash.equals(ACLFilterImpl.commandHash(approved)),
                approvedHash, ACLFilterImpl.commandHash(approved));
        report("changed command hash differs",
                !approvedHash.equals(ACLFilterImpl.commandHash(changed)),
                "different hash", ACLFilterImpl.commandHash(changed));
    }

    private static void parserStillRejectsUnsupportedWrites() {
        try {
            new MongoCommandParser().parse("db.order.drop()");
            report("restricted parser rejects drop after ACL handling", false, "exception", "accepted");
        } catch (RuntimeException e) {
            report("restricted parser rejects drop after ACL handling",
                    e.getMessage().contains("Unsupported command"),
                    "Unsupported command", e.getMessage());
        }
    }

    private static ACLFilterImpl filter(Common.CommandACL.Action action) {
        Common.CommandGroup group = Common.CommandGroup.newBuilder()
                .setId("group-t11")
                .setName("Mongo high risk")
                .setPattern("\\b(drop|update|updateMany|delete|deleteMany|dropDatabase)\\b")
                .setIgnoreCase(true)
                .build();
        Common.CommandACL acl = Common.CommandACL.newBuilder()
                .setId("acl-t11")
                .setName("Mongo high risk ACL")
                .setAction(action)
                .addCommandGroups(group)
                .build();
        Common.Session session = Common.Session.newBuilder()
                .setId("session-t11")
                .build();
        return new ACLFilterImpl(session, null, List.of(acl));
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-45s actual=%-45s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
