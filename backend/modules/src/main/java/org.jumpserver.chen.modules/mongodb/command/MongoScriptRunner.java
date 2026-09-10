package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Guest computation runs in a disposable, heap-limited JVM; database calls remain in the authorized parent. */
public final class MongoScriptRunner {
    private static final Semaphore SLOTS=new Semaphore(2);
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mongo-script-deadlines");t.setDaemon(true);return t;});
    private static final JsonWriterSettings EXTENDED=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    public static Document evaluate(String script,String database,long timeoutMs,BiFunction<String,String,String> execute) {
        if(script.length()>256*1024)throw new MongoCommandException("Script exceeds 256 KiB");
        if(!SLOTS.tryAcquire())throw new MongoCommandException("Script workers are busy; retry later");
        Process process=null;ScheduledFuture<?> deadline=null;Path temporary=null;
        try {
            List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-Xmx128m","-XX:MaxMetaspaceSize=128m","-XX:MaxDirectMemorySize=32m","-XX:ReservedCodeCacheSize=32m","-XX:ActiveProcessorCount=2"));
            String[] entries=System.getProperty("java.class.path").split(java.io.File.pathSeparator);
            String classpath=Arrays.stream(entries).map(e->Path.of(e).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
            String main=MongoScriptWorker.class.getName();
            if(entries.length==1 && entries[0].endsWith(".jar")) {
                try(var jar=new java.util.jar.JarFile(entries[0])) {
                    if(jar.getManifest()!=null && jar.getManifest().getMainAttributes().getValue("Start-Class")!=null) {
                        command.add("-Dloader.main="+main);main="org.springframework.boot.loader.launch.PropertiesLauncher";
                    }
                }
            }
            command.addAll(List.of("-cp",classpath,main));
            temporary=Files.createTempDirectory("chen-script-");
            ProcessBuilder builder=new ProcessBuilder(command).directory(temporary.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();builder.environment().put("LANG","C.UTF-8");
            process=builder.start();Process child=process;
            deadline=DEADLINES.schedule(child::destroyForcibly,timeoutMs,TimeUnit.MILLISECONDS);
            MongoScriptProtocol.write(child.getOutputStream(),new Document("script",script).append("database",database));
            long expiresAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            int calls=0;
            for(;;) {
                Document response=MongoScriptProtocol.read(child.getInputStream());
                switch(response.getString("event")) {
                    case "done" -> {return Document.parse(response.getString("json"));}
                    case "error" -> throw new MongoCommandException(response.getString("message"));
                    case "execute" -> {
                        if(System.nanoTime()>=expiresAt || !child.isAlive())throw new MongoCommandException("Script deadline exceeded");
                        if(++calls>1000)throw new MongoCommandException("Script exceeds 1000 database operations");
                        Document reply;
                        try { reply=new Document("json",execute.apply(response.getString("command"),response.getString("database"))); }
                        catch(RuntimeException error) {reply=new Document("error",error.getMessage()==null?"Database command failed":error.getMessage());}
                        MongoScriptProtocol.write(child.getOutputStream(),reply);
                    }
                    default -> throw new MongoCommandException("Invalid script worker message");
                }
            }
        } catch(java.io.IOException error) {throw new MongoCommandException("Script worker stopped, exceeded its resource limit, or returned an oversized result; completed database writes are not replayed");}
        finally {
            if(deadline!=null)deadline.cancel(false);
            if(process!=null) {process.destroyForcibly();try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(temporary!=null)try(var paths=Files.walk(temporary)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(java.io.IOException ignored){}
            SLOTS.release();
        }
    }
    public static SQLQueryResult execute(MongoCommand script,MongoConnectionManager manager,Session session,int limit) {
        long start=System.currentTimeMillis();boolean[] truncated={false};
        Document output=evaluate(script.getRawText(),manager.getCurrentDatabaseName(),60_000,(text,database)->{
            if(database==null||database.isEmpty()||database.indexOf('\0')>=0)throw new MongoCommandException("Invalid script database");
            String previous=manager.getCurrentDatabaseName();CommandRecord record=new CommandRecord(text);
            session.beginCommand(record);manager.setDatabaseContext(database);MongoCommand command=null;
            try {
                command=new MongoCommandParser().parse(text);
                if(command.getType()==MongoCommand.Type.SCRIPT)throw new MongoCommandException("Recursive script invocation is not allowed");
                var acl=session.checkACL(text);record.applyACL(acl);
                if(acl!=null&&!acl.allows(text))throw new MongoCommandException("Expanded script command rejected by ACL");
                String canonical=command.authorizationText();
                if(!canonical.equals(command.getRawText())) {
                    var decoded=session.checkACL(canonical);
                    if(decoded!=null&&!decoded.allows(canonical)){record.applyACL(decoded);throw new MongoCommandException("Decoded script command rejected by ACL");}
                }
                List<Document> documents=new ArrayList<>();
                SQLQueryResult result=new MongoActuator(manager,rows->{documents.clear();documents.addAll(rows);}).execute(command,0,limit);
                truncated[0]|=result.isTruncated();record.setOutput(result);record.setExecutionStats(MongoExecutionStatsBuilder.fromSuccess(manager,command,result));
                Object value=documents;
                switch(command.getType()) {
                    case FIND_ONE,FIND_AND_UPDATE,FIND_AND_REPLACE,FIND_AND_DELETE -> value=documents.isEmpty()?null:documents.get(0);
                    case COUNT -> {Number n=(Number)documents.get(0).get("count");value=n.longValue()<=9007199254740991L?n.doubleValue():n;}
                    case DISTINCT -> value=documents.stream().map(d->d.get("value")).toList();
                    case CREATE_INDEX -> value=documents.get(0).get("name");
                    case COMMAND -> value=documents.size()==1?documents.get(0):documents;
                    default -> {if(!result.isHasResultSet())value=documents.isEmpty()?new Document("acknowledged",result.getUpdateCount()>=0).append("affectedRows",result.getUpdateCount()<0?null:result.getUpdateCount()):documents.get(0);}
                }
                return new Document("value",value).toJson(EXTENDED);
            } catch(RuntimeException error) {
                record.setError(error.getMessage());record.setExecutionStats(command==null?MongoExecutionStatsBuilder.fromFailure(manager,text,error):MongoExecutionStatsBuilder.fromFailure(manager,command,error));throw error;
            } finally {
                if(record.getExecutionStats()!=null)record.getExecutionStats().putExtra("script_source",script.getRawText());
                try{session.recordCommand(record);}finally{manager.setDatabaseContext(previous);}
            }
        });
        List<Document> rows=new ArrayList<>();Object value=output.get("value");
        if(value instanceof List<?> values){for(Object item:values){if(rows.size()==1000){truncated[0]=true;break;}rows.add(item instanceof Document d?d:new Document("value",item));}}
        else rows.add(value instanceof Document d?d:new Document("value",value));
        if(output.get("output") instanceof List<?> lines&&!lines.isEmpty())rows.add(new Document("output",lines));
        SQLQueryResult result=new MongoResultTableAdapter().toResult(script.getRawText(),rows,start,System.currentTimeMillis());result.setTruncated(truncated[0]);return result;
    }
}
