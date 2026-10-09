import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.state.*;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.springframework.web.socket.WebSocketSession;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** LIM-07: a console whose connection the server closed reconnects, keeps its context, and reloads on the new connection. */
public class TestConsoleReconnect {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler));
    }
    static Connection connection(boolean[] closed) {
        return proxy(Connection.class, (p, m, v) -> switch (m.getName()) {
            case "isClosed" -> closed[0];
            case "close" -> { closed[0] = true; yield null; }
            default -> null;
        });
    }
    public static void main(String[] args) throws Exception {
        Session session = proxy(Session.class, (p, m, v) -> switch (m.getName()) {
            case "getConsoles" -> Map.of();
            default -> null;
        });
        String token = SessionManager.registerSession(session); SessionManager.setContext(token);
        boolean[] firstClosed = {false}, brokenClosed = {false}, secondClosed = {false}, failRestore = {false};
        Connection first = connection(firstClosed), broken = connection(brokenClosed), second = connection(secondClosed);
        List<Connection> pool = List.of(first, broken, second);
        List<Connection> opened = new ArrayList<>(), executedOn = new ArrayList<>();
        List<String> schemaChanges = new ArrayList<>();
        String[] schema = {"qa"};   // current schema of the open connection; a new connection starts on the default
        SQLActuator[] actuator = {null};
        actuator[0] = proxy(SQLActuator.class, (p, m, v) -> switch (m.getName()) {
            case "withConnection" -> actuator[0];
            case "getCurrentSchema" -> schema[0];
            case "changeSchema" -> {
                schemaChanges.add((String) v[0]);
                if (failRestore[0]) { failRestore[0] = false; throw new SQLException("restore failed"); }
                schema[0] = (String) v[0]; yield null;
            }
            case "parseSQL" -> SQLUtils.parseStatements(((SQL) v[0]).getSql(), DbType.postgresql).stream().map(Object::toString).toList();
            case "createPlan" -> {
                SQLExecutePlan plan = new SQLExecutePlan(((SQL) v[0]).getSql(), DbType.postgresql);
                plan.setConnection(opened.get(opened.size() - 1)); plan.setSqlActuator(actuator[0]); yield plan;
            }
            case "count" -> 1;
            case "executeWithAudit" -> {
                SQLExecutePlan plan = (SQLExecutePlan) v[0];
                executedOn.add(plan.getConnection());
                SQLQueryResult result = new SQLQueryResult(plan.getTargetSQL());
                result.setStartTime(new Time(0)); result.setQueryFinishedTime(new Time(1));
                result.setFetchFinishedTime(new Time(2)); result.setEndTime(new Time(2)); result.setTotal(1);
                yield result;
            }
            default -> null;
        });
        ConnectionManager manager = proxy(ConnectionManager.class, (p, m, v) -> switch (m.getName()) {
            case "getPhysicalConnection" -> {
                Connection c = pool.get(opened.size()); opened.add(c);
                if (c != first) schema[0] = "public";
                yield c;
            }
            case "getSqlActuator" -> actuator[0];
            default -> null;
        });
        Datasource ds = proxy(Datasource.class, (p, m, v) -> switch (m.getName()) {
            case "getConnectionManager" -> manager; case "getDruidDbType" -> DbType.postgresql; default -> null;
        });
        WebSocketSession ws = proxy(WebSocketSession.class, (p, m, v) -> null);
        QueryConsole console = new QueryConsole(ds, ws, "test");
        QueryConsoleState state = new QueryConsoleState("test"); state.setCurrentContext("qa");
        Field stateField = QueryConsole.class.getDeclaredField("stateManager"); stateField.setAccessible(true);
        stateField.set(console, new StateManager<>(state, new PacketIO(ws)));
        Method getConnection = QueryConsole.class.getDeclaredMethod("getConnection"); getConnection.setAccessible(true);
        try {
            console.onSQL("SELECT 1");
            require(opened.equals(List.of(first)) && executedOn.equals(List.of(first)), "first query must use the first connection");
            require(schemaChanges.isEmpty(), "an open connection must not be re-pointed");

            firstClosed[0] = true;   // server ended the session (pg_terminate_backend, failover)
            // The first replacement cannot be put back into the console's context: it must not be adopted.
            failRestore[0] = true;
            try {
                getConnection.invoke(console);
                throw new AssertionError("reconnect with a failed context restore succeeded");
            } catch (InvocationTargetException expected) { }
            require(brokenClosed[0], "connection with a failed context restore was not closed");
            require(executedOn.size() == 1, "nothing may run on a connection in the wrong context");
            Field viewsField = QueryConsole.class.getDeclaredField("dataViews"); viewsField.setAccessible(true);
            DataView view = ((Map<String, DataView>) viewsField.get(console)).values().iterator().next();
            view.refresh();
            require(opened.equals(List.of(first, broken, second)), "closed connection was not replaced: " + opened.size());
            require(schemaChanges.equals(List.of("qa", "qa")), "context not restored on the retry: " + schemaChanges);
            require(executedOn.size() == 2 && executedOn.get(1) == second, "reload still ran on the closed connection");

            console.onSQL("SELECT 2");
            require(opened.size() == 3 && executedOn.get(2) == second, "open replacement connection must be reused");

            console.close();
            secondClosed[0] = true;
            try {
                getConnection.invoke(console);
                throw new AssertionError("closed console reconnected");
            } catch (InvocationTargetException expected) { }
            require(opened.size() == 3, "closed console opened a new connection");
        } finally {
            SessionManager.unregisterSession(token);
            SessionManager.setContext(null);
        }
        System.out.println("OK: reconnect restores context before use, retries after a failed restore; reload uses it; no reconnect after close");
    }
}
