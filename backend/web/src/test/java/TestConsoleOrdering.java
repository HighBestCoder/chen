import org.jumpserver.chen.framework.console.Console;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.session.impl.BaseSession;
import org.jumpserver.chen.framework.ws.ConsoleWebSocketHandler;
import org.jumpserver.chen.framework.ws.io.*;
import org.springframework.web.socket.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TestConsoleOrdering {
    static <T> T proxy(Class<T> type,InvocationHandler fn){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},fn));}
    public static void main(String[] args) throws Exception {
        List<String> failures=new CopyOnWriteArrayList<>();
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), second=new CountDownLatch(1), done=new CountDownLatch(1);
        AtomicReference<String> database=new AtomicReference<>(); AtomicInteger cancels=new AtomicInteger(), creates=new AtomicInteger();
        ConnectionManager manager=proxy(ConnectionManager.class,(p,m,a)->{if(m.getName().equals("setDatabaseContext"))database.set((String)a[0]);return null;});
        Console[] first={null};
        first[0]=proxy(Console.class,(p,m,a)-> {
            if(m.getName().equals("getNodeKey"))return "database:A";
            if(m.getName().equals("handle")) {
                Packet packet=(Packet)a[0];
                if(packet.getType().equals("query_console_action")){cancels.incrementAndGet();return null;}
                if("tail".equals(packet.getType())){done.countDown();return null;}
                entered.countDown();release.await(3,TimeUnit.SECONDS);
                if(!"A".equals(database.get()))failures.add("database changed while query was active");
            }
            return null;
        });
        Datasource ds=proxy(Datasource.class,(p,m,a)->switch(m.getName()) {
            case "getConnectionManager"->manager;
            case "createQueryConsole"->{creates.incrementAndGet();yield first[0];}
            default->null;
        });
        BaseSession session=new BaseSession(ds,"local"); String token=SessionManager.registerSession(session);
        TestSessionBoundaries.Socket owner=new TestSessionBoundaries.Socket();session.activeSession(new PacketIO(owner.ws));
        TestSessionBoundaries.Socket a=new TestSessionBoundaries.Socket(),b=new TestSessionBoundaries.Socket();
        a.attrs.put("token",token);b.attrs.put("token",token);
        session.getConsoles().put(a.id,first[0]);
        session.getConsoles().put(b.id,proxy(Console.class,(p,m,v)->{
            if(m.getName().equals("getNodeKey"))return "database:B";
            if(m.getName().equals("handle")){
                if(release.getCount()>0)failures.add("second console overtook active query");second.countDown();
            }
            return null;
        }));
        var handler=new ConsoleWebSocketHandler();
        try {
            handler.handleMessage(a.ws,new TextMessage("{\"type\":\"work\"}"));
            if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("first query not started");
            handler.handleMessage(b.ws,new TextMessage("{\"type\":\"work\"}"));
            if(second.await(150,TimeUnit.MILLISECONDS))failures.add("concurrent console ran before first completed");
            handler.handleMessage(a.ws,new TextMessage("{\"type\":\"query_console_action\",\"data\":{\"action\":\"cancel\"}}"));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(cancels.get()==0 && System.nanoTime()<until)Thread.sleep(5);
            if(cancels.get()!=1)failures.add("cancel queued behind blocked query");
            release.countDown();if(!second.await(2,TimeUnit.SECONDS))failures.add("queued message lost");
            handler.handleMessage(a.ws,new TextMessage("{\"type\":\"connect\",\"data\":{\"type\":\"query\",\"nodeKey\":\"database:A\"}}"));
            handler.handleMessage(a.ws,new TextMessage("{\"type\":\"tail\"}"));
            if(!done.await(2,TimeUnit.SECONDS))failures.add("tail message lost");
            if(creates.get()!=0)failures.add("duplicate connect recreated console");
            // Occupy all workers once: every reused worker must have a clean context.
            for(Field field:ConsoleWebSocketHandler.class.getDeclaredFields()) if(ExecutorService.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true); ExecutorService pool=(ExecutorService)field.get(handler);
                CyclicBarrier barrier=new CyclicBarrier(10); List<Future<?>> checks=new ArrayList<>();
                for(int i=0;i<10;i++)checks.add(pool.submit(()->{
                    if(SessionManager.getContextToken()!=null)failures.add("worker retained prior identity");
                    try{barrier.await(2,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                }));
                for(var check:checks)check.get(3,TimeUnit.SECONDS);
            }
            TestSessionBoundaries.Socket unused=new TestSessionBoundaries.Socket();unused.attrs.put("token",token);
            try{handler.afterConnectionClosed(unused.ws,CloseStatus.NORMAL);}
            catch(Exception e){failures.add("close before connect threw");}
            if(SessionManager.getContextToken()!=null)failures.add("close callback leaked context");
        } finally {
            release.countDown();
            for(Field field:ConsoleWebSocketHandler.class.getDeclaredFields()) if(ExecutorService.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);((ExecutorService)field.get(handler)).shutdownNow();
            }
            SessionManager.unregisterSession(token);SessionManager.setContext(null);
        }
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("OK: FIFO across session consoles, cancel bypass, duplicate connect and early close");
    }
}
