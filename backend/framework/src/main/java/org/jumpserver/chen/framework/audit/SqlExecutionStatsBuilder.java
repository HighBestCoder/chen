package org.jumpserver.chen.framework.audit;

import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DatasourceInfo;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure helper that derives an {@link ExecutionStats} instance from the
 * data already present in the SQL execution path. Lives separately from
 * {@code JMSSession} so the audit wiring stays small and the derivation
 * logic is unit-testable in isolation.
 */
public final class SqlExecutionStatsBuilder {

    private static final Pattern LEADING_KEYWORD =
            Pattern.compile("^\\s*(/\\*.*?\\*/\\s*)*([A-Za-z]+)", Pattern.DOTALL);

    private SqlExecutionStatsBuilder() {
    }

    /**
     * Build stats for a successfully completed SQL execution.
     *
     * @param datasource    datasource the command ran against (for db type / version).
     * @param command       raw SQL text executed.
     * @param result        result set produced by the actuator.
     */
    public static ExecutionStats fromSuccess(Datasource datasource, String command, SQLQueryResult result) {
        ExecutionStats stats = baseStats(datasource, command);
        stats.setSuccess(Boolean.TRUE);

        if (result == null) {
            return stats;
        }

        applyLimitAudit(stats, result);

        try {
            stats.setDurationMs(result.getTotalTimeUsed());
        } catch (Throwable ignore) {
            // result timing fields may be null on partial failures; keep durationMs absent.
        }

        if (result.isHasResultSet()) {
            List<List<Object>> data = result.getData();
            long returnedRows = result.getTrueReturnedRows() >= 0
                    ? result.getTrueReturnedRows()
                    : (data == null ? 0L : (long) data.size());
            stats.setReturnedRows(returnedRows);
            if (result.getTotal() >= 0) {
                stats.setTotalRows((long) result.getTotal());
            }
            stats.setImpactColumns(extractColumnNames(result.getFields()));
            applySizeStats(stats, result, data, command, datasource == null ? null : datasource.getDruidDbType());
            stats.putExtra("result_truncated", result.isTruncated());
        } else {
            stats.setAffectedRows((long) result.getUpdateCount());
        }
        return stats;
    }

    private static void applySizeStats(ExecutionStats stats, SQLQueryResult result, List<List<Object>> data,
                                       String command, com.alibaba.druid.DbType dbType) {
        // Prefer the size accumulated over the FULL result during the streaming
        // fetch loop; the retained {@code data} may be capped for display.
        if (result.getStreamedSizeBytes() >= 0) {
            if (SizeCalculator.STATUS_OK.equals(result.getSizeStatsStatus())) {
                stats.setSizeBytes(result.getStreamedSizeBytes());
            }
            stats.putExtra("size_stats_status", result.getSizeStatsStatus());
            if (result.getSizeStatsUnavailableReason() != null) {
                stats.putExtra("size_stats_unavailable_reason", result.getSizeStatsUnavailableReason());
            }
            applyStreamedColumnSizeStats(stats, result);
            return;
        }
        SizeCalculator.Result size = SizeCalculator.compute(result.getFields(), data);
        if (SizeCalculator.STATUS_OK.equals(size.status)) {
            stats.setSizeBytes(size.sizeBytes);
        }
        stats.putExtra("size_stats_status", size.status);
        if (size.unavailableReason != null) {
            stats.putExtra("size_stats_unavailable_reason", size.unavailableReason);
        }
        applyMaterializedColumnSizeStats(stats, result, data, command, dbType);
    }

    private static void applyStreamedColumnSizeStats(ExecutionStats stats, SQLQueryResult result) {
        Map<String, Long> byColumn = result.getStreamedSizeByColumn();
        if (byColumn != null && !byColumn.isEmpty()) {
            stats.putExtra("size_by_column", byColumn);
        }
        if (result.getSizeByColumnSourceStatus() != null) {
            stats.putExtra("size_by_column_source_status", result.getSizeByColumnSourceStatus());
        }
        if (result.getSizeByColumnSourceUnavailableReason() != null) {
            stats.putExtra("size_by_column_source_unavailable_reason",
                    result.getSizeByColumnSourceUnavailableReason());
        }
    }

    private static void applyMaterializedColumnSizeStats(ExecutionStats stats, SQLQueryResult result,
                                                         List<List<Object>> data, String command,
                                                         com.alibaba.druid.DbType dbType) {
        if (result.getFields() == null || data == null) {
            return;
        }
        try {
            ColumnSizeKeyResolver.ResolveResult keys = ColumnSizeKeyResolver.resolve(command, dbType, result.getFields());
            Map<String, Long> byColumn = new LinkedHashMap<>();
            for (List<Object> row : data) {
                SizeCalculator.addRowBytesByColumn(keys.getKeys(), row, byColumn);
            }
            if (!byColumn.isEmpty()) {
                stats.putExtra("size_by_column", byColumn);
            }
            stats.putExtra("size_by_column_source_status",
                    result.isTruncated() ? SizeCalculator.STATUS_PARTIAL : keys.getStatus());
            if (result.isTruncated()) {
                stats.putExtra("size_by_column_source_unavailable_reason", "materialized result truncated");
            } else if (keys.getUnavailableReason() != null) {
                stats.putExtra("size_by_column_source_unavailable_reason", keys.getUnavailableReason());
            }
        } catch (RuntimeException e) {
            stats.putExtra("size_by_column_source_status", SizeCalculator.STATUS_UNAVAILABLE);
            stats.putExtra("size_by_column_source_unavailable_reason", e.getClass().getSimpleName());
        }
    }

    /**
     * Build stats for a failed SQL execution. {@code result} may be {@code null}
     * when the failure happened before the actuator produced any payload.
     */
    public static ExecutionStats fromFailure(Datasource datasource, String command, Throwable error) {
        ExecutionStats stats = baseStats(datasource, command);
        stats.setSuccess(Boolean.FALSE);
        if (error != null) {
            String errorType = error.getClass().getSimpleName();
            stats.setErrorMessage(errorType);
            stats.setErrorCode(errorType);
        }
        return stats;
    }

    private static void applyLimitAudit(ExecutionStats stats, SQLQueryResult result) {
        if (result.getOriginalCommand() != null) {
            stats.setRawCommand(result.getOriginalCommand());
            stats.putExtra("original_command", result.getOriginalCommand());
        }
        if (result.getExecutedCommand() != null) {
            stats.putExtra("executed_command", result.getExecutedCommand());
        }
        if (result.getLimitSource() != null) {
            stats.putExtra("limit_source", result.getLimitSource());
        }
        if (result.getQueryLimit() >= 0) {
            stats.putExtra("query_limit", result.getQueryLimit());
        }
        stats.putExtra("manual_limit_detected", result.isManualLimitDetected());
    }

    private static ExecutionStats baseStats(Datasource datasource, String command) {
        ExecutionStats stats = new ExecutionStats();
        stats.setEngineType("sql");
        stats.setOpType(detectOpType(command));

        if (datasource != null) {
            try {
                DatasourceInfo info = datasource.getInfo();
                if (info != null) {
                    stats.setDbType(info.getDbType());
                    stats.setDbVersion(info.getVersion());
                }
            } catch (Throwable ignore) {
                // getInfo() may transiently fail (e.g. driver not yet initialised);
                // db type/version simply remain null in that case.
            }
            try {
                if (datasource.getConnectInfo() != null) {
                    stats.setNamespace(datasource.getConnectInfo().getDb());
                    if (stats.getDbType() == null) {
                        stats.setDbType(datasource.getConnectInfo().getDbType());
                    }
                }
            } catch (Throwable ignore) {
                // namespace is best-effort.
            }
        }
        return stats;
    }

    static String detectOpType(String command) {
        if (command == null) {
            return "OTHER";
        }
        Matcher m = LEADING_KEYWORD.matcher(command);
        if (!m.find()) {
            return "OTHER";
        }
        String kw = m.group(2).toUpperCase();
        switch (kw) {
            case "SELECT":
            case "WITH":
            case "SHOW":
            case "EXPLAIN":
            case "DESC":
            case "DESCRIBE":
                return "SELECT";
            case "INSERT":
                return "INSERT";
            case "UPDATE":
                return "UPDATE";
            case "DELETE":
                return "DELETE";
            case "MERGE":
                return "MERGE";
            case "TRUNCATE":
                return "TRUNCATE";
            case "CREATE":
            case "ALTER":
            case "DROP":
            case "RENAME":
                return "DDL";
            case "GRANT":
            case "REVOKE":
                return "DCL";
            case "COMMIT":
            case "ROLLBACK":
            case "BEGIN":
            case "START":
            case "SAVEPOINT":
                return "TCL";
            case "CALL":
            case "EXEC":
            case "EXECUTE":
                return "CALL";
            default:
                return kw;
        }
    }

    private static List<String> extractColumnNames(List<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream()
                .map(Field::getName)
                .filter(n -> n != null && !n.isEmpty())
                .collect(Collectors.toList());
    }
}
