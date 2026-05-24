package sqlserver;

import com.alibaba.druid.pool.DruidDataSource;

import java.sql.SQLException;

public class TestSQLServerConnect {

    public static void main(String[] args) {
        String jdbcUrl = System.getenv().getOrDefault("CHEN_DB2_TEST_JDBC_URL", "jdbc:db2://localhost:50000/test");
        String username = System.getenv().getOrDefault("CHEN_DB2_TEST_USER", "example-user");
        String password = System.getenv().getOrDefault("CHEN_DB2_TEST_PASSWORD", "example-password");

        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.ibm.db2.jcc.DB2Driver");
        ds.setTestWhileIdle(true);
        ds.setValidationQuery("SELECT SERVICE_LEVEL FROM SYSIBMADM.ENV_INST_INFO");
        try {
            ds.init();
            var conn = ds.getConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
