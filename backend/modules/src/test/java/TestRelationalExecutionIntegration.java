import org.jumpserver.chen.framework.datasource.base.BaseResourceBrowser;
import org.jumpserver.chen.framework.datasource.ConnectionManager;
import org.jumpserver.chen.modules.postgresql.PostgresqlResourceBrowser;
import org.jumpserver.chen.modules.mysql.MysqlResourceBrowser;
import org.jumpserver.chen.modules.sqlserver.SQLServerResourceBrowser;
import java.util.*;
public class TestRelationalExecutionIntegration extends TestConnectionTlsIntegration {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static BaseResourceBrowser browser(String type,ConnectionManager manager){return switch(type){
        case "postgresql"->new PostgresqlResourceBrowser(manager);case "mysql"->new MysqlResourceBrowser(manager);
        default->new SQLServerResourceBrowser(manager);
    };}
    static void fields(String type)throws Exception{
        var manager=manager(info(type));String schema=type.equals("postgresql")?"public":type.equals("mysql")?"fixture":"dbo";
        try{
            manager.ping();
            try(var c=manager.getConnection();var s=c.createStatement()){
                s.execute("CREATE TABLE s04_fields (id INT PRIMARY KEY, required_value INT NOT NULL, optional_value VARCHAR(20) NULL)");
                s.execute("CREATE TABLE "+quote(type,"s04_%[,]:meta")+" (exact_column INT)");
            }
            var fields=browser(type,manager).getFields(schema,"s04_fields");
            require(fields.size()==3,"column count");
            var map=new HashMap<String,org.jumpserver.chen.framework.datasource.entity.resource.Field>();
            for(var f:fields)map.put(f.getName(),f);
            require(!map.get("required_value").isNullable(),"NOT NULL reported nullable");
            require(map.get("optional_value").isNullable(),"nullable column reported NOT NULL");
            require(map.get("id").isPrimaryKey(),"primary key missing");
            require(map.get("id").getType()!=null && map.get("id").getType().toLowerCase().contains("int"),"integer type missing");
            var exact=browser(type,manager).getFields(schema,"s04_%[,]:meta");
            require(exact.size()==1 && exact.get(0).getName().equals("exact_column"),"metadata wildcard name not matched literally");
            System.out.println("PASS "+type+" field names/type/nullability/primary key and literal metadata patterns");
        }finally{manager.close();}
    }
    static void fidelity(String type)throws Exception{
        var manager=manager(info(type));
        String name=type.equals("postgresql")?"a\"b":type.equals("mysql")?"a`b":"a]b";
        String identifier=quote(type,name);
        String value="a'b\\c";
        String literal="'"+value.replace("'","''").replace("\\",type.equals("mysql")?"\\\\":"\\")+"'";
        if(type.equals("sqlserver"))literal="N"+literal;
        try{
            manager.ping();
            try(var c=manager.getConnection();var st=c.createStatement()){
                st.execute("CREATE TABLE s04_text ("+identifier+" INT)");
                st.execute("INSERT INTO s04_text VALUES (7), (8)");
            }
            var actuator=manager.getSqlActuator();
            String sql="SELECT "+identifier+", "+literal+" AS value FROM s04_text ORDER BY "+identifier;
            var split=actuator.parseSQL(org.jumpserver.chen.framework.datasource.sql.SQL.of(sql));
            require(split.size()==1 && sql.equals(split.get(0)),"execution text changed");
            var params=new org.jumpserver.chen.framework.datasource.sql.SQLQueryParams();params.setLimit(1);
            var plan=actuator.createPlan(org.jumpserver.chen.framework.datasource.sql.SQL.of(split.get(0)),params);
            var result=plan.execute();
            require(result.getData().size()==1,"limit not enforced");
            require(((Number)result.getData().get(0).get(0)).intValue()==7,"identifier changed");
            require(value.equals(result.getData().get(0).get(1)),"literal value changed");
            System.out.println("PASS "+type+" raw execution and paging preserve real identifier/literal values");
        }finally{manager.close();}
    }
    static void sideEffects()throws Exception{
        var manager=manager(info("postgresql"));
        try{
            manager.ping();
            try(var c=manager.getConnection();var st=c.createStatement()){
                st.execute("CREATE SEQUENCE s04_sequence START 1");
                st.execute("CREATE TABLE s04_rows (id INT)");st.execute("INSERT INTO s04_rows VALUES (1),(2)");
            }
            var params=new org.jumpserver.chen.framework.datasource.sql.SQLQueryParams();params.setLimit(10);
            var plan=manager.getSqlActuator().createPlan(org.jumpserver.chen.framework.datasource.sql.SQL.of(
                    "SELECT id FROM s04_rows WHERE nextval('s04_sequence') > 0"),params);
            var result=plan.execute();require(result.getData().size()==2,"side effect query rows");
            try(var c=manager.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT last_value FROM s04_sequence")){
                rows.next();require(rows.getLong(1)==2,"automatic count repeated nextval: "+rows.getLong(1));
            }
            System.out.println("PASS PostgreSQL SELECT function runs only in requested query, never automatic count");
        }finally{manager.close();}
    }
    static void exportTransaction()throws Exception{
        var manager=manager(info("postgresql"));
        try{
            manager.ping();
            try(var c=manager.getConnection();var st=c.createStatement()){
                st.execute("CREATE TABLE s04_export (value INT)");
                st.execute("CREATE FUNCTION s04_export_write() RETURNS INT LANGUAGE plpgsql AS $$ BEGIN INSERT INTO s04_export VALUES (1); RETURN 1; END $$");
            }
            try(var connection=manager.getPhysicalConnection()){
                var actuator=manager.getSqlActuator().withConnection(connection);
                var plan=actuator.createPlan(org.jumpserver.chen.framework.datasource.sql.SQL.of("SELECT s04_export_write()"));
                plan.setRowConsumer(new org.jumpserver.chen.framework.datasource.sql.RowConsumer(){
                    public void begin(List<org.jumpserver.chen.framework.datasource.entity.resource.Field> fields){}
                    public void accept(List<Object> row)throws java.sql.SQLException{throw new java.sql.SQLException("fixture disk full");}
                });
                try{plan.execute();throw new AssertionError("sink failure ignored");}catch(java.sql.SQLException expected){}
                require(connection.getAutoCommit(),"autocommit not restored");
            }
            try(var c=manager.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM s04_export")){
                rows.next();require(rows.getInt(1)==0,"failed export committed function writes");
            }
            try(var connection=manager.getPhysicalConnection()) {
                var actuator=manager.getSqlActuator().withConnection(connection);
                var sink=new org.jumpserver.chen.framework.datasource.sql.RowConsumer(){
                    public void begin(List<org.jumpserver.chen.framework.datasource.entity.resource.Field> fields){}
                    public void accept(List<Object> row){}
                };
                var success=actuator.createPlan(org.jumpserver.chen.framework.datasource.sql.SQL.of("SELECT s04_export_write()"));
                success.setRowConsumer(sink);success.execute();require(connection.getAutoCommit(),"success autocommit not restored");
                connection.setAutoCommit(false);
                var borrowed=actuator.createPlan(org.jumpserver.chen.framework.datasource.sql.SQL.of("SELECT s04_export_write()"));
                borrowed.setRowConsumer(sink);borrowed.execute();require(!connection.getAutoCommit(),"caller transaction changed");
                connection.rollback();
            }
            try(var c=manager.getConnection();var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*) FROM s04_export")) {
                rows.next();require(rows.getInt(1)==1,"successful stream did not commit or caller transaction was committed");
            }
            System.out.println("PASS stream failure rollback, success commit, and caller transaction ownership");
        }finally{manager.close();}
    }
    static void properties(String type)throws Exception {
        var manager=manager(info(type));
        var datasource=(org.jumpserver.chen.framework.datasource.Datasource)java.lang.reflect.Proxy.newProxyInstance(
                org.jumpserver.chen.framework.datasource.Datasource.class.getClassLoader(),
                new Class[]{org.jumpserver.chen.framework.datasource.Datasource.class},
                (p,m,a)->m.getName().equals("getConnectionManager")?manager:null);
        try {
            manager.ping();
            try(var c=manager.getConnection();var st=c.createStatement()) {
                st.execute("CREATE SCHEMA s04_other");
                st.execute("CREATE TABLE s04_other.s04_fields (other_column INT)");
            }
            var node=new org.jumpserver.chen.framework.datasource.entity.resource.TreeNode();
            node.setKey("schema:s04_other,table:s04_fields");node.setType("table");
            org.jumpserver.chen.framework.datasource.entity.action.EventEmitter event;
            if(type.equals("mysql"))event=new org.jumpserver.chen.modules.mysql.MysqlActionHandler(){
                public org.jumpserver.chen.framework.datasource.Datasource getDatasource(){return datasource;}
            }.onTableProperties(node);
            else event=new org.jumpserver.chen.modules.postgresql.PostgresqlActionHandler(){
                public org.jumpserver.chen.framework.datasource.Datasource getDatasource(){return datasource;}
            }.onTableProperties(node);
            var dialog=(org.jumpserver.chen.framework.datasource.entity.dialog.detail.DetailDialog)event.getData();
            require(dialog.getItems().stream().anyMatch(item->"table_schema".equalsIgnoreCase(item.getName())
                    && "s04_other".equals(item.getValue())),"same-name table properties used another schema");
            System.out.println("PASS "+type+" properties isolate same-name tables by schema");
        }finally{manager.close();}
    }

    static void primaryKeyScope()throws Exception {
        var manager=manager(info("postgresql"));
        try {
            manager.ping();
            try(var c=manager.getConnection();var st=c.createStatement()) {
                st.execute("CREATE TABLE public.s04_keys (id INT PRIMARY KEY)");
                st.execute("CREATE TABLE s04_other.s04_keys (id INT)");
                st.execute("INSERT INTO public.s04_keys VALUES (1)");st.execute("INSERT INTO s04_other.s04_keys VALUES (1)");
            }
            var result=manager.getSqlActuator().execute(org.jumpserver.chen.framework.datasource.sql.SQL.of(
                    "SELECT a.id AS primary_alias, b.id AS optional_alias FROM public.s04_keys a JOIN s04_other.s04_keys b ON a.id=b.id"));
            require(result.getFields().get(0).isPrimaryKey() && !result.getFields().get(1).isPrimaryKey(),"same-name table PK mismatch: "+result.getFields());
            System.out.println("PASS joined same-name tables retain distinct primary-key metadata");
        }finally{manager.close();}
    }

    public static void main(String[] args)throws Exception{
        register("postgresql","drivers/postgresql/postgresql-42.7.13.jar");
        register("mysql","drivers/mysql/mysql-connector-java-8.0.30.jar");
        register("sqlserver","drivers/sqlserver/mssql-jdbc-12.8.1.jre11.jar");
        List<String> failures=new ArrayList<>();
        for(String type:List.of("postgresql","mysql","sqlserver")){
            try{fields(type);}catch(Throwable e){failures.add(type+" fields: "+e.getMessage());}
            try{fidelity(type);}catch(Throwable e){failures.add(type+" fidelity: "+e.getMessage());}
        }
        for(String type:List.of("postgresql","mysql")) {
            try{properties(type);}catch(Throwable e){failures.add(type+" properties: "+e.getMessage());}
        }
        try{primaryKeyScope();}catch(Throwable e){failures.add("PK scope: "+e.getMessage());}
        try{sideEffects();}catch(Throwable e){failures.add("count: "+e.getMessage());}
        try{exportTransaction();}catch(Throwable e){failures.add("transaction: "+e.getMessage());}
        if(!failures.isEmpty())throw new AssertionError(String.join("; ",failures));
        System.out.println("S04 real-driver cases: 11 passed (stream case includes rollback/commit/caller ownership)");
    }
}
