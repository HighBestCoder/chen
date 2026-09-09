import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.modules.sqlserver.SQLServerSQLHintsHandler;
import org.jumpserver.chen.modules.mysql.MysqlResourceBrowser;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.policy.*;
import java.lang.reflect.*;
import java.util.*;
public class TestRelationalMetadataBoundaries {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},h));}
    public static void main(String[] args)throws Exception {
        boolean[] enabled={false};int[] calls={0};
        var session=proxy(Session.class,(p,m,a)->m.getName().equals("enableAutoComplete")?enabled[0]:null);
        String token=SessionManager.registerSession(session);SessionManager.setContext(token);
        try {
            var hints=new SQLServerSQLHintsHandler(null) {
                public List<Schema> getAllSchemas(){calls[0]++;var a=new Schema();a.setName("a");var b=new Schema();b.setName("b");return List.of(a,b);}
                public List<Table> getAllTables(){var a=new Table();a.setName("same");a.setSchema("a");var b=new Table();b.setName("same");b.setSchema("b");return List.of(a,b);}
                public List<org.jumpserver.chen.framework.datasource.entity.resource.Field> getAllFields(){
                    var a=new org.jumpserver.chen.framework.datasource.entity.resource.Field();a.setName("only_a");a.setSchema("a");a.setTable("same");
                    var b=new org.jumpserver.chen.framework.datasource.entity.resource.Field();b.setName("only_b");b.setSchema("b");b.setTable("same");return List.of(a,b);
                }
            };
            require(hints.getHints("","").isEmpty() && calls[0]==0,"disabled autocomplete queried DB");
            enabled[0]=true;var values=hints.getHints("","");
            require(values.get("a.same").equals(List.of("only_a")) && values.get("b.same").equals(List.of("only_b")) && !values.containsKey("same"),"same-name hints merged schemas");
            var browser=new MysqlResourceBrowser(null){public List<TreeNode> getChildNodes(TreeNode node){return List.of();}};
            var root=new TreeNode();root.setKey("datasource:root");root.setChildren(List.of(new TreeNode()));
            var rootField=org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser.class.getDeclaredField("root");rootField.setAccessible(true);rootField.set(browser,root);
            browser.getChildren(root,false);require(browser.getChildren(root,true).isEmpty(),"empty refresh retained removed objects");
            require("SQL error near 100% and %s".equals(Message.error("error","SQL error near 100% and %s").getMessage()),"driver error treated as format string");
            var old=QueryPolicyHolder.current();
            try { var policy=new QueryPolicy();policy.setDefaultTimeoutSeconds(30);policy.setMaxTimeoutSeconds(5);QueryPolicyHolder.install(policy);
                require(policy.resolveTimeoutSeconds(0)==5 && new org.jumpserver.chen.framework.console.state.QueryConsoleState("test").getTimeout()==5,"default timeout bypassed hard maximum");
            }finally{QueryPolicyHolder.install(old);}
        }finally{SessionManager.unregisterSession(token);SessionManager.setContext(null);}
        System.out.println("OK: schema-safe hints, disabled completion, empty tree refresh, literal errors and timeout caps");
    }
}
