package org.jumpserver.chen.framework.datasource.base;

import com.alibaba.druid.DbType;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.alibaba.druid.sql.PagerUtils;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.audit.ColumnSizeKeyResolver;
import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.jms.exception.CommandRejectException;
import org.jumpserver.chen.framework.policy.QueryPolicyHolder;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.HexUtils;
import org.jumpserver.chen.framework.utils.PageUtils;
import org.jumpserver.chen.framework.utils.ReflectUtils;

import java.math.BigInteger;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class BaseSQLActuator implements SQLActuator {

    @Getter
    private final DbType druidDbType;
    private ConnectionManager connectionManager;
    private Connection connection;

    public DbType getDbType() {
        return druidDbType;
    }

    public BaseSQLActuator(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.druidDbType = connectionManager.getDatasource().getDruidDbType();
    }

    protected BaseSQLActuator(BaseSQLActuator sqlActuator, Connection connection) {
        this.druidDbType = sqlActuator.getDruidDbType();
        this.connection = connection;
    }


    @Override
    public int getAffectedRows(SQL sql) throws SQLException {
        var result = 0;

        var sqlStmts = SQLUtils.parseStatements(sql.getSql(), this.druidDbType);

        if (sqlStmts.size() != 1) {
            return -1;
        }

        var sqlStmt = sqlStmts.get(0);

        if (sqlStmt instanceof SQLUpdateStatement || sqlStmt instanceof SQLDeleteStatement || sqlStmt instanceof SQLInsertStatement) {
            var conn = this.getConnection();
            try {
                conn.setAutoCommit(false);
                var stmt = conn.createStatement();
                stmt.execute(sqlStmt.toString());

                result = stmt.getUpdateCount();

                conn.rollback();
                stmt.close();
            } finally {
                if (this.connection == null) {
                    conn.close();
                } else {
                    conn.setAutoCommit(true);
                }
            }
        }
        return result;
    }

    @Override
    public List<String> parseSQL(SQL sql) {
        return SQLUtils.parseStatements(sql.getSql(), this.druidDbType).stream()
                .map(stmt -> SQLUtils.toSQLString(stmt, this.druidDbType))
                .toList();
    }

    @Override
    public <T> List<T> getObjects(String sql, Class<T> clazz, Map<String, Integer> fieldMapping) throws SQLException {
        List<T> objects = new ArrayList<>();
        try (Connection conn = this.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                T object = clazz.getDeclaredConstructor().newInstance();
                for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                    if (entry.getValue() == null || entry.getValue() < 1 || entry.getValue() > rs.getMetaData().getColumnCount()) {
                        continue;
                    }
                    ReflectUtils.setFieldValue(object, entry.getKey(), rs.getObject(entry.getValue()));
                }
                objects.add(object);
            }
        } catch (Exception e) {
            var msg = "run sql %s error, %s".formatted(sql, e.getMessage());
            throw new SQLException(msg);
        }
        return objects;
    }

    @Override
    public SQLQueryResult execute(SQL sql) throws SQLException {
        var plan = this.createPlan(sql);
        return plan.execute();
    }


    @Override
    public SQLQueryResult execute(SQLExecutePlan plan) throws SQLException {
        String sql = plan.getTargetSQL();
        SQLQueryResult result = new SQLQueryResult(sql);
        result.setAclResult(plan.getAclResult());
        result.setOriginalCommand(plan.getSourceSQL());
        result.setExecutedCommand(plan.getTargetSQL());
        result.setQueryLimit(plan.getQueryLimit());
        result.setLimitSource(plan.getLimitSource());
        result.setManualLimitDetected(plan.isManualLimitDetected());
        boolean streamingExport = plan.getRowConsumer() != null;
        Connection planConn = plan.getConnection();
        Boolean priorAutoCommit = null;
        try {
            // Streaming export reads the full result row-by-row to disk. For a
            // server-side cursor to actually stream (rather than the driver
            // buffering the whole result client-side and risking OOM), some
            // drivers — notably PostgreSQL — require autoCommit=false. A SELECT
            // commits nothing, so toggling this for the read is invisible to the
            // client; we restore the prior value in finally so the pooled
            // connection is handed back unchanged.
            if (streamingExport && planConn != null && planConn.getAutoCommit()) {
                priorAutoCommit = Boolean.TRUE;
                planConn.setAutoCommit(false);
            }
            Statement statement = plan.createStatement();
            applyQueryTimeout(statement, plan);
            applyFetchStreaming(statement, plan);
            this.executeStatement(plan, statement, result);
        } finally {
            if (priorAutoCommit != null) {
                try {
                    planConn.commit();
                } catch (SQLException e) {
                    log.debug("commit after streaming export failed (non-fatal): {}", e.getMessage());
                }
                try {
                    planConn.setAutoCommit(priorAutoCommit);
                } catch (SQLException e) {
                    log.debug("restore autoCommit after streaming export failed (non-fatal): {}", e.getMessage());
                }
            }
            if (plan.getConnection() instanceof DruidPooledConnection) {
                plan.getConnection().close();
            }
        }
        return result;
    }

    /**
     * task-02: enforce JDBC {@code setQueryTimeout} on every executed
     * statement based on the active {@link QueryPolicyHolder}. The
     * caller-supplied timeout (via {@code SQLQueryParams.timeout}) is
     * clamped to the configured {@code maxTimeoutSeconds}; an unset
     * timeout falls back to {@code defaultTimeoutSeconds}.
     */
    private static void applyQueryTimeout(Statement statement, SQLExecutePlan plan) {
        if (statement == null || plan == null || plan.getSqlQueryParams() == null) {
            return;
        }
        try {
            int requested = plan.getSqlQueryParams().getTimeout();
            int effective = QueryPolicyHolder.current().resolveTimeoutSeconds(requested);
            if (effective > 0) {
                statement.setQueryTimeout(effective);
            }
        } catch (Throwable t) {
            // Drivers that do not support setQueryTimeout (or report a
            // negative value) must not break the execution path.
            log.debug("setQueryTimeout failed (non-fatal): {}", t.getMessage());
        }
    }

    /**
     * Enable driver-side cursor streaming so a large result set is not
     * buffered client-side in full before the fetch loop starts. Without
     * this, MySQL in particular reads the whole result into heap up front,
     * defeating the bounded-retention fetch loop and risking OOM.
     *
     * <ul>
     *   <li>MySQL: row-by-row streaming requires the magic
     *       {@code setFetchSize(Integer.MIN_VALUE)} on a forward-only,
     *       read-only statement (the default {@code createStatement()}).</li>
     *   <li>PostgreSQL: a positive fetch size enables a server-side cursor,
     *       but only when the connection is not in autocommit mode; if it is,
     *       the driver silently buffers everything (validation point).</li>
     *   <li>SQL Server (mssql-jdbc): adaptive buffering is on by default; a
     *       positive fetch size is a harmless hint.</li>
     * </ul>
     *
     * Failures are non-fatal and degrade to the previous buffering behavior.
     */
    private static void applyFetchStreaming(Statement statement, SQLExecutePlan plan) {
        if (statement == null || plan == null) {
            return;
        }
        try {
            DbType dbType = plan.getDruidDbType();
            if (dbType == DbType.mysql || dbType == DbType.mariadb) {
                statement.setFetchSize(Integer.MIN_VALUE);
            } else {
                statement.setFetchSize(1000);
            }
        } catch (Throwable t) {
            log.debug("setFetchSize failed (non-fatal): {}", t.getMessage());
        }
    }

    private void executeStatement(SQLExecutePlan plan, Statement statement, SQLQueryResult result) throws SQLException {
        try (statement) {
            result.setStartTime(new Time(System.currentTimeMillis()));

            var hasResult = statement.execute(plan.getTargetSQL());
            result.setHasResultSet(hasResult);

            result.setQueryFinishedTime(new Time(System.currentTimeMillis()));

            if (hasResult) {
                var resultSet = statement.getResultSet();
                var metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    Field field = new Field();
                    field.setName(nonEmpty(metadataString(metadata, i, MetadataField.LABEL),
                            metadataString(metadata, i, MetadataField.NAME)));
                    field.setTable(metadataString(metadata, i, MetadataField.TABLE));
                    field.setSchema(metadataString(metadata, i, MetadataField.SCHEMA));
                    field.setType(metadataString(metadata, i, MetadataField.TYPE));
                    result.getFields().add(field);
                }

                ColumnSizeKeyResolver.ResolveResult columnKeys =
                        ColumnSizeKeyResolver.resolve(plan.getSourceSQL(), plan.getDruidDbType(), result.getFields());

                int cap = QueryPolicyHolder.current().getMaxRows();
                RowConsumer sink = plan.getRowConsumer();
                if (sink != null) {
                    sink.begin(result.getFields());
                }

                long sizeBytes = 0;
                long rowCount = 0;
                boolean statsOk = true;
                String statsReason = null;
                Map<String, Long> sizeByColumn = new LinkedHashMap<>();
                boolean columnStatsOk = true;
                String columnStatsReason = columnKeys.getUnavailableReason();

                while (resultSet.next()) {
                    List<Object> fs = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        try {
                            var obj = resultSet.getObject(i);
                            if (obj instanceof Timestamp timestamp) {
                                fs.add(new Date(timestamp.getTime()));
                            } else if (obj instanceof Long l) {
                                fs.add(l.toString());
                            } else if (obj instanceof BigInteger b) {
                                fs.add(b.toString());
                            } else if (obj instanceof byte[]) {
                                fs.add(HexUtils.bytesToHex((byte[]) obj));
                            } else {
                                fs.add(obj);
                            }
                        } catch (NoClassDefFoundError e) {
                            log.error(e.getMessage());
                        }
                    }
                    rowCount++;
                    if (statsOk) {
                        try {
                            sizeBytes += SizeCalculator.addRowBytes(result.getFields(), fs);
                        } catch (RuntimeException e) {
                            statsOk = false;
                            statsReason = e.getClass().getSimpleName();
                        }
                    }
                    if (columnStatsOk) {
                        try {
                            SizeCalculator.addRowBytesByColumn(columnKeys.getKeys(), fs, sizeByColumn);
                        } catch (RuntimeException e) {
                            columnStatsOk = false;
                            columnStatsReason = e.getClass().getSimpleName();
                        }
                    }
                    if (sink != null) {
                        sink.accept(fs);
                    } else if (result.getData().size() < cap) {
                        result.getData().add(fs);
                    } else {
                        result.setTruncated(true);
                    }
                }
                resultSet.close();
                result.setFetchFinishedTime(new Time(System.currentTimeMillis()));

                result.setTrueReturnedRows(rowCount);
                result.setStreamedSizeBytes(statsOk ? sizeBytes : 0);
                result.setSizeStatsStatus(statsOk
                        ? SizeCalculator.STATUS_OK : SizeCalculator.STATUS_UNAVAILABLE);
                result.setSizeStatsUnavailableReason(statsReason);
                if (columnStatsOk) {
                    result.setStreamedSizeByColumn(sizeByColumn);
                    result.setSizeByColumnSourceStatus(columnKeys.getStatus());
                    result.setSizeByColumnSourceUnavailableReason(columnKeys.getUnavailableReason());
                } else {
                    result.setSizeByColumnSourceStatus(SizeCalculator.STATUS_UNAVAILABLE);
                    result.setSizeByColumnSourceUnavailableReason(columnStatsReason);
                }

                if (sink != null) {
                    // Streaming export: rows were not retained and no total is
                    // needed, so skip the extra full-table count scan.
                    result.setTotal((int) rowCount);
                } else {
                    var total = this.count(plan);
                    if (total < 0) {
                        result.setTotal((int) rowCount);
                    } else {
                        result.setPaged(true);
                        result.setTotal(total);
                    }
                }

            } else {
                result.setUpdateCount(statement.getUpdateCount());
            }
            result.setEndTime(new Time(System.currentTimeMillis()));
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    private enum MetadataField {
        LABEL, NAME, TABLE, SCHEMA, TYPE
    }

    private static String metadataString(ResultSetMetaData metadata, int index, MetadataField field) {
        try {
            String value = switch (field) {
                case LABEL -> metadata.getColumnLabel(index);
                case NAME -> metadata.getColumnName(index);
                case TABLE -> metadata.getTableName(index);
                case SCHEMA -> metadata.getSchemaName(index);
                case TYPE -> metadata.getColumnTypeName(index);
            };
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String nonEmpty(String primary, String fallback) {
        if (primary != null && !primary.isEmpty()) {
            return primary;
        }
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        return "column";
    }

    @Override
    public SQLQueryResult executeWithAudit(SQL sql) throws SQLException {
        var plan = this.createPlan(sql);
        return plan.executeWithAudit();
    }

    @Override
    public SQLQueryResult executeWithAudit(SQLExecutePlan plan) throws SQLException {
        var sess = SessionManager.getCurrentSession();
        try {
            return sess.withAudit(plan.getTargetSQL(), () -> this.execute(plan));
        } catch (CommandRejectException e) {
            throw new SQLException(e.getMessage());
        }
    }

    public int count(SQL sql) throws SQLException {
        return this.count(this.createPlan(sql));
    }

    public int count(SQLExecutePlan plan) throws SQLException {
        if (plan.getTargetSQLStatement() instanceof SQLSelectStatement) {
            var limit = PageUtils.getLimit(plan.getSourceSQL(), plan.getDruidDbType());
            if (limit > 0) {
                return -1;
            }
            var countSQL = PagerUtils.count(plan.getSourceSQL(), plan.getDruidDbType());
            try (Statement stmt = plan.createStatement()) {
                applyQueryTimeout(stmt, plan);
                var resultSet = stmt.executeQuery(countSQL);
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        }
        return -1;
    }


    @Override
    public SQLActuator withConnection(Connection connection) {
        try {
            return this.getClass()
                    .getDeclaredConstructor(this.getClass(),
                            Connection.class)
                    .newInstance(this, connection);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql) throws SQLException {
        SQLExecutePlan plan = new SQLExecutePlan(sql.getSql(), this.getDruidDbType());
        this.createPlan(plan);
        return plan;
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql, SQLQueryParams queryParams) throws SQLException {
        SQLExecutePlan plan = new SQLExecutePlan(sql.getSql(), this.getDruidDbType());
        plan.setSqlQueryParams(queryParams);
        this.createPlan(plan);
        plan.generateTargetSQL();
        return plan;
    }

    private void createPlan(SQLExecutePlan plan) throws SQLException {
        plan.setSqlActuator(this);
        plan.setConnection(this.getConnection());
    }

    private Connection getConnection() throws SQLException {
        return this.connection != null ? this.connection : this.connectionManager.getConnection();
    }
}
