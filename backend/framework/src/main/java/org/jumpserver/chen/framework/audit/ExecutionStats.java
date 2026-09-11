package org.jumpserver.chen.framework.audit;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured execution statistics emitted by chen for every audited
 * SQL/NoSQL command. Carries the high-frequency flat fields persisted in
 * the core terminal_command table plus an {@code extras} bag for less
 * common attributes (Mongo column profile metadata, size-stats availability,
 * etc.).
 *
 * <p>Designed for task-01 of the 2026-04-22 architecture plan. All fields
 * are nullable so legacy commands or partial captures can still be
 * serialised; the core side is responsible for tolerating nulls and
 * version-tagging the row schema.</p>
 */
@Data
public class ExecutionStats {

    /** Schema version of the envelope. Bump when the wire shape changes. */
    public static final int SCHEMA_VERSION = 1;

    /** Engine family. Currently one of {@code sql}, {@code mongo}. */
    private String engineType;

    /** Concrete database type (e.g. {@code mysql}, {@code postgresql}, {@code sqlserver}). */
    private String dbType;

    /** Database server version string as reported by the driver / metadata. */
    private String dbVersion;

    /** Operation kind derived from the command (SELECT / INSERT / UPDATE / DELETE / DDL / OTHER). */
    private String opType;

    /** Original command text, retained in the stats envelope independently of legacy input truncation. */
    private String rawCommand;

    /** Rows affected by DML/DDL statements. {@code null} for read-only queries. */
    private Long affectedRows;

    /** Rows actually returned to the client (after server-side limit). */
    private Long returnedRows;

    /** Total rows the server reported for the underlying result set, when known. */
    private Long totalRows;

    /** Columns touched / projected by the command (for column-level audit). */
    private List<String> impactColumns;

    /** Approximate transferred payload size in bytes, when measurable. */
    private Long sizeBytes;

    /** Wall-clock execution duration in milliseconds. */
    private Long durationMs;

    /** Logical namespace (database / schema / collection) the command targeted. */
    private String namespace;

    /** Whether the command executed without raising an error. */
    private Boolean success;

    /** Vendor / SQLState error code, when {@code success == false}. */
    private String errorCode;

    /** Sanitized error message, when {@code success == false}. */
    private String errorMessage;

    /**
     * Free-form extension bag. Reserved keys (per task-01 spec):
     * <ul>
     *   <li>{@code column_profile_source} - origin of column profile (Mongo).</li>
     *   <li>{@code matched_impact_columns} - columns matched against profile.</li>
     *   <li>{@code size_stats_status} - {@code ok} / {@code unavailable}.</li>
     *   <li>{@code size_stats_unavailable_reason} - reason string.</li>
     * </ul>
     */
    private Map<String, Object> extras = new HashMap<>();

    public void putExtra(String key, Object value) {
        if (key == null) {
            return;
        }
        this.extras.put(key, value);
    }
}
