package org.jumpserver.chen.web.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.framework.utils.AuditTag;
import org.jumpserver.chen.web.auth.AuthFlowDispatcher;
import org.jumpserver.chen.web.auth.ConnectionAuthSpec;
import org.jumpserver.chen.web.auth.RelationalAuthFlowHandler;
import org.jumpserver.chen.web.service.SessionService;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;

@Service
@Slf4j
public class JmsSessionService implements SessionService {
    @GrpcClient("wisp")
    private ServiceGrpc.ServiceBlockingStub serviceBlockingStub;

    public Session createNewSession(String token, String remoteAddr) {

        if (token == null || token.startsWith("entra-session:"))
            throw new IllegalArgumentException("A new login token is required");
        var tokenResp = this.getTokenResponse(token);
        var jmsSession = this.createJMSSession(tokenResp, remoteAddr);
        Datasource datasource = null;
        JMSSession session = null;
        try {
            datasource = this.createDatasource(tokenResp, jmsSession.getId());
            session = new JMSSession(jmsSession, datasource, remoteAddr, this.serviceBlockingStub, tokenResp);
            installTokenProvider(tokenResp, session, datasource);
            this.handleGateways(tokenResp, session, datasource);
            return session;
        } catch (RuntimeException failure) {
            if (session != null) {
                try { session.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            } else {
                if (datasource != null) {
                    try { datasource.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                }
                try { closeSession(jmsSession); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private java.util.Map<String, String> authSettings(ServiceOuterClass.TokenResponse response) {
        String protocol = selectedProtocol(response).getName();
        return response.getData().getPlatform().getProtocolsList().stream()
                .filter(p -> p.getName().equalsIgnoreCase(protocol)).findFirst()
                .map(Common.PlatformProtocol::getSettingsMap).orElse(java.util.Map.of());
    }

    private void installTokenProvider(ServiceOuterClass.TokenResponse original, JMSSession session, Datasource datasource) {
        var settings = authSettings(original);
        String id = settings.getOrDefault("entra_session_id", "");
        if (id.isBlank()) return; // Old Core: retain explicit expiry/reconnect handling.
        if (!id.equals(session.getJmsSession().getId())) throw new IllegalStateException("Renewal session binding mismatch");
        var initial = new org.jumpserver.chen.framework.datasource.SessionTokenProvider.Credential(
                original.getData().getAccount().getSecret(), Long.parseLong(settings.get("token_expires_at")));
        var provider = new org.jumpserver.chen.framework.datasource.SessionTokenProvider(initial, () -> {
            var renewed = getTokenResponse("entra-session:" + id);
            var next = authSettings(renewed);
            var before = original.getData();
            var after = renewed.getData();
            if (!id.equals(next.get("entra_session_id"))
                    || !before.getUser().getId().equals(after.getUser().getId())
                    || !before.getAsset().equals(after.getAsset())
                    || !before.getAccount().getId().equals(after.getAccount().getId())
                    || !before.getAccount().getUsername().equals(after.getAccount().getUsername())
                    || !selectedProtocol(original).equals(selectedProtocol(renewed))
                    || after.getExpireInfo().getExpireAt() <= Instant.now().getEpochSecond())
                throw new IllegalStateException("Renewed credential target changed; reconnect");
            for (String key : java.util.List.of("auth_type", "auth_source", "auth_flow_version", "scope"))
                if (!java.util.Objects.equals(settings.get(key), next.get(key)))
                    throw new IllegalStateException("Renewed authentication configuration changed; reconnect");
            return new org.jumpserver.chen.framework.datasource.SessionTokenProvider.Credential(
                    after.getAccount().getSecret(), Long.parseLong(next.get("token_expires_at")));
        }, session::allowsCredentialRenewal);
        datasource.getConnectInfo().setTokenProvider(provider);
    }

    private void handleGateways(ServiceOuterClass.TokenResponse tokenResp, Session session, Datasource dataSource) {
        if (tokenResp.getData().getGatewaysCount() == 0) {
            return;
        }


        var req = ServiceOuterClass.ForwardRequest
                .newBuilder()
                .setHost(dataSource.getConnectInfo().getHost())
                .setPort(dataSource.getConnectInfo().getPort())
                .addAllGateways(tokenResp.getData().getGatewaysList())
                .build();

        var resp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).createForward(req);
        if (!resp.getStatus().getOk()) {
            throw new IllegalStateException("Gateway connection failed");
        }
        session.setGatewayId(resp.getId());
        if (resp.getPort() <= 0 || resp.getPort() > 65535) {
            throw new IllegalStateException("Gateway returned an invalid port");
        }

        dataSource.getConnectInfo().setProxyHost("127.0.0.1");
        dataSource.getConnectInfo().setProxyPort(resp.getPort());

    }

    private void closeSession(Common.Session session) {
        var req = ServiceOuterClass
                .SessionFinishRequest
                .newBuilder()
                .setId(session.getId())
                .setDateEnd(Instant.now().getEpochSecond())
                .build();
        var resp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).finishSession(req);

        if (!resp.getStatus().getOk()) {
            log.error("finish session failed: {}", resp.getStatus().getErr());
        }
    }

    private ServiceOuterClass.TokenResponse getTokenResponse(String token) {
        var tokenReq = ServiceOuterClass
                .TokenRequest
                .newBuilder()
                .setToken(token)
                .build();
        var tokenResp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).getTokenAuthInfo(tokenReq);
        if (tokenResp.getStatus().getOk()) {
            return tokenResp;
        } else {
            throw new RuntimeException(tokenResp.getStatus().getErr());
        }
    }

    private Common.Protocol selectedProtocol(ServiceOuterClass.TokenResponse response) {
        var protocols = response.getData().getAsset().getProtocolsList();
        var selected = response.getData().getPlatform().getProtocolsList().stream()
                .map(p -> p.getSettingsMap().getOrDefault("selected_protocol", ""))
                .filter(name -> !name.isBlank()).distinct().toList();
        if (selected.isEmpty() && protocols.size() == 1) return protocols.get(0);
        if (selected.size() != 1) throw new IllegalArgumentException("Missing or ambiguous selected database protocol");
        var matches = protocols.stream().filter(p -> p.getName().equalsIgnoreCase(selected.get(0))).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Selected database protocol is unavailable");
        return matches.get(0);
    }

    private Datasource createDatasource(ServiceOuterClass.TokenResponse tokenResp, String sessionId) {
        DBConnectInfo dbConnectInfo = new DBConnectInfo();

        dbConnectInfo.setHost(tokenResp.getData().getAsset().getAddress());
        dbConnectInfo.setPort(selectedProtocol(tokenResp).getPort());
        dbConnectInfo.setDbType(selectedProtocol(tokenResp).getName().toLowerCase(Locale.ROOT));
        dbConnectInfo.setUser(tokenResp.getData().getAccount().getUsername());
        dbConnectInfo.setPassword(tokenResp.getData().getAccount().getSecret());
        dbConnectInfo.setDb(tokenResp.getData().getAsset().getSpecific().getDbName());

        // DB-side audit identity: built from the JumpServer USER (not the DB
        // account) + the Core session id, so the target DB's session table /
        // audit log can be joined back to terminal_command.session.
        if (sessionId == null || sessionId.isEmpty()) {
            log.warn("audit tag skipped: empty session id from core; DB-side identity will be absent");
        } else {
            dbConnectInfo.setAuditTag(
                    AuditTag.build(sessionId, tokenResp.getData().getUser().getUsername()));
        }

        var platformSettings = tokenResp.getData().getPlatform().getProtocolsList().stream()
                .filter(p -> p.getName().equalsIgnoreCase(dbConnectInfo.getDbType()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected protocol has no platform settings"))
                .getSettingsMap();
        for (String key : java.util.List.of("pg_ssl_mode", "token_expires_at")) {
            if (platformSettings.containsKey(key)) dbConnectInfo.getOptions().put(key, platformSettings.get(key));
        }
        var authSpec = ConnectionAuthSpec.fromSettings(platformSettings);
        var authFlowRoute = AuthFlowDispatcher.resolve(authSpec, dbConnectInfo.getDbType());
        this.applyAuthFlow(dbConnectInfo, authSpec, authFlowRoute);

        if (platformSettings.containsKey("sysdba") && platformSettings.get("sysdba").equals("true")) {
            dbConnectInfo.getOptions().put("internal_logon", "sysdba");
        }

        if (platformSettings.containsKey("version")) {
            dbConnectInfo.getOptions().put("version", platformSettings.get("version"));
        }

        var asset = tokenResp.getData().getAsset();

        if (asset.getSpecific().getUseSsl() || platformSettings.containsKey("pg_ssl_mode")) {
            dbConnectInfo.getOptions().put("useSSL", asset.getSpecific().getUseSsl());
            dbConnectInfo.getOptions().put("verifyServerCertificate", !asset.getSpecific().getAllowInvalidCert());
            dbConnectInfo.getOptions().put("caCert", asset.getSpecific().getCaCert());
            dbConnectInfo.getOptions().put("clientCert", asset.getSpecific().getClientCert());
            dbConnectInfo.getOptions().put("clientKey", asset.getSpecific().getClientKey());
        }
        return DatasourceFactory.fromConnectInfo(dbConnectInfo);
    }

    private void applyAuthFlow(
            DBConnectInfo dbConnectInfo,
            ConnectionAuthSpec authSpec,
            AuthFlowDispatcher.Route authFlowRoute
    ) {
        dbConnectInfo.getOptions().put("authFlowVersion", authSpec.normalizedFlowVersion());
        dbConnectInfo.getOptions().put("authRoute", authFlowRoute.name().toLowerCase(Locale.ROOT));

        if (authSpec.hasAuthContext()) {
            if (!authSpec.authType().isEmpty()) {
                dbConnectInfo.getOptions().put("authType", authSpec.authType());
            }
            if (!authSpec.authSource().isEmpty()) {
                dbConnectInfo.getOptions().put("authSource", authSpec.authSource());
            }
            if (!authSpec.scope().isEmpty()) {
                dbConnectInfo.getOptions().put("scope", authSpec.scope());
            }
        }

        // task-07 slice A: per-protocol relational decision. The decision
        // is recorded in DBConnectInfo.options so downstream connection
        // managers (and the audit pipeline) can act on it without
        // re-parsing the auth context. Slice C consumes
        // V1_ACCESS_TOKEN_REQUIRED to drive the SQL Server AccessToken
        // path; for now we only log + tag.
        var relationalDecision = RelationalAuthFlowHandler.decide(
                authSpec, authFlowRoute, dbConnectInfo.getDbType()
        );
        dbConnectInfo.getOptions().put("relationalAuthDecision", relationalDecision.decision().name());
        dbConnectInfo.getOptions().put("relationalAuthReason", relationalDecision.reason());

        // One line per connection, enough to tell a mis-declared asset from
        // platform settings that never reached chen (hasAuthContext=false).
        log.info(
                "Auth flow resolved: dbType={} hasAuthContext={} entraToken={} declaredFlow='{}' "
                        + "route={} routeSource={} decision={}",
                dbConnectInfo.getDbType(), authSpec.hasAuthContext(), authSpec.hasEntraToken(),
                authSpec.authFlowVersion(), authFlowRoute.name().toLowerCase(Locale.ROOT),
                AuthFlowDispatcher.routeSource(authSpec), relationalDecision.decision()
        );

        switch (authFlowRoute) {
            case LEGACY -> this.handleLegacyAuthFlow(authSpec);
            case V1, V2 -> log.info(
                    "Auth flow {} selected for datasource creation: authType={}, authSource={}, dbType={}, decision={}",
                    authSpec.normalizedFlowVersion(), authSpec.authType(), authSpec.authSource(),
                    dbConnectInfo.getDbType(), relationalDecision.decision()
            );
            case UNKNOWN -> log.warn(
                    "Unknown auth_flow_version '{}', connection will be rejected",
                    authSpec.authFlowVersion()
            );
        }

        if (relationalDecision.requiresAccessToken()) {
            // SQL Server v1 path needs a true AccessToken hand-off via the
            // mssql-jdbc driver. The bridge is now installed by chen
            // SQLServerConnectionManager (task-07 slice C); it picks up
            // the decision from DBConnectInfo.options and routes the
            // bearer token through the accessToken connection property.
            log.info(
                    "Relational auth decision '{}' will be honoured by the SQL Server "
                            + "AccessToken bridge (task-07 slice C) for dbType={}",
                    relationalDecision.decision(), dbConnectInfo.getDbType()
            );
        } else if (relationalDecision.isUnsupported()) {
            throw new IllegalArgumentException("Unsupported authentication flow for "
                    + relationalDecision.normalizedDbType() + ": " + relationalDecision.routeName());
        }
    }

    private void handleLegacyAuthFlow(ConnectionAuthSpec authSpec) {
        if (authSpec.isCorePocToken()) {
            log.info("Legacy auth flow detected with core_poc_token source, consume core token without re-fetch");
        }
    }

    private Common.Session createJMSSession(ServiceOuterClass.TokenResponse tokenResp, String remoteAddr) {
        String renewalSessionId = authSettings(tokenResp).getOrDefault("entra_session_id", "");
        if (!renewalSessionId.isBlank()) java.util.UUID.fromString(renewalSessionId);
        var jmsSession = Common.Session.newBuilder()
                .setId(renewalSessionId)
                .setUserId(tokenResp.getData().getUser().getId())
                .setUser(String.format("%s(%s)", tokenResp.getData().getUser().getName(), tokenResp.getData().getUser().getUsername()))
                .setAccountId(tokenResp.getData().getAccount().getId())
                .setAccount(String.format("%s(%s)", tokenResp.getData().getAccount().getName(), tokenResp.getData().getAccount().getUsername()))
                .setOrgId(tokenResp.getData().getAsset().getOrgId())
                .setAssetId(tokenResp.getData().getAsset().getId())
                .setAsset(tokenResp.getData().getAsset().getName())
                .setLoginFrom(Common.Session.LoginFrom.WT)
                .setProtocol(selectedProtocol(tokenResp).getName())
                .setDateStart(System.currentTimeMillis() / 1000)
                .setRemoteAddr(remoteAddr)
                .build();

        var sessionResp = this.serviceBlockingStub.withDeadlineAfter(15, java.util.concurrent.TimeUnit.SECONDS).createSession(
                ServiceOuterClass.SessionCreateRequest
                        .newBuilder()
                        .setData(jmsSession)
                        .build()
        );
        if (!sessionResp.getStatus().getOk()) {
            throw new RuntimeException(sessionResp.getStatus().getErr());
        }
        jmsSession = sessionResp.getData();
        return jmsSession;
    }
}
