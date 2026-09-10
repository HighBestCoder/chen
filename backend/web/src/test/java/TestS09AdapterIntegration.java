import com.alibaba.druid.DbType;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.base.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.datasource.entity.resource.*;
import org.jumpserver.chen.framework.datasource.entity.action.EventEmitter;
import org.jumpserver.chen.modules.clickhouse.*;
import org.jumpserver.chen.modules.mariadb.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Dedicated disposable databases only. Invoked by scripts/test-s09-adapters.sh. */
public class TestS09AdapterIntegration {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},h));}
    static SQL captured;
    public static void main(String[] args)throws Exception {
        for(String type:List.of("clickhouse","mariadb"))run(type);
    }
    static void run(String type)throws Exception {
        boolean ch=type.equals("clickhouse");
        String user=ch?"default":"root", password=ch?"":"s09-fixture-only";
        java.nio.file.Path jar;
        try(var files=java.nio.file.Files.list(java.nio.file.Path.of("drivers",type))){
            jar=files.filter(f->f.toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        try(var loader=new org.jumpserver.chen.framework.driver.DriverClassLoader(jar.getFileName().toString(),jar.toUri().toURL())) {
        org.jumpserver.chen.framework.driver.DriverManager.registerDriver(type,loader);
        DbType dbType=ch?DbType.clickhouse:DbType.mariadb;
        Datasource ds=proxy(Datasource.class,(p,m,a)->dbType);
        var info=new org.jumpserver.chen.framework.datasource.entity.DBConnectInfo();
        info.setDbType(type);info.setHost(ch?"s09-ch":"s09-maria");info.setPort(ch?8123:3306);
        info.setDb(ch?"default":"mysql");info.setUser(user);info.setPassword(password);
        ConnectionManager manager=ch?new ClickhouseConnectionManager(info,ds):new MariaDBConnectionManager(info,ds);
        try {
        manager.ping();
        try(var c=manager.getConnection();var st=c.createStatement()){
            for(String schema:List.of("S09_a","S09_b")){
                st.execute("CREATE DATABASE `"+schema+"`");
                st.execute("CREATE TABLE `"+schema+"`.`same_%` (id INT "+(ch?", required_value String, optional_value Nullable(String), nested Array(Nullable(String)), low LowCardinality(Nullable(String))) ENGINE=MergeTree ORDER BY id": "PRIMARY KEY, required_value VARCHAR(20) NOT NULL, optional_value VARCHAR(20) NULL)"));
                st.execute("CREATE VIEW `"+schema+"`.`only_view` AS SELECT id FROM `"+schema+"`.`same_%`");
            }
        }
        BaseResourceBrowser browser=ch?new ClickhouseResourceBrowser(manager):new MariaDBResourceBrowser(manager);
        for(String schema:List.of("S09_a","S09_b")){
            check(browser.getTables(schema).stream().anyMatch(t->t.getName().equals("same_%")),"missing table "+type);
            check(browser.getTables(schema).stream().noneMatch(t->t.getName().equals("only_view")),"view in tables "+type);
            check(browser.getViews(schema).stream().anyMatch(v->v.getName().equals("only_view")),"missing view "+type);
            var fields=browser.getFields(schema,"same_%");
            check(fields.size()==(ch?5:3),"field count "+type);
            var byName=new HashMap<String,org.jumpserver.chen.framework.datasource.entity.resource.Field>();
            fields.forEach(f->byName.put(f.getName(),f));
            check(!byName.get("required_value").isNullable() && byName.get("optional_value").isNullable(),"nullable "+type);
            check(byName.get("id").isPrimaryKey()!=ch,"primary key semantics "+type);
            check(schema.equals(byName.get("id").getSchema()),"schema identity "+type);
            if(ch){
                check(!byName.get("nested").isNullable() && byName.get("low").isNullable(),"nested nullability");
                var node=new TreeNode();node.setKey("datasource:x,schema:"+schema+",folder:tables,table:same_%");
                var handler=new ClickhouseActionHandler(){public EventEmitter onShowObjectProperties(String t,SQL sql,TreeNode n){captured=sql;return null;}};
                handler.onTableProperties(node);
                try(var c=manager.getConnection();var st=c.prepareStatement(captured.getSql())){
                    for(int i=0;i<captured.getParameters().size();i++)st.setObject(i+1,captured.getParameters().get(i));
                    try(var rows=st.executeQuery()){check(rows.next() && schema.equals(rows.getString("database")) && !rows.next(),"cross-schema properties");}
                }
                check(new ClickhouseSQLHintsHandler(manager).getAllFields(schema).stream().anyMatch(f->f.getTable().equals("same_%") && f.getName().equals("id")),"ClickHouse hints");
            }
        }
        System.out.println("PASS "+type+": real JDBC tables/views/literal name/nullable/keys/schema"+(ch?"/properties/hints":""));
        }finally{manager.close();}
        }
    }
}
