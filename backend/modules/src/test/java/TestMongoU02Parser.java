import org.jumpserver.chen.modules.mongodb.command.*;
import java.util.List;

/** U02 shapes absent at the prior release; fail before invoking any database. */
public class TestMongoU02Parser {
    public static void main(String[] args) {
        var parser=new MongoCommandParser();
        for(String text:List.of(
            "db.getCollection('orders.2024').find({}).skip(2).limit(3)",
            "db.orders.findOne({}, {_id:0}, {sort:{n:-1},maxTimeMS:500})",
            "db.orders.countDocuments({n:{$gte:1}}, {skip:2,limit:3,maxTimeMS:500})",
            "db.orders.distinct('tags', {n:{$gt:0}})",
            "db.orders.aggregate([], {allowDiskUse:true,maxTimeMS:500})",
            "db.orders.insertMany([{n:1}], {ordered:false})",
            "db.orders.updateOne({_id:1},{$set:{n:1}},{upsert:true})",
            "db.orders.updateMany({},{$set:{'a.$[x]':2}},{arrayFilters:[{x:1}]})")) {
            var c=parser.parse(text);
            if(!c.getRawText().equals(text))throw new AssertionError("Original text changed");
        }
        for(String text:List.of(
            "db.getCollection('x', {}).find({})", "db.getCollection(null).find({})", "db.getCollection('x').ignored.find({})",
            "db.c.findOne({}).limit(2)",
            "db.c.find({}).skip(-1)", "db.c.find({}).skip(1, ignored:2)", "db.c.find({}).skip(1).skip(2)",
            "db.c.countDocuments({}, {limit:1.5})", "db.c.countDocuments({}, {skip:2147483648})",
            "db.c.distinct('x', {}, {unknown:true})", "db.c.updateOne({},{$set:{x:1}},{upsert:'true'})",
            "db.c.updateOne({},{$set:{x:1}},{arrayFilters:[null]})",
            "db.c.updateOne({},{$set:{x:1}},{arrayFilters:[{$where:'true'}]})",
            "db.c.insertOne({}, {ordered:false})", "db.c.aggregate([], {allowDiskUse:1})",
            "db.c.findOne({}, {}, {projection:{x:1}})", "db.c.findOne({}, {}, {maxTimeMS:-1})")) {
            try {parser.parse(text);throw new AssertionError("Accepted invalid: "+text);}
            catch(MongoCommandException expected) { }
        }
        var script=parser.parse("db.getCollection('x');db.y.drop()");
        if(script.getType()!=MongoCommand.Type.SCRIPT || !script.writesCollection())throw new AssertionError("multi-statement must use the non-replayable script path");
        System.out.println("U02 parser: common methods/options accepted, original text retained, invalid/unknown inputs rejected");
    }
}
