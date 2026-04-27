import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.script.SqlScriptParser;
import org.jumpserver.chen.framework.script.SqlScriptValidator;

import java.util.List;

/**
 * task-03 slice A probe: validate {@link SqlScriptValidator} and
 * {@link SqlScriptParser} statement boundaries across dialect quirks.
 *
 * <p>Run via:</p>
 * <pre>
 *   mvn -pl backend/framework -am -DskipTests test-compile dependency:build-classpath \
 *       -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test -q
 *   java -cp backend/framework/target/classes:backend/framework/target/test-classes:$(cat /tmp/cp.txt) \
 *        TestSqlScriptParserAndValidator
 * </pre>
 */
public class TestSqlScriptParserAndValidator {

    static int failures = 0;

    public static void main(String[] args) {
        // ---- Validator: extension ----
        SqlScriptValidator v = new SqlScriptValidator();

        report("missing filename rejected",
                !v.validateExtension(null).isOk(), true,
                !v.validateExtension(null).isOk());
        report("non-sql extension rejected",
                v.validateExtension("script.txt").getReason() == SqlScriptValidator.Reason.UNSUPPORTED_EXTENSION,
                true, true);
        report("uppercase .SQL accepted",
                v.validateExtension("SCRIPT.SQL").isOk(), true, true);
        report("empty file rejected",
                v.validate("a.sql", new byte[0]).getReason() == SqlScriptValidator.Reason.EMPTY_FILE,
                true, true);
        report("over-size rejected",
                v.validate("a.sql", new byte[(int) (SqlScriptValidator.DEFAULT_MAX_BYTES + 1)]).getReason()
                        == SqlScriptValidator.Reason.SIZE_EXCEEDED,
                true, true);
        report("at-cap accepted",
                v.validate("a.sql", new byte[(int) SqlScriptValidator.DEFAULT_MAX_BYTES]).isOk(),
                true, true);

        // Custom cap honoured
        SqlScriptValidator small = new SqlScriptValidator(1024);
        report("custom cap (1KB) over-size rejected",
                small.validate("a.sql", new byte[2048]).getReason() == SqlScriptValidator.Reason.SIZE_EXCEEDED,
                true, true);

        // Localised message format
        SqlScriptValidator.Result over = v.validate("a.sql", new byte[(int) (SqlScriptValidator.DEFAULT_MAX_BYTES + 1)]);
        report("size message contains 10 MB",
                over.getMessage().contains("10 MB"), true, over.getMessage().contains("10 MB"));

        // ---- Parser: dbType resolution ----
        report("dbType mysql", SqlScriptParser.resolveDbType("mysql") == DbType.mysql, true, true);
        report("dbType MariaDB -> mysql", SqlScriptParser.resolveDbType("MariaDB") == DbType.mysql, true, true);
        report("dbType postgres -> postgresql", SqlScriptParser.resolveDbType("postgres") == DbType.postgresql, true, true);
        report("dbType MSSQL -> sqlserver", SqlScriptParser.resolveDbType("MSSQL") == DbType.sqlserver, true, true);
        report("dbType null -> other", SqlScriptParser.resolveDbType(null) == DbType.other, true, true);
        report("dbType unknown -> other", SqlScriptParser.resolveDbType("foobar") == DbType.other, true, true);

        // ---- Parser: empty / blank ----
        report("null script -> empty result", SqlScriptParser.parse(null, DbType.mysql).size() == 0, true, true);
        report("blank script -> empty result", SqlScriptParser.parse("   \n  ", DbType.mysql).size() == 0, true, true);

        // ---- Parser: simple multi-statement (mysql) ----
        var mysqlBasic = SqlScriptParser.parse(
                "SELECT 1; SELECT 2; SELECT 3;", DbType.mysql);
        report("mysql basic count=3", mysqlBasic.size() == 3, 3, mysqlBasic.size());
        report("mysql first leading keyword=SELECT",
                mysqlBasic.getStatements().get(0).getLeadingKeyword().equals("SELECT"), true, true);

        // Semicolon inside string literal must NOT split (mysql)
        var mysqlString = SqlScriptParser.parse(
                "INSERT INTO t (msg) VALUES ('a; b; c'); SELECT 1;", DbType.mysql);
        report("mysql semicolon-in-string count=2", mysqlString.size() == 2, 2, mysqlString.size());
        report("mysql semicolon-in-string first is INSERT",
                mysqlString.getStatements().get(0).getLeadingKeyword().equals("INSERT"), true, true);

        // Block comment containing semicolon must NOT split (mysql)
        var mysqlComment = SqlScriptParser.parse(
                "/* hello; world; */ SELECT 1; -- trailing; comment\n SELECT 2;", DbType.mysql);
        report("mysql comment-with-semicolon count=2", mysqlComment.size() == 2, 2, mysqlComment.size());
        // First statement leading keyword should be SELECT after the comment
        report("mysql leading-keyword skips block comment",
                mysqlComment.getStatements().get(0).getLeadingKeyword().equals("SELECT"), true, true);

        // PostgreSQL multi-statement with quoted identifiers and a regular
        // string literal containing semicolons. (druid's PG parser does not
        // yet accept dollar-quoted bodies or LANGUAGE plpgsql blocks; that
        // case is tracked as a known limitation in SqlScriptParser javadoc
        // and will be covered by a dialect-aware fallback splitter in
        // slice B if customer scripts require it.)
        var pg = SqlScriptParser.parse(
                "INSERT INTO \"t;1\" (msg) VALUES ('a; b; c'); SELECT 1;",
                DbType.postgresql);
        report("pg quoted-id + string count=2", pg.size() == 2, 2, pg.size());

        // SQL Server bracketed identifier and GO-less script
        var mssql = SqlScriptParser.parse(
                "SELECT TOP 5 [my;col] FROM [my;tbl]; SELECT 2;", DbType.sqlserver);
        report("mssql bracketed identifiers count=2", mssql.size() == 2, 2, mssql.size());

        // Trailing whitespace / no trailing semicolon
        var trailing = SqlScriptParser.parse("SELECT 1\n", DbType.mysql);
        report("mysql no-trailing-semicolon count=1", trailing.size() == 1, 1, trailing.size());

        // Indices are sequential
        var idx = SqlScriptParser.parse("SELECT 1; SELECT 2; SELECT 3;", DbType.mysql);
        boolean idxOk = idx.getStatements().get(0).getIndex() == 0
                && idx.getStatements().get(1).getIndex() == 1
                && idx.getStatements().get(2).getIndex() == 2;
        report("statement indices sequential", idxOk, true, idxOk);

        // Malformed input still returns gracefully (no exception escapes)
        var bad = SqlScriptParser.parse("THIS IS NOT SQL @@@@@", DbType.mysql);
        report("malformed input does not throw",
                bad != null && (bad.isOk() || bad.getParseError() != null), true, true);

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-8s actual=%-8s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
