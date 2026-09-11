import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.wisp.*;
import org.jumpserver.chen.framework.audit.*;
import org.jumpserver.chen.framework.jms.*;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.*;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
public class TestS07AuditRpc {
    static void check(boolean v,String msg){if(!v)throw new AssertionError(msg);}
    static void set(Object o,String name,Object value)throws Exception{var f=JMSSession.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    public static void main(String[] args)throws Exception {
        var uploads=new ArrayList<ServiceOuterClass.CommandRequest>();int[] replays={0};
        var server=ServerBuilder.forPort(0).addService(new ServiceGrpc.ServiceImplBase(){
            @Override public void uploadCommand(ServiceOuterClass.CommandRequest req,StreamObserver<ServiceOuterClass.CommandResponse> out){
                check(Context.current().getDeadline()!=null,"audit deadline absent");uploads.add(req);
                if(uploads.size()==2){out.onError(Status.UNAVAILABLE.asRuntimeException());return;}
                out.onNext(ServiceOuterClass.CommandResponse.newBuilder().setStatus(ServiceOuterClass.Status.newBuilder().setOk(true)).build());out.onCompleted();
            }
            @Override public void uploadReplayFile(ServiceOuterClass.ReplayRequest req,StreamObserver<ServiceOuterClass.ReplayResponse> out){
                check(Context.current().getDeadline()!=null,"replay deadline absent");replays[0]++;
                try{check(Files.readString(Path.of(req.getReplayFilePath())).contains("中文"),"replay not flushed");}catch(Exception e){throw new RuntimeException(e);}
                out.onNext(ServiceOuterClass.ReplayResponse.newBuilder().setStatus(ServiceOuterClass.Status.newBuilder().setOk(true)).build());out.onCompleted();
            }
        }).build().start();
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        String sid=UUID.randomUUID().toString();
        try {
            var stub=ServiceGrpc.newBlockingStub(channel);var identity=Common.Session.newBuilder().setId(sid).setOrgId("org").setAsset("asset").setAccount("account").setUser("user").build();
            var handler=new CommandHandlerImpl(identity,stub);var record=new CommandRecord("完整命令");record.setError("denied");handler.recordCommand(record);handler.recordCommand(record);
            check(uploads.size()==2,"transport error retried duplicate audit");var req=uploads.get(0);
            check(req.getTimestamp()==record.getTimestamp() && req.getOrgId().equals("org") && req.getUser().equals("user"),"audit identity/time changed");
            var stats=ExecutionStatsEnvelope.tryDecode(req.getOutput()).orElseThrow();check(Boolean.FALSE.equals(stats.getSuccess()) && stats.getRawCommand().equals(record.getInput()),"rejection stats absent");
            var replay=new ReplayHandlerImpl(identity,stub);replay.init();replay.writeInput("中文");replay.writeOutput(null);replay.release();replay.release();check(replays[0]==1,"replay uploaded twice");
            var broken=new ReplayHandlerImpl(identity.toBuilder().setId(sid+"/missing").build(),stub);
            try{broken.init();throw new AssertionError("init unexpectedly succeeded");}catch(org.jumpserver.chen.framework.jms.exception.ReplayException expected){}broken.release();
            var captured=new ArrayList<CommandRecord>();
            var session=new JMSSession(identity,null,"local",stub,ServiceOuterClass.TokenResponse.getDefaultInstance()){@Override public boolean allowsExecution(){return true;}};
            set(session,"commandHandler",(CommandHandler)captured::add);
            set(session,"replayHandler",Proxy.newProxyInstance(ReplayHandler.class.getClassLoader(),new Class[]{ReplayHandler.class},(p,m,a)->null));
            try{session.withAudit("raw failure","selected_db",()->{throw new IllegalStateException("fixture");});throw new AssertionError("runtime swallowed");}catch(IllegalStateException expected){}
            check(captured.size()==1,"runtime audit missing/duplicated");stats=captured.get(0).getExecutionStats();check(Boolean.FALSE.equals(stats.getSuccess()) && "selected_db".equals(stats.getNamespace()),"runtime failure misclassified/misbound");
        }finally{channel.shutdownNow();server.shutdownNow();Files.deleteIfExists(Path.of("data/replay",sid+".cast"));}
        System.out.println("OK: real audit/replay RPC deadlines, identity, failure/no retry, replay cleanup, runtime audit namespace");
    }
}
