package org.jumpserver.chen.modules.postgresql;

import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.base.BaseConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQL;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;

public class PostgresqlConnectionManager extends BaseConnectionManager {

    private static final String jdbcUrlTemplate = "jdbc:postgresql://${host}:${port}/${db}?useUnicode=true&characterEncoding=UTF-8";
    private String jdbcUrl;

    public PostgresqlConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        super(connectInfo, datasource);
        this.sqlActuator = new PostgresqlActuator(this);
    }

    @Override
    public String getDriverClassName() {
        return "org.postgresql.Driver";
    }

    @Override
    protected void setSSLProps(Properties props) {
        var options = this.getConnectInfo().getOptions();

        // pgJDBC does not understand these MySQL-style props; drop them.
        props.remove("useSSL");
        props.remove("requireSSL");
        props.remove("verifyServerCertificate");
        props.remove("trustCertificateKeyStoreUrl");
        props.remove("trustCertificateKeyStorePassword");

        boolean useSSL = Boolean.TRUE.equals(options.get("useSSL"));
        boolean verify = !Boolean.FALSE.equals(options.get("verifyServerCertificate"));
        String mode = (String) options.getOrDefault("pg_ssl_mode", "prefer");
        if (useSSL) mode = verify ? "verify-full" : "require";
        if (!java.util.Set.of("disable","allow","prefer","require","verify-ca","verify-full").contains(mode))
            throw new IllegalArgumentException("Invalid PostgreSQL SSL mode");
        props.setProperty("sslmode", mode);
        if (mode.equals("verify-ca") || mode.equals("verify-full")) {
            props.setProperty("sslfactory", "org.jumpserver.chen.framework.ssl.PemSslSocketFactory");
            String caCert = (String) options.get("caCert");
            if (StringUtils.isNotBlank(caCert)) props.setProperty("sslrootcert", writePemTempFile(caCert).toString());
        }
        if (StringUtils.isNotBlank((String) options.get("clientCert"))) {
            var generator = newJksGenerator();
            generator.setClientCert((String) options.get("clientCert")); generator.setClientKey((String) options.get("clientKey"));
            props.setProperty("chenClientKeyStore", generator.generateClientJKS().toString());
            props.setProperty("sslfactory", "org.jumpserver.chen.framework.ssl.PemSslSocketFactory");
            if (!mode.startsWith("verify-")) props.setProperty("chenTrustAll", "true");
        }
    }

    private Path writePemTempFile(String pem) {
        try {
            Path path = createTlsTempFile("pg-ca-", ".crt");
            Files.write(path, pem.getBytes(StandardCharsets.UTF_8));
            return path;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PostgreSQL CA cert: " + e.getMessage(), e);
        }
    }

    @Override
    public void ping() throws SQLException {
        var url = this.getConnectInfo().toJDBCUrl(jdbcUrlTemplate);
        this.ping(url);
        this.jdbcUrl = url;
    }

    private static final String SQL_GET_VERSION = "SELECT version()";

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
}
