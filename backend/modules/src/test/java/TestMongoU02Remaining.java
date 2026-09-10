import java.util.List;
import org.jumpserver.chen.modules.mongodb.command.*;

public class TestMongoU02Remaining {
    public static void main(String[] args) {
        var parser=new MongoCommandParser();
        for(String text:List.of(
            "db.c.find({},{},{collation:{locale:'en',strength:2}}).hint({n:1})",
            "db.c.updateOne({},[{$set:{n:2}}],{upsert:true,writeConcern:{w:1}})",
            "db.c.replaceOne({_id:1},{n:3},{upsert:true})",
            "db.c.findOneAndUpdate({_id:1},{$set:{n:4}},{returnDocument:'after'})",
            "db.c.findOneAndReplace({_id:1},{n:5},{returnNewDocument:true})",
            "db.c.findOneAndDelete({_id:1},{projection:{_id:0}})",
            "db.c.bulkWrite([{insertOne:{document:{n:1}}},{updateOne:{filter:{n:1},update:{$set:{n:2}}}}],{ordered:true})",
            "db.c.createIndex({n:1},{name:'n_idx'})", "db.c.getIndexes()", "db.c.dropIndex('n_idx')",
            "db.createCollection('new_c',{validator:{n:{$type:'int'}}})",
            "db.runCommand({ping:1})")) {
            var c=parser.parse(text);
            if(!c.getRawText().equals(text))throw new AssertionError("lost original command");
        }
        for (String text : List.of(
                "db.c.findOneAndUpdate({},{$set:{x:1}},{returnDocument:null})",
                "db.c.findOneAndUpdate({},{$set:{x:1}},{returnDocument:'after',returnNewDocument:true})",
                "db.c.updateOne({},[{$set:{x:1}}],{arrayFilters:[]})",
                "db.c.find({},{},{collation:{locale:'en',unknown:true}})",
                "db.c.insertOne({},{writeConcern:{w:-1}})",
                "db.c.bulkWrite([{insertOne:{document:{x:1},ignored:true}}])",
                "db.c.bulkWrite([{unrecognized:{}}])",
                "db.runCommand({ping:1,$db:'other'})", "db.runCommand({getMore:1})",
                "db.runCommand({eval:'1+1'})", "db.runCommand({saslStart:1})")) {
            try { parser.parse(text); throw new AssertionError("Accepted invalid: " + text); }
            catch (MongoCommandException expected) { }
        }
        if (parser.parse("db.c.find({text:'literal.map('})").getType()!=MongoCommand.Type.FIND) throw new AssertionError("literal misclassified as script");
        for (String text : List.of("db.runCommand({drop:'c'})", "db.c.bulkWrite([{insertOne:{document:{x:1}}}])", "let x=1;db.c.insertOne({x:x})")) {
            if (!parser.parse(text).writesCollection()) throw new AssertionError("mutation allows replay");
        }
        System.out.println("U02 remaining method shapes parse without dropping the original command");
    }
}
