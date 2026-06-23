package org.jumpserver.chen.modules.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * Native Mongo connection manager. Implements ConnectionManager directly
 * (NOT BaseConnectionManager, which is Druid/JDBC-bound). It owns a
 * MongoClient; the JDBC-shaped methods (getConnection / getJDBCUrl /
 * getDriverClassName) are not meaningful for Mongo and are stubbed so the
 * relational framework surfaces don't break, while the Mongo path uses
 * getClient()/getDatabase() instead.
 */
public class MongoConnectionManager implements ConnectionManager {

    private final DBConnectInfo connectInfo;
    private final Datasource datasource;
    private final SQLActuator sqlActuatorStub = new MongoSqlActuatorStub();

    private MongoClient client;
    private String databaseContext;

    public MongoConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        this.connectInfo = connectInfo;
        this.datasource = datasource;
        this.databaseContext = connectInfo.getDb();
    }

    private synchronized MongoClient client() {
        if (this.client == null) {
            this.client = MongoClients.create(buildSettings());
        }
        return this.client;
    }

    private MongoClientSettings buildSettings() {
        boolean oidc = MongoEntraAuthSupport.isOidcMode(this.connectInfo);
        String host = this.connectInfo.getProxyHost() != null
                ? this.connectInfo.getProxyHost() : this.connectInfo.getHost();
        Integer port = this.connectInfo.getProxyPort() != null
                ? this.connectInfo.getProxyPort() : this.connectInfo.getPort();
        StringBuilder uri = new StringBuilder("mongodb://");
        // Entra OIDC authenticates against $external via the driver
        // callback; user / password must NOT appear in the URI, and the
        // SCRAM ?authSource=<db> must be omitted or the server falls back
        // to SCRAM and rejects the bearer token.
        if (!oidc) {
            String user = this.connectInfo.getUser();
            String password = this.connectInfo.getPassword();
            if (user != null && !user.isEmpty()) {
                uri.append(URLEncoder.encode(user, StandardCharsets.UTF_8));
                if (password != null && !password.isEmpty()) {
                    uri.append(':').append(URLEncoder.encode(password, StandardCharsets.UTF_8));
                }
                uri.append('@');
            }
        }
        uri.append(host).append(':').append(port).append('/');
        if (!oidc) {
            String authSource = this.connectInfo.getDb();
            if (authSource != null && !authSource.isEmpty()) {
                uri.append("?authSource=").append(URLEncoder.encode(authSource, StandardCharsets.UTF_8));
            }
        }
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri.toString()));

        var options = this.connectInfo.getOptions();
        if (oidc) {
            // chen only RELAYS the Core-minted token; it never contacts
            // Azure. The bearer token is carried in password.
            String token = MongoEntraAuthSupport.resolveToken(this.connectInfo);
            if (token == null || token.isBlank()) {
                throw new RuntimeException(
                        "Mongo Entra OIDC requested but no token present in connection info");
            }
            // withMechanismProperty(String, T) is generic; a bare lambda
            // will not infer OidcCallback, so use an explicitly typed var.
            MongoCredential.OidcCallback callback =
                    context -> new MongoCredential.OidcCallbackResult(token);
            MongoCredential credential = MongoCredential.createOidcCredential(null)
                    .withMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY, callback);
            builder.credential(credential);
        }

        if (MongoSslContextFactory.sslEnabled(options)) {
            var sslContext = MongoSslContextFactory.build(options);
            boolean verify = MongoSslContextFactory.verifyServerCertificate(options);
            builder.applyToSslSettings(ssl -> ssl
                    .enabled(true)
                    .invalidHostNameAllowed(!verify)
                    .context(sslContext));
        } else if (oidc) {
            // Cosmos vCore mandates TLS for the OIDC handshake; enforce it
            // with the JVM default trust store even if useSsl was unset.
            builder.applyToSslSettings(ssl -> ssl
                    .enabled(true)
                    .invalidHostNameAllowed(false));
        }
        // DB-side audit identity: Mongo surfaces appName in
        // currentOp / Cosmos vCoreMongoRequests.userAgent_s.
        String auditTag = this.connectInfo.getAuditTag();
        if (auditTag != null && !auditTag.isEmpty()) {
            builder.applicationName(auditTag);
        }
        return builder.build();
    }

    public MongoClient getClient() {
        return client();
    }

    public MongoDatabase getDatabase(String database) {
        return client().getDatabase(database);
    }

    public List<String> listDatabases() {
        List<String> names = new ArrayList<>();
        client().listDatabaseNames().forEach(names::add);
        return names;
    }

    public String getCurrentDatabaseName() {
        return this.databaseContext;
    }

    @Override
    public void ping() {
        Document result = client().getDatabase(adminDbName()).runCommand(new Document("ping", 1));
        if (result.getDouble("ok") == null || result.getDouble("ok") != 1.0) {
            throw new RuntimeException("MongoDB ping failed: " + result.toJson());
        }
    }

    @Override
    public String getVersion() {
        Document buildInfo = client().getDatabase(adminDbName())
                .runCommand(new Document("buildInfo", 1));
        Object version = buildInfo.get("version");
        return version == null ? "" : version.toString();
    }

    private String adminDbName() {
        String db = this.connectInfo.getDb();
        return (db == null || db.isEmpty()) ? "admin" : db;
    }

    @Override
    public void setDatabaseContext(String database) {
        if (database != null && !database.isEmpty()) {
            this.databaseContext = database;
        }
    }

    @Override
    public Datasource getDatasource() {
        return this.datasource;
    }

    @Override
    public DBConnectInfo getConnectInfo() {
        return this.connectInfo;
    }

    @Override
    public SQLActuator getSqlActuator() {
        return this.sqlActuatorStub;
    }

    @Override
    public String getContextKey() {
        return "database";
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.close();
            this.client = null;
        }
    }

    // ---- JDBC-shaped surface: not applicable to Mongo ----

    @Override
    public String getDriverClassName() {
        return "";
    }

    @Override
    public String getJDBCUrl() {
        return getDisplayJDBCUrl();
    }

    @Override
    public String getDisplayJDBCUrl() {
        return "mongodb://" + this.connectInfo.getHost() + ":" + this.connectInfo.getPort();
    }

    @Override
    public String getJDBCUrl(String database) {
        return getDisplayJDBCUrl();
    }

    @Override
    public Connection getConnection() {
        throw new UnsupportedOperationException("MongoDB has no JDBC Connection; use getDatabase()");
    }

    @Override
    public Connection getPhysicalConnection() {
        throw new UnsupportedOperationException("MongoDB has no JDBC Connection; use getClient()");
    }
}
