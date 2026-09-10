package org.jumpserver.chen.modules.mongodb;

import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;

import java.util.List;
import java.util.Map;

/**
 * No-op hints handler for Mongo. ResourceController.getHints calls
 * getResourceBrowser().getSQLHintsHandler() directly, so this must be
 * non-null. Mongo autocomplete is a later phase; P1 returns no hints.
 */
public class MongoSqlHintsHandlerStub implements SQLHintsHandler {

    private final MongoConnectionManager connectionManager;

    public MongoSqlHintsHandlerStub(MongoConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public Map<String, List<String>> getHints(String nodeKey, String context) {
        if (context == null || context.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> hints = new java.util.LinkedHashMap<>();
        java.util.List<String> collections = new java.util.ArrayList<>();
        this.connectionManager.getDatabase(context).listCollectionNames().forEach(collections::add);
        hints.put(context, collections);
        for (String collection : collections) {
            java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
            this.connectionManager.getDatabase(context).getCollection(collection)
                    .find()
                    .limit(20)
                    .forEach(doc -> fields.addAll(doc.keySet()));
            hints.put(collection, new java.util.ArrayList<>(fields));
        }
        // Keep in sync with mongoKeywords in
        // frontend/src/components/Main/Explore/QueryConsole/CodeEditor.vue.
        hints.put("mongo", List.of("db", "getCollection", "findOne", "countDocuments", "distinct", "skip", "find", "aggregate", "insertOne", "insertMany",
                "updateOne", "updateMany", "deleteOne", "deleteMany", "drop",
                "show dbs", "show collections", "use", "limit", "sort", "ISODate",
                "hint", "collation", "maxTimeMS", "batchSize", "replaceOne", "findOneAndUpdate", "findOneAndReplace", "findOneAndDelete", "bulkWrite", "createIndex", "getIndexes", "dropIndex", "runCommand", "createCollection", "dropDatabase", "stats", "getSiblingDB", "toArray", "forEach", "map", "hasNext", "next", "ObjectId", "NumberLong", "NumberDecimal", "BinData", "print", "printjson"));
        return hints;
    }
}
