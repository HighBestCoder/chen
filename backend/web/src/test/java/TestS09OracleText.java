import com.alibaba.druid.DbType;
import org.jumpserver.chen.modules.oracle.OracleActuator;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.List;
public class TestS09OracleText {
    static <T>T proxy(Class<T> type,InvocationHandler h){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h));}
    public static void main(String[] args)throws Exception {
        Datasource ds=proxy(Datasource.class,(p,m,a)->DbType.oracle);
        Connection connection=proxy(Connection.class,(p,m,a)->m.getName().equals("isClosed")?false:null);
        ConnectionManager manager=proxy(ConnectionManager.class,(p,m,a)->switch(m.getName()){case "getDatasource"->ds;case "getConnection"->connection;default->null;});
        OracleActuator actuator=new OracleActuator(manager);
        for(String text:List.of("BEGIN NULL; END;","DECLARE n NUMBER; BEGIN n := 1; END;","CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;","CREATE OR REPLACE FUNCTION f RETURN NUMBER AS BEGIN RETURN 1; END;")){
            SQL sql=SQL.of(text);
            if(!actuator.parseSQL(sql).equals(List.of(text)))throw new AssertionError("PL/SQL split lost terminator");
            var plan=actuator.createPlan(sql);
        try{
                if(!text.equals(sql.getSql()) || !text.equals(plan.getTargetSQL()))throw new AssertionError("PL/SQL terminator removed: "+text);
            }finally{plan.close();}
        }
        SQL sql=SQL.bound("SELECT ? FROM DUAL;  ",7);
        var plan=actuator.createPlan(sql);
        try{
            if(!sql.getSql().endsWith(";  ") || !plan.getTargetSQL().equals("SELECT ? FROM DUAL") || !plan.getParameters().equals(List.of(7)))throw new AssertionError("ordinary SQL normalization/parameters/source");
        }finally{plan.close();}
        System.out.println("OK: Oracle anonymous blocks, procedures/functions, ordinary terminators and input preservation");
    }
}
