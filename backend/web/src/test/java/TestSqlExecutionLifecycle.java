import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.modules.postgresql.PostgresqlActuator;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

public class TestSqlExecutionLifecycle {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},h));}
    static Object zero(Method m){return m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?0:null;}
    static void transaction(boolean auto, String fail)throws Exception{
        List<String> events=new ArrayList<>();
        Statement st=proxy(Statement.class,(p,m,a)->{
            events.add(m.getName());
            if(m.getName().equals("getUpdateCount"))return -1;
            if(m.getName().equals("execute") && fail.equals("execute"))throw new SQLException("execute failed");
            return zero(m);
        });
        Connection c=proxy(Connection.class,(p,m,a)->{
            if(m.getName().equals("getAutoCommit"))return auto;
            if(m.getName().equals("createStatement"))return st;
            events.add(m.getName()+(m.getName().equals("setAutoCommit")?a[0]:""));
            if(m.getName().equals(fail))throw new SQLException(fail+" failed");
            if(fail.equals("rollback") && m.getName().equals("commit"))throw new SQLException("commit failed");
            return zero(m);
        });
        Datasource ds=proxy(Datasource.class,(p,m,a)->DbType.postgresql);
        ConnectionManager cm=proxy(ConnectionManager.class,(p,m,a)->m.getName().equals("getDatasource")?ds:c);
        var plan=new PostgresqlActuator(cm).withConnection(c).createPlan(SQL.of("SELECT 1"));
        plan.setRowConsumer(new RowConsumer(){public void begin(List<org.jumpserver.chen.framework.datasource.entity.resource.Field> f){} public void accept(List<Object> row){}});
        boolean failed=false;
        try { plan.execute(); } catch(SQLException expected){failed=true;}
        require(failed==(!fail.isEmpty() && (auto || fail.equals("execute"))),"failure lost: "+events);
        if(!auto)require(!events.contains("commit") && !events.contains("rollback") && !events.contains("setAutoCommittrue"),"caller transaction modified");
        else {
            require(events.contains("setAutoCommitfalse"),"stream transaction not started");
            require(events.contains("rollback")==!fail.isEmpty(),"rollback boundary: "+events);
            require(events.contains("setAutoCommittrue")==!fail.equals("rollback"),"unsafe autoCommit restoration: "+events);
            if(fail.equals("execute"))require(!events.contains("commit"),"failure committed");
        }
    }
    public static void main(String[] args)throws Exception{
        for(boolean auto:List.of(true,false))for(String failure:List.of("","execute","commit","rollback"))transaction(auto,failure);
        require(!new SQLExecutePlan("INSERT INTO fixture VALUES (1) RETURNING id",DbType.postgresql).isReloadableResult(),"write result can be replayed by refresh/export");
        require(new SQLExecutePlan("SELECT id FROM fixture",DbType.postgresql).isReloadableResult(),"SELECT result cannot refresh");
        var plan=new SQLExecutePlan("SELECT 1",DbType.postgresql);
        int[] created={0},cancelled={0},closed={0};
        Statement st=proxy(Statement.class,(p,m,a)->{if(m.getName().equals("cancel"))cancelled[0]++;if(m.getName().equals("close"))closed[0]++;return zero(m);});
        plan.setConnection(proxy(Connection.class,(p,m,a)->{if(m.getName().equals("createStatement")){created[0]++;return st;}return zero(m);}));
        plan.cancel();
        try{plan.createStatement();throw new AssertionError("pre-create cancellation ignored");}catch(SQLException expected){require("57014".equals(expected.getSQLState()),"cancel state");}
        require(created[0]==0,"cancelled request acquired statement");
        plan.beginExecution();plan.createStatement();plan.cancel();require(cancelled[0]==1,"active statement not cancelled");
        try{plan.createStatement();throw new AssertionError("cancelled stage advanced");}catch(SQLException expected){}
        require(closed[0]==1,"cancelled statement leaked");
        System.out.println("OK: transaction ownership/failure propagation/rollback failure and cancellation stage boundaries");
    }
}
