package org.jumpserver.chen.framework.console.entity.response;


import lombok.Data;
import org.jumpserver.chen.framework.policy.QueryPolicy;
import org.jumpserver.chen.framework.policy.QueryPolicyHolder;

import java.util.List;

@Data
public class Config {
    private String schema;
    private int maxResultRows;
    private int timeout;
    private boolean autoCommit;
    /**
     * task-02 (R04 §6.3): fixed limit options surfaced to the SQL
     * console limit selector. Sourced from {@link QueryPolicy} so an
     * operator can override via {@code application.yml} without code
     * changes.
     */
    private List<Integer> limitOptions;

    public static Config getDefault() {
        QueryPolicy policy = QueryPolicyHolder.current();
        var cfg = new Config();
        cfg.setSchema("");
        cfg.setMaxResultRows(policy.getDefaultConsoleLimit());
        cfg.setTimeout(policy.getDefaultTimeoutSeconds());
        cfg.setAutoCommit(true);
        cfg.setLimitOptions(policy.getConsoleLimitOptions());
        return cfg;
    }

}
