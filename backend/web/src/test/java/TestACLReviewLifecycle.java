import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.jumpserver.chen.wisp.*;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.session.impl.BaseSession;
import org.jumpserver.chen.framework.session.controller.*;
import org.jumpserver.chen.framework.session.controller.dialog.*;
import org.jumpserver.chen.framework.session.controller.message.MessageLevel;
import org.jumpserver.chen.framework.ws.io.Packet;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TestACLReviewLifecycle {
    static final String COMMAND="UPDATE fixture SET value=2";
    static final ServiceOuterClass.Status OK=ServiceOuterClass.Status.newBuilder().setOk(true).build();
    static int failures;
    static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    static class Core extends ServiceGrpc.ServiceImplBase {
        volatile String mode="approved",command;AtomicInteger created=new AtomicInteger(),cancelled=new AtomicInteger(),checked=new AtomicInteger();
        volatile boolean missingDeadline; volatile Runnable duringCheck;
        void deadline(){if(Context.current().getDeadline()==null)missingDeadline=true;}
        static <T>void reply(StreamObserver<T>out,T value){out.onNext(value);out.onCompleted();}
        @Override public void createCommandTicket(ServiceOuterClass.CommandConfirmRequest req,StreamObserver<ServiceOuterClass.CommandConfirmResponse> out){
            deadline();created.incrementAndGet();command=req.getCmd();
            if(mode.equals("create-failure")){out.onError(Status.UNAVAILABLE.asRuntimeException());return;}
            reply(out,ServiceOuterClass.CommandConfirmResponse.newBuilder().setStatus(OK).setInfo(ServiceOuterClass.TicketInfo.newBuilder()
                    .setTicketDetailUrl("/tickets/11111111-1111-4111-8111-111111111111")).build());
        }
        @Override public void checkTicketState(ServiceOuterClass.TicketRequest req,StreamObserver<ServiceOuterClass.TicketStateResponse> out){
            deadline();checked.incrementAndGet();
            if(duringCheck!=null)duringCheck.run();
            if(mode.equals("rpc-failure")){out.onError(Status.UNAVAILABLE.asRuntimeException());return;}
            var state=switch(mode){case "approved","double-submit","parallel","late-approval"->ServiceOuterClass.TicketState.State.Approved;case "rejected"->ServiceOuterClass.TicketState.State.Rejected;case "closed"->ServiceOuterClass.TicketState.State.Closed;default->ServiceOuterClass.TicketState.State.Open;};
            reply(out,ServiceOuterClass.TicketStateResponse.newBuilder().setStatus(OK).setData(ServiceOuterClass.TicketState.newBuilder().setState(state)).build());
        }
        @Override public void cancelTicket(ServiceOuterClass.TicketRequest req,StreamObserver<ServiceOuterClass.StatusResponse> out){
            deadline();cancelled.incrementAndGet();reply(out,ServiceOuterClass.StatusResponse.newBuilder().setStatus(OK).build());
        }
    }
    static class UI implements Controller {
        final BlockingQueue<Dialog> dialogs=new LinkedBlockingQueue<>();String mode="submit";
        volatile Dialog waiting;
        public void showDialog(Dialog d){
            dialogs.add(d);
            if(d.getButtons().size()==1){waiting=d;if(mode.equals("cancel-wait"))d.getButtons().get(0).getEventHandler().run();}
            if(d.getButtons().size()==2 && !mode.equals("ignore")){
                var handler=d.getButtons().get(mode.equals("cancel")?1:0).getEventHandler();handler.run();
                if(mode.equals("double-submit"))handler.run();
            }
        }
        public void closeDialog(){}
        public void showMessage(MessageLevel l,String s){}
        public void handlePacket(Packet p){}
        public void sendFile(String key){}
    }
    static ACLFilterImpl filter(Common.Session session,ServiceGrpc.ServiceBlockingStub stub,List<Common.CommandACL> rules)throws Exception{
        try {var ctor=ACLFilterImpl.class.getDeclaredConstructor(Common.Session.class,ServiceGrpc.ServiceBlockingStub.class,List.class,long.class,long.class);ctor.setAccessible(true);return ctor.newInstance(session,stub,rules,1000L,10L);}
        catch(NoSuchMethodException old){return new ACLFilterImpl(session,stub,rules);}
    }
    public static void main(String[] args)throws Exception{
        var core=new Core();var server=ServerBuilder.forPort(0).directExecutor().addService(core).build().start();
        var channel=ManagedChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        var executor=Executors.newCachedThreadPool();
        try{
            for(String mode:List.of("approved","rejected","closed","create-failure","rpc-failure","timeout","cancel","no-submit","disconnect","interrupt","cancel-wait","double-submit","parallel","late-approval")){
                var ui=new UI();if(mode.equals("cancel"))ui.mode="cancel";if(mode.equals("no-submit")||mode.equals("parallel"))ui.mode="ignore";
                if(mode.equals("cancel-wait")||mode.equals("double-submit"))ui.mode=mode;
                core.duringCheck=mode.equals("late-approval")?()->ui.waiting.getButtons().get(0).getEventHandler().run():null;
                var dbCalls=new AtomicInteger();var active=new AtomicBoolean(true);
                var actuator=(SQLActuator)Proxy.newProxyInstance(SQLActuator.class.getClassLoader(),new Class[]{SQLActuator.class},(p,m,a)->{dbCalls.incrementAndGet();return m.getReturnType()==int.class?0:null;});
                var manager=(ConnectionManager)Proxy.newProxyInstance(ConnectionManager.class.getClassLoader(),new Class[]{ConnectionManager.class},(p,m,a)->actuator);
                var ds=(Datasource)Proxy.newProxyInstance(Datasource.class.getClassLoader(),new Class[]{Datasource.class},(p,m,a)->manager);
                var session=new BaseSession(ds,"fixture"){@Override public Controller getController(){return ui;}@Override public boolean isActive(){return active.get();}};
                String token=SessionManager.registerSession(session);core.mode=mode;int before=core.created.get(),cancelBefore=core.cancelled.get();
                var acl=Common.CommandACL.newBuilder().setId("fixture-rule").setAction(Common.CommandACL.Action.Review)
                        .addCommandGroups(Common.CommandGroup.newBuilder().setPattern(".*")).build();
                var filter=filter(Common.Session.newBuilder().setId("fixture-session").build(),ServiceGrpc.newBlockingStub(channel),List.of(acl));
                var worker=new AtomicReference<Thread>();var interrupted=new AtomicBoolean();
                Future<ACLResult> work=executor.submit(()->{worker.set(Thread.currentThread());SessionManager.setContext(token);try{return filter.commandACLFilter(COMMAND,null);}finally{interrupted.set(Thread.currentThread().isInterrupted());SessionManager.setContext(null);}});
                try{
                    if(mode.equals("disconnect")||mode.equals("interrupt")){
                        check(ui.dialogs.poll(1,TimeUnit.SECONDS)!=null,"missing confirm dialog");
                        if(mode.equals("disconnect"))active.set(false);else worker.get().interrupt();
                    }
                    if(mode.equals("parallel")) {
                        var first=ui.dialogs.poll(1,TimeUnit.SECONDS);check(first!=null,"missing first dialog");
                        var other=executor.submit(()->{SessionManager.setContext(token);try{return filter.commandACLFilter("DELETE FROM fixture",null);}finally{SessionManager.setContext(null);}});
                        try {check(other.get(300,TimeUnit.MILLISECONDS).getRiskLevel()==Common.RiskLevel.ReviewReject,"parallel approval overwrote dialog");}
                        finally{other.cancel(true);}
                        first.getButtons().get(0).getEventHandler().run();
                    }
                    ACLResult result=work.get(3,TimeUnit.SECONDS);
                    check(result.getRiskLevel()==(List.of("approved","double-submit","parallel").contains(mode)?Common.RiskLevel.ReviewAccept:Common.RiskLevel.ReviewReject),"wrong terminal decision");
                    check(dbCalls.get()==0,"database accessed before approval");
                    check(!core.missingDeadline,"unbounded RPC");
                    if(List.of("approved","double-submit","parallel").contains(mode)){check(core.created.get()==before+1,"duplicate ticket");check(COMMAND.equals(core.command),"reviewed command changed");check(ACLFilterImpl.commandHash(COMMAND).equals(result.getApprovedCommandHash()),"missing approved hash");}
                    if(mode.equals("cancel")||mode.equals("no-submit"))check(core.created.get()==before,"ticket before submit");
                    if(List.of("cancel-wait","rpc-failure","timeout").contains(mode))check(core.cancelled.get()==cancelBefore+1,"pending ticket not cancelled");
                    if(mode.equals("interrupt"))check(interrupted.get(),"interrupt flag lost");
                    System.out.println("PASS review "+mode);
                }catch(Throwable failure){failures++;System.out.println("FAIL review "+mode+": "+failure);}
                finally{active.set(false);work.cancel(true);SessionManager.unregisterSession(token);}
            }
        }finally{executor.shutdownNow();channel.shutdownNow();server.shutdownNow();}
        if(failures>0){System.err.println(failures+" review lifecycle failures");System.exit(1);}
        System.out.println("OK: real gRPC review outcomes, no pre-approval DB operations and bounded waiting");
    }
}
