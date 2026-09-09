package org.jumpserver.chen.framework.datasource.base;

import com.alibaba.druid.pool.DruidDataSource;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.driver.DriverClassLoader;
import org.jumpserver.chen.framework.driver.DriverManager;
import org.jumpserver.chen.framework.ssl.JKSGenerator;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public abstract class BaseConnectionManager implements ConnectionManager {

    private final DBConnectInfo connectInfo;
    @Getter
    private final Datasource datasource;
    private final Map<String, DruidDataSource> dataSourceMap = new HashMap<>();
    private boolean closed;
    private final List<AutoCloseable> tlsResources = new ArrayList<>();
    private final ThreadLocal<String> currentDatabase = new ThreadLocal<>();

    protected SQLActuator sqlActuator;

    public BaseConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        this.datasource = datasource;
        this.connectInfo = connectInfo;
    }

    public synchronized void ping(String jdbcUrl, Properties props) throws SQLException {
        ensureOpen();
        configureConnectionProperties(props);
        this.applyAuthProps(props);
        this.setSSLProps(props);
        this.applyAuditProps(props);
        this.getDriver().connect(jdbcUrl, props).close();
    }

    public void ping(String jdbcUrl) throws SQLException {
        Properties props = new Properties();
        this.ping(jdbcUrl, props);
    }

    /**
     * task-07 slice C hook: subclasses may override to install an
     * AccessToken (or other non-password credential) on the JDBC
     * connection properties used for {@code Driver.connect()}.
     *
     * <p>Default behaviour preserves the legacy username + password
     * semantics so existing connectors are bit-compatible.</p>
     */
    protected void applyAuthProps(Properties props) {
        org.jumpserver.chen.framework.datasource.TokenGuardDriver.requireCurrent(connectInfo);
        props.setProperty("user", this.getConnectInfo().getUser());
        if (StringUtils.isNotBlank(this.getConnectInfo().getPassword())) {
            props.setProperty("password", this.getConnectInfo().getPassword());
        }
    }

    protected void configureConnectionProperties(Properties props) {
        org.jumpserver.chen.framework.datasource.TokenGuardDriver.requireCurrent(connectInfo);
        var internal = java.util.Set.of("caCert", "clientCert", "clientKey", "useSSL", "verifyServerCertificate", "authType", "authSource", "authFlowVersion", "authRoute", "scope", "relationalAuthDecision", "relationalAuthReason", "pg_ssl_mode", "token_expires_at");
        connectInfo.getOptions().forEach((key,value) -> { if (value != null && !internal.contains(key)) props.setProperty(key, value.toString()); });
        String type = connectInfo.getDbType() == null ? "" : connectInfo.getDbType();
        switch (type) {
            case "postgresql" -> { props.setProperty("connectTimeout", "5");props.setProperty("loginTimeout", "10");props.setProperty("socketTimeout", "7200"); }
            case "mysql", "mariadb" -> { props.setProperty("connectTimeout", "5000");props.setProperty("socketTimeout", "7200000"); }
            case "sqlserver" -> { props.setProperty("loginTimeout", "10");props.setProperty("socketTimeout", "7200000"); }
        }
        if (type.equals("mysql")) props.setProperty("socketFactory", "org.jumpserver.chen.modules.mysql.MysqlGatewaySocketFactory");
        if (connectInfo.getProxyHost() != null && (type.equals("mysql") || type.equals("postgresql"))) {
            String gateway = connectInfo.getProxyHost() + ":" + connectInfo.getProxyPort();
            props.setProperty("chenGateway", gateway);
            if (type.equals("postgresql")) props.setProperty("socketFactory", "org.jumpserver.chen.framework.ssl.GatewaySocketFactory");
            else { props.setProperty("socketFactory", "org.jumpserver.chen.modules.mysql.MysqlGatewaySocketFactory"); }
        }
        boolean cert = StringUtils.isNotBlank((String)connectInfo.getOptions().get("clientCert"));
        boolean key = StringUtils.isNotBlank((String)connectInfo.getOptions().get("clientKey"));
        if (cert != key) throw new IllegalArgumentException("Client certificate and key must be supplied together");
    }

    protected synchronized JKSGenerator newJksGenerator() {
        if (closed) throw new IllegalStateException("Connection manager is closed");
        var generator = new JKSGenerator();
        tlsResources.add(generator);
        return generator;
    }

    protected synchronized Path createTlsTempFile(String prefix, String suffix) throws IOException {
        if (closed) throw new IllegalStateException("Connection manager is closed");
        Path path = Files.createTempFile(prefix, suffix);
        tlsResources.add(() -> Files.deleteIfExists(path));
        return path;
    }

    protected void setSSLProps(Properties props) {
        if (this.getConnectInfo().getOptions().get("useSSL") != null
                && (boolean) this.getConnectInfo().getOptions().get("useSSL")) {
            props.setProperty("useSSL", "true");
            props.setProperty("requireSSL", "true");
            var jksGenerator = newJksGenerator();
            if (!Boolean.FALSE.equals(this.getConnectInfo().getOptions().get("verifyServerCertificate"))) {
                props.setProperty("verifyServerCertificate", "true");
                if (StringUtils.isNotBlank((String) this.getConnectInfo().getOptions().get("caCert"))) {
                jksGenerator.setCaCert((String) this.getConnectInfo().getOptions().get("caCert"));

                var caCertPath = jksGenerator.generateCaJKS();
                props.setProperty("trustCertificateKeyStoreUrl", "file:" + caCertPath);
                props.setProperty("trustCertificateKeyStorePassword", JKSGenerator.JSK_PASS);
                }

            }
            if (StringUtils.isNotBlank((String) this.getConnectInfo().getOptions().get("clientCert"))) {
                jksGenerator.setClientCert((String) this.getConnectInfo().getOptions().get("clientCert"));
                jksGenerator.setClientKey((String) this.getConnectInfo().getOptions().get("clientKey"));
                var clientCertPath = jksGenerator.generateClientJKS();
                props.setProperty("clientCertificateKeyStoreUrl", "file:" + clientCertPath);
                props.setProperty("clientCertificateKeyStorePassword", JKSGenerator.JSK_PASS);
                props.setProperty("clientKeyPassword", JKSGenerator.JSK_PASS);

            }
        } else {
            props.setProperty("useSSL", "false");
        }
    }

    /**
     * Inject the DB-side audit identity (jumpserver user + session id) into
     * the per-engine JDBC property the target database surfaces in its
     * session table / audit log. Applied AFTER options so it cannot be
     * overridden, and on BOTH the pooled datasource and the direct ping path
     * so no connection reaches the DB without an identity.
     */
    protected void applyAuditProps(Properties props) {
        String tag = this.connectInfo.getAuditTag();
        if (StringUtils.isBlank(tag)) {
            return;
        }
        switch (this.connectInfo.getDbType()) {
            case "postgresql" -> props.setProperty("ApplicationName", tag);
            case "sqlserver" -> props.setProperty("applicationName", tag);
            case "mysql", "mariadb" -> props.setProperty("connectionAttributes", "program_name:" + tag);
            default -> {
            }
        }
    }


    public List<DriverClassLoader> getDriverClassLoaders() {
        var driverClassLoaders = DriverManager.getDrivers(this.connectInfo.getDbType());
        if (driverClassLoaders == null) {
            throw new RuntimeException("driver not found");
        }
        return driverClassLoaders;
    }

    public Driver getDriver() {
        var driverClassLoaders = this.getDriverClassLoaders();
        for (ClassLoader classLoader : driverClassLoaders) {
            try {
                return (Driver) classLoader.loadClass(this.getDriverClassName()).getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException |
                     InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("driver not load");
    }

    @Override
    public void setDatabaseContext(String database) {
        this.currentDatabase.set(database);
    }

    @Override
    public String getContextKey() {
        return "schema";
    }

    @Override
    public DBConnectInfo getConnectInfo() {
        return this.connectInfo;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.getOrInitDataSource(this.getCurrentDatabaseName()).getConnection();
    }

    @Override
    public synchronized Connection getPhysicalConnection() throws SQLException {
        return this.getOrInitDataSource(this.getCurrentDatabaseName())
                .createPhysicalConnection()
                .getPhysicalConnection();
    }

    private String getCurrentDatabaseName() {
        return this.currentDatabase.get() == null ? this.connectInfo.getDb() : this.currentDatabase.get();
    }

    @Override
    public SQLActuator getSqlActuator() {
        return this.sqlActuator;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (DruidDataSource dataSource : dataSourceMap.values()) {
            try { dataSource.close(); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        dataSourceMap.clear();
        for (AutoCloseable resource : tlsResources) {
            try { resource.close(); }
            catch (Exception e) {
                if (failure == null) failure = new IllegalStateException("TLS resource cleanup failed", e);
                else failure.addSuppressed(e);
            }
        }
        tlsResources.clear();
        currentDatabase.remove();
        if (failure != null) throw failure;
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("Connection manager is closed");
    }

    public synchronized DruidDataSource getOrInitDataSource(String database) throws SQLException {
        ensureOpen();
        if (StringUtils.isEmpty(database)) {
            database = this.connectInfo.getDb();
        }
        if (this.dataSourceMap.containsKey(database)) {
            return this.dataSourceMap.get(database);
        }
        DruidDataSource ds = new DruidDataSource();

        try {
            var properties = new Properties();
            configureConnectionProperties(properties);
            this.setSSLProps(properties);

            this.applyAuditProps(properties);

            ds.setConnectProperties(properties);

            ds.setDriver(new org.jumpserver.chen.framework.datasource.TokenGuardDriver(this.getDriver(), connectInfo));
            ds.setUrl(this.getJDBCUrl(database));
            this.applyAuthOnDataSource(ds, properties);

            ds.setMaxWait(15000);
            ds.setKeepAlive(true);
            ds.setFailFast(true);
            ds.setKillWhenSocketReadTimeout(false);
            ds.setTestWhileIdle(true);
            ds.setSocketTimeout(1000 * 60 * 120);
            ds.init();
            ds.getConnection().close();
            this.dataSourceMap.put(database, ds);
            return ds;
        } catch (SQLException | RuntimeException e) {
            try { ds.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /**
     * task-07 slice C hook: subclasses may override to install an
     * AccessToken (or other non-password credential) on the underlying
     * Druid pool. Implementations that do not need the password can
     * skip {@link DruidDataSource#setPassword(String)} entirely and
     * push the credential through {@code properties} (which is already
     * wired into {@link DruidDataSource#setConnectProperties(Properties)}).
     *
     * <p>Default behaviour preserves the legacy username + password
     * semantics so existing connectors are bit-compatible.</p>
     */
    protected void applyAuthOnDataSource(DruidDataSource ds, Properties properties) {
        ds.setUsername(this.getConnectInfo().getUser());
        if (StringUtils.isNotBlank(this.getConnectInfo().getPassword())) {
            ds.setPassword(this.getConnectInfo().getPassword());
        }
    }
}
