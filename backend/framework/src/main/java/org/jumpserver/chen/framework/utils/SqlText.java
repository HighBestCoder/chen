package org.jumpserver.chen.framework.utils;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.parser.*;
import java.util.*;
import java.util.function.Function;

/** Retain execution text; ASTs are for validation and bounded structural edits. */
public final class SqlText {
    private SqlText() {}

    public static List<SQLStatement> analyze(String sql, DbType type) {
        return com.alibaba.druid.sql.SQLUtils.parseStatements(protect(sql, type).text(), type);
    }

    public static List<String> statements(String sql, DbType type) {
        var protectedSql = protect(sql, type);
        var parser = SQLParserUtils.createSQLStatementParser(protectedSql.text(), type);
        var lexer = parser.getLexer();
        var result = new ArrayList<String>();
        int start = 0;
        while (lexer.token() != Token.EOF) {
            var parsed = new ArrayList<SQLStatement>();
            parser.parseStatementList(parsed, 1);
            if (parsed.isEmpty()) break;
            int end;
            if (lexer.token() == Token.SEMI) end = protectedSql.originalPosition(lexer.pos() - 1);
            else if (lexer.token() == Token.EOF) end = sql.length();
            else throw new ParserException("Separate SQL statements with semicolons");
            String text = sql.substring(start, end).strip();
            if (!text.isEmpty()) result.add(text);
            start = lexer.token() == Token.SEMI ? end + 1 : end;
        }
        return result;
    }

    /** Shield quoted tokens from Druid's dialect-dependent literal re-escaping. */
    public static String rewrite(String sql, DbType type, Function<String, String> rewrite) {
        var protectedSql = protect(sql, type);
        String output = rewrite.apply(protectedSql.text());
        for (var token : protectedSql.tokens().entrySet()) output = output.replace(token.getKey(), token.getValue());
        if (output.toLowerCase(Locale.ROOT).contains(protectedSql.prefix())) throw new ParserException("SQL rewrite lost an original token");
        return output;
    }

    private record ProtectedSql(String text, String prefix, Map<String, String> tokens, List<int[]> positions) {
        int originalPosition(int position) {
            int delta = 0;
            for (int[] span : positions) {
                if (span[0] > position) break;
                delta = span[1] - span[0];
            }
            return position + delta;
        }
    }

    private static ProtectedSql protect(String sql, DbType type) {
        String prefix = "__chen_original_";
        while (sql.toLowerCase(Locale.ROOT).contains(prefix)) prefix += "x";
        var tokens = new LinkedHashMap<String, String>();
        var positions = new ArrayList<int[]>();
        var masked = new StringBuilder();
        for (int i = 0; i < sql.length();) {
            int start = i;
            char c = sql.charAt(i);
            if (i + 1 < sql.length() && ((c == '-' && sql.charAt(i + 1) == '-')
                    || (c == '#' && (type == DbType.mysql || type == DbType.mariadb)))) {
                int end = sql.indexOf('\n', i); if (end < 0) end = sql.length();
                masked.append(sql, i, end); i = end; continue;
            }
            if (i + 1 < sql.length() && c == '/' && sql.charAt(i + 1) == '*') {
                int depth = 1; i += 2;
                while (i < sql.length() && depth > 0) {
                    if (sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                masked.append(sql, start, i); continue;
            }
            // PostgreSQL dollar-quoted values are opaque, including embedded SQL.
            if (c == '$' && type == DbType.postgresql) {
                var match = java.util.regex.Pattern.compile("\\$(?:[A-Za-z_][A-Za-z_0-9]*)?\\$").matcher(sql.substring(i));
                if (match.lookingAt()) {
                    String tag = match.group(); int end = sql.indexOf(tag, i + tag.length());
                    if (end < 0) throw new ParserException("Unterminated dollar-quoted SQL value");
                    i = end + tag.length();
                    String key = "'" + prefix + tokens.size() + "'";
                    tokens.put(key, sql.substring(start, i)); masked.append(key);
                    positions.add(new int[]{masked.length(), i}); continue;
                }
            }
            boolean prefixed = i + 1 < sql.length() && "eEnNbBxX".indexOf(c) >= 0 && sql.charAt(i + 1) == '\''
                    && (i == 0 || !Character.isJavaIdentifierPart(sql.charAt(i - 1)));
            if (prefixed) c = sql.charAt(++i);
            boolean quoted = c == '\'' || c == '"' || c == '`' || (c == '[' && type == DbType.sqlserver);
            if (!quoted) { masked.append(sql.charAt(i++)); continue; }
            char close = c == '[' ? ']' : c;
            boolean slash = type == DbType.mysql || type == DbType.mariadb || type == DbType.clickhouse
                    || (prefixed && (sql.charAt(start) == 'e' || sql.charAt(start) == 'E'));
            i++;
            boolean ended = false;
            while (i < sql.length()) {
                if (sql.charAt(i) == '\\' && slash) { i += Math.min(2, sql.length() - i); continue; }
                if (sql.charAt(i++) == close) {
                    if (i < sql.length() && sql.charAt(i) == close) { i++; continue; }
                    ended = true; break;
                }
            }
            if (!ended) throw new ParserException("Unterminated quoted SQL token");
            String key = "" + c + prefix + tokens.size() + close;
            tokens.put(key, sql.substring(start, i)); masked.append(key);
            positions.add(new int[]{masked.length(), i});
        }
        return new ProtectedSql(masked.toString(), prefix, tokens, positions);
    }
}
