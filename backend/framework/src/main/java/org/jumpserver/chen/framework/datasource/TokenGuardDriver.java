package org.jumpserver.chen.framework.datasource;

import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import java.sql.*;
import java.time.Instant;
import java.util.Properties;
import java.util.logging.Logger;

/** Core owns token acquisition. Expired credentials cannot create new physical connections. */
public final class TokenGuardDriver implements Driver {
    private final Driver driver;
    private final DBConnectInfo info;

    public TokenGuardDriver(Driver driver, DBConnectInfo info) {
        this.driver = driver;
        this.info = info;
    }

    public static void requireCurrent(DBConnectInfo info) {
        Object expires = info.getOptions().get("token_expires_at");
        if (expires == null) return; // Older Core versions do not supply expiry metadata.
        long expiry;
        try {
            expiry = Long.parseLong(expires.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid Entra token expiry");
        }
        if (expiry <= Instant.now().getEpochSecond()) {
            throw new IllegalStateException("Entra token expired; reconnect to obtain a fresh token");
        }
    }

    @Override
    public Connection connect(String url, Properties properties) throws SQLException {
        requireCurrent(info);
        return driver.connect(url, properties);
    }

    @Override public boolean acceptsURL(String url) throws SQLException { return driver.acceptsURL(url); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) throws SQLException { return driver.getPropertyInfo(url, properties); }
    @Override public int getMajorVersion() { return driver.getMajorVersion(); }
    @Override public int getMinorVersion() { return driver.getMinorVersion(); }
    @Override public boolean jdbcCompliant() { return driver.jdbcCompliant(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return driver.getParentLogger(); }
}
