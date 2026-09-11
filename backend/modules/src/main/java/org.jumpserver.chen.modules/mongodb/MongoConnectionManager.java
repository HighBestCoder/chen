package org.jumpserver.chen.modules.mongodb;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class MongoConnectionManager implements ConnectionManager {

    private final DBConnectInfo connectInfo;
    private final Datasource datasource;
    private final SQLActuator sqlActuatorStub;

    private MongoClient client;
    private boolean closed;
    private String databaseContext;

    public MongoConnectionManager(DBConnectInfo connectInfo, Datasource datasource) {
        this.connectInfo = connectInfo;
        this.datasource = datasource;
        this.databaseContext = connectInfo.getDb();
        this.sqlActuatorStub = new MongoSqlActuatorStub(this);
    }

    private synchronized MongoClient client() {
        if (closed) throw new IllegalStateException("Mongo connection manager is closed");
        if (this.client == null) {
            this.client = MongoClients.create(buildSettings());
        }
        return this.client;
    }

    private MongoClientSettings buildSettings() {
        boolean oidc = MongoEntraAuthSupport.isOidcMode(this.connectInfo);
        String host = this.connectInfo.getHost();
        boolean documentDb = MongoAzureEndpoint.isDocumentDb(host);
        boolean azureMongo = MongoAzureEndpoint.isAzureMongo(host);
        if (documentDb && this.connectInfo.getProxyHost() != null) {
            throw new IllegalArgumentException("DocumentDB SRV requires direct or private-network access; a fixed-port gateway cannot route SRV targets");
        }
        Integer port = this.connectInfo.getProxyPort() != null ? this.connectInfo.getProxyPort() : this.connectInfo.getPort();
        if (host == null || host.isBlank() || port == null || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid Mongo host or port");
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyToSocketSettings(socket -> socket.connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(120, java.util.concurrent.TimeUnit.MINUTES))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(10, java.util.concurrent.TimeUnit.SECONDS));
        if (documentDb) {
            builder.applyToClusterSettings(cluster -> cluster.srvHost(host));
        } else {
            builder.applyToClusterSettings(cluster -> cluster.hosts(java.util.List.of(new com.mongodb.ServerAddress(host, port))));
        }
        if (azureMongo) {
            builder.retryWrites(false);
            builder.applyToConnectionPoolSettings(pool -> pool.maxConnectionIdleTime(120, java.util.concurrent.TimeUnit.SECONDS));
        }
        if (this.connectInfo.getProxyHost() != null) {
            String proxy = this.connectInfo.getProxyHost();
            builder.inetAddressResolver(name -> java.util.List.of(java.net.InetAddress.getByAddress(name, java.net.InetAddress.getByName(proxy).getAddress())));
            builder.applyToClusterSettings(cluster -> cluster.mode(com.mongodb.connection.ClusterConnectionMode.SINGLE));
        }
        if (!oidc && this.connectInfo.getUser() != null && !this.connectInfo.getUser().isEmpty()) {
            String authDb = this.connectInfo.getDb();
            if (authDb == null || authDb.isEmpty()) authDb = "admin";
            builder.credential(MongoCredential.createCredential(this.connectInfo.getUser(), authDb,
                    (this.connectInfo.getPassword() == null ? "" : this.connectInfo.getPassword()).toCharArray()));
        }

        var options = this.connectInfo.getOptions();
        if (oidc) {
            org.jumpserver.chen.framework.datasource.TokenGuardDriver.requireCurrent(this.connectInfo);
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
                    context -> {
                        if (this.connectInfo.getTokenProvider() != null) {
                            var fresh = this.connectInfo.getTokenProvider().current();
                            long remaining = fresh.expiresAt() - java.time.Instant.now().getEpochSecond();
                            return new MongoCredential.OidcCallbackResult(fresh.token(), java.time.Duration.ofSeconds(Math.max(1, remaining - 60)));
                        }
                        org.jumpserver.chen.framework.datasource.TokenGuardDriver.requireCurrent(this.connectInfo);
                        Object expiry = options.get("token_expires_at");
                        if (expiry == null) return new MongoCredential.OidcCallbackResult(token);
                        long remaining = Long.parseLong(expiry.toString()) - java.time.Instant.now().getEpochSecond();
                        return new MongoCredential.OidcCallbackResult(token, java.time.Duration.ofSeconds(Math.max(1, remaining)));
                    };
            MongoCredential credential = MongoCredential.createOidcCredential(null)
                    .withMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY, callback);
            builder.credential(credential);
            // Length only, never the token body — mirrors the SQL Server
            // bridge. Without this the Mongo Entra path is silent, and a
            // failure cannot be told apart from the handshake never starting.
            log.info("[MongoEntra] Connecting with Entra OIDC bearer token (length={})", token.length());
        }

        if (MongoSslContextFactory.sslEnabled(options)) {
            var sslContext = MongoSslContextFactory.build(options);
            boolean verify = MongoSslContextFactory.verifyServerCertificate(options);
            builder.applyToSslSettings(ssl -> ssl
                    .enabled(true)
                    .invalidHostNameAllowed(!verify)
                    .context(sslContext));
        } else if (oidc || azureMongo) {
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
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (this.client != null) {
            try { this.client.close(); } finally { this.client = null; }
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
