package org.jumpserver.chen.modules.sqlserver;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.driver.DriverClassLoader;

import java.lang.reflect.InvocationTargetException;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

@Slf4j
public class SQLServerConnectionManager extends BaseConnectionManager {

    private static final String jdbcUrlTemplate = "jdbc:sqlserver://${host}:${port};DatabaseName=${db};trustServerCertificate=true;";
    private String jdbcUrl;

    private String driverClassloaderName = "mssql-jdbc-12.2.0.jre11.jar";

    public SQLServerConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new SQLServerActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    }

    @Override
    public String getContextKey() {
        return "database";
    }

    @Override
    public Driver getDriver() {
        var driverClassLoaders = this.getDriverClassLoaders();

        if (this.getConnectInfo().getOptions().containsKey("version")) {
            var version = (String) this.getConnectInfo().getOptions().remove("version");
            if (version.equals("<2014")) {
                this.driverClassloaderName = "mssql-jdbc-6.4.0.jre9.jar";
            }
        }
        for (DriverClassLoader classLoader : driverClassLoaders) {
            if (!classLoader.getJarName().equals(this.driverClassloaderName)) {
                continue;
            }
            try {
                log.info("select driver jar: {}", this.driverClassloaderName);
                return (Driver) classLoader.loadClass(this.getDriverClassName()).getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException |
                     InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("driver not load");
    }

    @Override
    public void ping() throws SQLException {
        var url = this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate);
        this.ping(url);
        this.jdbcUrl = url;
    }


    private static final String SQL_GET_VERSION = "SELECT @@VERSION";

    @Override
    public String getVersion() throws SQLException {
        var result = this.sqlActuator.execute(SQL.of(SQL_GET_VERSION));
        return (String) result.getData().get(0).get(0);
    }

    @Override
    public String getJDBCUrl() {
        return this.jdbcUrl;
    }


    @Override
    public String getDisplayJDBCUrl() {
        return this.getConnectInfo().toDisplayJDBCUrl(jdbcUrlTemplate);
    }


    @Override
    public String getJDBCUrl(String database) {
        return this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate, database);
    }

    /**
     * task-07 slice C: when chen-web's RelationalAuthFlowHandler tagged
     * the connection with V1_ACCESS_TOKEN_REQUIRED, route the bearer
     * token through the mssql-jdbc {@code accessToken} property and
     * suppress {@code user} / {@code password}, which the driver
     * rejects in AccessToken mode.
     */
    @Override
    protected void applyAuthProps(Properties props) {
        if (!SqlServerAccessTokenSupport.isAccessTokenMode(this.getConnectInfo())) {
            super.applyAuthProps(props);
            return;
        }
        String token = SqlServerAccessTokenSupport.resolveAccessToken(this.getConnectInfo());
        if (StringUtils.isBlank(token)) {
            log.warn("[SqlServerEntra] AccessToken mode requested but token is blank; "
                    + "falling back to legacy user/password auth");
            super.applyAuthProps(props);
            return;
        }
        // mssql-jdbc rejects (user, accessToken) and (password, accessToken)
        // combinations. Strip both before injecting the bearer token.
        props.remove("user");
        props.remove("password");
        props.setProperty(SqlServerAccessTokenSupport.JDBC_PROP_ACCESS_TOKEN, token);
        log.info("[SqlServerEntra] Connecting with Entra AccessToken (token length={})", token.length());
    }

    /**
     * Pool-level mirror of {@link #applyAuthProps(Properties)}. Druid
     * forwards {@code connectProperties} into every physical connect,
     * so dropping the bearer token there is sufficient for both the
     * keep-alive validation query and user statements.
     */
    @Override
    protected void applyAuthOnDataSource(DruidDataSource ds, Properties properties) {
        if (!SqlServerAccessTokenSupport.isAccessTokenMode(this.getConnectInfo())) {
            super.applyAuthOnDataSource(ds, properties);
            return;
        }
        String token = SqlServerAccessTokenSupport.resolveAccessToken(this.getConnectInfo());
        if (StringUtils.isBlank(token)) {
            log.warn("[SqlServerEntra] AccessToken mode requested for pool but token is blank; "
                    + "falling back to legacy user/password auth");
            super.applyAuthOnDataSource(ds, properties);
            return;
        }
        // Do NOT call ds.setUsername / ds.setPassword: mssql-jdbc treats
        // any non-empty user as SQL Auth and rejects the AccessToken.
        properties.remove("user");
        properties.remove("password");
        properties.setProperty(SqlServerAccessTokenSupport.JDBC_PROP_ACCESS_TOKEN, token);
        ds.setConnectProperties(properties);
        log.info("[SqlServerEntra] Druid pool initialised with Entra AccessToken (token length={})", token.length());
    }
}
