import org.jumpserver.chen.framework.datasource.error.*;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.*;
import org.bson.Document;
import java.sql.SQLException;
import java.util.List;

/** Local database roles exercise authorization, not Azure token issuance. */
public class TestU05PermissionIntegration extends TestConnectionTlsIntegration {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void relational(String type)throws Exception {
        var admin=manager(info(type));var readonly=info(type);readonly.setUser("u05_reader");readonly.setPassword("FixtureReader9!");
        if(type.equals("postgresql")) {
            readonly.getOptions().put("clientCert",pem("u05-reader.crt")+pem("ca.crt"));
            readonly.getOptions().put("clientKey",pem("u05-reader.key"));
        }
        var reader=manager(readonly);
        try {
            try(var connection=admin.getConnection();var statement=connection.createStatement()) {
                statement.execute("CREATE TABLE u05_rows (id INT)");
                switch(type) {
                    case "postgresql" -> {
                        statement.execute("CREATE ROLE u05_reader LOGIN PASSWORD 'FixtureReader9!'");
                        statement.execute("GRANT USAGE ON SCHEMA public TO u05_reader");
                    }
                    case "mysql" -> statement.execute("CREATE USER 'u05_reader'@'%' IDENTIFIED BY 'FixtureReader9!'");
                    default -> {
                        statement.execute("CREATE LOGIN u05_reader WITH PASSWORD='FixtureReader9!', CHECK_POLICY=OFF");
                        statement.execute("CREATE USER u05_reader FOR LOGIN u05_reader");
                    }
                }
                statement.execute("GRANT SELECT ON u05_rows TO "+(type.equals("mysql")?"'u05_reader'@'%'":"u05_reader"));
            }
            var actuator=reader.getSqlActuator();
            require(actuator.createPlan(SQL.of("SELECT * FROM u05_rows")).execute().getData().isEmpty(),type+" allowed read rejected");
            deniedInsert(reader,type);
            try(var connection=admin.getConnection();var statement=connection.createStatement()) {
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM u05_rows")){rows.next();require(rows.getInt(1)==0,type+" denied insert changed table");}
                statement.execute("GRANT INSERT ON u05_rows TO "+(type.equals("mysql")?"'u05_reader'@'%'":"u05_reader"));
            }
            actuator.createPlan(SQL.of("INSERT INTO u05_rows VALUES (1)")).execute();
            try(var connection=admin.getConnection();var statement=connection.createStatement()) {
                statement.execute("REVOKE INSERT ON u05_rows FROM "+(type.equals("mysql")?"'u05_reader'@'%'":"u05_reader"));
            }
            deniedInsert(reader,type);
            try(var connection=admin.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM u05_rows")) {
                rows.next();require(rows.getInt(1)==1,type+" revoked insert changed table or allowed insert failed");
            }
            System.out.println("PASS U05 "+type+": read allowed, read-only INSERT zero writes, grant INSERT succeeds, revoke INSERT zero additional writes");
        } finally {reader.close();admin.close();}
    }
    static void deniedInsert(org.jumpserver.chen.framework.datasource.ConnectionManager manager,String type)throws Exception {
        try {manager.getSqlActuator().createPlan(SQL.of("INSERT INTO u05_rows VALUES (2)")).execute();throw new AssertionError(type+" unauthorized insert succeeded");}
        catch(SQLException expected){require(SqlPermissionErrorClassifier.isPermissionDenied(expected),type+" denial not classified");}
    }
    static void mongo()throws Exception {
        var admin=(MongoConnectionManager)manager(info("mongodb"));String db="u05_permissions";
        try {
            admin.getDatabase(db).createCollection("rows");
            admin.getDatabase("admin").runCommand(new Document("createUser","u05_reader").append("pwd","FixtureReader9!").append("roles",List.of(new Document("role","read").append("db",db))));
            var readonly=info("mongodb");readonly.setUser("u05_reader");readonly.setPassword("FixtureReader9!");
            var reader=(MongoConnectionManager)manager(readonly);reader.setDatabaseContext(db);
            try {
                var actuator=new MongoActuator(reader);var parser=new MongoCommandParser();
                require(actuator.execute(parser.parse("db.rows.find({})"),0,50).getData().isEmpty(),"Mongo allowed read rejected");
                for(String command:List.of("db.rows.insertOne({n:1})","db.runCommand({insert:'rows',documents:[{n:2}]})")) {
                    try {actuator.execute(parser.parse(command),0,50);throw new AssertionError("Mongo denied write accepted");}
                    catch(OperationPermissionDeniedException expected){require(org.jumpserver.chen.modules.mongodb.MongoPermissionErrorClassifier.isPermissionDenied(expected),"Mongo denial cause lost");}
                }
                var records=new java.util.ArrayList<org.jumpserver.chen.framework.jms.entity.CommandRecord>();
                var session=(org.jumpserver.chen.framework.session.Session)java.lang.reflect.Proxy.newProxyInstance(
                        org.jumpserver.chen.framework.session.Session.class.getClassLoader(),new Class[]{org.jumpserver.chen.framework.session.Session.class},(o,m,args)->{
                            if(m.getName().equals("recordCommand"))records.add((org.jumpserver.chen.framework.jms.entity.CommandRecord)args[0]);
                            if(m.getName().equals("checkACL")){var acl=new org.jumpserver.chen.framework.jms.acl.ACLResult();acl.setRiskLevel(org.jumpserver.chen.wisp.Common.RiskLevel.Normal);return acl;}
                            return null;
                        });
                try {MongoScriptRunner.execute(MongoCommand.script("const value={n:5};db.rows.insertOne(value);"),reader,session,50);throw new AssertionError("script bypassed database permission");}
                catch(MongoCommandException expected) {require(records.size()==1&&records.get(0).isError(),"script permission denial not audited");}
                require(admin.getDatabase(db).getCollection("rows").countDocuments()==0,"Mongo denied writes changed data");
                admin.getDatabase("admin").runCommand(new Document("grantRolesToUser","u05_reader").append("roles",List.of(new Document("role","readWrite").append("db",db))));
                actuator.execute(parser.parse("db.rows.insertOne({n:3})"),0,50);
                admin.getDatabase("admin").runCommand(new Document("revokeRolesFromUser","u05_reader").append("roles",List.of(new Document("role","readWrite").append("db",db))));
                try {actuator.execute(parser.parse("db.rows.insertOne({n:4})"),0,50);throw new AssertionError("Mongo revoked write accepted");}catch(OperationPermissionDeniedException expected){}
                require(admin.getDatabase(db).getCollection("rows").countDocuments()==1,"Mongo grant/revoke effects incorrect");
            } finally {reader.close();}
            System.out.println("PASS U05 Mongo: read, native command denial, zero writes, grant and revoke on same connection");
        } finally {admin.getDatabase(db).drop();admin.getDatabase("admin").runCommand(new Document("dropUser","u05_reader"));admin.close();}
    }
    public static void main(String[] args)throws Exception {
        register("postgresql","drivers/postgresql/postgresql-42.6.0.jar");
        register("mysql","drivers/mysql/mysql-connector-java-8.0.30.jar");
        register("sqlserver","drivers/sqlserver/mssql-jdbc-12.8.1.jre11.jar");
        for(String type:List.of("postgresql","mysql","sqlserver"))relational(type);
        mongo();System.out.println("U05 four-database authorization integration passed");
    }
}
