package org.jumpserver.chen.modules.mongodb.command;

import lombok.Getter;
import org.bson.Document;

@Getter
public class MongoCommand {

    public enum Type {
        FIND,
        SHOW_DBS,
        SHOW_COLLECTIONS,
        USE_DB
    }

    private final Type type;
    private final String rawText;
    private String collection;
    private Document filter = new Document();
    private Document projection;
    private Document sort;
    private Integer limit;
    private String targetDatabase;

    private MongoCommand(Type type, String rawText) {
        this.type = type;
        this.rawText = rawText;
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
}
