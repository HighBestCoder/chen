package org.jumpserver.chen.modules.mongodb.command;

import lombok.Getter;
import org.bson.Document;

import java.util.List;

@Getter
public class MongoCommand {

    public enum Type {
        SCRIPT,
        COMMAND,
        REPLACE,
        FIND_AND_UPDATE,
        FIND_AND_REPLACE,
        FIND_AND_DELETE,
        BULK_WRITE,
        CREATE_INDEX,
        LIST_INDEXES,
        DROP_INDEX,
        FIND,
        FIND_ONE,
        COUNT,
        DISTINCT,
        AGGREGATE,
        SHOW_DBS,
        SHOW_COLLECTIONS,
        USE_DB,
        INSERT,
        UPDATE,
        DELETE,
        DROP_COLLECTION
    }

    private final Type type;
    private final String rawText;
    private String collection;
    private Document filter = new Document();
    private Document projection;
    private Document sort;
    private Integer limit;
    private String targetDatabase;
    private List<Document> pipeline;
    private List<Document> documents;
    private Document update;
    public MongoCommand withMulti(boolean multi) {this.multi=multi;return this;}
    public static MongoCommand script(String raw) {return new MongoCommand(Type.SCRIPT,raw);}
    private boolean multi;
    private List<Document> updatePipeline;
    private Document databaseCommand;
    public MongoCommand withUpdatePipeline(List<Document> stages) { this.updatePipeline=stages; return this; }
    public static MongoCommand operation(Type type, String raw, String collection, Document filter, Document update, Document options) {
        MongoCommand c=new MongoCommand(type,raw);c.collection=collection;c.filter=filter;c.update=update;c.options=options;return c;
    }
    public static MongoCommand databaseCommand(String raw, Document command) {
        MongoCommand c=new MongoCommand(Type.COMMAND,raw);c.databaseCommand=command;return c;
    }
    public String authorizationText() {
        if(type==Type.COMMAND)return "db.runCommand("+databaseCommand.toJson()+")";
        if(type==Type.BULK_WRITE)return "db.getCollection("+com.alibaba.fastjson.JSON.toJSONString(collection)+").bulkWrite("+documents.stream().map(Document::toJson).collect(java.util.stream.Collectors.joining(",", "[", "]"))+")";
        return rawText;
    }
    public MongoCommand withDocuments(List<Document> documents) {this.documents=documents;return this;}

    private int skip;
    private String distinctField;
    private Document options = new Document();

    public MongoCommand withOptions(Document options) { this.options = options; return this; }
    public MongoCommand withSkip(int skip) { this.skip = skip; return this; }

    public static MongoCommand read(Type type, String text, String collection, Document filter,
                                    Document projection, String field) {
        MongoCommand c = new MongoCommand(type, text);
        c.collection = collection;
        c.filter = filter;
        c.projection = projection;
        c.distinctField = field;
        return c;
    }

    private MongoCommand(Type type, String rawText) {
        this.type = type;
        this.rawText = rawText;
    }

    public boolean writesCollection() {
        if (type == Type.SCRIPT) return true;
        if (type == Type.AGGREGATE && pipeline != null && !pipeline.isEmpty()) {
            Document last = pipeline.get(pipeline.size() - 1);
            return last.containsKey("$out") || last.containsKey("$merge");
        }
        if(type==Type.COMMAND) {
            String name=databaseCommand.keySet().iterator().next();
            if(name.equals("aggregate")) {
                Object stages=databaseCommand.get("pipeline");
                if(!(stages instanceof List<?> pipeline))return true;
                return pipeline.stream().anyMatch(v->v instanceof java.util.Map<?,?> stage && (stage.containsKey("$out")||stage.containsKey("$merge")));
            }
            return !java.util.Set.of("find","count","distinct","ping","listIndexes","listCollections","collStats","dbStats","serverStatus","buildInfo","explain").contains(name);
        }
        if (java.util.Set.of(Type.REPLACE, Type.FIND_AND_UPDATE, Type.FIND_AND_REPLACE,
                Type.FIND_AND_DELETE, Type.BULK_WRITE, Type.CREATE_INDEX, Type.DROP_INDEX).contains(type)) return true;
        return type == Type.INSERT || type == Type.UPDATE || type == Type.DELETE || type == Type.DROP_COLLECTION;
    }

    public static MongoCommand showDbs(String rawText) {
        return new MongoCommand(Type.SHOW_DBS, rawText);
    }

    public static MongoCommand showCollections(String rawText) {
        return new MongoCommand(Type.SHOW_COLLECTIONS, rawText);
    }

    public static MongoCommand useDb(String rawText, String database) {
        MongoCommand c = new MongoCommand(Type.USE_DB, rawText);
        c.targetDatabase = database;
        return c;
    }

    public static MongoCommand find(String rawText, String collection, Document filter,
                                    Document projection, Document sort, Integer limit) {
        MongoCommand c = new MongoCommand(Type.FIND, rawText);
        c.collection = collection;
        c.filter = filter == null ? new Document() : filter;
        c.projection = projection;
        c.sort = sort;
        c.limit = limit;
        return c;
    }

    public static MongoCommand aggregate(String rawText, String collection,
                                         List<Document> pipeline, Integer limit) {
        MongoCommand c = new MongoCommand(Type.AGGREGATE, rawText);
        c.collection = collection;
        c.pipeline = pipeline == null ? List.of() : pipeline;
        c.limit = limit;
        return c;
    }

    public static MongoCommand insert(String rawText, String collection, List<Document> documents) {
        MongoCommand c = new MongoCommand(Type.INSERT, rawText);
        c.collection = collection;
        c.documents = documents == null ? List.of() : documents;
        return c;
    }

    public static MongoCommand update(String rawText, String collection, Document filter,
                                      Document update, boolean multi) {
        MongoCommand c = new MongoCommand(Type.UPDATE, rawText);
        c.collection = collection;
        c.filter = filter == null ? new Document() : filter;
        c.update = update;
        c.multi = multi;
        return c;
    }

    public static MongoCommand delete(String rawText, String collection, Document filter, boolean multi) {
        MongoCommand c = new MongoCommand(Type.DELETE, rawText);
        c.collection = collection;
        c.filter = filter == null ? new Document() : filter;
        c.multi = multi;
        return c;
    }

    public static MongoCommand dropCollection(String rawText, String collection) {
        MongoCommand c = new MongoCommand(Type.DROP_COLLECTION, rawText);
        c.collection = collection;
        return c;
    }
}
