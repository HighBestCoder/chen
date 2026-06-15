import org.jumpserver.chen.framework.utils.AuditTag;

import java.nio.charset.StandardCharsets;

/**
 * Probe for AuditTag.build — the DB-side audit identity tag.
 *
 * Verifies the security-critical invariants from the design review:
 * full session_id (join key) is never truncated, user is byte-truncated
 * without splitting multibyte chars, username is sanitized to a safe
 * whitelist, and edge inputs (null/empty) are handled.
 *
 * <pre>
 *   mvn -pl framework -am -DskipTests test-compile -q
 *   java -cp framework/target/classes:framework/target/test-classes TestAuditTag
 * </pre>
 */
public class TestAuditTag {

    private static int pass = 0;
    private static int fail = 0;
    private static final String UUID = "8a0681d1-c781-4c52-983e-dbfc7ac17dde";

    static void check(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  PASS  " + name + "  " + detail);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "  " + detail);
        }
    }

    static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    public static void main(String[] args) {
        System.out.println("=== AuditTag.build verification ===");

        // 1. basic format
        String t1 = AuditTag.build(UUID, "zhangsan");
        check("format js:{uuid}:{user}", t1.equals("js:" + UUID + ":zhangsan"), "=" + t1);
        check("starts with js:", t1.startsWith("js:"), "=" + t1);

        // 2. session_id always full, even with long username
        String t2 = AuditTag.build(UUID, "verylongusername_from_ldap_system_exceeding_budget");
        check("long user: UUID still full", t2.contains(UUID), "=" + t2);
        check("long user: tag <= 63 bytes", bytes(t2) <= 63, "bytes=" + bytes(t2));

        // 3. CJK username: byte-truncate, no broken char, UUID full
        String t3 = AuditTag.build(UUID, "中文用户名测试一二三四五六七八");
        check("cjk: UUID still full", t3.contains(UUID), "=" + t3);
        check("cjk: tag <= 63 bytes", bytes(t3) <= 63, "bytes=" + bytes(t3));
        check("cjk: valid utf-8 (no broken char)", t3.equals(new String(t3.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)), "ok");

        // 4. sanitize: dangerous chars become _
        String t4 = AuditTag.build(UUID, "ev,il:user\nname\u0000x");
        check("sanitize: no comma (would break MySQL)", !t4.substring(t4.lastIndexOf(":") + 1).contains(","), "user-part=" + t4.substring(t4.lastIndexOf(":") + 1));
        check("sanitize: no newline", !t4.contains("\n"), "ok");
        check("sanitize: no NUL", !t4.contains("\u0000"), "ok");
        // colon only the 2 structural ones
        long colons = t4.chars().filter(c -> c == ':').count();
        check("sanitize: only 2 structural colons", colons == 2, "colons=" + colons);

        // 5. allowed chars survive: . _ @ - alnum
        String t5 = AuditTag.build(UUID, "first.last_2@corp-x");
        check("allowed chars survive", t5.endsWith(":first.last_2@corp-x"), "=" + t5);

        // 6. empty username -> empty user segment, still valid
        String t6 = AuditTag.build(UUID, "");
        check("empty user -> js:{uuid}:", t6.equals("js:" + UUID + ":"), "=" + t6);

        // 7. null username treated as empty
        String t7 = AuditTag.build(UUID, null);
        check("null user -> js:{uuid}:", t7.equals("js:" + UUID + ":"), "=" + t7);

        // 8. null / empty session_id -> null (no injection without join key)
        check("null session -> null", AuditTag.build(null, "x") == null, "ok");
        check("empty session -> null", AuditTag.build("", "x") == null, "ok");

        System.out.println("\n=== RESULT: pass=" + pass + " fail=" + fail + " ===");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
