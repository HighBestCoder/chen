package oracle;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

public class TestOracleDriver {

    public static void main(String[] args) throws MalformedURLException {
        String driverPath = System.getenv().getOrDefault("CHEN_ORACLE_DRIVER_JAR", "file:/path/to/ojdbc8.jar");
        String username = System.getenv().getOrDefault("CHEN_ORACLE_TEST_USER", "example-user");
        String password = System.getenv().getOrDefault("CHEN_ORACLE_TEST_PASSWORD", "example-password");
        String jdbcUrl = System.getenv().getOrDefault("CHEN_ORACLE_TEST_JDBC_URL", "jdbc:oracle:thin:@127.0.0.1:1521:xe");

        try {
            ClassLoader classLoader = new URLClassLoader(new URL[]{new URL(driverPath)});
            Driver driver = (Driver) classLoader.loadClass("oracle.jdbc.driver.OracleDriver").getDeclaredConstructor().newInstance();

            Properties properties = new Properties();

            properties.setProperty("user", username);
            properties.setProperty("password", password);

            var conn = driver.connect(jdbcUrl, properties);
            conn.close();


        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
