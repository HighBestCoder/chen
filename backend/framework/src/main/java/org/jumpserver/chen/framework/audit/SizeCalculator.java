package org.jumpserver.chen.framework.audit;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
            int columnCount = fields.size();
            for (List<Object> row : data) {
                int upto = Math.min(columnCount, row.size());
                for (int i = 0; i < upto; i++) {
                    Object value = row.get(i);
                    if (value != null) {
                        total += value.toString().getBytes(StandardCharsets.UTF_8).length;
                    }
                }
            }
            return new Result(total, STATUS_OK, null);
        } catch (RuntimeException e) {
            return new Result(0L, STATUS_UNAVAILABLE, e.getClass().getSimpleName());
        }
    }
}
