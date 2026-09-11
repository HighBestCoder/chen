import jakarta.servlet.http.*;
import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.jms.*;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.session.impl.JMSSession;
import org.jumpserver.chen.framework.ws.ConsoleWebSocketHandler;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.web.interceptor.SessionInterceptor;
import org.jumpserver.chen.wisp.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TestSessionExecutionBoundary {
    static void require(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static <T>T proxy(Class<T> type,InvocationHandler h){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h));}
    static void set(Object o,String name,Object value)throws Exception{
        Field f=JMSSession.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    public static void main(String[] args)throws Exception{
        var template=ServiceOuterClass.TokenResponse.getDefaultInstance().getData();
        long now=Instant.now().getEpochSecond();
        for(String mode:List.of("valid","locked","expired","idle","maximum","disconnected","lock-during-acl")){
            AtomicInteger acl=new AtomicInteger(),db=new AtomicInteger(),handled=new AtomicInteger();
            var data=template.toBuilder().setSetting(template.getSetting().toBuilder().setMaxIdleTime(5).setMaxSessionTime(1))
                .setExpireInfo(template.getExpireInfo().toBuilder().setExpireAt(mode.equals("expired")?now-1:now+3600)).build();
            ConnectionManager manager=proxy(ConnectionManager.class,(p,m,a)->{db.incrementAndGet();return null;});
            Datasource ds=proxy(Datasource.class,(p,m,a)->m.getName().equals("getConnectionManager")?manager:null);
            JMSSession session=new JMSSession(Common.Session.newBuilder().setDateStart(mode.equals("maximum")?now-7200:now).build(),
                    ds,"local",null,ServiceOuterClass.TokenResponse.newBuilder().setData(data).build()){
                @Override public boolean isActive(){return !mode.equals("disconnected");}
            };
            set(session,"locked",mode.equals("locked"));
            set(session,"lastActiveTime",(mode.equals("idle")?now-600:now)*1000);
            set(session,"aclFilter",proxy(ACLFilter.class,(p,m,a)->{
                acl.incrementAndGet();if(mode.equals("lock-during-acl"))set(session,"locked",true);
                var result=new ACLResult();result.setRiskLevel(Common.RiskLevel.Normal);return result;
            }));
            set(session,"commandHandler",proxy(CommandHandler.class,(p,m,a)->null));
            String token=SessionManager.registerSession(session);
            try{
                boolean valid=mode.equals("valid");
                require(session.checkACL("SELECT 1").allows("SELECT 1")==valid,mode+" ACL boundary");
                require(acl.get()==(valid||mode.equals("lock-during-acl")?1:0),mode+" invoked ACL on unavailable session");
                if(!valid){
                    try{session.withAudit("SELECT 1",()->{db.incrementAndGet();return null;});throw new AssertionError(mode+" audit executed");}
                    catch(org.jumpserver.chen.framework.jms.exception.CommandRejectException expected){}
                    session.recordCommand(new org.jumpserver.chen.framework.jms.entity.CommandRecord("SELECT 1"));
                    require(!session.allowsExecution(),mode+" rejection revived session");
                }
                int[] status={200};
                HttpServletRequest request=proxy(HttpServletRequest.class,(p,m,a)->switch(m.getName()){
                    case "getServletPath"->"/api/resource/tree";case "getHeader"->token;default->null;
                });
                HttpServletResponse response=proxy(HttpServletResponse.class,(p,m,a)->{
                    if(m.getName().equals("setStatus"))status[0]=(int)a[0];
                    return m.getName().equals("getWriter")?new PrintWriter(new StringWriter()):null;
                });
                var interceptor=new SessionInterceptor();
                require(interceptor.preHandle(request,response,null)==valid,mode+" HTTP boundary");
                require(status[0]==(valid?200:mode.equals("disconnected")?401:403),mode+" HTTP status");
                interceptor.afterCompletion(request,response,null,null);
                require(SessionManager.getContextToken()==null,"HTTP identity leaked");
                var socket=new TestSessionBoundaries.Socket();
                session.getConsoles().put(socket.id,proxy(Console.class,(p,m,a)->{
                    if(m.getName().equals("handle"))handled.incrementAndGet();
                    return m.getName().equals("getNodeKey")?"database:fixture":null;
                }));
                var handler=new ConsoleWebSocketHandler();
                try{
                    Method dispatch=ConsoleWebSocketHandler.class.getDeclaredMethod("dispatch",org.springframework.web.socket.WebSocketSession.class,String.class,Packet.class,boolean.class);
                    dispatch.setAccessible(true);Packet packet=new Packet();packet.setType("work");
                    dispatch.invoke(handler,socket.ws,token,packet,false);
                    require(handled.get()==(valid?1:0),mode+" WebSocket dispatched work");
                    require(db.get()==(valid?1:0),mode+" reached database");
                    if(mode.equals("locked")){
                        dispatch.invoke(handler,socket.ws,token,packet,true);
                        require(handled.get()==1 && db.get()==0,"locked session cannot cancel");
                    }
                }finally{handler.shutdown();}
            }finally{SessionManager.unregisterSession(token);SessionManager.setContext(null);}
        }
        System.out.println("OK: ACL, audit, HTTP and WebSocket deny locked/expired/idle/maximum/disconnected sessions; cancellation preserved");
    }
}
