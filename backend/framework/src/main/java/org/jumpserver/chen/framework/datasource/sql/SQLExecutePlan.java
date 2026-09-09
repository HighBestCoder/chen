package org.jumpserver.chen.framework.datasource.sql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.policy.QueryPolicyHolder;
import org.jumpserver.chen.framework.utils.PageUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;


@Setter
@Getter
@Slf4j
public class SQLExecutePlan {
    private final String sourceSQL;

    private SQLActuator sqlActuator;
    private String targetSQL;
    private final DbType druidDbType;
    private Statement statement;
    private Connection connection;
    private ACLResult aclResult;


    private boolean counted;

    private boolean manualLimitDetected;
    private int queryLimit = -1;
    private String limitSource;

    private RowConsumer rowConsumer;

    private SQLQueryParams sqlQueryParams = new SQLQueryParams();


    public SQLExecutePlan(String sql, DbType druidDbType) {
        this.sourceSQL = sql;
        this.targetSQL = sql;
        this.druidDbType = druidDbType;
    }

    public void generateTargetSQL() throws SQLException {
        this.targetSQL = this.sourceSQL;
        this.manualLimitDetected = false;
        this.queryLimit = -1;
        this.limitSource = null;

        if (this.sqlQueryParams == null) {
            return;
        }

        // task-02 (R01/R04): clamp the requested limit to the configured
        // hard cap before any pagination rewrite happens. -1 (no limit
        // requested) is preserved so the existing "explicit unlimited"
        // path keeps working; everything else is min(requested, maxRows).
        var policy = QueryPolicyHolder.current();
        int clamped = policy.clampLimit(this.sqlQueryParams.getLimit());
        if (clamped != this.sqlQueryParams.getLimit()) {
            log.info("QueryPolicy clamped limit {} -> {}", this.sqlQueryParams.getLimit(), clamped);
            this.sqlQueryParams.setLimit(clamped);
        }

        // A limit of -1 means "no limit". The streaming export path (rowConsumer
        // set) legitimately wants the full result and is memory-safe because it
        // writes each row to disk instead of retaining it; keep -1 there. The
        // interactive query / console path must NOT bypass the row cap (contract
        // §2.3: "只返回选中数量以内"), so force -1 up to the hard maxRows before the
        // LIMIT rewrite runs.
        if (this.sqlQueryParams.getLimit() == -1 && this.rowConsumer == null) {
            this.sqlQueryParams.setLimit(policy.getMaxRows());
        }

        if (this.sqlQueryParams.getLimit() == -1) {
            return;
        }

        if (this.getTargetSQLStatement() instanceof SQLSelectStatement selectStatement) {
            int manualLimit = PageUtils.getLimit(this.targetSQL, this.druidDbType);
            if (manualLimit > -1) {
                this.manualLimitDetected = true;
                this.queryLimit = manualLimit;
                this.limitSource = "manual";
                return;
            }
            this.queryLimit = this.getSqlQueryParams().getLimit();
            this.limitSource = this.sqlQueryParams.getLimitSource() != null
                    ? this.sqlQueryParams.getLimitSource() : "toolbar";
            this.targetSQL = PageUtils.limit(selectStatement.toString(),
                    this.druidDbType,
                    this.getSqlQueryParams().getOffset(),
                    this.getSqlQueryParams().getLimit());

            if (this.getSqlQueryParams().getOffset() < 0) {
                this.close();
                throw new SQLException(MessageUtils.get("msg.error.already_first_page"));
            }

            var count = this.sqlActuator.count(this);
            if (count > 0 && this.getSqlQueryParams().getOffset() >= count) {
                this.close();
                throw new SQLException(MessageUtils.get("msg.error.already_last_page"));
            }
        }
    }


    public SQLQueryResult execute() throws SQLException {
        return this.sqlActuator.execute(this);
    }

    public SQLQueryResult executeWithAudit() throws SQLException {
        return this.sqlActuator.executeWithAudit(this);
    }


    public Statement createStatement() throws SQLException {
        if (this.statement == null || this.statement.isClosed()) {
            this.statement = this.connection.createStatement();
        }
        return this.statement;
    }

    public SQLStatement getTargetSQLStatement() {
        return SQLUtils.parseSingleStatement(this.targetSQL, this.druidDbType.name());
    }

    public void cancel() throws SQLException {
        this.statement.cancel();
    }

    public void close() {
        try {
            if (this.connection instanceof DruidPooledConnection) {
                this.connection.close();
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        }
    }

}
