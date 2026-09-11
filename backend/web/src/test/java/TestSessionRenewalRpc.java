import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.wisp.*;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.modules.postgresql.PostgresqlDatasource;
import org.jumpserver.chen.web.service.impl.JmsSessionService;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.sql.Driver;

/** Runs the production session service over actual loopback gRPC. Compiles on pre-renewal code. */
public class TestSessionRenewalRpc {
    static final ServiceOuterClass.Status OK=ServiceOuterClass.Status.newBuilder().setOk(true).build();
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static class Core extends ServiceGrpc.ServiceImplBase {
        String id=UUID.randomUUID().toString(), mode="ok"; int refreshed,created,finished;
        static <T> void reply(StreamObserver<T> out,T value){out.onNext(value);out.onCompleted();}
        @Override public void getTokenAuthInfo(ServiceOuterClass.TokenRequest req,StreamObserver<ServiceOuterClass.TokenResponse> out){
            boolean renew=req.getToken().startsWith("entra-session:");
            check(Context.current().getDeadline()!=null,"missing RPC deadline");
            if(renew){refreshed++;check(req.getToken().equals("entra-session:"+id),"wrong session reference");}
            if(renew && mode.equals("denied")){out.onError(Status.PERMISSION_DENIED.withDescription("sensitive-fixture").asRuntimeException());return;}
            var settings=new HashMap<String,String>();settings.put("selected_protocol","postgresql");
            settings.put("auth_type","entra_sp");settings.put("auth_source","core_poc_token");settings.put("auth_flow_version","v1");
            settings.put("token_expires_at",Long.toString(renew?Instant.now().getEpochSecond()+3600:1));
            settings.put("entra_session_id",id);
            var data=Common.TokenAuthInfo.newBuilder()
                    .setAsset(Common.Asset.newBuilder().setId(renew && mode.equals("target")?"other":"asset").setOrgId("org").setAddress("fixture")
                            .addProtocols(Common.Protocol.newBuilder().setName("postgresql").setPort(5432)))
                    .setAccount(Common.Account.newBuilder().setId("account").setUsername("dbuser").setSecret(renew?"fresh-fixture":"old-fixture"))
                    .setUser(Common.User.newBuilder().setId("user"))
                    .setPlatform(Common.Platform.newBuilder().addProtocols(Common.PlatformProtocol.newBuilder().setName("postgresql").putAllSettings(settings)))
                    .setExpireInfo(Common.ExpireInfo.newBuilder().setExpireAt(Instant.now().getEpochSecond()+7200))
                    .setSetting(Common.ComponentSetting.newBuilder().setMaxIdleTime(60).setMaxSessionTime(1));
            reply(out,ServiceOuterClass.TokenResponse.newBuilder().setStatus(OK).setData(data).build());
        }
        @Override public void createSession(ServiceOuterClass.SessionCreateRequest req,StreamObserver<ServiceOuterClass.SessionCreateResponse> out){
            created++;reply(out,ServiceOuterClass.SessionCreateResponse.newBuilder().setStatus(OK).setData(req.getData().toBuilder().setId(id)).build());
        }
        @Override public void finishSession(ServiceOuterClass.SessionFinishRequest req,StreamObserver<ServiceOuterClass.SessionFinishResp> out){
            finished++;reply(out,ServiceOuterClass.SessionFinishResp.newBuilder().setStatus(OK).build());
        }
        @Override public void recordSessionLifecycleLog(ServiceOuterClass.SessionLifecycleLogRequest req,StreamObserver<ServiceOuterClass.StatusResponse> out){
            reply(out,ServiceOuterClass.StatusResponse.newBuilder().setStatus(OK).build());
        }
    }
    public static void main(String[] args)throws Exception {
        var core=new Core();var server=ServerBuilder.forPort(0).directExecutor().addService(core).build().start();
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        DatasourceFactory.Register(PostgresqlDatasource.class);
        var service=new JmsSessionService();var field=JmsSessionService.class.getDeclaredField("serviceBlockingStub");field.setAccessible(true);field.set(service,ServiceGrpc.newBlockingStub(channel));
        try {
            for(String mode:List.of("ok","target","denied","closed")){
                core.id=UUID.randomUUID().toString();core.mode=mode;int before=core.refreshed;
                Session session=service.createNewSession("login-fixture","127.0.0.1");
                var received=new ArrayList<Properties>();
                Driver driver=(Driver)Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{Driver.class},(p,m,a)->{if(m.getName().equals("connect"))received.add((Properties)a[1]);return null;});
                try {
                    if(mode.equals("closed"))session.close();
                    var guard=new TokenGuardDriver(driver,session.getDatasource().getConnectInfo());
                    try {
                        guard.connect("fixture",new Properties());
                        check(mode.equals("ok"),"rejected renewal reached driver");
                        check(received.size()==1 && received.get(0).getProperty("password").equals("fresh-fixture"),"new connection used stale token");
                    } catch(IllegalStateException failure){
                        check(!mode.equals("ok"),"authorized expired credential was not renewed");
                        check(!failure.toString().contains("sensitive-fixture"),"RPC detail leaked");
                        check(received.isEmpty(),"driver used on renewal failure");
                    }
                    check(core.refreshed-before==(mode.equals("closed")?0:1),"wrong renewal RPC count");
                } finally{session.close();}
            }
            int before=core.created;
            try{service.createNewSession("entra-session:"+core.id,"127.0.0.1");throw new AssertionError("renewal reference reused as login");}catch(IllegalArgumentException expected){}
            check(core.created==before && core.finished==core.created,"session leak or reference login reuse");
        }finally{channel.shutdownNow();server.shutdownNow();}
        System.out.println("OK: production session -> gRPC renewal -> fresh JDBC credential; target binding, denial and closure");
    }
}
