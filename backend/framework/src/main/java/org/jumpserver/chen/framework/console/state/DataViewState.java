package org.jumpserver.chen.framework.console.state;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;


@EqualsAndHashCode(callSuper = true)
@Data
public class DataViewState extends State {
    private int page;
    private int limit;
    private int maxDisplayLimit = 50_000;
    private int total;
    private boolean pinned;
    private boolean paged;
    private boolean manualLimitDetected;
    /** Bytes of the returned data; -1 when the engine could not measure it. */
    private long sizeBytes = -1;

    public DataViewState(String title) {
        super(title);
        this.paged = true;
        this.pinned = false;
        this.total = 0;
        this.page = 1;
        this.limit = 50;
    }

}
