import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.datasource.entity.resource.Table;
import org.jumpserver.chen.modules.postgresql.PostgresqlActuator;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

public class TestResourceQueryBoundary {
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},h));}
    public static void main(String[] args)throws Exception{
        String value="x' OR 1=1; -- $1 ? \\";
        List<String> templates=new ArrayList<>();List<Object> values=new ArrayList<>();int[] query={0};
        ResultSet rs=proxy(ResultSet.class,(p,m,a)->m.getName().equals("next")?false:null);
        PreparedStatement statement=proxy(PreparedStatement.class,(p,m,a)->{
            if(m.getName().equals("setObject"))values.add(a[1]);
            if(m.getName().equals("executeQuery")){if(a!=null && a.length>0)throw new AssertionError("SQL interpolated");query[0]++;return rs;}
            if(m.getName().equals("execute")){if(a!=null && a.length>0)throw new AssertionError("SQL interpolated");query[0]++;return false;}
            if(m.getName().equals("getUpdateCount"))return 0;
            if(m.getReturnType()==boolean.class)return false;
            if(m.getReturnType()==int.class)return 0;
            return null;
        });
        Connection connection=proxy(Connection.class,(p,m,a)->{
            if(m.getName().equals("createStatement"))throw new AssertionError("bound query used raw Statement");
            if(m.getName().equals("prepareStatement")){templates.add((String)a[0]);return statement;}
            return null;
        });
        Datasource ds=proxy(Datasource.class,(p,m,a)->DbType.postgresql);
        ConnectionManager manager=proxy(ConnectionManager.class,(p,m,a)->m.getName().equals("getDatasource")?ds:connection);
        var actuator=new PostgresqlActuator(manager).withConnection(connection);
        String template="SELECT table_name FROM information_schema.tables WHERE table_schema = ?";
        actuator.getObjects(SQL.bound(template,value),Table.class,Map.of("name",1));
        actuator.execute(SQL.bound("SELECT ?",value));
        if(!templates.equals(List.of(template,"SELECT ?")) || !values.equals(List.of(value,value)) || query[0]!=2)
            throw new AssertionError("metadata parameters not bound once to unchanged template");
        List<SQL> lookups=new ArrayList<>();
        SQLActuator capture=proxy(SQLActuator.class,(p,m,a)->{
            if(m.getName().equals("getObjects")){
                if(!(a[0] instanceof SQL))throw new AssertionError("metadata lost parameter object");
                lookups.add((SQL)a[0]);return List.of();
            }
            return null;
        });
        ConnectionManager capturedManager=proxy(ConnectionManager.class,(p,m,a)->m.getName().equals("getSqlActuator")?capture:null);
        String[][] adapters={{"mysql","Mysql"},{"postgresql","Postgresql"},{"sqlserver","SQLServer"},
            {"oracle","Oracle"},{"db2","DB2"},{"dameng","DM"},{"clickhouse","Clickhouse"}};
        for(String[] adapter:adapters){
            String prefix="org.jumpserver.chen.modules."+adapter[0]+"."+adapter[1];
            var browser=(org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser)Class.forName(prefix+"ResourceBrowser")
                    .getConstructor(ConnectionManager.class).newInstance(capturedManager);
            browser.getTables(value);browser.getViews(value);browser.getFields(value,value);
        }
        if(lookups.size()!=21)throw new AssertionError("missing metadata adapter branches");
        for(SQL lookup:lookups){
            if(lookup.getSql().contains(value) || lookup.getParameters().isEmpty()
                    || !lookup.getParameters().stream().allMatch(value::equals))throw new AssertionError("unbound resource name");
        }
        System.out.println("OK: metadata lookup and property execution bind values with JDBC, including quotes/dollar/question/backslash");
    }
}
