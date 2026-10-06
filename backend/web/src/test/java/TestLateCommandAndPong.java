import com.alibaba.fastjson.JSON;
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

/** RUN-261002 OBS-09: pong carries server time on arrival; a command that arrives >10 s after its click is refused. */
public class TestLateCommandAndPong {
    static <T> T proxy(Class<T> type, InvocationHandler fn) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, fn)); }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        String id = UUID.randomUUID().toString();
        Map<String, Object> attrs = new HashMap<>();
        WebSocketSession ws = proxy(WebSocketSession.class, (p, m, a) -> switch (m.getName()) {
            case "getId" -> id;
            case "getAttributes" -> attrs;
            case "isOpen" -> true;
            case "sendMessage" -> { sent.add(((TextMessage) a[0]).getPayload()); yield null; }
            default -> null;
        });
        LinkedBlockingQueue<String> handled = new LinkedBlockingQueue<>();
        Console console = proxy(Console.class, (p, m, a) -> {
            if (m.getName().equals("getNodeKey")) return "database:A";
            if (m.getName().equals("handle")) handled.add(JSON.toJSONString(((Packet) a[0]).getData()));
            return null;
        });
        ConnectionManager manager = proxy(ConnectionManager.class, (p, m, a) -> null);
        Datasource ds = proxy(Datasource.class, (p, m, a) -> m.getName().equals("getConnectionManager") ? manager : null);
        BaseSession session = new BaseSession(ds, "local");
        String token = SessionManager.registerSession(session);
        attrs.put("token", token);
        WebSocketSession owner = proxy(WebSocketSession.class, (p, m, a) -> m.getName().equals("isOpen") ? true : null);
        session.activeSession(new PacketIO(owner));
        session.getConsoles().put(id, console);
        var handler = new ConsoleWebSocketHandler();
        try {
            long before = System.currentTimeMillis();
            handler.handleMessage(ws, new TextMessage("{\"type\":\"ping\",\"data\":{\"t\":12345}}"));
            require(sent.size() == 1, "pong sent on arrival: " + sent);
            var pong = JSON.parseObject(sent.get(0));
            require("pong".equals(pong.getString("type")), "pong type");
            require(pong.getJSONObject("data").getLongValue("t") == 12345, "pong echoes client time");
            long s = pong.getJSONObject("data").getLongValue("s");
            require(s >= before && s <= System.currentTimeMillis(), "pong carries server time");
            require(handled.isEmpty(), "ping no longer queued to the console");

            sent.clear();
            long late = System.currentTimeMillis() - 11_000;
            handler.handleMessage(ws, new TextMessage("{\"type\":\"query_console_action\",\"data\":{\"action\":\"run_sql\",\"data\":\"INSERT INTO t VALUES (1)\",\"sentAt\":" + late + "}}"));
            require(handled.poll(300, TimeUnit.MILLISECONDS) == null, "late command reached the console");
            require(sent.size() == 1 && sent.get(0).contains("Operation denied") && sent.get(0).contains("was not executed"),
                    "late command answered with a reason: " + sent);

            long fresh = System.currentTimeMillis() - 1_000;
            handler.handleMessage(ws, new TextMessage("{\"type\":\"query_console_action\",\"data\":{\"action\":\"run_sql\",\"data\":\"SELECT 1\",\"sentAt\":" + fresh + "}}"));
            String ran = handled.poll(2, TimeUnit.SECONDS);
            require(ran != null && ran.contains("SELECT 1"), "fresh command executed");

            handler.handleMessage(ws, new TextMessage("{\"type\":\"query_console_action\",\"data\":{\"action\":\"run_sql\",\"data\":\"SELECT 2\"}}"));
            String unstamped = handled.poll(2, TimeUnit.SECONDS);
            require(unstamped != null && unstamped.contains("SELECT 2"), "command without sentAt (no clock offset yet) executed");
        } finally {
            SessionManager.unregisterSession(token);
            SessionManager.setContext(null);
        }
        System.out.println("OK: pong on arrival with server time; command >10 s late refused with reason; fresh and unstamped commands run");
    }
}
