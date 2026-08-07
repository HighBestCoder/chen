import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.audit.ExecutionStats;
import org.jumpserver.chen.framework.audit.SqlExecutionStatsBuilder;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.policy.QueryPolicy;
import org.jumpserver.chen.framework.policy.QueryPolicyHolder;
import org.jumpserver.chen.framework.utils.PageUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class TestQueryLimitBehavior {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        policyOptionsMatchContract();
        detectsManualLimits();
        rewritesToolbarLimits();
        recordsLimitAuditExtras();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void policyOptionsMatchContract() {
        List<Integer> expected = List.of(50, 100, 500, 5000, 10000);
        report("policy options match contract",
                QueryPolicy.DEFAULT_CONSOLE_LIMIT_OPTIONS.equals(expected),
                expected, QueryPolicy.DEFAULT_CONSOLE_LIMIT_OPTIONS);
        report("default console limit is 50",
                QueryPolicy.DEFAULT_CONSOLE_LIMIT == 50, 50, QueryPolicy.DEFAULT_CONSOLE_LIMIT);
    }

    private static void detectsManualLimits() {
        report("mysql LIMIT 200 detected",
                PageUtils.getLimit("SELECT * FROM users LIMIT 200", DbType.mysql) == 200,
                200, PageUtils.getLimit("SELECT * FROM users LIMIT 200", DbType.mysql));
        report("postgres LIMIT 200 detected",
                PageUtils.getLimit("SELECT * FROM users LIMIT 200", DbType.postgresql) == 200,
                200, PageUtils.getLimit("SELECT * FROM users LIMIT 200", DbType.postgresql));
        report("sqlserver TOP 200 detected",
                PageUtils.getLimit("SELECT TOP 200 * FROM users", DbType.sqlserver) == 200,
                200, PageUtils.getLimit("SELECT TOP 200 * FROM users", DbType.sqlserver));
        report("sqlserver OFFSET FETCH 200 detected",
                PageUtils.getLimit("SELECT * FROM users ORDER BY id OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY", DbType.sqlserver) == 200,
                200, PageUtils.getLimit("SELECT * FROM users ORDER BY id OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY", DbType.sqlserver));
    }

    private static void rewritesToolbarLimits() throws Exception {
        QueryPolicy policy = new QueryPolicy();
        policy.setMaxRows(10000);
        policy.setDefaultConsoleLimit(50);
        QueryPolicyHolder.install(policy);

        SQLExecutePlan mysql = new SQLExecutePlan("SELECT * FROM users", DbType.mysql);
        mysql.setSqlActuator(new CountingActuator(DbType.mysql));
        SQLQueryParams params = new SQLQueryParams();
        params.setLimit(500);
        params.setLimitSource("toolbar");
        mysql.setSqlQueryParams(params);
        mysql.generateTargetSQL();
        report("mysql toolbar rewrites LIMIT 500",
                mysql.getTargetSQL().toUpperCase().contains("LIMIT 500"),
                "LIMIT 500", mysql.getTargetSQL());
        report("mysql toolbar audit queryLimit 500", mysql.getQueryLimit() == 500, 500, mysql.getQueryLimit());
        report("mysql toolbar source", "toolbar".equals(mysql.getLimitSource()), "toolbar", mysql.getLimitSource());

        SQLExecutePlan postgres = plan("SELECT * FROM users", DbType.postgresql, 500, "toolbar");
        report("postgres toolbar rewrites LIMIT 500",
                postgres.getTargetSQL().toUpperCase().contains("LIMIT 500"),
                "LIMIT 500", postgres.getTargetSQL());

        SQLExecutePlan sqlServer = plan("SELECT * FROM users", DbType.sqlserver, 500, "toolbar");
        report("sqlserver toolbar rewrites TOP 500",
                sqlServer.getTargetSQL().toUpperCase().contains("TOP 500"),
                "TOP 500", sqlServer.getTargetSQL());

        SQLExecutePlan manual = new SQLExecutePlan("SELECT * FROM users LIMIT 200", DbType.mysql);
        manual.setSqlActuator(new CountingActuator(DbType.mysql));
        SQLQueryParams manualParams = new SQLQueryParams();
        manualParams.setLimit(500);
        manual.setSqlQueryParams(manualParams);
        manual.generateTargetSQL();
        report("manual SQL not rewritten",
                "SELECT * FROM users LIMIT 200".equals(manual.getTargetSQL()),
                "SELECT * FROM users LIMIT 200", manual.getTargetSQL());
        report("manual limit detected", manual.isManualLimitDetected(), true, manual.isManualLimitDetected());
        report("manual source", "manual".equals(manual.getLimitSource()), "manual", manual.getLimitSource());
        report("manual queryLimit records 200", manual.getQueryLimit() == 200, 200, manual.getQueryLimit());
    }

    private static void recordsLimitAuditExtras() {
        SQLQueryResult toolbar = new SQLQueryResult("SELECT * FROM users LIMIT 500");
        toolbar.setOriginalCommand("SELECT * FROM users");
        toolbar.setExecutedCommand("SELECT * FROM users LIMIT 500");
        toolbar.setLimitSource("toolbar");
        toolbar.setQueryLimit(500);
        toolbar.setManualLimitDetected(false);
        ExecutionStats toolbarStats = SqlExecutionStatsBuilder.fromSuccess(null, "SELECT * FROM users", toolbar);
        report("audit original command",
                "SELECT * FROM users".equals(toolbarStats.getExtras().get("original_command")),
                "SELECT * FROM users", toolbarStats.getExtras().get("original_command"));
        report("audit original_sql alias",
                "SELECT * FROM users".equals(toolbarStats.getExtras().get("original_sql")),
                "SELECT * FROM users", toolbarStats.getExtras().get("original_sql"));
        report("audit executed command",
                "SELECT * FROM users LIMIT 500".equals(toolbarStats.getExtras().get("executed_command")),
                "SELECT * FROM users LIMIT 500", toolbarStats.getExtras().get("executed_command"));
        report("audit executed_sql alias",
                "SELECT * FROM users LIMIT 500".equals(toolbarStats.getExtras().get("executed_sql")),
                "SELECT * FROM users LIMIT 500", toolbarStats.getExtras().get("executed_sql"));
        report("audit limit source toolbar",
                "toolbar".equals(toolbarStats.getExtras().get("limit_source")),
                "toolbar", toolbarStats.getExtras().get("limit_source"));
        report("audit query limit 500",
                Integer.valueOf(500).equals(toolbarStats.getExtras().get("query_limit")),
                500, toolbarStats.getExtras().get("query_limit"));
        report("audit manual false",
                Boolean.FALSE.equals(toolbarStats.getExtras().get("manual_limit_detected")),
                false, toolbarStats.getExtras().get("manual_limit_detected"));

        SQLQueryResult manual = new SQLQueryResult("SELECT * FROM users LIMIT 200");
        manual.setOriginalCommand("SELECT * FROM users LIMIT 200");
        manual.setExecutedCommand("SELECT * FROM users LIMIT 200");
        manual.setLimitSource("manual");
        manual.setQueryLimit(200);
        manual.setManualLimitDetected(true);
        ExecutionStats manualStats = SqlExecutionStatsBuilder.fromSuccess(null, "SELECT * FROM users LIMIT 200", manual);
        report("audit manual query limit 200",
                Integer.valueOf(200).equals(manualStats.getExtras().get("query_limit")),
                200, manualStats.getExtras().get("query_limit"));
        report("audit manual true",
                Boolean.TRUE.equals(manualStats.getExtras().get("manual_limit_detected")),
                true, manualStats.getExtras().get("manual_limit_detected"));
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-30s actual=%-30s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }

    private static SQLExecutePlan plan(String sql, DbType dbType, int limit, String limitSource) throws Exception {
        SQLExecutePlan plan = new SQLExecutePlan(sql, dbType);
        plan.setSqlActuator(new CountingActuator(dbType));
        SQLQueryParams params = new SQLQueryParams();
        params.setLimit(limit);
        params.setLimitSource(limitSource);
        plan.setSqlQueryParams(params);
        plan.generateTargetSQL();
        return plan;
    }

    private static final class CountingActuator implements SQLActuator {
        private final DbType dbType;

        private CountingActuator(DbType dbType) {
            this.dbType = dbType;
        }

        public DbType getDbType() {
            return dbType;
        }

        public int getAffectedRows(SQL sql) {
            return 0;
        }

        public List<String> parseSQL(SQL sql) {
            return List.of();
        }

        public <T> List<T> getObjects(String sql, Class<T> clazz, Map<String, Integer> fieldMapping) {
            return List.of();
        }

        public int count(SQL sql) {
            return -1;
        }

        public int count(SQLExecutePlan plan) {
            return -1;
        }

        public SQLQueryResult execute(SQLExecutePlan plan) {
            return null;
        }

        public SQLQueryResult execute(SQL sql) {
            return null;
        }

        public SQLQueryResult executeWithAudit(SQLExecutePlan plan) {
            return null;
        }

        public SQLQueryResult executeWithAudit(SQL sql) {
            return null;
        }

        public SQLActuator withConnection(Connection connection) {
            return this;
        }

        public SQLExecutePlan createPlan(SQL sql) {
            return null;
        }

        public SQLExecutePlan createPlan(SQL sql, SQLQueryParams params) {
            return null;
        }

        public SQLExecutePlan createPlan(String schema, String table, SQLQueryParams sqlQueryParams) {
            return null;
        }

        public String getCurrentSchema() throws SQLException {
            return null;
        }

        public List<String> getSchemas() {
            return List.of();
        }

        public void changeSchema(String schema) {
        }
    }
}
