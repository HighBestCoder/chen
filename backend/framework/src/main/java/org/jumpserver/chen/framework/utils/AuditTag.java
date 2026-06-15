package org.jumpserver.chen.framework.utils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Builds the DB-side audit identity tag injected into target-database
 * connection properties (PG application_name / SQLServer applicationName /
 * MySQL connectionAttributes program_name / Mongo appName).
 *
 * Format: {@code js:{sessionId}:{user}} — sessionId (the Core terminal
 * session UUID, also terminal_command.session) is FIRST and never
 * truncated, so the DB-side tag can always be joined back to the
 * application-layer command audit. user is best-effort display and may be
 * truncated.
 *
 * Security contract:
 * - sessionId first + full: it is the join key; user is the part that gets
 *   cut. PostgreSQL caps application_name at 63 bytes (NAMEDATALEN-1), and
 *   "js:" + 36-byte UUID + ":" = 40 bytes, leaving 23 bytes for user.
 * - username is attacker-controllable (LDAP/SSO sync, no charset
 *   constraint), so it is whitelisted to [A-Za-z0-9._@-]; everything else
 *   (control chars, CR/LF, NUL, commas that break MySQL connectionAttributes,
 *   colons that confuse parsing) becomes '_'. This is audit-injection
 *   defense, not SQL injection (values go through Properties, not string
 *   concatenation).
 * - truncation is by UTF-8 BYTES (not chars) and never splits a multibyte
 *   character, so a CJK username cannot blow the byte budget or produce
 *   invalid encoding.
 */
public final class AuditTag {

    private static final int USER_MAX_BYTES = 23;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._@\\-]");

    private AuditTag() {
    }

    public static String build(String sessionId, String username) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        String user = username == null ? "" : username;
        user = UNSAFE.matcher(user).replaceAll("_");
        user = truncateUtf8(user, USER_MAX_BYTES);
        return "js:" + sessionId + ":" + user;
    }

    private static String truncateUtf8(String s, int maxBytes) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return s;
        }
        int len = maxBytes;
        // back off to a UTF-8 character boundary (continuation bytes are 10xxxxxx)
        while (len > 0 && (bytes[len] & 0xC0) == 0x80) {
            len--;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
