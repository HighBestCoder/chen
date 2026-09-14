import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.policy.*;
import org.jumpserver.chen.framework.utils.SqlText;
import org.jumpserver.chen.modules.mongodb.command.*;
import java.util.*;

public class TestQaRun260911 {
    public static void main(String[] args) throws Exception {
        var failures = new ArrayList<String>();
        for (DbType type : List.of(DbType.postgresql, DbType.mysql, DbType.sqlserver)) {
            String raw = "  /* ticket */ SELECT 'a;''b' AS x; -- trailing ticket\n";
            try { if (!SqlText.statements(raw, type).equals(List.of(raw))) failures.add("DEF-03 " + type); }
            catch (Exception e) { failures.add("DEF-03 " + type + " " + e); }
        }
        QueryPolicy policy = new QueryPolicy(); policy.setMaxRows(20); QueryPolicyHolder.install(policy);
        for (DbType type : List.of(DbType.postgresql, DbType.mysql, DbType.sqlserver)) {
            String sql = type == DbType.sqlserver ? "SELECT TOP 30 * FROM fixture" : "SELECT * FROM fixture LIMIT 30";
            SQLExecutePlan plan = new SQLExecutePlan(sql, type);
            try { plan.generateTargetSQL(); failures.add("DEF-09 oversized manual limit accepted " + type); }
            catch (java.sql.SQLException expected) { if (!expected.getMessage().contains("20")) failures.add("DEF-09 unreadable cap"); }
        }
        QueryPolicyHolder.install(new QueryPolicy());
        try { new MongoCommandParser().parse("var x='" + "x".repeat(300_000) + "'; x;"); failures.add("DEF-10 oversize parsed"); }
        catch (MongoCommandException expected) { }
        try { MongoScriptRunner.evaluate("'" + "\u4e2d".repeat(90_000) + "'", "fixture", 20000, (a,b) -> "{}"); failures.add("DEF-10 UTF8 byte limit"); }
        catch (MongoCommandException expected) { }
        try { MongoScriptRunner.evaluate("var n=NumberLong(9007199254740993); db.c.insertOne({n:n});", "fixture", 30000,
                (a,b) -> { throw new AssertionError("DEF-07 rounded write reached database"); }); failures.add("DEF-07 unsafe numeric input accepted"); }
        catch (MongoCommandException expected) { }
        var csv = org.jumpserver.chen.framework.console.dataview.DataView.class.getDeclaredMethod("writeRow", java.io.BufferedWriter.class, java.util.List.class, java.util.List.class);
        csv.setAccessible(true); var field = new org.jumpserver.chen.framework.datasource.entity.resource.Field(); field.setName("value");
        var text = new java.io.StringWriter();var writer=new java.io.BufferedWriter(text);
        csv.invoke(null, writer, List.of(field), java.util.Arrays.asList((Object)null));
        csv.invoke(null, writer, List.of(field), List.of("NULL"));writer.flush();
        if (!text.toString().equals(System.lineSeparator()+"NULL"+System.lineSeparator())) failures.add("DEF-13 null confused with literal NULL");
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
        System.out.println("RUN-260911: raw SQL, manual cap, preparse UTF8 bound, unsafe NumberLong rejection passed");
    }
}
