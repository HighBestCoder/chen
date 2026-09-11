import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.base.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.datasource.entity.resource.TreeNode;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.modules.oracle.*;
import org.jumpserver.chen.modules.db2.*;
import org.jumpserver.chen.modules.dameng.*;
import org.jumpserver.chen.modules.clickhouse.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

public class TestS09Adapters {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler));}
    static SQL captured;
    static EventEmitter capture(SQL sql){captured=sql;return null;}
    public static void main(String[] args)throws Exception {
        if(args.length>0 && args[0].equals("properties")){properties();return;}
        for(DbType type:List.of(DbType.oracle,DbType.db2,DbType.dm))metadata(type);
        properties();
        System.out.println("OK: Oracle/DB2/DM JDBC columns and cleanup; four schema-bound properties");
    }
    static void metadata(DbType type)throws Exception {
        int[] closed={0},calls={0};
        DatabaseMetaData md=proxy(DatabaseMetaData.class,(p,m,a)->{
            if(m.getName().equals("getSearchStringEscape"))return "\\";
            if(m.getName().equals("getPrimaryKeys") || m.getName().equals("getColumns")){
                boolean cols=m.getName().equals("getColumns");
                check((cols?"S\\_\\%":"S_%").equals(a[1]),"schema pattern");
                check((cols?"T\\_\\%":"T_%").equals(a[2]),"table pattern");
                calls[0]++;int[] row={0};
                return proxy(ResultSet.class,(rp,rm,ra)->switch(rm.getName()){
                    case "next" -> ++row[0]<=(cols?2:1);
                    case "getString" -> ra[0].equals("COLUMN_NAME")?(row[0]==1?"id":"value"):"VARCHAR";
                    case "getInt" -> row[0]==1?DatabaseMetaData.columnNoNulls:DatabaseMetaData.columnNullable;
                    case "close" -> {closed[0]++;yield null;}
                    default -> throw new AssertionError(rm.getName());
                });
            }
            throw new AssertionError(m.getName());
        });
        Connection con=proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getMetaData" -> md;case "getCatalog" -> null;case "close" -> {closed[0]++;yield null;}
            default -> throw new AssertionError(m.getName());
        });
        SQLActuator actuator=proxy(SQLActuator.class,(p,m,a)->{if(m.getName().equals("getDbType"))return type;throw new AssertionError("unexpected legacy SQL: "+m.getName());});
        Datasource ds=proxy(Datasource.class,(p,m,a)->type);
        ConnectionManager manager=proxy(ConnectionManager.class,(p,m,a)->switch(m.getName()){
            case "getConnection" -> con;case "getSqlActuator" -> actuator;case "getDatasource" -> ds;default -> null;
        });
        BaseResourceBrowser browser=switch(type){case oracle->new OracleResourceBrowser(manager);case db2->new DB2ResourceBrowser(manager);default->new DMResourceBrowser(manager);};
        var fields=browser.getFields("S_%","T_%");
        check(fields.size()==2 && !fields.get(0).isNullable() && fields.get(0).isPrimaryKey() && fields.get(1).isNullable(),"column attributes");
        check("S_%".equals(fields.get(1).getSchema()) && "T_%".equals(fields.get(1).getTable()),"column identity");
        check(calls[0]==2 && closed[0]==3,"JDBC resources leaked");
    }
    static void properties()throws Exception {
        TreeNode node=new TreeNode();node.setKey("datasource:x,schema:s'one,folder:tables,table:same");
        List<BaseActionHandler> handlers=List.of(
            new OracleActionHandler(){public EventEmitter onShowObjectProperties(String type,SQL sql,TreeNode n){return capture(sql);}},
            new DB2ActionHandler(){public EventEmitter onShowObjectProperties(String type,SQL sql,TreeNode n){return capture(sql);}},
            new DMActionHandler(){public EventEmitter onShowObjectProperties(String type,SQL sql,TreeNode n){return capture(sql);}},
            new ClickhouseActionHandler(){public EventEmitter onShowObjectProperties(String type,SQL sql,TreeNode n){return capture(sql);}}
        );
        var schemaHandler=new DMActionHandler(){public EventEmitter onShowObjectProperties(String type,SQL sql,TreeNode n){return capture(sql);}};
        schemaHandler.onSchemaProperties(node);
        check(captured.getParameters().equals(List.of("s'one")) && captured.getSql().contains("SYSOBJECTS"), "DM schema properties must use schema catalog");
        for(var handler:handlers){
            handler.getClass().getMethod("onTableProperties",TreeNode.class).invoke(handler,node);
            check(captured.getParameters().equals(List.of("s'one","same")),"properties missing schema: "+handler.getClass());
            check(!captured.getSql().contains("s'one"),"interpolated schema");
        }
    }
}
