import org.jumpserver.chen.modules.postgresql.PostgresqlActuator;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import com.alibaba.druid.DbType;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Guards the old estimator itself, including accidental future callers. */
public class TestApprovalEstimate {
    static <T>T proxy(Class<T> type,InvocationHandler h){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h));}
    public static void main(String[] args)throws Exception{
        List<String> effects=new ArrayList<>();
        Statement statement=proxy(Statement.class,(p,m,a)->{effects.add(m.getName());return m.getReturnType()==boolean.class?false:m.getReturnType()==int.class?1:null;});
        Connection connection=proxy(Connection.class,(p,m,a)->{
            effects.add(m.getName());return m.getName().equals("createStatement")?statement:m.getReturnType()==boolean.class?false:null;
        });
        Datasource ds=proxy(Datasource.class,(p,m,a)->DbType.postgresql);
        ConnectionManager manager=proxy(ConnectionManager.class,(p,m,a)->m.getName().equals("getDatasource")?ds:connection);
        var actuator=new PostgresqlActuator(manager).withConnection(connection);
        int estimate=actuator.getAffectedRows(SQL.of("INSERT INTO fixture(value) VALUES (1)"));
        if(estimate!=-1 || !effects.isEmpty())throw new AssertionError("Approval estimator touched transaction/DB: "+effects);
        System.out.println("OK: affected-row estimation leaves existing transaction and DB untouched");
    }
}
