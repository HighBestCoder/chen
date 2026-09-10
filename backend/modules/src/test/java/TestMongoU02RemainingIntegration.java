import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.*;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.wisp.Common;
import java.lang.reflect.Proxy;
import java.util.*;

public class TestMongoU02RemainingIntegration extends TestConnectionTlsIntegration {
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        var cm=(MongoConnectionManager)manager(info("mongodb"));String db="u02_remaining_"+UUID.randomUUID().toString().replace("-","");
        cm.setDatabaseContext(db);var p=new MongoCommandParser();var a=new MongoActuator(cm);
        try {
            var c=cm.getDatabase(db).getCollection("c");c.insertMany(List.of(new Document("n",1).append("label","alpha"),new Document("n",2).append("label","ALPHA"),new Document("n",3).append("label","beta")));
            a.execute(p.parse("db.c.createIndex({n:1},{name:'n_idx',unique:true})"),0,50);
            require(a.execute(p.parse("db.c.getIndexes()"),0,50).getData().size()==2,"index not created/listed");
            var r=a.execute(p.parse("db.c.find({label:'alpha'},{},{collation:{locale:'en',strength:2}}).hint('n_idx').batchSize(1).maxTimeMS(5000)"),0,50);
            require(r.getData().size()==2 && r.getTotal()==2,"find/count collation or hint not applied");
            r=a.execute(p.parse("db.c.distinct('label',{}, {collation:{locale:'en',strength:2},maxTimeMS:5000})"),0,50);require(r.getData().size()==2,"distinct collation ignored");
            a.execute(p.parse("db.c.updateOne({n:1},[{$set:{v:2}}],{hint:'n_idx',writeConcern:{w:1}})"),0,50);
            require(c.find(new Document("n",1)).first().getInteger("v")==2,"pipeline update ignored");
            require(a.execute(p.parse("db.c.replaceOne({n:1},{n:1,v:3},{hint:{n:1}})"),0,50).getUpdateCount()==1,"replace failed");
            r=a.execute(p.parse("db.c.findOneAndUpdate({n:1},{$set:{v:4}},{returnDocument:'after',projection:{_id:0,v:1},hint:'n_idx'})"),0,50);
            require(r.getData().equals(List.of(List.of(4))),"findOneAndUpdate returned wrong document");
            r=a.execute(p.parse("db.c.findOneAndReplace({n:1},{n:1,v:5},{returnNewDocument:true,projection:{_id:0,v:1}})"),0,50);
            require(r.getData().equals(List.of(List.of(5))),"findOneAndReplace ignored return alias");
            a.execute(p.parse("db.c.findOneAndDelete({n:1})"),0,50);require(c.countDocuments()==2,"findOneAndDelete did not delete");
            r=a.execute(p.parse("db.bulk.bulkWrite([{insertOne:{document:{n:1}}},{insertOne:{document:{n:2}}},{updateOne:{filter:{n:1},update:{$set:{v:1}}}},{replaceOne:{filter:{n:2},replacement:{n:2,v:2}}},{deleteOne:{filter:{n:1}}}],{ordered:true})"),0,50);
            require(r.getUpdateCount()==5&&cm.getDatabase(db).getCollection("bulk").countDocuments()==1,"bulk counts/order failed");
            r=a.execute(p.parse("db.c.insertOne({n:4},{writeConcern:{w:0}})"),0,50);
            require(r.getUpdateCount()<0&&r.getOutput().contains("unknown"),"unacknowledged write claimed a known result");
            for(int i=0;i<20;i++)cm.getDatabase(db).getCollection("cursor_data").insertOne(new Document("n",i));
            r=a.execute(p.parse("db.runCommand({find:'cursor_data',batchSize:1})"),0,3);require(r.getData().size()==3&&r.isTruncated(),"raw command cursor not bounded");
            r=a.execute(p.parse("db.runCommand({find:'cursor_data',batchSize:1})"),0,50);require(r.getData().size()==20&&!r.isTruncated(),"raw command cursor not drained");
            a.execute(p.parse("db.createCollection('validated',{validator:{n:{$type:'int'}}})"),0,50);
            a.execute(p.parse("db.validated.insertOne({n:'string'},{bypassDocumentValidation:true})"),0,50);
            require(cm.getDatabase(db).getCollection("validated").countDocuments()==1,"insert validation option ignored");
            r=a.execute(p.parse("db.c.find({},{},{min:{n:2},max:{n:4},hint:'n_idx'})"),0,50);
            require(r.getData().size()==2 && r.getTotal()==2,"index-bounded query published unrelated count");
            try { a.execute(p.parse("db.c.find({}).hint('missing_index')"),0,50); throw new AssertionError("server option error hidden"); }
            catch (com.mongodb.MongoException expected) { }
            var partial=p.parse("db.bulk.bulkWrite([{insertOne:{document:{_id:100}}},{insertOne:{document:{_id:100}}},{insertOne:{document:{_id:101}}}],{ordered:false})");
            try {a.execute(partial,0,50);throw new AssertionError("bulk partial failure hidden");}
            catch (com.mongodb.MongoBulkWriteException expected) {
                var stats=MongoExecutionStatsBuilder.fromFailure(cm,partial,expected);
                require(Boolean.FALSE.equals(stats.getSuccess())&&stats.getAffectedRows()==2,"partial bulk effect count/audit wrong");
            }
            var noAck=p.parse("db.runCommand({insert:'raw_write',documents:[{n:1}],writeConcern:{w:0}})");
            r=a.execute(noAck,0,50);require(MongoExecutionStatsBuilder.fromSuccess(cm,noAck,r).getSuccess()==null,"raw unacknowledged write marked successful");
            a.execute(p.parse("db.c.dropIndex('n_idx')"),0,50);require(a.execute(p.parse("db.c.getIndexes()"),0,50).getData().size()==1,"dropIndex failed");
            List<CommandRecord> records=new ArrayList<>();
            Session session=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(o,m,args1)->{
                if(m.getName().equals("recordCommand"))records.add((CommandRecord)args1[0]);
                if(m.getName().equals("checkACL")){ACLResult acl=new ACLResult();acl.setRiskLevel(((String)args1[0]).contains(".drop(")?Common.RiskLevel.Reject:Common.RiskLevel.Normal);return acl;}
                return null;
            });
            String source="const ids=[];for(let i=0;i<3;i++){ids.push(db.script_data.insertOne({n:i}).insertedId);} db.script_data.find({_id:{$in:ids}}).sort({n:1}).toArray();";
            r=MongoScriptRunner.execute(MongoCommand.script(source),cm,session,50);
            require(r.getData().size()==3&&records.size()==4&&records.stream().allMatch(record->record.getExecutionStats().getRawCommand().startsWith("db.")),"script data/operation audits wrong");
            try{MongoScriptRunner.execute(MongoCommand.script("const op='dr'+'op';db.script_data[op]();"),cm,session,50);throw new AssertionError("dynamic drop bypassed ACL");}catch(MongoCommandException expected){}
            require(cm.getDatabase(db).getCollection("script_data").countDocuments()==3&&records.get(records.size()-1).isError(),"denied dynamic command changed database or lacked audit");
            System.out.println("U02 remaining Mongo: options/pipelines/mutations/bulk/index/command cursor/script BSON and dynamic ACL passed");
        }finally{cm.getDatabase(db).drop();cm.close();}
        TestMongoU02Integration.main(args);
    }
}
