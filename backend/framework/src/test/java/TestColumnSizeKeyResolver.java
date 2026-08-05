import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.audit.ColumnSizeKeyResolver;
import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;

import java.util.List;

public class TestColumnSizeKeyResolver {

    static int failures = 0;

    public static void main(String[] args) {
        baseTableFromMetadata();
        singleTableFallback();
        joinFallback();
        unknownFallback();

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    private static void baseTableFromMetadata() {
        Field f = field("user_id", "user");
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT u.user_id FROM user u", DbType.mysql, List.of(f));
        report("metadata table wins", result.getKeys().equals(List.of("user.user_id")),
                List.of("user.user_id"), result.getKeys());
        report("metadata status ok", SizeCalculator.STATUS_OK.equals(result.getStatus()),
                SizeCalculator.STATUS_OK, result.getStatus());
    }

    private static void singleTableFallback() {
        Field f = field("cnt", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT COUNT(*) AS cnt FROM user", DbType.mysql, List.of(f));
        report("single table derived key", result.getKeys().equals(List.of("user.cnt")),
                List.of("user.cnt"), result.getKeys());
    }

    private static void joinFallback() {
        Field f = field("cnt", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT COUNT(*) AS cnt FROM user u LEFT JOIN orders o ON u.id=o.user_id INNER JOIN car c ON o.car_id=c.id",
                DbType.mysql, List.of(f));
        report("join derived key",
                result.getKeys().equals(List.of("user_left_join_orders_inner_join_car.cnt")),
                List.of("user_left_join_orders_inner_join_car.cnt"), result.getKeys());
    }

    private static void unknownFallback() {
        Field f = field("cnt", null);
        ColumnSizeKeyResolver.ResolveResult result = ColumnSizeKeyResolver.resolve(
                "SELECT 1 AS cnt", DbType.mysql, List.of(f));
        report("unknown derived key", result.getKeys().equals(List.of("unknown.cnt")),
                List.of("unknown.cnt"), result.getKeys());
        report("unknown is partial", SizeCalculator.STATUS_PARTIAL.equals(result.getStatus()),
                SizeCalculator.STATUS_PARTIAL, result.getStatus());
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
