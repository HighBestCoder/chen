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
            var mongoInfo = new org.jumpserver.chen.framework.datasource.entity.DBConnectInfo();
            mongoInfo.setDb("s05");
            var mongo = new org.jumpserver.chen.modules.mongodb.MongoDatasource(mongoInfo);
            consoles.clear();
            var m1 = mongo.createQueryConsole(socket, ""); consoles.put("m1",m1);
            var m2 = mongo.createQueryConsole(socket, ""); consoles.put("m2",m2);
            consoles.remove("m1");
            var m3 = mongo.createQueryConsole(socket, "");
            if(m2.getTitle().equals(m3.getTitle()))throw new AssertionError("Mongo title collision after close");
            var mongoState = new org.jumpserver.chen.framework.console.state.QueryConsoleState(m2.getTitle());
            mongoState.setInQuery(true);
            var stateField = m2.getClass().getDeclaredField("stateManager"); stateField.setAccessible(true);
            stateField.set(m2,new org.jumpserver.chen.framework.console.state.StateManager<>(mongoState,
                    new org.jumpserver.chen.framework.ws.io.PacketIO(socket)));
            var cancel = new org.jumpserver.chen.framework.console.action.QueryConsoleAction(); cancel.setAction("cancel");
            var onAction = m2.getClass().getDeclaredMethod("onAction",cancel.getClass()); onAction.setAccessible(true);
            onAction.invoke(m2,cancel);
            if(!mongoState.isInQuery())throw new AssertionError("unsupported cancel falsely reports query ended");
            var prior = new org.jumpserver.chen.framework.console.dataview.DataView("prior",
                    new org.jumpserver.chen.framework.ws.io.PacketIO(socket), null);
            prior.setLoadDataInterface((params,sink) -> new org.jumpserver.chen.framework.datasource.sql.SQLQueryResult("prior"));
            var viewsField = m2.getClass().getDeclaredField("dataViews"); viewsField.setAccessible(true);
            ((Map) viewsField.get(m2)).put("prior",prior);
            var change = new org.jumpserver.chen.framework.console.action.DataViewAction();
            change.setAction("change_limit"); change.setDataView("prior"); change.setData(500);
            var onView = m2.getClass().getDeclaredMethod("onDataViewAction",change.getClass()); onView.setAccessible(true);
            onView.invoke(m2,change);
            List<Integer> executedLimits = new ArrayList<>();
            var fakeActuator = new org.jumpserver.chen.modules.mongodb.command.MongoActuator(
                    (org.jumpserver.chen.modules.mongodb.MongoConnectionManager)mongo.getConnectionManager()) {
                @Override public org.jumpserver.chen.framework.datasource.sql.SQLQueryResult execute(
                        org.jumpserver.chen.modules.mongodb.command.MongoCommand command,int offset,int limit) {
                    executedLimits.add(limit);
                    return new org.jumpserver.chen.modules.mongodb.command.MongoResultTableAdapter()
                            .toResult(command.getRawText(),List.of(new org.bson.Document("x",1)),1L,2L);
                }
            };
            var actuatorField = m2.getClass().getDeclaredField("actuator"); actuatorField.setAccessible(true); actuatorField.set(m2,fakeActuator);
            var onCommand = m2.getClass().getDeclaredMethod("onCommand",String.class); onCommand.setAccessible(true);
            onCommand.invoke(m2,"db.items.find({})");
            if(!executedLimits.equals(List.of(500)))throw new AssertionError("new Mongo command forgot chosen limit");
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
