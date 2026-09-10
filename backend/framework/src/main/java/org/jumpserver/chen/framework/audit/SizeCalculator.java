package org.jumpserver.chen.framework.audit;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Computes the contract's "data size = impact-column field length x rows"
 * by summing the UTF-8 byte length of each impact-column value across the
 * already-materialized result rows. It does NOT copy or retain the result:
 * the rows are the result set the client already holds, so this adds no
 * extra full-result buffering (STAT-003). Returns a per-call status so a
 * calculation failure is recorded without losing the query result.
 */
public final class SizeCalculator {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_UNAVAILABLE = "unavailable";

    public static final class Result {
        public final long sizeBytes;
        public final String status;
        public final String unavailableReason;

        private Result(long sizeBytes, String status, String reason) {
            this.sizeBytes = sizeBytes;
            this.status = status;
            this.unavailableReason = reason;
        }
    }

    private SizeCalculator() {
    }

    /**
     * @param fields  the result columns; their names define the impact columns.
     * @param data    materialized rows, each a positional list aligned to fields.
     */
    public static Result compute(List<Field> fields, List<List<Object>> data) {
        if (fields == null || data == null) {
            return new Result(0L, STATUS_UNAVAILABLE, "missing fields or data");
        }
        try {
            long total = 0L;
            for (List<Object> row : data) {
                total += addRowBytes(fields, row);
            }
            return new Result(total, STATUS_OK, null);
        } catch (RuntimeException e) {
            return new Result(0L, STATUS_UNAVAILABLE, e.getClass().getSimpleName());
        }
    }

    /**
     * Byte length contributed by a single row, using the exact same
     * impact-column semantics as {@link #compute}: only the columns aligned
     * to {@code fields} count, nulls are skipped, and each value is measured
     * by the UTF-8 byte length of its {@code toString()}. Extracted so the
     * streaming fetch loop can accumulate size incrementally without
     * retaining the full result, yielding identical totals to {@code compute}.
     */
    public static long addRowBytes(List<Field> fields, List<Object> row) {
        long total = 0L;
        int upto = Math.min(fields.size(), row.size());
        for (int i = 0; i < upto; i++) {
            Object value = row.get(i);
            if (value != null) {
                total += value.toString().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    /**
     * Incrementally accumulates UTF-8 byte length per resolved column key.
     * The key list is positional and must be aligned to the row values. This
     * mirrors {@link #addRowBytes(List, List)} without retaining any row data.
     */
    public static void addRowBytesByColumn(List<String> columnKeys, List<Object> row, Map<String, Long> acc) {
        if (columnKeys == null || row == null || acc == null) {
            return;
        }
        int upto = Math.min(columnKeys.size(), row.size());
        for (int i = 0; i < upto; i++) {
            String key = columnKeys.get(i);
            Object value = row.get(i);
            if (key == null || key.isEmpty()) {
                continue;
            }
            long bytes = value == null ? 0L : value.toString().getBytes(StandardCharsets.UTF_8).length;
            acc.merge(key, bytes, Long::sum);
        }
    }
}
