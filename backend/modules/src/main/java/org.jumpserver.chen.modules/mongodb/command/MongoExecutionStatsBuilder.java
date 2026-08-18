package org.jumpserver.chen.modules.mongodb.command;

import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Derives an {@link ExecutionStats} from a parsed Mongo command and its
 * result, mirroring SqlExecutionStatsBuilder so the Mongo audit path emits
 * the same structured fields (dbType / opType / returnedRows / durationMs /
 * namespace / impactColumns) the relational path already reports.
 */
public final class MongoExecutionStatsBuilder {

    private MongoExecutionStatsBuilder() {
    }

    public static ExecutionStats fromSuccess(MongoConnectionManager cm, MongoCommand command, SQLQueryResult result) {
        ExecutionStats stats = baseStats(cm, command);
        stats.setSuccess(Boolean.TRUE);
        if (result == null) {
            return stats;
        }
        try {
            stats.setDurationMs(result.getTotalTimeUsed());
        } catch (Throwable ignore) {
        }
        if (result.isHasResultSet()) {
            List<List<Object>> data = result.getData();
            stats.setReturnedRows(data == null ? 0L : (long) data.size());
            if (result.getTotal() >= 0) {
                stats.setTotalRows((long) result.getTotal());
            }
            stats.setImpactColumns(result.getFields().stream()
                    .map(f -> f.getName())
                    .filter(n -> n != null && !n.isEmpty())
                    .collect(Collectors.toList()));
            SizeCalculator.Result size = SizeCalculator.compute(result.getFields(), data);
            if (SizeCalculator.STATUS_OK.equals(size.status)) {
                stats.setSizeBytes(size.sizeBytes);
            }
            stats.putExtra("size_stats_status", size.status);
            if (size.unavailableReason != null) {
                stats.putExtra("size_stats_unavailable_reason", size.unavailableReason);
            }
            applyColumnSizeStats(stats, result.getFields(), data);
        }
        return stats;
    }

    private static void applyColumnSizeStats(ExecutionStats stats, List<Field> fields, List<List<Object>> data) {
        if (fields == null || data == null) {
            return;
        }
        try {
            List<String> keys = fields.stream()
                    .map(MongoExecutionStatsBuilder::keyForMongoField)
                    .collect(Collectors.toList());
            Map<String, Long> byColumn = new LinkedHashMap<>();
            for (List<Object> row : data) {
                SizeCalculator.addRowBytesByColumn(keys, row, byColumn);
            }
            if (!byColumn.isEmpty()) {
                stats.putExtra("size_by_column", byColumn);
                stats.putExtra("column_size", new LinkedHashMap<>(byColumn));
            }
            stats.putExtra("size_by_column_source_status", SizeCalculator.STATUS_OK);
        } catch (RuntimeException e) {
            stats.putExtra("size_by_column_source_status", SizeCalculator.STATUS_UNAVAILABLE);
            stats.putExtra("size_by_column_source_unavailable_reason", e.getClass().getSimpleName());
        }
    }

    private static String keyForMongoField(Field field) {
        String table = field == null ? null : field.getTable();
        if (table == null || table.trim().isEmpty()) {
            table = "unknown";
        } else {
            table = table.trim().replaceAll("\\s+", "_");
        }
        String name = field == null ? null : field.getName();
        if (name == null || name.trim().isEmpty()) {
            name = "column";
        } else {
            name = name.trim().replaceAll("\\s+", "_");
        }
        return table + "." + name;
    }

    public static ExecutionStats fromFailure(MongoConnectionManager cm, MongoCommand command, Throwable error) {
        ExecutionStats stats = baseStats(cm, command);
        stats.setSuccess(Boolean.FALSE);
        if (error != null) {
            String errorType = error.getClass().getSimpleName();
            stats.setErrorMessage(errorType);
            stats.setErrorCode(errorType);
        }
        return stats;
    }

    public static ExecutionStats fromFailure(MongoConnectionManager cm, String rawCommand, Throwable error) {
        ExecutionStats stats = baseStats(cm, null);
        stats.setOpType("OTHER");
        stats.setRawCommand(rawCommand);
        stats.setSuccess(Boolean.FALSE);
        if (error != null) {
            String errorType = error.getClass().getSimpleName();
            stats.setErrorMessage(error.getMessage() == null ? errorType : error.getMessage());
            stats.setErrorCode(errorType);
        }
        return stats;
    }

    private static ExecutionStats baseStats(MongoConnectionManager cm, MongoCommand command) {
        ExecutionStats stats = new ExecutionStats();
        stats.setEngineType("mongo");
        stats.setDbType("mongodb");
        if (command != null) {
            stats.setOpType(opType(command.getType()));
            stats.setRawCommand(command.getRawText());
        }
        if (cm != null) {
            try {
                stats.setDbVersion(cm.getVersion());
            } catch (Throwable ignore) {
            }
            stats.setNamespace(cm.getCurrentDatabaseName());
        }
        return stats;
    }

    private static String opType(MongoCommand.Type type) {
        if (type == null) {
            return "OTHER";
        }
        return switch (type) {
            case FIND -> "FIND";
            case SHOW_DBS, SHOW_COLLECTIONS -> "SHOW";
            case USE_DB -> "USE";
        };
    }
}
