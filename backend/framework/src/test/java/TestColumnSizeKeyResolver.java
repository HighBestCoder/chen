import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.audit.ColumnSizeKeyResolver;
import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.List;

public class TestColumnSizeKeyResolver {

    static int failures = 0;

    public static void main(String[] args) {
        baseTableFromMetadata();
        baseColumnFromMetadataWinsOverAlias();
        tableWithoutBaseColumnIsNotExact();
        singleTableFallback();
        joinFallback();
        joinWithoutMetadataIsUnresolved();
        unknownFallback();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void baseTableFromMetadata() {
        Field f = field("user_id", "user");
        f.setSourceName("user_id");
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT u.user_id FROM user u", DbType.mysql, List.of(f));
        report("metadata table wins", result.getKeys().equals(List.of("user.user_id")),
                List.of("user.user_id"), result.getKeys());
        report("metadata status ok", SizeCalculator.STATUS_OK.equals(result.getStatus()),
                SizeCalculator.STATUS_OK, result.getStatus());
        report("metadata mode exact", "metadata_exact".equals(result.getSourceMode()),
                "metadata_exact", result.getSourceMode());
    }

    private static void baseColumnFromMetadataWinsOverAlias() {
        Field f = field("alias_id", "user");
        f.setSourceName("user_id");
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT u.user_id AS alias_id FROM user u", DbType.mysql, List.of(f));
        report("base column wins over alias", result.getKeys().equals(List.of("user.user_id")),
                List.of("user.user_id"), result.getKeys());
        report("alias metadata status ok", SizeCalculator.STATUS_OK.equals(result.getStatus()),
                SizeCalculator.STATUS_OK, result.getStatus());
        report("alias metadata mode exact", "metadata_exact".equals(result.getSourceMode()),
                "metadata_exact", result.getSourceMode());
    }

    private static void tableWithoutBaseColumnIsNotExact() {
        Field f = field("alias_id", "user");
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT u.user_id AS alias_id FROM user u", DbType.sqlserver, List.of(f));
        report("table without base column keeps emitted key", result.getKeys().equals(List.of("user.alias_id")),
                List.of("user.alias_id"), result.getKeys());
        report("table without base column is partial", SizeCalculator.STATUS_PARTIAL.equals(result.getStatus()),
                SizeCalculator.STATUS_PARTIAL, result.getStatus());
        report("table without base column mode unresolved", "unresolved".equals(result.getSourceMode()),
                "unresolved", result.getSourceMode());
    }

    private static void singleTableFallback() {
        Field f = field("cnt", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT COUNT(*) AS cnt FROM user", DbType.mysql, List.of(f));
        report("single table derived key", result.getKeys().equals(List.of("user.cnt")),
                List.of("user.cnt"), result.getKeys());
        report("single table fallback partial", SizeCalculator.STATUS_PARTIAL.equals(result.getStatus()),
                SizeCalculator.STATUS_PARTIAL, result.getStatus());
        report("single table fallback mode", "sql_single_table_fallback".equals(result.getSourceMode()),
                "sql_single_table_fallback", result.getSourceMode());
    }

    private static void joinFallback() {
        Field f = field("cnt", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT COUNT(*) AS cnt FROM user u LEFT JOIN orders o ON u.id=o.user_id INNER JOIN car c ON o.car_id=c.id",
                DbType.mysql, List.of(f));
        report("join derived key",
                result.getKeys().equals(List.of("unknown.cnt")),
                List.of("unknown.cnt"), result.getKeys());
        report("join derived mode unresolved", "unresolved".equals(result.getSourceMode()),
                "unresolved", result.getSourceMode());
    }

    private static void joinWithoutMetadataIsUnresolved() {
        Field leftId = field("id", null);
        Field rightId = field("id", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT * FROM T20251027 t1 LEFT JOIN T100 t2 ON t1.ID = t2.ID",
                DbType.mysql, List.of(leftId, rightId));
        report("join select-star does not guess physical tables",
                result.getKeys().equals(List.of("unknown.id", "unknown.id")),
                List.of("unknown.id", "unknown.id"), result.getKeys());
        report("join select-star partial", SizeCalculator.STATUS_PARTIAL.equals(result.getStatus()),
                SizeCalculator.STATUS_PARTIAL, result.getStatus());
        report("join select-star mode unresolved", "unresolved".equals(result.getSourceMode()),
                "unresolved", result.getSourceMode());
    }

    private static void unknownFallback() {
        Field f = field("cnt", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT 1 AS cnt", DbType.mysql, List.of(f));
        report("unknown derived key", result.getKeys().equals(List.of("unknown.cnt")),
                List.of("unknown.cnt"), result.getKeys());
        report("unknown is partial", SizeCalculator.STATUS_PARTIAL.equals(result.getStatus()),
                SizeCalculator.STATUS_PARTIAL, result.getStatus());
        report("unknown mode unresolved", "unresolved".equals(result.getSourceMode()),
                "unresolved", result.getSourceMode());
    }

    private static Field field(String name, String table) {
        Field f = new Field();
        f.setName(name);
        f.setTable(table);
        return f;
    }

    private static <T> void report(String label, boolean ok, T expected, T actual) {
        if (!ok) {
            failures++;
        }
        System.out.printf("%-4s  expect=%-45s actual=%-45s  %s%n",
                ok ? "ok" : "FAIL", expected, actual, label);
    }
}
