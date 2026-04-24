package org.jumpserver.chen.framework.audit;

import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DatasourceInfo;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.util.List;
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

        try {
            stats.setDurationMs(result.getTotalTimeUsed());
        } catch (Throwable ignore) {
            // result timing fields may be null on partial failures; keep durationMs absent.
        }

        if (result.isHasResultSet()) {
            List<List<Object>> data = result.getData();
            stats.setReturnedRows(data == null ? 0L : (long) data.size());
            if (result.getTotal() >= 0) {
                stats.setTotalRows((long) result.getTotal());
            }
            stats.setImpactColumns(extractColumnNames(result.getFields()));
        } else {
            stats.setAffectedRows((long) result.getUpdateCount());
        }
        return stats;
    }

    /**
     * Build stats for a failed SQL execution. {@code result} may be {@code null}
     * when the failure happened before the actuator produced any payload.
     */
    public static ExecutionStats fromFailure(Datasource datasource, String command, Throwable error) {
        ExecutionStats stats = baseStats(datasource, command);
        stats.setSuccess(Boolean.FALSE);
        if (error != null) {
            stats.setErrorMessage(error.getMessage());
            stats.setErrorCode(error.getClass().getSimpleName());
        }
        return stats;
    }

    private static ExecutionStats baseStats(Datasource datasource, String command) {
        ExecutionStats stats = new ExecutionStats();
        stats.setEngineType("sql");
        stats.setRawCommand(command);
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
