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
                System.out.println("PASS "+type+" exact decimal and microsecond timestamp");
            }finally{cm.close();}
        }
    }
}
