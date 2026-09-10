package org.jumpserver.chen.modules.mongodb.command;

import java.nio.charset.StandardCharsets;
import org.bson.Document;
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/** Disposable JVM: no database credentials, no Java/OS/network bindings exposed to JS. */
public final class MongoScriptWorker {
    public static void main(String[] args) throws Exception {
        Document request=MongoScriptProtocol.read(System.in);
        try(Context context=Context.newBuilder("js").allowHostAccess(HostAccess.NONE).allowHostClassLookup(name->false)
                .allowIO(IOAccess.NONE).allowCreateThread(false).allowNativeAccess(false)
                .option("engine.WarnInterpreterOnly","false")
                .resourceLimits(ResourceLimits.newBuilder().statementLimit(100_000,source->true).build()).build()) {
            context.getBindings("js").putMember("__execute",(ProxyExecutable)values->{
                if(values.length!=2 || !values[0].isString() || !values[1].isString())throw new IllegalArgumentException("Invalid database call");
                try {
                    MongoScriptProtocol.write(System.out,new Document("event","execute").append("command",values[0].asString()).append("database",values[1].asString()));
                    Document response=MongoScriptProtocol.read(System.in);
                    if(response.containsKey("error"))return new Document("error",response.getString("error")).toJson();
                    return response.getString("json");
                }catch(java.io.IOException e){throw new IllegalStateException("Script channel closed");}
            });
            context.getBindings("js").putMember("__newObjectId",(ProxyExecutable)values->new org.bson.types.ObjectId().toHexString());
            context.getBindings("js").putMember("__databaseName",request.getString("database"));
            try(var stream=MongoScriptWorker.class.getResourceAsStream("/mongo-console.js")) {
                if(stream==null)throw new IllegalStateException("Missing script bindings");
                context.eval("js",new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            }
            Value value=context.eval(Source.newBuilder("js",request.getString("script"),"console.js").build());
            String json=context.getBindings("js").getMember("__finish").execute(value).asString();
            MongoScriptProtocol.write(System.out,new Document("event","done").append("json",json));
        }catch(Throwable error) {
            // Errors contain guest diagnostics, never a host stack trace or classpath.
            String message=error instanceof PolyglotException p && p.isGuestException()?p.getMessage():"Script failed or exceeded resource limits";
            MongoScriptProtocol.write(System.out,new Document("event","error").append("message",message));
        }
    }
}
