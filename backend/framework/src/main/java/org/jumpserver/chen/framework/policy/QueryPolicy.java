package org.jumpserver.chen.framework.policy;

import lombok.Data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Centralized SQL query execution policy used by the chen execution
 * pipeline (R01 / R04, task-02 of the 2026-04-22 plan).
 *
 * <p>Holds the runtime-resolved values for:</p>
 * <ul>
 *   <li>maximum rows the backend will ever return (hard cap);</li>
 *   <li>default rows for the SQL console / query toolbar;</li>
 *   <li>default rows for the object-browse / table-preview path;</li>
 *   <li>default and maximum query timeout (seconds);</li>
 *   <li>fixed limit options surfaced to the SQL console UI;</li>
 *   <li>maximum rows allowed for export.</li>
 * </ul>
 *
 * <p>This class is a plain POJO and intentionally has no Spring or
 * configuration framework dependency so it can live in
 * {@code framework} and be reused outside the web module.
 * The {@code backend/web} layer is responsible for binding
 * {@code application.yml -> QueryPolicy} and pushing it into
 * {@link QueryPolicyHolder} at startup.</p>
 */
@Data
public class QueryPolicy {

    public static final int DEFAULT_MAX_ROWS = 10_000;
    public static final int DEFAULT_CONSOLE_LIMIT = 50;
    public static final int DEFAULT_PREVIEW_LIMIT = 100;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_EXPORT_MAX_ROWS = 100_000;

    /**
     * Fixed options surfaced to the SQL console limit selector,
     * per task-02 acceptance §6.3.
     */
    public static final List<Integer> DEFAULT_CONSOLE_LIMIT_OPTIONS =
            Collections.unmodifiableList(Arrays.asList(50, 100, 500, 5000, 10000));

    private int maxRows = DEFAULT_MAX_ROWS;
    private int defaultConsoleLimit = DEFAULT_CONSOLE_LIMIT;
    private int defaultPreviewLimit = DEFAULT_PREVIEW_LIMIT;
    private int defaultTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private int maxTimeoutSeconds = DEFAULT_MAX_TIMEOUT_SECONDS;
    private int exportMaxRows = DEFAULT_EXPORT_MAX_ROWS;
    private List<Integer> consoleLimitOptions = DEFAULT_CONSOLE_LIMIT_OPTIONS;

    /**
     * Clamp a caller-requested row limit to {@link #maxRows}.
     * <ul>
     *   <li>{@code requested == -1} (no limit requested) -> returned unchanged
     *       so existing callers that intentionally bypass paging keep
     *       working; the pipeline still relies on result-set streaming
     *       to keep memory bounded.</li>
     *   <li>{@code requested <= 0} (invalid / unset) -> falls back to
     *       {@link #defaultConsoleLimit}.</li>
     *   <li>otherwise -> {@code min(requested, maxRows)}.</li>
     * </ul>
     */
    public int clampLimit(int requested) {
        if (requested == -1) {
            return -1;
        }
        if (requested <= 0) {
            return Math.min(this.defaultConsoleLimit, this.maxRows);
        }
        return Math.min(requested, this.maxRows);
    }

    /**
     * Resolve the effective JDBC {@code setQueryTimeout} value given a
     * caller-requested seconds value.
     * <ul>
     *   <li>{@code requested <= 0} (unset / disabled) -> returns the
     *       default timeout.</li>
     *   <li>otherwise -> {@code min(requested, maxTimeoutSeconds)}.</li>
     * </ul>
     */
    public int resolveTimeoutSeconds(int requested) {
        if (requested <= 0) {
            return this.defaultTimeoutSeconds;
        }
        return Math.min(requested, this.maxTimeoutSeconds);
    }
}
