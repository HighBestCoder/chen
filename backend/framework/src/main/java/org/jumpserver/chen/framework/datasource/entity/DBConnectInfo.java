package org.jumpserver.chen.framework.datasource.entity;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class DBConnectInfo {
    private String dbType;
    private String host;
    private Integer port;
    private String user;
    private String password;
    private String db;
    private String proxyHost;
    private Integer proxyPort;
    private String auditTag;

    private Map<String, Object> options = new HashMap<>();

    public String toDisplayJDBCUrl(String template) { return buildUrl(template, host, port, db); }
    public String toJDBCUrl(String template) { return toJDBCUrl(template, db); }
    public String toJDBCUrl(String template, String database) {
        boolean logicalHost = template.startsWith("jdbc:postgresql:") || template.startsWith("jdbc:mysql:");
        return buildUrl(template, logicalHost || proxyHost == null ? host : proxyHost,
                logicalHost || proxyPort == null ? port : proxyPort, database);
    }
    private String buildUrl(String template, String address, Integer targetPort, String database) {
        if (address == null || address.isBlank() || address.matches(".*[/?#;@\\s].*") || targetPort == null || targetPort < 1 || targetPort > 65535)
            throw new IllegalArgumentException("Invalid database host or port");
        if (address.contains(":" ) && !address.startsWith("[")) address = "[" + address + "]";
        String value = database == null ? "" : database;
        if (template.startsWith("jdbc:postgresql:") || template.startsWith("jdbc:mysql:") || template.startsWith("jdbc:mariadb:"))
            value = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        else if (template.startsWith("jdbc:sqlserver:")) value = "{" + value.replace("}", "}}") + "}";
        return template.replace("${host}", address).replace("${port}", targetPort.toString()).replace("${db}", value);
    }
}
