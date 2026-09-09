package org.jumpserver.chen.framework.script;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * task-03 slice A: parse a raw SQL script into a list of executable
 * statements honouring the dialect's quoting, comment, delimiter and
 * stored-procedure rules.
 *
 * <p>SqlText uses Druid to validate statement boundaries while retaining the
 * original execution text and protecting quoted tokens during analysis.
 * Unsupported grammar is reported with an empty statement list. Separate
 * top-level statements with semicolons; client directives such as GO and
 * DELIMITER are not SQL statements and are not executed.</p>
 */
@Slf4j
public final class SqlScriptParser {

    private SqlScriptParser() {
    }

    public static Result parse(String script, DbType dbType) {
        if (script == null || script.isBlank()) {
            return Result.empty();
        }
        DbType effective = dbType == null ? DbType.other : dbType;
        try {
            List<String> stmts = org.jumpserver.chen.framework.utils.SqlText.statements(script, effective);
            if (stmts == null || stmts.isEmpty()) {
                return Result.empty();
            }
            List<Statement> out = new ArrayList<>(stmts.size());
            int index = 0;
            for (String sql : stmts) {
                if (sql == null) {
                    continue;
                }
                String trimmed = sql.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String leadingKeyword = extractLeadingKeyword(trimmed);
                out.add(new Statement(index++, trimmed, leadingKeyword));
            }
            return new Result(out, null);
        } catch (RuntimeException e) {
            log.warn("[SqlScriptParser] Failed to parse script (dbType={}): {}",
                    effective, e.getMessage());
            return new Result(Collections.emptyList(), e.getMessage());
        }
    }

    private static String extractLeadingKeyword(String sql) {
        int i = 0;
        int n = sql.length();
        // skip leading line / block comments and whitespace
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '#' || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-')) {
                int eol = sql.indexOf('\n', i + 2);
                i = eol < 0 ? n : eol + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            break;
        }
        int start = i;
        while (i < n && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
            i++;
        }
        return start == i ? "" : sql.substring(start, i).toUpperCase(java.util.Locale.ROOT);
    }

    /** Map a runtime dbType string (chen DBConnectInfo.dbType) to druid {@link DbType}. */
    public static DbType resolveDbType(String dbType) {
        if (dbType == null) {
            return DbType.other;
        }
        switch (dbType.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "mysql":
            case "mariadb":
                return DbType.mysql;
            case "postgresql":
            case "postgres":
            case "pg":
                return DbType.postgresql;
            case "sqlserver":
            case "mssql":
                return DbType.sqlserver;
            case "oracle":
                return DbType.oracle;
            case "db2":
                return DbType.db2;
            case "clickhouse":
                return DbType.clickhouse;
            case "dameng":
            case "dm":
                return DbType.dm;
            default:
                return DbType.other;
        }
    }

    @Getter
    public static final class Statement {
        private final int index;
        private final String sql;
        private final String leadingKeyword;

        public Statement(int index, String sql, String leadingKeyword) {
            this.index = index;
            this.sql = Objects.requireNonNull(sql);
            this.leadingKeyword = leadingKeyword == null ? "" : leadingKeyword;
        }
    }

    @Getter
    public static final class Result {
        private final List<Statement> statements;
        private final String parseError;

        Result(List<Statement> statements, String parseError) {
            this.statements = statements;
            this.parseError = parseError;
        }

        public static Result empty() {
            return new Result(Collections.emptyList(), null);
        }

        public boolean isOk() {
            return parseError == null;
        }

        public int size() {
            return statements.size();
        }
    }
}
