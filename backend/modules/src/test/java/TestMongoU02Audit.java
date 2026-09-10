import org.jumpserver.chen.modules.mongodb.command.*;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.wisp.Common;
import java.lang.reflect.Proxy;
import java.util.*;

public class TestMongoU02Audit {
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        DBConnectInfo info=new DBConnectInfo();info.setDb("original");
        var manager=new MongoConnectionManager(info,null){@Override public String getVersion(){return "fixture";}};
        for(String text:List.of("let x=1;db.c.insertOne({x:x})", "db.c.replaceOne({},{x:1})",
                "db.c.findOneAndUpdate({},{$set:{x:1}})", "db.c.findOneAndReplace({},{x:1})", "db.c.findOneAndDelete({})",
                "db.c.createIndex({x:1})", "db.c.dropIndex('x_1')", "db.getCollection('c').findOne({})", "db.c.countDocuments({})", "db.c.distinct('n')",
                "db.getCollection('c').updateOne({},{$set:{n:1}},{upsert:true})", "db.c.insertMany([{n:1}],{ordered:false})")) {
            var command=new MongoCommandParser().parse(text);
            int[] executions={0};List<CommandRecord> records=new ArrayList<>();
            ACLResult denied=new ACLResult();denied.setRiskLevel(Common.RiskLevel.Reject);
            ACLResult accepted=new ACLResult();accepted.setRiskLevel(Common.RiskLevel.Normal);
            Session session=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(o,m,a)->{
                if(m.getName().equals("recordCommand"))records.add((CommandRecord)a[0]);
                if(m.getName().equals("checkACL")){require(text.equals(a[0]),"ACL lost raw command");return denied;}
                return null;
            });
            var actuator=new MongoActuator(manager){@Override public SQLQueryResult execute(MongoCommand c,int offset,int limit){
                executions[0]++;require(manager.getCurrentDatabaseName().equals("original"),"wrong execution database");
                SQLQueryResult r=new SQLQueryResult(text);r.setHasResultSet(false);r.setUpdateCount(1);return r;
            }};
            var blocked=new MongoQueryLoader(session,manager,actuator,command,denied,text);
            try{blocked.loadData(new SQLQueryParams(),null);throw new AssertionError("denied command executed");}catch(MongoCommandException expected){}
            require(executions[0]==0 && records.size()==1 && records.get(0).isError(),"denial has side effect or no audit");
            var loader=new MongoQueryLoader(session,manager,actuator,command,accepted,text);
            manager.setDatabaseContext("other");loader.loadData(new SQLQueryParams(),null);
            require(executions[0]==1 && manager.getCurrentDatabaseName().equals("other"),"context not restored");
            try{loader.loadData(new SQLQueryParams(),null);throw new AssertionError("revoked reload accepted");}catch(MongoCommandException expected){}
            require(executions[0]==1 && records.size()==3,"reload bypassed ACL or audit");
            for(var record:records)require(record.getExecutionStats().getRawCommand().equals(text) && record.getExecutionStats().getNamespace().equals("original"),"audit text/database lost");
            manager.setDatabaseContext("original");
        }
        System.out.println("U02 audit: each new command family denies before execution, rechecks reload and retains original text/database");
    }
}
