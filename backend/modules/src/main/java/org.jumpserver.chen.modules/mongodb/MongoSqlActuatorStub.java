package org.jumpserver.chen.modules.mongodb;

import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * No-op SQLActuator for Mongo. The framework exposes getSqlActuator() on
 * every ConnectionManager and the ACL review path
 * (ACLFilterImpl.getAffectedRows) may reach it; Mongo has no SQL, so this
 * returns -1 for affected rows and refuses every SQL execution method.
 * MongoQueryConsole must never drive execution through here.
 */
public class MongoSqlActuatorStub implements SQLActuator {

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("SQL execution is not supported on MongoDB");
    }

    @Override
    public DbType getDbType() {
        return DbType.other;
    }

    @Override
    public int getAffectedRows(SQL sql) {
        return -1;
    }

    @Override
    public List<String> parseSQL(SQL sql) {
        return List.of();
    }

    @Override
    public <T> List<T> getObjects(String sql, Class<T> clazz, Map<String, Integer> fieldMapping) {
        return List.of();
    }

    @Override
    public int count(SQL sql) {
        return -1;
    }

    @Override
    public int count(SQLExecutePlan plan) {
        return -1;
    }

    @Override
    public SQLQueryResult execute(SQLExecutePlan plan) {
        throw unsupported();
    }

    @Override
    public SQLQueryResult execute(SQL sql) {
        throw unsupported();
    }

    @Override
    public SQLQueryResult executeWithAudit(SQLExecutePlan plan) {
        throw unsupported();
    }

    @Override
    public SQLQueryResult executeWithAudit(SQL sql) {
        throw unsupported();
    }

    @Override
    public SQLActuator withConnection(Connection connection) {
        return this;
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql) {
        throw unsupported();
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql, SQLQueryParams params) {
        throw unsupported();
    }

    @Override
    public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) {
        throw unsupported();
    }

    @Override
    public String getCurrentSchema() {
        return null;
    }

    @Override
    public List<String> getSchemas() {
        return List.of();
    }

    @Override
    public void changeSchema(String schema) {
    }
}
