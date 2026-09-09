import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.impl.BaseSession;
import org.jumpserver.chen.framework.ws.SessionWebSocketHandler;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.chen.web.config.WebSocketConfig;
import org.jumpserver.chen.web.interceptor.SessionInterceptor;
import org.jumpserver.chen.web.interceptor.WebExceptionResolver;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public class TestSessionBoundaries {
    static final List<String> failures = new ArrayList<>();
    static void check(boolean ok,String message) { if(!ok) failures.add(message); }
    static <T> T proxy(Class<T> type,InvocationHandler fn) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},fn)); }
    static final class Socket implements InvocationHandler {
        final String id=UUID.randomUUID().toString(); final Map<String,Object> attrs=new HashMap<>(); boolean open=true;
        final WebSocketSession ws=proxy(WebSocketSession.class,this);
        public Object invoke(Object p,Method m,Object[] a) { return switch(m.getName()) {
            case "getId" -> id; case "getAttributes" -> attrs; case "isOpen" -> open;
            case "close" -> {open=false; yield null;} default -> null;
        }; }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("session-boundaries-");
        int[] closed={0,0};
        Datasource ds=proxy(Datasource.class,(p,m,a)-> {if(m.getName().equals("close")) closed[0]++; return null;});
        BaseSession session=new BaseSession(ds,"127.0.0.1") { @Override public Path getTempPath(){return root;} };
        String token=SessionManager.registerSession(session);
        try {
            var handshake=new WebSocketConfig.ServletWebSocketHandshakeInterceptor();
            for(String offered:new String[]{null,"unknown",token}) {
                HttpHeaders headers=new HttpHeaders();if(offered!=null) headers.add("Sec-WebSocket-Protocol",offered);
                ServerHttpRequest request=proxy(ServerHttpRequest.class,(p,m,a)->m.getName().equals("getHeaders")?headers:URI.create("http://localhost/chen/ws/console"));
                HttpHeaders responseHeaders=new HttpHeaders();
                ServerHttpResponse response=proxy(ServerHttpResponse.class,(p,m,a)->m.getName().equals("getHeaders")?responseHeaders:null);
                try {check(!handshake.beforeHandshake(request,response,null,new HashMap<>()),"handshake accepted missing/unknown/inactive identity");}
                catch(Exception e) {failures.add("handshake threw instead of rejecting malformed identity");}
            }
            Socket owner=new Socket();owner.attrs.put("token",token);
            var handler=new SessionWebSocketHandler();
            handler.afterConnectionEstablished(owner.ws);
            check(session.isActive(),"valid session failed activation");
            check(SessionManager.getContextToken()==null,"activation leaked thread context");
            Socket duplicate=new Socket();duplicate.attrs.put("token",token);
            handler.afterConnectionEstablished(duplicate.ws);
            check(!duplicate.open && session.getPacketIO().getWsSession()==owner.ws,"duplicate stole session socket");
            handler.afterConnectionClosed(duplicate.ws,CloseStatus.NORMAL);
            check(session.isActive() && SessionManager.getSession(token)==session,"duplicate close killed real session");
            // Re-establish test fixture if baseline behavior destroyed it.
            if(SessionManager.getSession(token)==null) token=SessionManager.registerSession(session);
            session.activeSession(new PacketIO(owner.ws)); owner.open=true;
            final String requestToken=token;
            HttpServletRequest request=proxy(HttpServletRequest.class,(p,m,a)->switch(m.getName()) {
                case "getServletPath" -> "/api/profile"; case "getHeader" -> requestToken; default -> null;
            });
            int[] status={200};StringWriter body=new StringWriter();
            HttpServletResponse response=proxy(HttpServletResponse.class,(p,m,a)->switch(m.getName()) {
                case "setStatus" -> {status[0]=(Integer)a[0];yield null;} case "getWriter" -> new PrintWriter(body);
                case "isCommitted" -> false; default -> null;
            });
            var interceptor=new SessionInterceptor();
            check(interceptor.preHandle(request,response,null),"valid HTTP rejected");
            interceptor.afterCompletion(request,response,null,null);
            check(SessionManager.getContextToken()==null,"HTTP completion leaked context");
            SessionManager.setContext(requestToken);
            HttpServletRequest denied=proxy(HttpServletRequest.class,(p,m,a)->m.getName().equals("getServletPath")?"/api/profile":null);
            check(!interceptor.preHandle(denied,response,null) && status[0]==401,"invalid HTTP accepted");
            check(SessionManager.getContextToken()==null,"HTTP rejection retained previous identity");
            new WebExceptionResolver().resolveException(request,response,null,new NullPointerException("injected failure"));
            check(status[0]==500,"unhandled API error returned success status");
            try { SessionManager.setContext(null);check(SessionManager.getCurrentSession()==null,"missing context should return null"); }
            catch(Exception e) {failures.add("missing context threw NPE");}
            session.getConsoles().put("test",proxy(Console.class,(p,m,a)-> {if(m.getName().equals("close"))closed[1]++;return null;}));
            Files.createDirectories(root);
            Files.writeString(root.resolve("result.csv"),"sensitive test result");
            int before=closed[0];session.close();session.close();
            check(closed[0]==before+1 && closed[1]==1,"close not idempotent or console not closed");
            check(!Files.exists(root),"close retained session result files");
        } finally {
            SessionManager.unregisterSession(token); SessionManager.setContext(null);
            if(Files.exists(root)) try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);}
        }
        Path failureDir=Files.createTempDirectory("session-close-failure-");
        Files.writeString(failureDir.resolve("result.csv"),"test");
        Socket failureSocket=new Socket();
        Datasource broken=proxy(Datasource.class,(p,m,a)->{if(m.getName().equals("close"))throw new RuntimeException("injected close failure");return null;});
        BaseSession failureSession=new BaseSession(broken,"local") { @Override public Path getTempPath(){return failureDir;} };
        String failureToken=SessionManager.registerSession(failureSession);
        failureSession.activeSession(new PacketIO(failureSocket.ws));
        try {
            try {failureSession.close();}catch(RuntimeException e){failures.add("datasource close failure escaped cleanup");}
            check(!failureSocket.open && !Files.exists(failureDir),"one cleanup failure prevented socket/file cleanup");
        } finally {
            SessionManager.unregisterSession(failureToken);
            if(Files.exists(failureDir))try(var files=Files.walk(failureDir)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
        int[] pendingClosed={0};
        Path pendingDir=Files.createTempDirectory("session-pending-");
        BaseSession pending=new BaseSession(proxy(Datasource.class,(p,m,a)->{if(m.getName().equals("close"))pendingClosed[0]++;return null;}),"local") {
            @Override public Path getTempPath(){return pendingDir;}
        };
        String pendingToken=SessionManager.registerSession(pending);
        try {
            try {
                Method expire=SessionManager.class.getDeclaredMethod("expirePendingSessions",long.class);expire.setAccessible(true);
                expire.invoke(null,System.currentTimeMillis()+301_000);
                check(SessionManager.getSession(pendingToken)==null && pendingClosed[0]==1,"abandoned authentication leaked session/resources");
            }catch(NoSuchMethodException e){failures.add("abandoned authentication has no expiry cleanup");}
        } finally {
            try{pending.close();}catch(Exception ignored){}
            SessionManager.unregisterSession(pendingToken);Files.deleteIfExists(pendingDir);
        }
        Path activationDir=Files.createTempDirectory("session-activation-");
        BaseSession badActivation=new BaseSession(ds,"local") {
            @Override public Path getTempPath(){return activationDir;}
            @Override public void activeSession(PacketIO packetIO){throw new IllegalStateException("injected activation failure");}
        };
        String badToken=SessionManager.registerSession(badActivation);Socket badSocket=new Socket();badSocket.attrs.put("token",badToken);
        try {
            try {new SessionWebSocketHandler().afterConnectionEstablished(badSocket.ws);}catch(Exception expected){}
            check(SessionManager.getSession(badToken)==null && !badSocket.open,"partial activation leaked registered identity/socket");
            check(SessionManager.getContextToken()==null,"activation failure leaked context");
        } finally {
            SessionManager.unregisterSession(badToken);SessionManager.setContext(null);Files.deleteIfExists(activationDir);
        }
        if(!failures.isEmpty()) throw new AssertionError(String.join("; ",failures));
        System.out.println("OK: handshake/owner/HTTP context/error status and idempotent file/console cleanup");
    }
}
