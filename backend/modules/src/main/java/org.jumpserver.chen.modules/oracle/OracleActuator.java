package org.jumpserver.chen.modules.oracle;

import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.base.BaseSQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class OracleActuator extends BaseSQLActuator {
    public OracleActuator(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    public OracleActuator(OracleActuator sqlActuator, Connection connection) {
        super(sqlActuator, connection);
    }

    @Override
    public String getCurrentSchema() throws SQLException {
        var result = this.execute(SQL.of("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL"));
        return (String) result.getData().get(0).get(0);
    }

    @Override
    public List<String> getSchemas() throws SQLException {
        var result = this.execute(SQL.of("SELECT USERNAME FROM ALL_USERS"));
        return result.getData().stream().map(row -> (String) row.get(0)).toList();
    }

    @Override
    public void changeSchema(String schema) throws SQLException {
        this.execute(SQL.of("ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdentifier(schema)));
    }

    @Override
    public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) throws SQLException {
        return this.createPreviewPlan(quoteIdentifier(schema) + "." + quoteIdentifier(table), sqlQueryParams);
    }


    private SQL beforeCreatePlan(SQL sql) {
        String text = sql.getSql().stripTrailing();
        if (!text.endsWith(";")) return sql;
        var statements = org.jumpserver.chen.framework.utils.SqlText.analyze(text, com.alibaba.druid.DbType.oracle);
        if (statements.size() != 1) return sql;
        var statement = statements.get(0);
        // JDBC ordinary statements omit the client delimiter; PL/SQL owns its END;.
        if (statement instanceof com.alibaba.druid.sql.ast.statement.SQLBlockStatement
                || statement instanceof com.alibaba.druid.sql.ast.statement.SQLCreateProcedureStatement
                || statement instanceof com.alibaba.druid.sql.ast.statement.SQLCreateFunctionStatement
                || statement instanceof com.alibaba.druid.sql.ast.statement.SQLCreateTriggerStatement
                || statement instanceof com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleCreatePackageStatement) return sql;
        return SQL.bound(text.substring(0, text.length() - 1), sql.getParameters().toArray());
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql, SQLQueryParams params) throws SQLException {
        return super.createPlan(this.beforeCreatePlan(sql), params);
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql) throws SQLException {
        return super.createPlan(this.beforeCreatePlan(sql));
    }

    private static String quoteIdentifier(String value) {
        if (value == null) throw new IllegalArgumentException("Missing database identifier");
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
