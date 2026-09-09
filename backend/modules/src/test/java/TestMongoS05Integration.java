import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import java.util.*;

/** TLS/authenticated ephemeral Mongo fixture; invoke with test-s02b-integration.sh. */
public class TestMongoS05Integration extends TestConnectionTlsIntegration {
    static final List<String> failures = new ArrayList<>();
    interface Case { void run() throws Exception; }
    static void test(String name, Case body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failures.add(name + ": " + e); }
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var cm = (MongoConnectionManager) manager(info("mongodb"));
        String db = "s05_" + UUID.randomUUID().toString().replace("-", "");
        cm.setDatabaseContext(db);
        var a = new MongoActuator(cm); var p = new MongoCommandParser();
        try {
            var c = cm.getDatabase(db).getCollection("items");
            List<Document> docs = new ArrayList<>();
            for (int i=0;i<1500;i++) docs.add(new Document("_id",i).append("n",i));
            c.insertMany(docs);
            test("find sort/projection and second page", () -> {
                var r = a.execute(p.parse("db.items.find({}, {_id:0,n:1}).sort({n:-1})"),5,5);
                require(r.getData().equals(List.of(List.of(1494),List.of(1493),List.of(1492),List.of(1491),List.of(1490))),"wrong rows");
                require(r.isPaged() && r.getTotal()==1500,"wrong pagination");
            });
            test("manual limit precedence and UI flag", () -> {
                var r=a.execute(p.parse("db.items.find({}).limit(3)"),0,50);
                require(r.getData().size()==3 && r.isManualLimitDetected() && !r.isPaged() && !r.isTruncated(),"wrong manual state");
            });
            test("export preserves explicit limit without a false truncation error", () -> {
                for(String text:List.of("db.items.find({}).limit(3)","db.items.aggregate([]).limit(3)")) {
                    var r=a.execute(p.parse(text),0,-1);
                    require(r.getData().size()==3 && !r.isTruncated(),"explicit export wrongly capped");
                }
            });
            test("capped manual find warns, exact cap does not", () -> {
                var r=a.execute(p.parse("db.items.find({}).limit(0)"),0,50);
                require(r.getData().size()==1000 && r.isTruncated(),"silent safety cap");
                r=a.execute(p.parse("db.items.find({_id:{$lt:1000}}).limit(0)"),0,50);
                require(r.getData().size()==1000 && !r.isTruncated(),"false truncation");
            });
            test("unsupported toolbar size cannot silently skip rows", () -> {
                try { a.execute(p.parse("db.items.find({})"),0,5000); throw new AssertionError("accepted a misleading page size"); }
                catch(MongoCommandException expected) { require(expected.getMessage().contains("1000"),"unclear cap error"); }
            });
            test("terminal out writes all and exposes no reloadable table", () -> {
                var r=a.execute(p.parse("db.items.aggregate([{$out:'copied'}])"),0,5);
                require(cm.getDatabase(db).getCollection("copied").countDocuments()==1500,"out truncated writes");
                require(!r.isHasResultSet(),"write exposed result table");
            });
            test("terminal merge writes all and exposes no table", () -> {
                var r=a.execute(p.parse("db.items.aggregate([{$merge:'merged'}])"),0,5);
                require(cm.getDatabase(db).getCollection("merged").countDocuments()==1500 && !r.isHasResultSet(),"merge result/write mismatch");
            });
            test("aggregate manual and default limit", () -> {
                var r=a.execute(p.parse("db.items.aggregate([{$limit:3}])"),0,5);
                require(r.getData().size()==3 && r.isManualLimitDetected(),"manual aggregate state");
                r=a.execute(p.parse("db.items.aggregate([{$match:{}}])"),0,5);
                require(r.getData().size()==5 && !r.isManualLimitDetected(),"default aggregate cap");
            });
            test("aggregate chained unlimited cap warns even for export", () -> {
                var r=a.execute(p.parse("db.items.aggregate([]).limit(0)"),0,-1);
                require(r.getData().size()==1000 && r.isTruncated(),"aggregate safety cap mismatch");
            });
            test("special collection preview selects exact target", () -> {
                cm.getDatabase(db).getCollection("orders").insertOne(new Document("sentinel","wrong"));
                cm.getDatabase(db).getCollection("orders.find_archive").insertOne(new Document("sentinel","right"));
                var r=cm.getSqlActuator().createPlan(null,"orders.find_archive",new SQLQueryParams()).execute();
                require(r.getData().get(0).contains("right"),"preview queried other collection");
            });
            test("write counts, no-op update, duplicate key and delete", () -> {
                require(a.execute(p.parse("db.w.insertOne({_id:1,n:1})"),0,50).getUpdateCount()==1,"insert count");
                require(a.execute(p.parse("db.w.updateOne({_id:1},{$set:{n:1}})"),0,50).getUpdateCount()==0,"no-op update count");
                try { a.execute(p.parse("db.w.insertOne({_id:1})"),0,50); throw new AssertionError("duplicate accepted"); }
                catch(com.mongodb.MongoException expected) { require(cm.getDatabase(db).getCollection("w").countDocuments()==1,"duplicate changed data"); }
                require(a.execute(p.parse("db.w.deleteMany({})"),0,50).getUpdateCount()==1,"delete count");
            });
            test("100001 row export detects truncation for find/aggregate/preview", () -> {
                var bulk=cm.getDatabase(db).getCollection("bulk");
                for(int start=0;start<100001;start+=1000) {
                    List<Document> batch=new ArrayList<>();
                    for(int i=start;i<Math.min(start+1000,100001);i++)batch.add(new Document("_id",i));
                    bulk.insertMany(batch);
                }
                var r=a.execute(p.parse("db.bulk.find({})"),0,-1);
                require(r.getData().size()==100000 && r.isTruncated(),"find export silently truncated");
                r=a.execute(p.parse("db.bulk.aggregate([])"),0,-1);
                require(r.getData().size()==100000 && r.isTruncated(),"aggregate export silently truncated");
                var params=new SQLQueryParams();params.setLimit(-1);
                r=cm.getSqlActuator().createPlan(null,"bulk",params).execute();
                require(r.isTruncated(),"preview export silently truncated");
            });
            test("show databases/collections and use context", () -> {
                require(a.execute(p.parse("show collections"),0,50).getData().stream().anyMatch(row->row.contains("items")),"show collections");
                require(a.execute(p.parse("show dbs"),0,50).getData().stream().anyMatch(row->row.contains(db)),"show dbs");
                a.execute(p.parse("use " + db + "_other"),0,50);
                require(cm.getCurrentDatabaseName().equals(db+"_other"),"use ignored");
                cm.setDatabaseContext(db);
            });
        } finally { cm.getDatabase(db).drop(); cm.close(); }
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        TestMongoWriteExecution.main(new String[]{"--fixture"});
        System.out.println("S05 real Mongo: " + passed + " groups passed");
    }
}
