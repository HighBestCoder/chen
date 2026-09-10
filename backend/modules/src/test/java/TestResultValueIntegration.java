import org.jumpserver.chen.framework.datasource.sql.SQL;
import java.util.*;
/** Three real JDBC drivers: decimal and timestamp conversion before UI/CSV. */
public class TestResultValueIntegration extends TestConnectionTlsIntegration {
    public static void main(String[] args)throws Exception {
        register("postgresql","drivers/postgresql/postgresql-42.6.0.jar");register("mysql","drivers/mysql/mysql-connector-java-8.0.30.jar");register("sqlserver","drivers/sqlserver/mssql-jdbc-12.8.1.jre11.jar");
        for(String type:List.of("postgresql","mysql","sqlserver")) {
            var cm=manager(info(type));
            try {
                String timestamp=type.equals("sqlserver")?"datetime2(6)":type.equals("mysql")?"datetime(6)":"timestamp(6)";
                var r=cm.getSqlActuator().execute(SQL.of("SELECT CAST(9007199254740993.123456 AS DECIMAL(24,6)) AS amount, CAST('2026-09-09 12:34:56.123456' AS "+timestamp+") AS stamp"));
                var row=r.getData().get(0);
                if(!row.equals(List.of("9007199254740993.123456","2026-09-09 12:34:56.123456")))throw new AssertionError(type+" lost value precision: "+row);
                String left=type.equals("mysql")?"`":type.equals("sqlserver")?"[":"\"";
                String right=type.equals("sqlserver")?"]":left;
                var sized=cm.getSqlActuator().execute(SQL.of("SELECT "+(type.equals("sqlserver")?"N'中'":"'中'")+" AS "+left+"a.b"+right+", 'xx' AS b, NULL AS empty_value"));
                var audit=org.jumpserver.chen.framework.audit.SqlExecutionStatsBuilder.fromSuccess(null,"SELECT fixture",sized);
                if(!Long.valueOf(5).equals(audit.getSizeBytes()))throw new AssertionError(type+" UTF-8 size != 5: "+audit.getSizeBytes());
                if(!sized.getStreamedSizeByColumn().equals(Map.of("unknown.a.b",3L,"unknown.b",2L,"unknown.empty_value",0L)))throw new AssertionError(type+" column stats "+sized.getStreamedSizeByColumn());
                System.out.println("PASS "+type+" exact decimal, microsecond, independent UTF-8 size and dotted/null column stats");
            }finally{cm.close();}
        }
    }
}
