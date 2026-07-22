package org.jumpserver.chen.framework.datasource.sql;

import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.sql.SQLException;
import java.util.List;

/**
 * Per-row sink used by the SQL fetch loop to stream a result set to a
 * consumer (e.g. a CSV writer) without retaining all rows in memory. When a
 * {@link RowConsumer} is set on the {@link SQLExecutePlan}, the actuator
 * streams each row to it and does NOT accumulate the rows in
 * {@link SQLQueryResult#getData()}.
 *
 * <p>Uses checked {@link SQLException} so an underlying I/O failure in the
 * sink propagates through the existing execution path without per-row
 * wrapping.</p>
 */
public interface RowConsumer {

    /**
     * Called once after the result-set metadata (columns) is read, before
     * any row is delivered. Lets the sink emit a header derived from the
     * column names.
     */
    void begin(List<Field> fields) throws SQLException;

    /**
     * Called once per data row, in fetch order.
     */
    void accept(List<Object> row) throws SQLException;
}
