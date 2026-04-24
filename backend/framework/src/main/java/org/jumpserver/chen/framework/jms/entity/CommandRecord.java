package org.jumpserver.chen.framework.jms.entity;

import lombok.Data;
import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.wisp.Common;

@Data
public class CommandRecord {

    private String input = "";
    private String output = "";
    private Common.RiskLevel riskLevel = Common.RiskLevel.Normal;
    private String CmdGroupId;
    private String CmdAclId;
    private boolean error = false;

    /**
     * Optional structured execution statistics produced by the audited
     * SQL/NoSQL command. When present, the {@link org.jumpserver.chen.framework.jms.CommandHandler}
     * implementation is expected to attach it to the wisp upload payload
     * (currently as an {@link org.jumpserver.chen.framework.audit.ExecutionStatsEnvelope}
     * suffix on {@code output} until the proto is upgraded).
     */
    private ExecutionStats executionStats;

    public CommandRecord(String input) {
        this.input = input;
    }

    public void setError(String errorMessage) {
        this.error = true;
        this.output = String.format("Error: %s", errorMessage);
    }

    public void setOutput(SQLQueryResult result) {
        if (this.error) {
            this.output = String.format("Error: %s", result.getOutput());
            return;
        }
        if (!result.isHasResultSet()) {
            this.output = result.getOutput();
        } else {
            this.output = String.format("Query OK, %d rows  discovered ", result.getData().size());
        }
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
