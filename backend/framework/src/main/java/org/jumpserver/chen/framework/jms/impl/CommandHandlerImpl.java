package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.ExecutionStatsEnvelope;
import org.jumpserver.chen.framework.jms.CommandHandler;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;

@Slf4j
public class CommandHandlerImpl implements CommandHandler {
    private final org.jumpserver.chen.framework.audit.AuditOutbox outbox;
    private final Common.Session session;
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;


    public CommandHandlerImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub serviceBlockingStub) {
        this.outbox = org.jumpserver.chen.framework.audit.AuditOutbox.configured();
        this.session = session;
        this.serviceBlockingStub = serviceBlockingStub;
    }

    private java.util.Map<String, Object> event(CommandRecord record, String output) {
        var event = new java.util.LinkedHashMap<String, Object>();
        event.put("id", record.getAuditId()); event.put("session", session.getId());
        event.put("org_id", session.getOrgId()); event.put("asset", session.getAsset());
        event.put("account", session.getAccount()); event.put("user", session.getUser());
        event.put("timestamp", record.getTimestamp()); event.put("input", record.getInput());
        event.put("output", output); event.put("risk_level", record.getRiskLevel().getNumber());
        return event;
    }

    @Override
    public void beginCommand(CommandRecord record) {
        if (outbox != null) outbox.begin(event(record, "Execution started; outcome pending"));
    }

    @Override
    public void recordCommand(CommandRecord commandRecord) {

        // task-19: carry ACL risk metadata (matched / action / ticket) into the
        // same exec-stats envelope the core entra-patch already persists into
        // exec_extra. Reject/review commands never execute and thus have no
        // ExecutionStats, so synthesize a minimal carrier when one is absent.
        // Values MUST stay string/bool (no float): the task-13 integrity ledger
        // canonicalizes exec_extra and rejects float values.
        ExecutionStats stats = commandRecord.getExecutionStats();
        if (commandRecord.isRiskMatched() || commandRecord.getTicketId() != null) {
            if (stats == null) {
                stats = new ExecutionStats();
            }
            stats.putExtra("risk_matched", commandRecord.isRiskMatched());
            if (commandRecord.getRiskAction() != null) {
                stats.putExtra("risk_action", commandRecord.getRiskAction());
            }
            if (commandRecord.getCmdAclId() != null) {
                stats.putExtra("risk_rule_id", commandRecord.getCmdAclId());
            }
            if (commandRecord.getCmdGroupId() != null) {
                stats.putExtra("risk_group_id", commandRecord.getCmdGroupId());
            }
            if (commandRecord.getTicketId() != null) {
                stats.putExtra("ticket_id", commandRecord.getTicketId());
            }
        }

        if (stats == null) stats = new ExecutionStats();
        if (stats.getRawCommand() == null) stats.setRawCommand(commandRecord.getInput());
        if (commandRecord.isError()) stats.setSuccess(false);
        String output = ExecutionStatsEnvelope.appendTo(
                commandRecord.getOutput(),
                stats
        );

        if (outbox != null) {
            outbox.complete(event(commandRecord, output));
            return;
        }

        var reqBuilder = ServiceOuterClass.CommandRequest
                .newBuilder()
                .setSid(this.session.getId())
                .setOrgId(this.session.getOrgId())
                .setAsset(this.session.getAsset())
                .setAccount(this.session.getAccount())
                .setUser(this.session.getUser())
                .setTimestamp(commandRecord.getTimestamp())
                .setInput(commandRecord.getInput())
                .setOutput(output)
                .setRiskLevel(commandRecord.getRiskLevel());

        if (commandRecord.getCmdAclId() != null && commandRecord.getCmdGroupId() != null) {
            reqBuilder.setCmdAclId(commandRecord.getCmdAclId());
            reqBuilder.setCmdGroupId(commandRecord.getCmdGroupId());
        }

        // This handler is constructed directly, not through an async Spring proxy.
        // Bound transport latency and report failure without retrying a write whose
        // outcome may be unknown (Core has no upload idempotency key).
        try {
            var resp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).uploadCommand(reqBuilder.build());
            if (!resp.getStatus().getOk()) {
                log.error("upload command failed (audit may be lost): {}", resp.getStatus().getErr());
            }
        } catch (Exception e) {
            log.error("upload command transport failed (audit may be lost): {}", e.getMessage(), e);
        }
    }



}
