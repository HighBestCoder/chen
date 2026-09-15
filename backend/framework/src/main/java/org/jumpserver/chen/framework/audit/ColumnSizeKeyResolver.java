package org.jumpserver.chen.framework.audit;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import lombok.Getter;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves stable keys for per-column size accounting. JDBC metadata is the
 * primary source; SQL text is only a best-effort fallback for derived columns
 * whose metadata has no table name.
 */
public final class ColumnSizeKeyResolver {

    private static final String UNKNOWN_PREFIX = "unknown";
    public static final String MODE_METADATA_EXACT = "metadata_exact";
    public static final String MODE_SQL_SINGLE_TABLE_FALLBACK = "sql_single_table_fallback";
    public static final String MODE_UNRESOLVED = "unresolved";
    private ColumnSizeKeyResolver() {
    }

    public static ResolveResult resolve(String command, DbType dbType, List<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return new ResolveResult(List.of(), SizeCalculator.STATUS_OK, MODE_METADATA_EXACT, null);
        }

        SourceInfo sourceInfo = null;
        List<String> keys = new ArrayList<>(fields.size());
        boolean partial = false;
        String reason = null;
        String mode = MODE_METADATA_EXACT;

        for (Field field : fields) {
            String label = emptyToFallback(field == null ? null : field.getName(), "column");
            String sourceName = field == null ? null : field.getSourceName();
            String column = emptyToFallback(sourceName, label);
            String table = field == null || field.getTable() == null ? "" : field.getTable();
            if (!table.isEmpty()) {
                keys.add(table + "." + column);
                if (sourceName == null || sourceName.trim().isEmpty()) {
                    partial = true;
                    mode = MODE_UNRESOLVED;
                    reason = "driver did not provide source column";
                }
                continue;
            }

            partial = true;
            if (sourceInfo == null) {
                sourceInfo = SourceInfo.from(command, dbType);
            }
            if (sourceInfo.singleTable()) {
                keys.add(sourceInfo.baseTable + "." + label);
                if (MODE_METADATA_EXACT.equals(mode)) {
                    mode = MODE_SQL_SINGLE_TABLE_FALLBACK;
                }
                if (reason == null) {
                    reason = "driver did not provide source table; single SQL source used";
                }
            } else {
                keys.add(UNKNOWN_PREFIX + "." + label);
                mode = MODE_UNRESOLVED;
                reason = sourceInfo.baseTable == null || sourceInfo.baseTable.isEmpty()
                        ? "source table parse failed"
                        : "driver did not provide source table and SQL source is ambiguous";
            }
        }

        return new ResolveResult(keys,
                partial ? SizeCalculator.STATUS_PARTIAL : SizeCalculator.STATUS_OK,
                mode,
                reason);
    }

    public static String keyForField(Field field) {
        String column = emptyToFallback(field == null ? null : field.getSourceName(),
                emptyToFallback(field == null ? null : field.getName(), "column"));
        String table = field == null || field.getTable() == null ? "" : field.getTable();
        if (table.isEmpty()) {
            table = UNKNOWN_PREFIX;
        }
        return table + "." + column;
    }

    // The AST has already separated schema/catalog from the final identifier.
    // Preserve dots and spaces inside quoted names so fallback keys match JDBC.
    private static String unquoteIdentifier(String value) {
        // Druid can rewrite escaped quotes (e.g. PostgreSQL doubled quotes)
        // into backslash escapes. Without the original token their physical
        // spelling is ambiguous; keep such identifiers unresolved.
        if (value != null && value.indexOf('\\') >= 0) return null;
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '`' && last == '`')
                || (first == '[' && last == ']')) {
            String quote = String.valueOf(last);
            return value.substring(1, value.length() - 1).replace(quote + quote, quote);
        }
        return value;
    }

    private static String emptyToFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @Getter
    public static final class ResolveResult {
        private final List<String> keys;
        private final String status;
        private final String sourceMode;
        private final String unavailableReason;

        private ResolveResult(List<String> keys, String status, String sourceMode, String unavailableReason) {
            this.keys = keys;
            this.status = status;
            this.sourceMode = sourceMode;
            this.unavailableReason = unavailableReason;
        }
    }

    private static final class SourceInfo {
        private final String baseTable;

        private SourceInfo(String baseTable) {
            this.baseTable = baseTable;
        }

        static SourceInfo from(String command, DbType dbType) {
            if (command == null || command.trim().isEmpty()) {
                return new SourceInfo(null);
            }
            try {
                var statements = SQLUtils.parseStatements(command, dbType);
                if (statements.size() != 1 || !(statements.get(0) instanceof SQLSelectStatement statement)) {
                    return new SourceInfo(null);
                }
                SQLSelect select = statement.getSelect();
                if (select.getWithSubQuery() != null
                        || !(select.getQuery() instanceof SQLSelectQueryBlock block)
                        || !(block.getFrom() instanceof SQLExprTableSource table)
                        || !(table.getExpr() instanceof SQLName name)) {
                    return new SourceInfo(null);
                }
                // A simple outer FROM is insufficient: SELECT/WHERE may contain
                // subqueries with additional sources. Do not infer their lineage.
                boolean[] nestedSelect = {false};
                select.accept(new SQLASTVisitorAdapter() {
                    @Override
                    public void preVisit(SQLObject object) {
                        if (object instanceof SQLSelect && object != select) nestedSelect[0] = true;
                    }
                });
                if (nestedSelect[0]) return new SourceInfo(null);
                return new SourceInfo(unquoteIdentifier(name.getSimpleName()));
            } catch (RuntimeException ignored) {
                // Unsupported dialect or malformed SQL must not affect querying
                // or the independently accumulated total byte count.
                return new SourceInfo(null);
            }
        }

        boolean singleTable() {
            return baseTable != null && !baseTable.isEmpty();
        }
    }
}
