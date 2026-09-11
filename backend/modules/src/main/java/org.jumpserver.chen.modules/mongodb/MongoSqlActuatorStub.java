package org.jumpserver.chen.modules.mongodb;

import com.alibaba.druid.DbType;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.datasource.sql.RowConsumer;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.modules.mongodb.command.MongoCommand;
import org.jumpserver.chen.modules.mongodb.command.MongoExecutionStatsBuilder;
import org.jumpserver.chen.modules.mongodb.command.MongoResultTableAdapter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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

    private static final int EXPORT_MAX = 100_000;
    private static final Document STABLE_SORT = new Document("_id", 1);

    private final MongoConnectionManager connectionManager;
    private final MongoResultTableAdapter adapter = new MongoResultTableAdapter();

    public MongoSqlActuatorStub(MongoConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

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
    public int count(SQLExecutePlan plan) throws SQLException {
        try {
            String table = collectionFromPlan(plan);
            long count = this.connectionManager.getDatabase(currentDatabase()).getCollection(table).countDocuments();
            return (int) Math.min(count, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    @Override
    public SQLQueryResult execute(SQLExecutePlan plan) throws SQLException {
        try {
            return executePreviewPlan(plan);
        } catch (RuntimeException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    @Override
    public SQLQueryResult execute(SQL sql) {
        throw unsupported();
    }

    @Override
    public SQLQueryResult executeWithAudit(SQLExecutePlan plan) throws SQLException {
        CommandRecord record = new CommandRecord(plan.getTargetSQL());
        record.applyACL(plan.getAclResult());
        MongoCommand command = commandFromPlan(plan);
        SessionManager.getCurrentSession().beginCommand(record);
        try {
            SQLQueryResult result = execute(plan);
            record.setOutput(result);
            record.setExecutionStats(MongoExecutionStatsBuilder.fromSuccess(
                    this.connectionManager,
                    command,
                    result));
            return result;
        } catch (SQLException e) {
            record.setError(e.getMessage());
            record.setExecutionStats(MongoExecutionStatsBuilder.fromFailure(
                    this.connectionManager,
                    command,
                    e));
            throw e;
        } finally {
            SessionManager.getCurrentSession().recordCommand(record);
        }
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
        SQLExecutePlan plan = new MongoPreviewPlan(table);
        plan.setSqlActuator(this);
        plan.setSqlQueryParams(sqlQueryParams == null ? new SQLQueryParams() : sqlQueryParams);
        return plan;
    }

    private final class MongoPreviewPlan extends SQLExecutePlan {
        private final String collection;
        private MongoPreviewPlan(String collection) {
            super(previewCommand(collection), DbType.other);
            this.collection = collection;
        }
        @Override
        public void generateTargetSQL() {
            setTargetSQL(getSourceSQL());
        }
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

    private SQLQueryResult executePreviewPlan(SQLExecutePlan plan) throws SQLException {
        String collectionName = collectionFromPlan(plan);
        String databaseName = currentDatabase();
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = this.connectionManager
                .getDatabase(databaseName)
                .getCollection(collectionName);

        FindIterable<Document> iterable = collection.find().sort(STABLE_SORT);
        SQLQueryParams params = plan.getSqlQueryParams() == null ? new SQLQueryParams() : plan.getSqlQueryParams();
        if (params.getOffset() > 0) {
            iterable = iterable.skip(params.getOffset());
        }
        iterable = iterable.limit(resolveLimit(params.getLimit()));

        List<Document> documents = new ArrayList<>();
        int cap = resolveLimit(params.getLimit());
        boolean truncated;
        try (var cursor = iterable.limit(cap + 1).iterator()) {
            while (documents.size() < cap && cursor.hasNext()) documents.add(cursor.next());
            truncated = cursor.hasNext();
        }
        long queryDone = System.currentTimeMillis();
        long total = collection.countDocuments();
        SQLQueryResult result = this.adapter.toResult(
                commandText(collectionName, params.getLimit()),
                collectionName, documents, start, queryDone, total, params.getLimit() >= 0);

        result.setTruncated(params.getLimit() < 0 && truncated);
        if (result.isTruncated() && plan.getRowConsumer() != null) {
            throw new SQLException("Export exceeds the MongoDB row limit; narrow the query before exporting");
        }
        streamRowsIfRequested(result, plan.getRowConsumer());
        return result;
    }

    private void streamRowsIfRequested(SQLQueryResult result, RowConsumer sink) throws SQLException {
        if (sink == null) {
            return;
        }
        sink.begin(result.getFields());
        for (List<Object> row : result.getData()) {
            sink.accept(row);
        }
        sink.finish();
    }

    private int resolveLimit(int requested) {
        if (requested < 0) {
            return EXPORT_MAX;
        }
        return requested > 0 ? Math.min(requested, EXPORT_MAX) : 50;
    }

    private String collectionFromPlan(SQLExecutePlan plan) throws SQLException {
        if (!(plan instanceof MongoPreviewPlan preview)) {
            throw new SQLException("Unsupported Mongo preview plan");
        }
        return preview.collection;
    }

    private String currentDatabase() throws SQLException {
        String db = this.connectionManager.getCurrentDatabaseName();
        if (db == null || db.isEmpty()) {
            throw new SQLException("No database selected. Use 'use <db>' first.");
        }
        return db;
    }

    private String previewCommand(String collection) {
        return String.format("db.%s.find({}).limit(50)", collection);
    }

    private MongoCommand commandFromPlan(SQLExecutePlan plan) throws SQLException {
        String collection = collectionFromPlan(plan);
        SQLQueryParams params = plan.getSqlQueryParams() == null ? new SQLQueryParams() : plan.getSqlQueryParams();
        Integer limit = params.getLimit() < 0 ? null : resolveLimit(params.getLimit());
        return MongoCommand.find(commandText(collection, params.getLimit()), collection, new Document(), null, null, limit);
    }

    private String commandText(String collection, int requestedLimit) {
        if (requestedLimit < 0) {
            return String.format("db.%s.find({})", collection);
        }
        return String.format("db.%s.find({}).limit(%d)", collection, resolveLimit(requestedLimit));
    }
}
