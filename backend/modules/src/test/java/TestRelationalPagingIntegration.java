import org.jumpserver.chen.framework.datasource.sql.*;
import java.util.*;
public class TestRelationalPagingIntegration extends TestConnectionTlsIntegration {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run(String type)throws Exception {
        var manager=manager(info(type));
        try {
            manager.ping();
            try(var c=manager.getConnection();var st=c.createStatement()) {
                st.execute("CREATE TABLE s04_pages (id INT PRIMARY KEY)");
                st.execute("INSERT INTO s04_pages VALUES (1),(2),(3),(4)");
            }
            for(String sql:List.of("SELECT id FROM s04_pages ORDER BY id", "SELECT DISTINCT id FROM s04_pages ORDER BY id", "WITH source AS (SELECT id FROM s04_pages) SELECT id FROM source ORDER BY id", "SELECT id FROM s04_pages WHERE id <= 2 UNION ALL SELECT id FROM s04_pages WHERE id > 2 ORDER BY id")) {
                for(int offset:List.of(0,2)) {
                    var params=new SQLQueryParams();params.setLimit(2);params.setOffset(offset);
                    var result=manager.getSqlActuator().createPlan(SQL.of(sql),params).execute();
                    require(result.getFields().size()==1,"paging added synthetic column");
                    require(result.getData().size()==2,"page row count");
                    for(int i=0;i<2;i++)require(((Number)result.getData().get(i).get(0)).intValue()==offset+i+1,"paging changed order/rows");
                }
            }
            if(!type.equals("mysql")) {
                String sql="SELECT id INTO s04_copied FROM s04_pages";
                var params=new SQLQueryParams();params.setLimit(1);
                manager.getSqlActuator().createPlan(SQL.of(sql),params).execute();
                try(var c=manager.getConnection();var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM s04_copied")) {
                    rs.next();require(rs.getInt(1)==4,"toolbar limit silently truncated SELECT INTO writes");
                }
            }
            var actuator=manager.getSqlActuator();
            require(actuator.execute(SQL.of("INSERT INTO s04_pages VALUES (5)")).getUpdateCount()==1,"insert count");
            require(actuator.execute(SQL.of("UPDATE s04_pages SET id=6 WHERE id=5")).getUpdateCount()==1,"update count");
            require(actuator.execute(SQL.of("DELETE FROM s04_pages WHERE id=6")).getUpdateCount()==1,"delete count");
            if(!type.equals("postgresql")) {
                try(var c=manager.getConnection();var st=c.createStatement()) {
                    st.execute(type.equals("mysql")?"CREATE PROCEDURE s04_multi() BEGIN SELECT 1 AS a; SELECT 2 AS b; END"
                            :"CREATE PROCEDURE s04_multi AS SELECT 1 AS a; SELECT 2 AS b;");
                }
                try { actuator.execute(SQL.of(type.equals("mysql")?"CALL s04_multi()":"EXEC s04_multi"));
                    throw new AssertionError("multiple result sets silently accepted");
                } catch(java.sql.SQLException expected) { if(!expected.getMessage().contains("multiple results")) expected.printStackTrace(); require(expected.getMessage().contains("multiple results"),"unexpected multi-result error: "+expected.getMessage()); }
            }
            System.out.println("PASS "+type+" ordered pages/UNION preserve rows and shape; SELECT INTO retains all writes where supported");
        } finally {manager.close();}
    }
    public static void main(String[] args)throws Exception {
        register("postgresql","drivers/postgresql/postgresql-42.6.0.jar");
        register("mysql","drivers/mysql/mysql-connector-java-8.0.30.jar");
        register("sqlserver","drivers/sqlserver/mssql-jdbc-12.8.1.jre11.jar");
        List<String> failures=new ArrayList<>();
        for(String type:List.of("postgresql","mysql","sqlserver"))try{run(type);}catch(Throwable e){failures.add(type+": "+e.getMessage());}
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("S04 paging: 24 page cases, 2 SELECT INTO, 9 DML and 2 explicit multi-result errors passed");
    }
}
