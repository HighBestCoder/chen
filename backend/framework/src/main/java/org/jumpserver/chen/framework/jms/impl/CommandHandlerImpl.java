package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.ExecutionStatsEnvelope;
import org.jumpserver.chen.framework.jms.CommandHandler;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;
import org.springframework.scheduling.annotation.Async;

@Slf4j
public class CommandHandlerImpl implements CommandHandler {
    private final Common.Session session;
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;


    public CommandHandlerImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub serviceBlockingStub) {
        this.session = session;
        this.serviceBlockingStub = serviceBlockingStub;
    }

    @Override
    @Async
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

        String output = ExecutionStatsEnvelope.appendTo(
                commandRecord.getOutput(),
                stats
        );

        var reqBuilder = ServiceOuterClass.CommandRequest
                .newBuilder()
                .setSid(this.session.getId())
                .setOrgId(this.session.getOrgId())
                .setAsset(this.session.getAsset())
                .setAccount(this.session.getAccount())
                .setUser(this.session.getUser())
                .setTimestamp(System.currentTimeMillis() / 1000)
                .setInput(commandRecord.getInput())
                .setOutput(output)
                .setRiskLevel(commandRecord.getRiskLevel());

        if (commandRecord.getCmdAclId() != null && commandRecord.getCmdGroupId() != null) {
            reqBuilder.setCmdAclId(commandRecord.getCmdAclId());
            reqBuilder.setCmdGroupId(commandRecord.getCmdGroupId());
        }

        // Audit upload runs @Async, so a failure cannot surface as a user-visible
        // error on the query itself. Per the task-14 honesty boundary we do not
        // silently swallow it: log loudly so the Chen->Wisp->Core loss window is
        // observable. We do not block or retry the user's command here.
        try {
            var resp = this.serviceBlockingStub.uploadCommand(reqBuilder.build());
            if (!resp.getStatus().getOk()) {
                log.error("upload command failed (audit may be lost): {}", resp.getStatus().getErr());
            }
        } catch (Exception e) {
            log.error("upload command transport failed (audit may be lost): {}", e.getMessage(), e);
        }
    }



}
