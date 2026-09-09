import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.modules.postgresql.PostgresqlDatasource;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.web.service.impl.JmsSessionService;
import org.jumpserver.chen.web.auth.*;
import org.jumpserver.chen.wisp.*;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** End-to-end metadata mapping plus expiry boundaries without Core/Azure credentials. */
public class TestConnectionConfiguration {
    static void check(boolean value, String message) {if(!value)throw new AssertionError(message);}
    interface Action {void run() throws Exception;}
    static void rejected(Action action) throws Exception {
        try{action.run();throw new AssertionError("invalid configuration accepted");}
        catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof IllegalStateException))throw e;}
        catch(IllegalArgumentException | IllegalStateException expected){}
    }
    static Object call(Object object,String method,Class<?>[] types,Object... args) throws Exception {
        var m=object.getClass().getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(object,args);
    }
    static ServiceOuterClass.TokenResponse response(String selected) {
        var asset=Common.Asset.newBuilder().setAddress("db.fixture")
                .addProtocols(Common.Protocol.newBuilder().setName("ssh").setPort(22))
                .addProtocols(Common.Protocol.newBuilder().setName("postgresql").setPort(5433))
                .setSpecific(Common.Asset.Specific.newBuilder().setDbName("fixture").setUseSsl(false).setCaCert("fixture-ca").setClientCert("fixture-cert").setClientKey("fixture-key"));
        var settings=new HashMap<>(Map.of("auth_type","password","auth_source","direct_password","auth_flow_version","v1","pg_ssl_mode","verify-full"));
        if(selected!=null)settings.put("selected_protocol",selected);
        var data=Common.TokenAuthInfo.newBuilder().setAsset(asset)
                .setAccount(Common.Account.newBuilder().setUsername("fixture").setSecret("fixture-password"))
                .setPlatform(Common.Platform.newBuilder()
                    .addProtocols(Common.PlatformProtocol.newBuilder().setName("ssh"))
                    .addProtocols(Common.PlatformProtocol.newBuilder().setName("postgresql").putAllSettings(settings)));
        return ServiceOuterClass.TokenResponse.newBuilder().setData(data).build();
    }
    static void metadata() throws Exception {
        var field=DatasourceFactory.class.getDeclaredField("DATASOURCE_MAP");field.setAccessible(true);
        @SuppressWarnings("unchecked") var registry=(Map<String,Class<? extends Datasource>>)field.get(null);
        var previous=registry.put("postgresql",PostgresqlDatasource.class);
        try {
            var service=new JmsSessionService();
            var ds=(Datasource)call(service,"createDatasource",new Class[]{ServiceOuterClass.TokenResponse.class,String.class},response("postgresql"),"fixture-session");
            try {
                var info=ds.getConnectInfo();
                check(info.getPort()==5433 && info.getDbType().equals("postgresql"),"selected protocol/port lost");
                check(info.getOptions().get("relationalAuthDecision").equals("LEGACY_PASSWORD"),"password account inherited Entra routing");
                check(info.getOptions().get("pg_ssl_mode").equals("verify-full"),"PG mode lost");
                check(info.getOptions().get("clientKey").equals("fixture-key"),"legacy PG mode lost TLS material");
            }finally{ds.close();}
            for(String selected:Arrays.asList(null,"mysql")) rejected(()->call(service,"createDatasource",new Class[]{ServiceOuterClass.TokenResponse.class,String.class},response(selected),"fixture"));
            for(String type:List.of("postgresql","mysql","sqlserver","mongodb")) {
                var spec=ConnectionAuthSpec.fromSettings(Map.of("auth_type","password","auth_source","direct_password","auth_flow_version",type.equals("mongodb")?"v2":"v1"));
                check(RelationalAuthFlowHandler.decide(spec,AuthFlowDispatcher.resolve(spec,type),type).decision()==RelationalAuthFlowHandler.Outcome.LEGACY_PASSWORD,"mixed account routing: "+type);
            }
        }finally{if(previous==null)registry.remove("postgresql");else registry.put("postgresql",previous);}
    }
    static void expiry() throws Exception {
        var info=new DBConnectInfo();var calls=new int[1];
        var driver=(Driver)Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{Driver.class},(p,m,a)->{if(m.getName().equals("connect")){calls[0]++;return null;}return null;});
        var guard=new TokenGuardDriver(driver,info);
        guard.connect("fixture",new Properties());
        info.getOptions().put("token_expires_at",java.time.Instant.now().getEpochSecond()+3600);guard.connect("fixture",new Properties());
        info.getOptions().put("token_expires_at",1);rejected(()->guard.connect("fixture",new Properties()));
        info.getOptions().put("token_expires_at","bad");rejected(()->guard.connect("fixture",new Properties()));
        check(calls[0]==2,"expired token reached driver");
        info.setHost("fixture");info.setPort(27017);info.setDb("fixture");info.setPassword("fixture-token");
        info.getOptions().put("relationalAuthDecision","V2_OIDC_TOKEN_REQUIRED");
        var mongo=new MongoConnectionManager(info,null);
        try {
            rejected(()->call(mongo,"buildSettings",new Class[]{}));
            info.getOptions().put("token_expires_at",java.time.Instant.now().getEpochSecond()+3600);
            var settings=(MongoClientSettings)call(mongo,"buildSettings",new Class[]{});
            var callback=settings.getCredential().getMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY,(MongoCredential.OidcCallback)null);
            callback.onRequest(null);
            info.getOptions().put("token_expires_at",1);rejected(()->callback.onRequest(null));
        } finally{mongo.close();}
    }
    static void urls() throws Exception {
        var info=new DBConnectInfo();info.setHost("::1");info.setPort(5432);info.setDb("a?sslmode=disable&b");
        String template="jdbc:postgresql://${host}:${port}/${db}";
        check(info.toJDBCUrl(template).equals("jdbc:postgresql://[::1]:5432/a%3Fsslmode%3Ddisable%26b"),"database URL escaping");
        info.setHost("x?sslmode=disable");rejected(()->info.toJDBCUrl(template));
        info.setHost("fixture");info.setPort(0);rejected(()->info.toJDBCUrl(template));
    }
    public static void main(String[] args)throws Exception {metadata();expiry();urls();System.out.println("OK: selected protocol, mixed auth, legacy TLS fields, URL validation, JDBC/Mongo expiry");}
}
