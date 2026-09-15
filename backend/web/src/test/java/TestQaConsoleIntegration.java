import com.alibaba.druid.DbType;
import com.alibaba.fastjson.JSON;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.console.state.*;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.session.impl.BaseSession;
import org.jumpserver.chen.framework.ws.*;
import org.jumpserver.chen.framework.ws.io.*;
import org.springframework.web.socket.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Full console -> cancel WebSocket -> JDBC -> fresh independent database observer. */
public class TestQaConsoleIntegration {
    static void set(QueryConsole console,String name,Object value)throws Exception{var f=QueryConsole.class.getDeclaredField(name);f.setAccessible(true);f.set(console,value);}
    public static void main(String[] args)throws Exception{
        var jar=java.nio.file.Path.of("drivers/postgresql/postgresql-42.7.13.jar");
        org.jumpserver.chen.framework.driver.DriverManager.registerDriver("postgresql",new org.jumpserver.chen.framework.driver.DriverClassLoader(jar.getFileName().toString(),jar.toUri().toURL()));
        var info=new org.jumpserver.chen.framework.datasource.entity.DBConnectInfo();
        info.setDbType("postgresql");info.setHost("pg.fixture");info.setPort(5432);info.setDb("fixture");info.setUser("fixture");info.setPassword("FixturePass9!");
        info.getOptions().put("useSSL",true);
        info.getOptions().put("caCert",java.nio.file.Files.readString(java.nio.file.Path.of("/fixtures/ca.crt")));
        info.getOptions().put("clientCert",java.nio.file.Files.readString(java.nio.file.Path.of("/fixtures/client.crt")));
        info.getOptions().put("clientKey",java.nio.file.Files.readString(java.nio.file.Path.of("/fixtures/client.key")));
        Datasource ds=new org.jumpserver.chen.modules.postgresql.PostgresqlDatasource(info);
        var manager=ds.getConnectionManager();
        var session=new BaseSession(ds,"fixture");String token=SessionManager.registerSession(session);SessionManager.setContext(token);
        var packets=new CopyOnWriteArrayList<String>();Map<String,Object> attrs=new HashMap<>();attrs.put("token",token);
        WebSocketSession socket=(WebSocketSession)Proxy.newProxyInstance(WebSocketSession.class.getClassLoader(),new Class[]{WebSocketSession.class},(p,m,a)->switch(m.getName()){
            case "getId"->"qa-console";case "isOpen"->true;case "getAttributes"->attrs;
            case "sendMessage"->{packets.add(((TextMessage)a[0]).getPayload());yield null;}default->null;
        });
        session.activeSession(new PacketIO(socket));
        var console=new QueryConsole(ds,socket,"database:fixture");session.getConsoles().put("qa-console",console);
        var state=new QueryConsoleState(console.getTitle());
        set(console,"stateManager",new StateManager<>(state,new PacketIO(socket)));
        var connection=manager.getPhysicalConnection();set(console,"conn",connection);
        var handler=new ConsoleWebSocketHandler();var pool=Executors.newSingleThreadExecutor();
        try(var observer=manager.getConnection()){
            observer.setAutoCommit(true);int pid;
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();pid=r.getInt(1);}
            // The QA sequence performs earlier queries on this SAME console.
            console.onSQL("SELECT 'warmup-double-click'");
            console.onSQL("SELECT 'warmup-keyboard'");
            for(int cycle=0;cycle<2;cycle++) {
            var running=pool.submit(()->{SessionManager.setContext(token);try{console.onSQL("SELECT pg_sleep(20)");}finally{SessionManager.setContext(null);}});
            boolean active=false;
            for(int i=0;i<100;i++){
                try(var s=observer.createStatement();var r=s.executeQuery("SELECT state,query FROM pg_stat_activity WHERE pid="+pid)){
                    active=r.next()&&"active".equals(r.getString(1))&&r.getString(2).contains("pg_sleep");
                }
                if(active)break;Thread.sleep(50);
            }
            if(!active)throw new AssertionError("real console query did not start");
            handler.handleMessage(socket,new TextMessage("{\"type\":\"query_console_action\",\"data\":{\"action\":\"cancel\"}}"));
            running.get(5,TimeUnit.SECONDS);
            try(var s=observer.createStatement();var r=s.executeQuery("SELECT state FROM pg_stat_activity WHERE pid="+pid)){
                if(r.next()&&"active".equals(r.getString(1)))throw new AssertionError("cancel did not reach database");
            }
            SessionManager.setContext(token);
            console.onSQL("SELECT 'after-cancel'");
            }
            packets.clear();
            console.onSQL("SELECT 'first' AS marker; SELECT 'second' AS marker;");
            long views=packets.stream().map(JSON::parseObject).filter(p->"new_data_view".equals(p.getString("type"))).count();
            if(views!=2)throw new AssertionError("OBS-08 multiple SELECT result tabs lost: "+views);
            if(packets.stream().noneMatch(x->x.contains("first")) || packets.stream().noneMatch(x->x.contains("second")))throw new AssertionError("result lost");
            System.out.println("DEF-14/OBS-08 full console: repeated WebSocket cancel after warmup ends actual DB query; multiple SELECTs retain two independent result tabs");
        }finally{pool.shutdownNow();handler.shutdown();SessionManager.setContext(null);SessionManager.unregisterSession(token);console.close();manager.close();}
    }
}
