package org.jumpserver.chen.framework.datasource.error;

import java.sql.SQLException;

/**
 * Classifies a relational JDBC {@link SQLException} as a "write permission
 * denied" error, so the execution layer can surface the contract-mandated
 * message "无该操作权限" (GUI-SQL-006) instead of a raw driver string.
 *
 * <p>The Entra access token is opaque to chen and carries no in-database
 * privilege information, so a true pre-execution check is infeasible. Instead
 * the database enforces permissions and rejects the write; this classifier
 * recognizes that rejection by the engine-specific SQLState / vendor error
 * code. Authentication failures (a different error class) are intentionally
 * NOT matched.</p>
 */
public final class SqlPermissionErrorClassifier {

    private SqlPermissionErrorClassifier() {
    }

    /**
     * @return true if the exception (or any cause in its chain) is a relational
     * write-permission denial for PostgreSQL, MySQL/MariaDB or SQL Server.
     */
    public static boolean isPermissionDenied(SQLException e) {
        if (e == null) {
            return false;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se) {
                // PostgreSQL: SQLState 42501 = insufficient_privilege.
                if ("42501".equals(se.getSQLState())) {
                    return true;
                }
                int code = se.getErrorCode();
                // MySQL/MariaDB: 1142 table-level, 1143 column-level, 1044 db-level.
                if (code == 1142 || code == 1143 || code == 1044) {
                    return true;
                }
                // SQL Server: 229 object-level, 230 column-level, 262 DDL.
                if (code == 229 || code == 230 || code == 262) {
                    return true;
                }
            }
        }
        return false;
    }
}
