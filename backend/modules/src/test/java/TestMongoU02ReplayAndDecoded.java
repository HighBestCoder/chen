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

public class TestMongoU02ReplayAndDecoded {
    static void require(boolean ok,String why) {if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        DBConnectInfo info=new DBConnectInfo();info.setDb("original");
        var manager=new MongoConnectionManager(info,null){@Override public String getVersion(){return "fixture";}};
        ACLResult accepted=new ACLResult();accepted.setRiskLevel(Common.RiskLevel.Normal);
        List<CommandRecord> records=new ArrayList<>();int[] executions={0};boolean[] denyDecoded={false};
        Session session=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(o,m,a)->{
            if(m.getName().equals("recordCommand"))records.add((CommandRecord)a[0]);
            if(m.getName().equals("checkACL")) {
                ACLResult result=new ACLResult();String text=(String)a[0];
                result.setRiskLevel(denyDecoded[0]&&(text.contains("\"drop\"")||text.contains("\"insertOne\""))?Common.RiskLevel.Reject:Common.RiskLevel.Normal);return result;
            }
            return null;
        });
        var actuator=new MongoActuator(manager){@Override public SQLQueryResult execute(MongoCommand c,int offset,int limit) {
            executions[0]++;SQLQueryResult result=new SQLQueryResult(c.getRawText());result.setHasResultSet(true);return result;
        }};
        for(String text:List.of("db.c.replaceOne({},{n:1})", "db.c.findOneAndUpdate({},{$set:{n:1}})",
                "db.c.findOneAndReplace({},{n:1})", "db.c.findOneAndDelete({})", "db.c.bulkWrite([{insertOne:{document:{n:1}}}])",
                "db.c.createIndex({n:1})", "db.c.dropIndex('n_1')", "db.runCommand({drop:'c'})", "let n=1;db.c.insertOne({n:n})")) {
            var loader=new MongoQueryLoader(session,manager,actuator,new MongoCommandParser().parse(text),accepted,text);
            int before=executions[0];loader.loadData(new SQLQueryParams(),null);
            try {loader.loadData(new SQLQueryParams(),null);throw new AssertionError("write replay accepted");}catch(MongoCommandException expected){}
            require(executions[0]==before+1 && records.get(records.size()-1).isError(),"replay reached database or was not audited");
        }
        denyDecoded[0]=true;
        for(String text:List.of("db.runCommand({'dr"+(char)92+"u006fp':'c'})",
                "db.c.bulkWrite([{'insert"+(char)92+"u004fne':{document:{n:1}}}])")) {
            int before=executions[0];var command=new MongoCommandParser().parse(text);
            var loader=new MongoQueryLoader(session,manager,actuator,command,accepted,text);
            try {loader.loadData(new SQLQueryParams(),null);throw new AssertionError("encoded command bypassed ACL");}catch(MongoCommandException expected){}
            require(executions[0]==before && records.get(records.size()-1).isError(),"decoded denial missing");
        }
        System.out.println("U02: result-bearing mutations cannot replay; decoded native/bulk commands are authorized and denial audited");
    }
}
