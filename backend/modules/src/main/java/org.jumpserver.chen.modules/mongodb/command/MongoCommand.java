package org.jumpserver.chen.modules.mongodb.command;

import lombok.Getter;
import org.bson.Document;

import java.util.List;

@Getter
public class MongoCommand {

    public enum Type {
        FIND,
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
    private boolean multi;

    private MongoCommand(Type type, String rawText) {
        this.type = type;
        this.rawText = rawText;
    }

    public boolean writesCollection() {
        if (type == Type.AGGREGATE && pipeline != null && !pipeline.isEmpty()) {
            Document last = pipeline.get(pipeline.size() - 1);
            return last.containsKey("$out") || last.containsKey("$merge");
        }
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
