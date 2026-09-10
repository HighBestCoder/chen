import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.command.*;
import java.util.*;

public class TestMongoScriptWorker {
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) {
        List<String> calls=new ArrayList<>();
        var result=MongoScriptRunner.evaluate("let n=0; for(let i=0;i<3;i++){ const r=db.c.insertOne({n:i,id:ObjectId()}); n+=r.affectedRows; } n;","fixture",30_000,(text,db)->{
            require(db.equals("fixture"),"database changed");new MongoCommandParser().parse(text);calls.add(text);
            return new Document("value",new Document("affectedRows",1)).toJson();
        });
        String newlineScript="db.c.insertOne({n:1})\ndb.c.insertOne({n:2})";
        require(new MongoCommandParser().parse(newlineScript).getType()==MongoCommand.Type.SCRIPT,"newline statements not dispatched to JavaScript");
        int[] newlineCalls={0};MongoScriptRunner.evaluate(newlineScript,"fixture",30_000,(text,db)->{new MongoCommandParser().parse(text);newlineCalls[0]++;return new Document("value",new Document("acknowledged",true)).toJson();});
        require(newlineCalls[0]==2,"automatic semicolon insertion lost an operation");
        require(((Number)result.get("value")).intValue()==3&&calls.size()==3,"variables/loop/expanded operations failed");
        result=MongoScriptRunner.evaluate("const rows=db.getCollection('a.b').find({d:ISODate('2024-01-01'),n:NumberLong('9007199254740993'),r:/abc/i}).sort({n:1}).limit(2).toArray(); rows.map(x=>x.n+1);","fixture",30_000,(text,db)->{
            var command=new MongoCommandParser().parse(text);require(command.getCollection().equals("a.b") && command.getFilter().getLong("n")==9007199254740993L && command.getFilter().get("d") instanceof Date,"BSON fidelity lost");
            return new Document("value",List.of(new Document("n",1),new Document("n",2))).toJson();
        });
        require(result.getList("value",Integer.class).equals(List.of(2,3)),"cursor/map results changed");
        for(String script:List.of("Java.type('java.lang.System').getenv()", "require('fs').readFileSync('/etc/passwd')", "while(true) {}")) {
            try{MongoScriptRunner.evaluate(script,"fixture",20_000,(text,db)->{throw new AssertionError("unexpected I/O");});throw new AssertionError("unrestricted script accepted");}
            catch(MongoCommandException expected){}
        }
        try{MongoScriptRunner.evaluate("const a=[];for(;;)a.push(new Array(1000000).fill('x'));","fixture",20_000,(text,db)->"{}");throw new AssertionError("heap limit not enforced");}
        catch(MongoCommandException expected){}
        result=MongoScriptRunner.evaluate("let x=2; x+3;","fixture",30_000,(text,db)->"{}");
        require(((Number)result.get("value")).intValue()==5,"failed worker poisoned subsequent scripts");
        System.out.println("U02 script worker: variables/loops/cursors/BSON, host I/O denial, CPU/heap limits and recovery passed");
    }
}
