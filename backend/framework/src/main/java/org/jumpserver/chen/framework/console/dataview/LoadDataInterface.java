package org.jumpserver.chen.framework.console.dataview;

import org.jumpserver.chen.framework.datasource.sql.RowConsumer;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.SQLException;

@FunctionalInterface
public interface LoadDataInterface {
    /**
     * @param sink optional per-row consumer for streaming (e.g. export);
     *             when non-null the rows are streamed to it and not retained
     *             in the returned result. Pass {@code null} for the normal
     *             bounded-retention display path.
     */
    SQLQueryResult loadData(SQLQueryParams params, RowConsumer sink) throws SQLException;
}
