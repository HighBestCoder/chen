import org.jumpserver.chen.framework.console.*;
import org.jumpserver.chen.framework.session.*;
import org.springframework.web.socket.WebSocketSession;
import java.lang.reflect.*;
import java.util.*;
public class TestConsoleTitleIdentity {
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},h));}
    public static void main(String[] args)throws Exception {
        Map<String,org.jumpserver.chen.framework.console.Console> consoles=new HashMap<>();
        Session session=proxy(Session.class,(p,m,a)->m.getName().equals("getConsoles")?consoles:null);
        String token=SessionManager.registerSession(session);SessionManager.setContext(token);
        try {
            WebSocketSession socket=proxy(WebSocketSession.class,(p,m,a)->null);
            var first=new QueryConsole(null,socket,"");consoles.put("socket-1",first);
            var second=new QueryConsole(null,socket,"");consoles.put("socket-2",second);
            consoles.remove("socket-1");var third=new QueryConsole(null,socket,"");
            if(second.getTitle().equals(third.getTitle()))throw new AssertionError("closing first console causes title collision");
            var method=DataViewConsole.class.getDeclaredMethod("generateConsoleName");method.setAccessible(true);
            var a=new DataViewConsole(null,socket,"database:a,schema:public,table:users");
            var b=new DataViewConsole(null,socket,"database:b,schema:public,table:users");
            for(var view:List.of(a,b))for(String name:List.of("schema","table")){
                var field=DataViewConsole.class.getDeclaredField(name);field.setAccessible(true);field.set(view,name.equals("schema")?"public":"users");
            }
            a.setTitle((String)method.invoke(a));consoles.put("random-socket-id",a);
            if(a.getTitle().equals(method.invoke(b)))throw new AssertionError("previews in different databases collide");
            try{method.invoke(a);throw new AssertionError("existing preview not found by socket-keyed registry");}
            catch(InvocationTargetException expected){if(!(expected.getCause() instanceof RuntimeException))throw expected;}
        } finally {SessionManager.unregisterSession(token);SessionManager.setContext(null);}
        System.out.println("OK: console names survive closed tabs; preview identity includes database and uses registry values");
    }
}
