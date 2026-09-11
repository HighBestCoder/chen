import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.*;
import java.util.*;

/** Native Mongo fixture through the production parser/actuator; no business credentials. */
public class TestMongoU02Integration extends TestConnectionTlsIntegration {
    static void require(boolean ok, String why) { if(!ok)throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        var cm=(MongoConnectionManager)manager(info("mongodb"));
        String db="u02_"+UUID.randomUUID().toString().replace("-", "");
        cm.setDatabaseContext(db);var p=new MongoCommandParser();var a=new MongoActuator(cm);
        try {
            var c=cm.getDatabase(db).getCollection("orders.2024");
            for(int i=0;i<12;i++)c.insertOne(new Document("_id",i).append("n",i).append("tag",i%3).append("a",List.of(1,3)));
            var r=a.execute(p.parse("db.getCollection('orders.2024').find({}, {_id:0,n:1}).sort({n:1}).skip(2)"),3,2);
            require(r.getData().equals(List.of(List.of(5),List.of(6))) && r.getTotal()==10 && r.isPaged(),"skip + pagination/count mismatch");
            r=a.execute(p.parse("db.getCollection('orders.2024').find({}).skip(20)"),0,5);
            require(r.getData().isEmpty() && r.getTotal()==0,"skip past end");
            r=a.execute(p.parse("db.getCollection('orders.2024').findOne({}, {_id:0,n:1}, {sort:{n:-1},maxTimeMS:5000})"),0,50);
            require(r.getData().equals(List.of(List.of(11))) && !r.isPaged(),"findOne returned wrong row/count");
            r=a.execute(p.parse("db.getCollection('orders.2024').findOne({n:999})"),0,50);
            require(r.getData().isEmpty(),"missing findOne produced a document");
            r=a.execute(p.parse("db.getCollection('orders.2024').countDocuments({n:{$gt:0}}, {skip:2,limit:3,maxTimeMS:5000})"),0,1);
            require(r.getData().get(0).get(0).toString().equals("3"),"count options ignored");
            r=a.execute(p.parse("db.getCollection('orders.2024').countDocuments({})"),0,1);
            require(r.getData().get(0).get(0).toString().equals("12"),"toolbar limited scalar count");
            r=a.execute(p.parse("db.getCollection('orders.2024').distinct('tag')"),0,50);
            require(r.getData().size()==3 && !r.isTruncated(),"distinct values wrong");
            r=a.execute(p.parse("db.getCollection('orders.2024').distinct('n', {n:{$gte:3}})"),0,2);
            require(r.getData().size()==2 && r.isTruncated(),"distinct cap not reported");
            r=a.execute(p.parse("db.getCollection('orders.2024').distinct('n', {n:{$gte:3}})"),0,-1);
            require(r.getData().size()==9 && !r.isTruncated(),"distinct export not complete");
            r=a.execute(p.parse("db.getCollection('orders.2024').aggregate([{$match:{n:{$lt:2}}}], {allowDiskUse:true,maxTimeMS:5000})"),0,50);
            require(r.getData().size()==2,"aggregate options changed result");
            r=a.execute(p.parse("db.up.updateOne({_id:1},{$set:{n:7}},{upsert:true})"),0,50);
            require(r.getUpdateCount()==1 && cm.getDatabase(db).getCollection("up").countDocuments()==1,"upsert inserted row not reported");
            require(MongoExecutionStatsBuilder.fromSuccess(cm,p.parse("db.up.updateOne({_id:1},{$set:{n:7}},{upsert:true})"),r).getAffectedRows()==1,"upsert audit count");
            require(a.execute(p.parse("db.up.updateOne({_id:1},{$set:{n:7}},{upsert:true})"),0,50).getUpdateCount()==0,"no-op upsert count");
            r=a.execute(p.parse("db.getCollection('orders.2024').updateMany({},{$set:{'a.$[x]':2}},{arrayFilters:[{x:1}]})"),0,50);
            require(r.getUpdateCount()==12 && c.find().first().getList("a",Integer.class).equals(List.of(2,3)),"arrayFilters ignored");
            try {a.execute(p.parse("db.up.insertMany([{_id:1},{_id:2}],{ordered:false})"),0,50);throw new AssertionError("partial failure reported success");}
            catch(com.mongodb.MongoBulkWriteException expected){require(cm.getDatabase(db).getCollection("up").countDocuments()==2,"unordered did not continue after duplicate");}
            for(String text:List.of("db.up.findOne()","db.up.countDocuments({})","db.up.distinct('n')")) {
                var command=p.parse(text);var result=a.execute(command,0,50);
                var stats=MongoExecutionStatsBuilder.fromSuccess(cm,command,result);
                require(stats.getRawCommand().equals(text) && stats.getNamespace().equals(db) && stats.getReturnedRows()==result.getData().size(),"read audit mismatch");
            }
            System.out.println("U02 Mongo: skip/page/count, findOne, distinct/cap/export, aggregate options, upsert/audit, arrayFilters, unordered partial failure passed");
        } finally {cm.getDatabase(db).drop();cm.close();}
        TestMongoS05Integration.main(args);
    }
}
