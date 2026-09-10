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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // Approval must not execute SQL to estimate effects. -1 means unknown.
        return -1;
    }

    @Override
    public List<String> parseSQL(SQL sql) {
        return org.jumpserver.chen.framework.utils.SqlText.statements(sql.getSql(), this.druidDbType);
    }

    @Override
    public <T> List<T> getObjects(String sql, Class<T> clazz, Map<String, Integer> fieldMapping) throws SQLException {
        return getObjects(SQL.of(sql), clazz, fieldMapping);
    }

    @Override
    public <T> List<T> getObjects(SQL sql, Class<T> clazz, Map<String, Integer> fieldMapping) throws SQLException {
        List<T> objects = new ArrayList<>();
        Connection conn = this.getConnection();
        try (java.sql.PreparedStatement stmt = conn.prepareStatement(sql.getSql())) {
            for (int i = 0; i < sql.getParameters().size(); i++) stmt.setObject(i + 1, sql.getParameters().get(i));
            try (ResultSet rs = stmt.executeQuery()) {
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
            }
        } catch (Exception e) {
            var msg = "run sql %s error, %s".formatted(sql, e.getMessage());
            if (e instanceof SQLException sqlError) throw new SQLException(msg, sqlError.getSQLState(), sqlError.getErrorCode(), sqlError);
            throw new SQLException(msg, e);
        } finally {
            if (this.connection == null) conn.close();
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
        boolean ownedTransaction = false;
        Throwable failure = null;
        try {
            // PostgreSQL streaming cursors require a transaction. Only finish
            // transactions we started; SQL functions can write even in SELECT.
            if (streamingExport && planConn != null && planConn.getAutoCommit()) {
                planConn.setAutoCommit(false);
                ownedTransaction = true;
            }
            Statement statement = plan.createStatement();
            applyQueryTimeout(statement, plan);
            applyFetchStreaming(statement, plan);
            this.executeStatement(plan, statement, result);
            if (result.isHasResultSet() && !streamingExport) {
                int total = count(plan);
                if (total >= 0) { result.setPaged(true); result.setTotal(total); }
            }
            if (ownedTransaction) planConn.commit();
        } catch (SQLException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            SQLException cleanupFailure = null;
            if (ownedTransaction) {
                boolean transactionFinished = failure == null;
                if (failure != null) {
                    try { planConn.rollback(); transactionFinished = true; }
                    catch (SQLException error) { cleanupFailure = error; }
                }
                try {
                    // Restoring autoCommit after a failed rollback can commit
                    // unfinished writes. Retire that connection instead.
                    if (transactionFinished) planConn.setAutoCommit(true);
                    else planConn.close();
                } catch (SQLException error) {
                    if (cleanupFailure == null) cleanupFailure = error;
                    else cleanupFailure.addSuppressed(error);
                }
            }
            if (planConn instanceof DruidPooledConnection) {
                try { planConn.close(); }
                catch (SQLException error) {
                    if (cleanupFailure == null) cleanupFailure = error;
                    else cleanupFailure.addSuppressed(error);
                }
            }
            if (cleanupFailure != null) {
                if (failure != null) failure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
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
                // Connector/J cannot advance multiple streaming outputs from
                // CALL. Procedure results are checked explicitly below; only
                // SELECT is eligible for row-by-row streaming here.
                if (plan.getTargetSQLStatement() instanceof SQLSelectStatement) statement.setFetchSize(Integer.MIN_VALUE);
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

            var hasResult = statement instanceof java.sql.PreparedStatement prepared
                    ? prepared.execute() : statement.execute(plan.getTargetSQL());
            result.setHasResultSet(hasResult);

            result.setQueryFinishedTime(new Time(System.currentTimeMillis()));

            if (hasResult) {
                var resultSet = statement.getResultSet();
                var metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();

                // 列名可能是别名(LABEL)，主键比对必须用真实列名(NAME)。
                List<String> rawColumnNames = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    Field field = new Field();
                    field.setName(nonEmpty(metadataString(metadata, i, MetadataField.LABEL),
                            metadataString(metadata, i, MetadataField.NAME)));
                    field.setTable(metadataString(metadata, i, MetadataField.TABLE));
                    field.setSchema(metadataString(metadata, i, MetadataField.SCHEMA));
                    field.setType(metadataString(metadata, i, MetadataField.TYPE));
                    result.getFields().add(field);
                    rawColumnNames.add(metadataString(metadata, i, MetadataField.NAME));
                }
                applyNullable(metadata, result.getFields());

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
                for (String key : columnKeys.getKeys()) sizeByColumn.put(key, 0L);
                boolean columnStatsOk = true;
                String columnStatsReason = columnKeys.getUnavailableReason();

                while (resultSet.next()) {
                    List<Object> fs = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        try {
                            var obj = resultSet.getObject(i);
                            if (obj instanceof Timestamp timestamp) {
                                fs.add(timestamp.toString());
                            } else if (obj instanceof java.time.LocalDateTime timestamp) {
                                fs.add(Timestamp.valueOf(timestamp).toString());
                            } else if (obj instanceof Long l) {
                                fs.add(l.toString());
                            } else if (obj instanceof java.math.BigDecimal decimal) {
                                fs.add(decimal.toPlainString());
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
                if (sink != null) sink.finish();
                // 主键查询必须等结果集关闭之后再做：MySQL 驱动是流式读取，
                // 结果集还开着时同一连接上发任何语句都会抛
                // "Streaming result set ... is still active"。
                applyPrimaryKeys(statement, metadata, result.getFields(), rawColumnNames);
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

                result.setTotal((int) rowCount);

            } else {
                result.setUpdateCount(statement.getUpdateCount());
            }
            // Do not silently report success for only the first output of a
            // stored procedure. Advancing also surfaces delayed JDBC errors.
            if (statement.getMoreResults() || statement.getUpdateCount() != -1) {
                throw new SQLException("Command produced multiple results, which this result view cannot display. "
                        + "Database operations may already have executed; inspect the database before retrying.");
            }
            result.setEndTime(new Time(System.currentTimeMillis()));
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    private enum MetadataField {
        LABEL, NAME, TABLE, SCHEMA, CATALOG, TYPE
    }

    /**
     * 合同 §4.6.8 要求「结构预览中的字段约束与数据库实际一致」。
     *
     * <p>此前只从 {@link ResultSetMetaData} 取 LABEL/NAME/TABLE/SCHEMA/TYPE，
     * 从不设 nullable 与主键，两个 boolean 一直是默认的 false —— 可空列被报成
     * NOT NULL、主键列被报成非主键。（{@code BaseResourceBrowser.getFields(schema,
     * table)} 会查 information_schema 拿 nullable，但没有任何调用者。）</p>
     *
     * <p>nullable 直接来自结果集元数据，零额外开销。主键必须查
     * {@link DatabaseMetaData#getPrimaryKeys}，因此按「每个不同表只查一次」
     * 执行，并且只在表数量不多时才查，避免宽联接把成本放大。整段 fail-open：
     * 任何异常都只让约束信息回退为原来的默认值，绝不影响查询本身。</p>
     */
    private static final int PK_LOOKUP_MAX_TABLES = 4;

    /** 从连接本身取 catalog / schema，取不到就返回空串。 */
    private static String safeConnectionScope(Connection connection, boolean catalog) {
        try {
            String value = catalog ? connection.getCatalog() : connection.getSchema();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** nullable 直接来自结果集元数据，零额外查询，建字段时即可填。 */
    private static void applyNullable(ResultSetMetaData metadata, List<Field> fields) {
        for (int i = 0; i < fields.size(); i++) {
            try {
                // columnNullableUnknown 时无法断言存在 NOT NULL 约束，按「可能为空」
                // 处理；把未知说成有约束比说成没有更容易误导。
                fields.get(i).setNullable(metadata.isNullable(i + 1) != ResultSetMetaData.columnNoNulls);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyPrimaryKeys(Statement statement, ResultSetMetaData metadata,
                                         List<Field> fields, List<String> rawColumnNames) {
        record Scope(String catalog, String schema, String table) {}
        try {
            Connection connection = statement.getConnection();
            if (connection == null) return;
            DatabaseMetaData dbMetadata = connection.getMetaData();
            if (dbMetadata == null) return;
            List<Scope> scopes = new ArrayList<>();
            Set<Scope> tables = new LinkedHashSet<>();
            for (int i = 0; i < fields.size(); i++) {
                String catalog = metadataString(metadata, i + 1, MetadataField.CATALOG);
                String schema = fields.get(i).getSchema();
                if (catalog.isEmpty()) catalog = safeConnectionScope(connection, true);
                if (schema == null || schema.isEmpty()) schema = safeConnectionScope(connection, false);
                var scope = new Scope(catalog, schema, fields.get(i).getTable());
                scopes.add(scope);
                if (scope.table() != null && !scope.table().isEmpty()) tables.add(scope);
            }
            if (tables.size() > PK_LOOKUP_MAX_TABLES) return;
            Map<Scope, Set<String>> primaryKeys = new HashMap<>();
            for (Scope scope : tables) {
                Set<String> names = new HashSet<>();
                try (var rs = dbMetadata.getPrimaryKeys(scope.catalog().isEmpty() ? null : scope.catalog(),
                        scope.schema().isEmpty() ? null : scope.schema(), scope.table())) {
                    while (rs.next()) names.add(rs.getString("COLUMN_NAME"));
                }
                primaryKeys.put(scope, names);
            }
            for (int i = 0; i < fields.size(); i++) {
                fields.get(i).setPrimaryKey(primaryKeys.getOrDefault(scopes.get(i), Set.of()).contains(rawColumnNames.get(i)));
            }
        } catch (Throwable e) {
            log.warn("Primary key lookup failed: {}", e.toString());
        }
    }

    private static String metadataString(ResultSetMetaData metadata, int index, MetadataField field) {
        try {
            // PostgreSQL's standard getSchemaName is empty and getColumnName
            // can be an alias. Its PGResultSetMetaData exposes the base origin.
            // Drivers live in isolated loaders, so do not link their classes.
            ResultSetMetaData raw = metadata instanceof com.alibaba.druid.proxy.jdbc.ResultSetMetaDataProxy proxy
                    ? proxy.getResultSetMetaDataRaw() : metadata;
            String baseMethod = switch (field) {
                case NAME -> "getBaseColumnName";
                case TABLE -> "getBaseTableName";
                case SCHEMA -> "getBaseSchemaName";
                default -> null;
            };
            if (baseMethod != null) {
                try {
                    Object value = raw.getClass().getMethod(baseMethod, int.class).invoke(raw, index);
                    if (value instanceof String text && !text.isEmpty()) return text;
                } catch (ReflectiveOperationException ignored) { }
            }
            String value = switch (field) {
                case LABEL -> metadata.getColumnLabel(index);
                case NAME -> metadata.getColumnName(index);
                case TABLE -> metadata.getTableName(index);
                case SCHEMA -> metadata.getSchemaName(index);
                case CATALOG -> metadata.getCatalogName(index);
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
        try { return plan.executeWithAudit(); } finally { plan.close(); }
    }

    @Override
    public SQLQueryResult executeWithAudit(SQLExecutePlan plan) throws SQLException {
        var sess = SessionManager.getCurrentSession();
        try {
            String namespace = null;
            try { if (plan.getConnection() != null) namespace = plan.getConnection().getCatalog(); } catch (SQLException ignored) { }
            return sess.withAudit(plan.getSourceSQL(), namespace, () -> this.execute(plan));
        } catch (CommandRejectException e) {
            throw new SQLException(e.getMessage());
        }
    }

    public int count(SQL sql) throws SQLException {
        var plan = this.createPlan(sql);
        try { return this.count(plan); } finally { plan.close(); }
    }

    public int count(SQLExecutePlan plan) throws SQLException {
        // Bound metadata results are small, non-paged lookups. Never run an
        // unbound count or pass SQL text to PreparedStatement.executeQuery.
        if (!plan.getParameters().isEmpty()) return -1;
        if (plan.getCachedCount() != null) return plan.getCachedCount();
        String countSQL;
        if (plan.getQuotedPreviewTable() != null) {
            countSQL = "SELECT COUNT(*) FROM " + plan.getQuotedPreviewTable();
        } else {
            if (!(plan.getTargetSQLStatement() instanceof SQLSelectStatement select)) return -1;
            // A count rewrite is an extra execution. Never replay explicit
            // function calls, CTEs or SELECT INTO as a hidden metadata lookup.
            final boolean[] unsafe = {false};
            select.accept(new com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter() {
                @Override public void preVisit(com.alibaba.druid.sql.ast.SQLObject node) {
                    if (node instanceof com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr
                            || node instanceof com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause
                            || (node instanceof com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock block
                                && block.getInto() != null)) unsafe[0] = true;
                }
            });
            if (unsafe[0]) return -1;
            if (PageUtils.getLimit(plan.getSourceSQL(), plan.getDruidDbType()) >= 0) return -1;
            countSQL = org.jumpserver.chen.framework.utils.SqlText.rewrite(plan.getSourceSQL(), plan.getDruidDbType(),
                    text -> PagerUtils.count(text, plan.getDruidDbType()));
        }
        try (Statement stmt = plan.createStatement()) {
            applyQueryTimeout(stmt, plan);
            try (var rows = stmt.executeQuery(countSQL)) {
                int total = rows.next() ? rows.getInt(1) : -1;
                plan.setCachedCount(total);
                return total;
            }
        }
    }

    protected SQLExecutePlan createPreviewPlan(String quotedTable, SQLQueryParams params) throws SQLException {
        var plan = createPlan(SQL.of("SELECT * FROM " + quotedTable));
        plan.setQuotedPreviewTable(quotedTable);
        plan.setSqlQueryParams(params);
        try { plan.generateTargetSQL(); return plan; }
        catch (SQLException | RuntimeException failure) { plan.close(); throw failure; }
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
        plan.setParameters(sql.getParameters());
        this.createPlan(plan);
        return plan;
    }

    @Override
    public SQLExecutePlan createPlan(SQL sql, SQLQueryParams queryParams) throws SQLException {
        SQLExecutePlan plan = new SQLExecutePlan(sql.getSql(), this.getDruidDbType());
        plan.setParameters(sql.getParameters());
        plan.setSqlQueryParams(queryParams);
        this.createPlan(plan);
        try { plan.generateTargetSQL(); return plan; }
        catch (SQLException | RuntimeException failure) { plan.close(); throw failure; }
    }

    private void createPlan(SQLExecutePlan plan) throws SQLException {
        plan.setSqlActuator(this);
        plan.setConnection(this.getConnection());
    }

    private Connection getConnection() throws SQLException {
        return this.connection != null ? this.connection : this.connectionManager.getConnection();
    }
}
