import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.utils.PageUtils;

public class TestSqlServerTop {
    public static void main(String[] args) {
        check("SELECT TOP 1 * FROM t", 1);
        check("SELECT TOP 5 * FROM t", 5);
        check("SELECT TOP 999 * FROM t", 999);
        check("SELECT * FROM t", -1);
        check("SELECT TOP (100) PERCENT * FROM t", Integer.MAX_VALUE);

        if (failures > 0) {
            System.err.println("FAIL: " + failures + " case(s) failed");
            System.exit(1);
        }
        System.out.println("OK: all cases passed");
    }

    static int failures = 0;

    static void check(String sql, int expected) {
        int actual = PageUtils.getLimit(sql, DbType.sqlserver);
        String mark = actual == expected ? "ok" : "FAIL";
        if (actual != expected) failures++;
        System.out.printf("%-4s  expect=%-12d actual=%-12d  %s%n", mark, expected, actual, sql);
    }
}
