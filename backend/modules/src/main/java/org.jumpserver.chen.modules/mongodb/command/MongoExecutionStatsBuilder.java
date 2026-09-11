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
        org.bson.Document options = command.getType() == MongoCommand.Type.COMMAND ? command.getDatabaseCommand() : command.getOptions();
        if(options.containsKey("writeConcern") && !MongoOptions.writeConcern(options.get("writeConcern",org.bson.Document.class)).isAcknowledged()) {
            stats.setSuccess(null);stats.putExtra("write_acknowledged",false);
        }
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
            stats.setImpactColumns(result.getStreamedImpactColumns() != null
                    ? result.getStreamedImpactColumns() : result.getFields().stream()
                    .map(f -> f.getName())
                    .filter(n -> n != null && !n.isEmpty())
                    .collect(Collectors.toList()));
            applySizeStats(stats, result, data);
            if (result.getStreamedSizeByColumn() != null) {
                stats.putExtra("size_by_column", new LinkedHashMap<>(result.getStreamedSizeByColumn()));
                stats.putExtra("column_size", new LinkedHashMap<>(result.getStreamedSizeByColumn()));
                stats.putExtra("size_by_column_source_status", result.getSizeByColumnSourceStatus());
                stats.putExtra("size_measurement", "utf8_leaf_values_v2");
            } else if (SizeCalculator.STATUS_UNAVAILABLE.equals(result.getSizeByColumnSourceStatus())) {
                stats.putExtra("size_by_column_source_status", SizeCalculator.STATUS_UNAVAILABLE);
                stats.putExtra("size_by_column_source_unavailable_reason", result.getSizeByColumnSourceUnavailableReason());
            } else {
                applyColumnSizeStats(stats, result.getFields(), data);
            }
        } else {
            // Writes and `use <db>` produce no result set; the affected-row
            // count is the only volume figure they carry, and it is reported
            // through the same field the relational builder uses.
            if (result.getUpdateCount() >= 0) stats.setAffectedRows((long) result.getUpdateCount());
        }
        return stats;
    }

    /**
     * Prefers the size the adapter already measured over the whole result, so
     * the WebGUI display and this audit envelope report the same number.
     * Falls back to computing it from the retained rows for results built
     * without going through the adapter.
     */
    private static void applySizeStats(ExecutionStats stats, SQLQueryResult result, List<List<Object>> data) {
        long measured = result.getStreamedSizeBytes();
        String status = result.getSizeStatsStatus();
        String unavailableReason = result.getSizeStatsUnavailableReason();
        if (measured < 0 && !SizeCalculator.STATUS_UNAVAILABLE.equals(status)) {
            SizeCalculator.Result size = SizeCalculator.compute(result.getFields(), data);
            measured = size.sizeBytes;
            status = size.status;
            unavailableReason = size.unavailableReason;
        }
        if (SizeCalculator.STATUS_OK.equals(status)) {
            stats.setSizeBytes(measured);
        }
        stats.putExtra("size_stats_status", status);
        if (unavailableReason != null) {
            stats.putExtra("size_stats_unavailable_reason", unavailableReason);
        }
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
        if (error instanceof com.mongodb.MongoBulkWriteException bulk && bulk.getWriteResult().wasAcknowledged()) {
            var partial = bulk.getWriteResult();
            stats.setAffectedRows((long) partial.getInsertedCount() + partial.getModifiedCount() + partial.getDeletedCount() + partial.getUpserts().size());
            stats.putExtra("partial_write", true);
            stats.putExtra("write_error_indices", bulk.getWriteErrors().stream().map(com.mongodb.bulk.BulkWriteError::getIndex).toList());
        }
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
            case SCRIPT -> "SCRIPT";
            case COMMAND -> "COMMAND";
            case REPLACE -> "REPLACE";
            case FIND_AND_UPDATE -> "FIND_AND_UPDATE";
            case FIND_AND_REPLACE -> "FIND_AND_REPLACE";
            case FIND_AND_DELETE -> "FIND_AND_DELETE";
            case BULK_WRITE -> "BULK_WRITE";
            case CREATE_INDEX -> "CREATE_INDEX";
            case LIST_INDEXES -> "LIST_INDEXES";
            case DROP_INDEX -> "DROP_INDEX";
            case FIND -> "FIND";
            case FIND_ONE -> "FIND_ONE";
            case COUNT -> "COUNT";
            case DISTINCT -> "DISTINCT";
            case AGGREGATE -> "AGGREGATE";
            case SHOW_DBS, SHOW_COLLECTIONS -> "SHOW";
            case USE_DB -> "USE";
            case INSERT -> "INSERT";
            case UPDATE -> "UPDATE";
            case DELETE -> "DELETE";
            case DROP_COLLECTION -> "DROP";
        };
    }
}
