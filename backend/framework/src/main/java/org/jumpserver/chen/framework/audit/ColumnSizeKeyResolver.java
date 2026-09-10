package org.jumpserver.chen.framework.audit;

import com.alibaba.druid.DbType;
import lombok.Getter;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves stable keys for per-column size accounting. JDBC metadata is the
 * primary source; SQL text is only a best-effort fallback for derived columns
 * whose metadata has no table name.
 */
public final class ColumnSizeKeyResolver {

    private static final String UNKNOWN_PREFIX = "unknown";
    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?is)\\bfrom\\s+([`\\\"\\[]?[A-Za-z0-9_.$-]+[`\\\"\\]]?)");
    private static final Pattern JOIN_TABLE = Pattern.compile(
            "(?is)\\b(left\\s+outer|right\\s+outer|full\\s+outer|inner|left|right|full|cross)?\\s*join\\s+([`\\\"\\[]?[A-Za-z0-9_.$-]+[`\\\"\\]]?)");

    private ColumnSizeKeyResolver() {
    }

    public static ResolveResult resolve(String command, DbType dbType, List<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return new ResolveResult(List.of(), SizeCalculator.STATUS_OK, null);
        }

        SourceInfo sourceInfo = SourceInfo.from(command);
        List<String> keys = new ArrayList<>(fields.size());
        boolean partial = false;
        String reason = null;

        for (Field field : fields) {
            String column = emptyToFallback(field == null ? null : field.getName(), "column");
            String table = field == null || field.getTable() == null ? "" : field.getTable();
            if (!table.isEmpty()) {
                keys.add(table + "." + column);
                continue;
            }

            String fallbackPrefix = sourceInfo.prefix();
            if (fallbackPrefix == null || fallbackPrefix.isEmpty()) {
                fallbackPrefix = UNKNOWN_PREFIX;
                partial = true;
                reason = "source table parse failed";
            } else if (sourceInfo.partial()) {
                partial = true;
                reason = "source table parse partial";
            }
            keys.add(fallbackPrefix + "." + column);
        }

        return new ResolveResult(keys,
                partial ? SizeCalculator.STATUS_PARTIAL : SizeCalculator.STATUS_OK,
                reason);
    }

    public static String keyForField(Field field) {
        String column = emptyToFallback(field == null ? null : field.getName(), "column");
        String table = field == null || field.getTable() == null ? "" : field.getTable();
        if (table.isEmpty()) {
            table = UNKNOWN_PREFIX;
        }
        return table + "." + column;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        while ((v.startsWith("`") && v.endsWith("`"))
                || (v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("[") && v.endsWith("]"))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        int dot = Math.max(v.lastIndexOf('.'), v.lastIndexOf(':'));
        if (dot >= 0 && dot + 1 < v.length()) {
            v = v.substring(dot + 1);
        }
        return v.replaceAll("\\s+", "_");
    }

    private static String emptyToFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    @Getter
    public static final class ResolveResult {
        private final List<String> keys;
        private final String status;
        private final String unavailableReason;

        private ResolveResult(List<String> keys, String status, String unavailableReason) {
            this.keys = keys;
            this.status = status;
            this.unavailableReason = unavailableReason;
        }
    }

    private static final class SourceInfo {
        private final String baseTable;
        private final List<JoinPart> joins;
        private final boolean partial;

        private SourceInfo(String baseTable, List<JoinPart> joins, boolean partial) {
            this.baseTable = baseTable;
            this.joins = joins;
            this.partial = partial;
        }

        static SourceInfo from(String command) {
            if (command == null || command.trim().isEmpty()) {
                return new SourceInfo(null, List.of(), true);
            }
            Matcher from = FROM_TABLE.matcher(command);
            if (!from.find()) {
                return new SourceInfo(null, List.of(), true);
            }
            String base = normalize(from.group(1));
            List<JoinPart> joins = new ArrayList<>();
            Matcher join = JOIN_TABLE.matcher(command);
            while (join.find()) {
                joins.add(new JoinPart(joinKind(join.group(1)), normalize(join.group(2))));
            }
            return new SourceInfo(base, joins, true); // Regex attribution is best-effort, never authoritative.
        }

        String prefix() {
            if (baseTable == null || baseTable.isEmpty()) {
                return null;
            }
            if (joins.isEmpty()) {
                return baseTable;
            }
            StringBuilder sb = new StringBuilder(baseTable);
            for (JoinPart join : joins) {
                if (join.table == null || join.table.isEmpty()) {
                    return null;
                }
                sb.append('_').append(join.kind).append('_').append(join.table);
            }
            return sb.toString();
        }

        boolean partial() {
            return partial;
        }

        private static String joinKind(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return "inner_join";
            }
            String kind = raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
            if (!kind.endsWith("_join")) {
                kind = kind + "_join";
            }
            return kind;
        }
    }

    private static final class JoinPart {
        private final String kind;
        private final String table;

        private JoinPart(String kind, String table) {
            this.kind = kind;
            this.table = table;
        }
    }
}
