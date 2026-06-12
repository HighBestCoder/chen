package org.jumpserver.chen.modules.mongodb.command;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;

import java.util.ArrayList;
import java.util.List;

public class MongoActuator {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final Document STABLE_SORT = new Document("_id", 1);

    private final MongoConnectionManager connectionManager;
    private final MongoResultTableAdapter adapter = new MongoResultTableAdapter();

    public MongoActuator(MongoConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public SQLQueryResult execute(MongoCommand command, int offset, int limit) {
        return switch (command.getType()) {
            case FIND -> executeFind(command, offset, limit);
            case SHOW_DBS -> executeShowDbs(command);
            case SHOW_COLLECTIONS -> executeShowCollections(command);
            case USE_DB -> executeUseDb(command);
        };
    }

    private SQLQueryResult executeFind(MongoCommand command, int offset, int limit) {
        long start = System.currentTimeMillis();
        MongoDatabase database = this.connectionManager.getDatabase(currentDatabase());
        MongoCollection<Document> collection = database.getCollection(command.getCollection());

        FindIterable<Document> iterable = collection.find(command.getFilter());
        if (command.getProjection() != null) {
            iterable = iterable.projection(command.getProjection());
        }
        iterable = iterable.sort(command.getSort() != null ? command.getSort() : STABLE_SORT);

        boolean explicitLimit = command.getLimit() != null;
        int effectiveLimit = resolveLimit(command.getLimit(), limit);
        if (offset > 0) {
            iterable = iterable.skip(offset);
        }
        iterable = iterable.limit(effectiveLimit);

        List<Document> documents = new ArrayList<>();
        iterable.forEach(documents::add);
        long queryDone = System.currentTimeMillis();

        long total;
        boolean paged;
        if (explicitLimit) {
            total = documents.size();
            paged = false;
        } else {
            total = collection.countDocuments(command.getFilter());
            paged = true;
        }

        return this.adapter.toResult(command.getRawText(), documents, start, queryDone, total, paged);
    }

    private int resolveLimit(Integer commandLimit, int consoleLimit) {
        int chosen = commandLimit != null ? commandLimit
                : (consoleLimit > 0 ? consoleLimit : DEFAULT_LIMIT);
        return Math.min(chosen, MAX_LIMIT);
    }

    private SQLQueryResult executeShowDbs(MongoCommand command) {
        long start = System.currentTimeMillis();
        List<Document> rows = new ArrayList<>();
        for (String name : this.connectionManager.listDatabases()) {
            rows.add(new Document("name", name));
        }
        return singleColumnResult(command.getRawText(), "name", rows, start);
    }

    private SQLQueryResult executeShowCollections(MongoCommand command) {
        long start = System.currentTimeMillis();
        MongoDatabase database = this.connectionManager.getDatabase(currentDatabase());
        List<Document> rows = new ArrayList<>();
        database.listCollectionNames().forEach(name -> rows.add(new Document("name", name)));
        return singleColumnResult(command.getRawText(), "name", rows, start);
    }

    private SQLQueryResult executeUseDb(MongoCommand command) {
        this.connectionManager.setDatabaseContext(command.getTargetDatabase());
        SQLQueryResult result = new SQLQueryResult(command.getRawText());
        result.setHasResultSet(false);
        result.setUpdateCount(0);
        long now = System.currentTimeMillis();
        result.setStartTime(new java.sql.Time(now));
        result.setEndTime(new java.sql.Time(now));
        return result;
    }

    private SQLQueryResult singleColumnResult(String sql, String column, List<Document> rows, long start) {
        SQLQueryResult result = new SQLQueryResult(sql);
        Field field = new Field();
        field.setName(column);
        field.setType("string");
        result.setFields(List.of(field));

        List<List<Object>> data = new ArrayList<>();
        for (Document row : rows) {
            data.add(new ArrayList<>(List.of(row.getString(column))));
        }
        result.setData(data);
        result.setHasResultSet(true);
        result.setTotal(rows.size());
        result.setPaged(false);

        long now = System.currentTimeMillis();
        result.setStartTime(new java.sql.Time(start));
        result.setQueryFinishedTime(new java.sql.Time(now));
        result.setFetchFinishedTime(new java.sql.Time(now));
        result.setEndTime(new java.sql.Time(now));
        return result;
    }

    private String currentDatabase() {
        String db = this.connectionManager.getCurrentDatabaseName();
        if (db == null || db.isEmpty()) {
            throw new MongoCommandException("No database selected. Use 'use <db>' first.");
        }
        return db;
    }
}
