import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.script.SqlScriptParser;
import org.jumpserver.chen.modules.postgresql.PostgresqlActuator;
import org.jumpserver.chen.framework.utils.PageUtils;
import java.lang.reflect.*;
import java.util.*;
public class TestSqlTextFidelity {
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},h));}
    public static void main(String[] args)throws Exception{
        List<String> failures=new ArrayList<>();
        for(DbType type:List.of(DbType.postgresql,DbType.mysql,DbType.sqlserver)){
            Datasource ds=proxy(Datasource.class,(p,m,a)->type);
            ConnectionManager cm=proxy(ConnectionManager.class,(p,m,a)->m.getName().equals("getDatasource")?ds:null);
            var actuator=new PostgresqlActuator(cm);
            String ident=type==DbType.postgresql?"\"a\"\"b\"":type==DbType.mysql?"`a``b`":"[a]]b]";
            String value=type==DbType.postgresql?"E'a\\\\b'":type==DbType.mysql?"'a\\\\b'":"N'a\\b'";
            String sql="select "+ident+", "+value+" as v from fixture";
            try{
                if(!actuator.parseSQL(SQL.of(sql)).equals(List.of(sql)))failures.add(type+" execution original changed");
                var parsed=SqlScriptParser.parse(sql,type);
                if(!parsed.isOk() || !parsed.getStatements().get(0).getSql().equals(sql))failures.add(type+" script original changed");
                String limited=PageUtils.limit(sql,type,0,2);
                if(!limited.contains(ident) || !limited.contains(value))failures.add(type+" paging changed identifier/literal: "+limited);
            }catch(Exception e){failures.add(type+" "+e.getClass().getSimpleName());}
        }
        for(DbType type:List.of(DbType.postgresql,DbType.mysql,DbType.sqlserver)) {
            List<String> originals=List.of("SELECT 'a;''b' AS value", "SELECT '__chen_original_0' AS collision", "SELECT 3 /* ; 'quoted' */");
            String script=String.join("; ", originals)+";";
            try {
                var split=org.jumpserver.chen.framework.utils.SqlText.statements(script,type);
                if(!split.equals(originals))failures.add(type+" quoted/comment semicolon split: "+split);
            }catch(Exception e){failures.add(type+" split: "+e.getMessage());}
        }
        try {
            String dollar="SELECT $tag$a; 'b'\\c$tag$ AS value";
            var split=org.jumpserver.chen.framework.utils.SqlText.statements(dollar+"; SELECT 2",DbType.postgresql);
            if(!split.equals(List.of(dollar,"SELECT 2")))failures.add("dollar split changed");
            if(!PageUtils.limit(dollar,DbType.postgresql,0,1).contains("$tag$a; 'b'\\c$tag$"))failures.add("dollar literal changed");
        }catch(Exception e){failures.add("dollar: "+e.getMessage());}
        var commented=SqlScriptParser.parse("# mysql comment\nSELECT 1",DbType.mysql);
        if(!commented.isOk() || !commented.getStatements().get(0).getLeadingKeyword().equals("SELECT"))failures.add("MySQL comment hides script keyword");
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("OK: raw execution/script SQL and paged identifiers/literals retain original semantics");
    }
}
