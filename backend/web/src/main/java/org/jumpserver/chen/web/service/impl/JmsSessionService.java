package org.jumpserver.chen.web.service.impl;

import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.impl.JMSSession;
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

        var tokenResp = this.getTokenResponse(token);
        var jmsSession = this.createJMSSession(tokenResp, remoteAddr);
        var datasource = this.createDatasource(tokenResp);
        var session = new JMSSession(jmsSession, datasource, remoteAddr, this.serviceBlockingStub, tokenResp);
        this.handleGateways(tokenResp, session, datasource);
        return session;
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

        var resp = this.serviceBlockingStub.createForward(req);

        dataSource.getConnectInfo().setProxyHost("127.0.0.1");
        dataSource.getConnectInfo().setProxyPort(resp.getPort());

        session.setGatewayId(resp.getId());
    }

    private void closeSession(Common.Session session) {
        var req = ServiceOuterClass
                .SessionFinishRequest
                .newBuilder()
                .setId(session.getId())
                .setDateEnd(Instant.now().getEpochSecond())
                .build();
        var resp = this.serviceBlockingStub.finishSession(req);

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
        var tokenResp = this.serviceBlockingStub.getTokenAuthInfo(tokenReq);
        if (tokenResp.getStatus().getOk()) {
            return tokenResp;
        } else {
            throw new RuntimeException(tokenResp.getStatus().getErr());
        }
    }

    private Datasource createDatasource(ServiceOuterClass.TokenResponse tokenResp) {
        DBConnectInfo dbConnectInfo = new DBConnectInfo();

        dbConnectInfo.setHost(tokenResp.getData().getAsset().getAddress());
        dbConnectInfo.setPort(tokenResp.getData().getAsset().getProtocols(0).getPort());
        dbConnectInfo.setDbType(tokenResp.getData().getAsset().getProtocols(0).getName().toLowerCase());
        dbConnectInfo.setUser(tokenResp.getData().getAccount().getUsername());
        dbConnectInfo.setPassword(tokenResp.getData().getAccount().getSecret());
        dbConnectInfo.setDb(tokenResp.getData().getAsset().getSpecific().getDbName());

        var platformSettings = tokenResp.getData().getPlatform().getProtocols(0).getSettingsMap();
        var authSpec = ConnectionAuthSpec.fromSettings(platformSettings);
        var authFlowRoute = AuthFlowDispatcher.resolve(authSpec);
        this.applyAuthFlow(dbConnectInfo, authSpec, authFlowRoute);

        if (platformSettings.containsKey("sysdba") && platformSettings.get("sysdba").equals("true")) {
            dbConnectInfo.getOptions().put("internal_logon", "sysdba");
        }

        if (platformSettings.containsKey("version")) {
            dbConnectInfo.getOptions().put("version", platformSettings.get("version"));
        }

        var asset = tokenResp.getData().getAsset();

        if (asset.getSpecific().getUseSsl()) {
            dbConnectInfo.getOptions().put("useSSL", true);
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

        switch (authFlowRoute) {
            case LEGACY -> this.handleLegacyAuthFlow(authSpec);
            case V1, V2 -> log.info(
                    "Auth flow {} selected for datasource creation: authType={}, authSource={}, dbType={}, decision={}",
                    authSpec.normalizedFlowVersion(), authSpec.authType(), authSpec.authSource(),
                    dbConnectInfo.getDbType(), relationalDecision.decision()
            );
            case UNKNOWN -> log.warn(
                    "Unknown auth_flow_version '{}', continue with base datasource flow",
                    authSpec.authFlowVersion()
            );
        }

        if (relationalDecision.requiresAccessToken()) {
            // SQL Server v1 path needs a true AccessToken hand-off via the
            // mssql-jdbc driver. Slice C will install that bridge; until
            // then we keep the legacy token-as-password behaviour and warn
            // loudly so the operator knows the protocol-level upgrade is
            // pending.
            log.warn(
                    "Relational auth decision '{}' is not yet wired (slice C pending) — "
                            + "falling back to token-as-password for dbType={}",
                    relationalDecision.decision(), dbConnectInfo.getDbType()
            );
        } else if (relationalDecision.isUnsupported()) {
            log.warn(
                    "Relational auth decision UNSUPPORTED: dbType={}, route={}, reason={} — "
                            + "datasource will use the inbound password verbatim",
                    relationalDecision.normalizedDbType(),
                    relationalDecision.routeName(),
                    relationalDecision.reason()
            );
        }
    }

    private void handleLegacyAuthFlow(ConnectionAuthSpec authSpec) {
        if (authSpec.isCorePocToken()) {
            log.info("Legacy auth flow detected with core_poc_token source, consume core token without re-fetch");
        }
    }

    private Common.Session createJMSSession(ServiceOuterClass.TokenResponse tokenResp, String remoteAddr) {
        var jmsSession = Common.Session.newBuilder()
                .setUserId(tokenResp.getData().getUser().getId())
                .setUser(String.format("%s(%s)", tokenResp.getData().getUser().getName(), tokenResp.getData().getUser().getUsername()))
                .setAccountId(tokenResp.getData().getAccount().getId())
                .setAccount(String.format("%s(%s)", tokenResp.getData().getAccount().getName(), tokenResp.getData().getAccount().getUsername()))
                .setOrgId(tokenResp.getData().getAsset().getOrgId())
                .setAssetId(tokenResp.getData().getAsset().getId())
                .setAsset(tokenResp.getData().getAsset().getName())
                .setLoginFrom(Common.Session.LoginFrom.WT)
                .setProtocol(tokenResp.getData().getAsset().getProtocols(0).getName())
                .setDateStart(System.currentTimeMillis() / 1000)
                .setRemoteAddr(remoteAddr)
                .build();

        var sessionResp = this.serviceBlockingStub.createSession(
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
