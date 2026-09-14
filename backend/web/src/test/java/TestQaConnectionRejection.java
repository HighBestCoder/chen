import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.wisp.*;
import org.jumpserver.chen.web.service.impl.JmsSessionService;
import org.jumpserver.chen.web.service.SessionService;
import org.jumpserver.chen.web.controller.AuthController;
import org.jumpserver.chen.web.entity.AuthRequest;
import org.jumpserver.chen.web.exception.ConnectionRejectedException;
import org.jumpserver.chen.web.interceptor.WebExceptionResolver;
import org.springframework.mock.web.*;

public class TestQaConnectionRejection {
    public static void main(String[] args)throws Exception {
        var core=new TestConnectionCreationCleanup.Core(){
            @Override public void getTokenAuthInfo(ServiceOuterClass.TokenRequest request,StreamObserver<ServiceOuterClass.TokenResponse> out){
                var asset=Common.Asset.newBuilder().setAddress("fixture.mongocluster.cosmos.azure.com")
                    .addProtocols(Common.Protocol.newBuilder().setName("mongodb").setPort(27017));
                var data=Common.TokenAuthInfo.newBuilder().setAsset(asset).addGateways(Common.Gateway.getDefaultInstance())
                    .setPlatform(Common.Platform.newBuilder().addProtocols(Common.PlatformProtocol.newBuilder().setName("mongodb")));
                reply(out,ServiceOuterClass.TokenResponse.newBuilder().setStatus(TestConnectionCreationCleanup.OK).setData(data).build());
            }
        };
        var server=ServerBuilder.forPort(0).directExecutor().addService(core).build().start();
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        try{
            var service=new JmsSessionService();var stub=JmsSessionService.class.getDeclaredField("serviceBlockingStub");stub.setAccessible(true);stub.set(service,ServiceGrpc.newBlockingStub(channel));
            try{service.createNewSession("fixture","local");throw new AssertionError("SRV gateway accepted");}
            catch(ConnectionRejectedException expected){if(expected.getStatus()!=400 || !expected.getMessage().contains("SRV"))throw new AssertionError("unreadable rejection");}
            if(core.created!=0)throw new AssertionError("gateway check happened after session creation");
            var auth=new AuthController();var field=AuthController.class.getDeclaredField("sessionService");field.setAccessible(true);
            field.set(auth,(SessionService)(token,remote)->{throw new IllegalStateException("fixture-secret-must-not-escape");});
            var request=new AuthRequest();request.setToken("fixture");
            try{auth.auth(new MockHttpServletRequest(),request);throw new AssertionError("auth failed open");}
            catch(ConnectionRejectedException error){
                var response=new MockHttpServletResponse();new WebExceptionResolver().resolveException(new MockHttpServletRequest(),response,null,error);
                if(response.getStatus()!=403 || response.getContentAsString().contains("fixture-secret") || !response.getContentAsString().contains("authentication failed"))throw new AssertionError("auth error leak or wrong response");
            }
            System.out.println("DEF-08/17 pre-forward SRV rejection and sanitized visible authentication response passed");
        }finally{channel.shutdownNow();server.shutdownNow();}
    }
}
