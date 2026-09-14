import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.wisp.*;
import org.jumpserver.chen.framework.datasource.DatasourceFactory;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.chen.modules.postgresql.PostgresqlDatasource;
import org.jumpserver.chen.web.service.impl.JmsSessionService;

public class TestQaSessionRpc {
    static class Core extends TestSessionRenewalRpc.Core {
        boolean revoked, download; int checked;
        @Override public void getTokenAuthInfo(ServiceOuterClass.TokenRequest request, StreamObserver<ServiceOuterClass.TokenResponse> out) {
            if(request.getToken().startsWith("chen-session-check:")) {
                checked++;
                if(!request.getToken().equals("chen-session-check:"+id))throw new AssertionError("wrong binding");
                if(revoked){out.onError(Status.PERMISSION_DENIED.asRuntimeException());return;}
                var protocol=Common.PlatformProtocol.newBuilder().setName("postgresql")
                    .putSettings("chen_session_id",id).putSettings("chen_session_valid","true")
                    .putSettings("chen_download_allowed",Boolean.toString(download));
                reply(out,ServiceOuterClass.TokenResponse.newBuilder().setStatus(TestSessionRenewalRpc.OK).setData(Common.TokenAuthInfo.newBuilder()
                    .setPlatform(Common.Platform.newBuilder().addProtocols(protocol))).build());return;
            }
            super.getTokenAuthInfo(request,new StreamObserver<>() {
                public void onNext(ServiceOuterClass.TokenResponse response){
                    var data=response.getData().toBuilder();
                    data.setPlatform(data.getPlatform().toBuilder().setProtocols(0,data.getPlatform().getProtocols(0).toBuilder().putSettings("chen_session_id",id)));
                    out.onNext(response.toBuilder().setData(data).build());
                }
                public void onError(Throwable error){out.onError(error);}
                public void onCompleted(){out.onCompleted();}
            });
        }
    }
    public static void main(String[] args)throws Exception {
        var core=new Core();var server=ServerBuilder.forPort(0).directExecutor().addService(core).build().start();
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        DatasourceFactory.Register(PostgresqlDatasource.class);
        var service=new JmsSessionService();var field=JmsSessionService.class.getDeclaredField("serviceBlockingStub");field.setAccessible(true);field.set(service,ServiceGrpc.newBlockingStub(channel));
        JMSSession session=null;
        try{
            session=(JMSSession)service.createNewSession("fixture-login","local");
            session.setPacketIO(new PacketIO(new TestSessionBoundaries.Socket().ws));
            if(!session.allowsExecution() || session.canDownload())throw new AssertionError("connect-only permissions ignored");
            core.download=true;if(!session.canDownload())throw new AssertionError("new download grant ignored");
            core.revoked=true;if(session.allowsExecution() || session.canDownload())throw new AssertionError("revocation ignored");
            if(core.refreshed!=0 || core.checked!=4)throw new AssertionError("authorization caused token acquisition or stale cache");
            System.out.println("DEF-12/19 production session + real gRPC: no download, grant, revoke without token refresh passed");
        }finally{if(session!=null)session.close();channel.shutdownNow();server.shutdownNow();}
    }
}
