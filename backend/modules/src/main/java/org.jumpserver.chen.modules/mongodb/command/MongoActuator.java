package org.jumpserver.chen.modules.mongodb.command;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;

import java.util.ArrayList;
import java.util.List;

public class MongoActuator {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    private static final int EXPORT_MAX = 100_000;
    private static final Document STABLE_SORT = new Document("_id", 1);
    private static final String LIMIT_STAGE = "$limit";

    private final MongoConnectionManager connectionManager;
    private final MongoResultTableAdapter adapter = new MongoResultTableAdapter();

    public MongoActuator(MongoConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public SQLQueryResult execute(MongoCommand command, int offset, int limit) {
        return switch (command.getType()) {
            case FIND -> executeFind(command, offset, limit);
            case AGGREGATE -> executeAggregate(command, limit);
            case SHOW_DBS -> executeShowDbs(command);
            case SHOW_COLLECTIONS -> executeShowCollections(command);
            case USE_DB -> executeUseDb(command);
            case INSERT -> executeInsert(command);
            case UPDATE -> executeUpdate(command);
            case DELETE -> executeDelete(command);
            case DROP_COLLECTION -> executeDrop(command);
        };
    }

    private SQLQueryResult executeFind(MongoCommand command, int offset, int limit) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);

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

        return this.adapter.toResult(command.getRawText(), command.getCollection(), documents,
                command.getProjection(), start, queryDone, total, paged);
    }

    /**
     * Runs an aggregation pipeline. A pipeline that does not bound itself with
     * a {@code $limit} stage gets one appended, so an unbounded aggregate is
     * capped the same way an unbounded find is instead of streaming the whole
     * collection into memory. A pipeline that already declares {@code $limit}
     * is left untouched — the author's own limit wins, mirroring the
     * manual-limit precedence rule the relational console applies.
     */
    private SQLQueryResult executeAggregate(MongoCommand command, int limit) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);

        List<Document> pipeline = new ArrayList<>(command.getPipeline());
        if (!hasLimitStage(pipeline)) {
            pipeline.add(new Document(LIMIT_STAGE, resolveLimit(command.getLimit(), limit)));
        }

        AggregateIterable<Document> iterable = collection.aggregate(pipeline);
        List<Document> documents = new ArrayList<>();
        iterable.forEach(documents::add);
        long queryDone = System.currentTimeMillis();

        // An aggregation has no cheap total-count equivalent, so the result is
        // always reported as a complete (non-paged) set of what the pipeline
        // produced rather than inventing a page count.
        return this.adapter.toResult(command.getRawText(), command.getCollection(), documents,
                null, start, queryDone, documents.size(), false);
    }

    private boolean hasLimitStage(List<Document> pipeline) {
        for (Document stage : pipeline) {
            if (stage != null && stage.containsKey(LIMIT_STAGE)) {
                return true;
            }
        }
        return false;
    }

    private int resolveLimit(Integer commandLimit, int consoleLimit) {
        if (commandLimit != null) {
            return Math.min(commandLimit, MAX_LIMIT);
        }
        // consoleLimit < 0 is the DataView "export all" signal; cap it at
        // EXPORT_MAX rather than collapsing to DEFAULT_LIMIT so exports are
        // not silently truncated to a page, while still bounding memory.
        if (consoleLimit < 0) {
            return EXPORT_MAX;
        }
        return Math.min(consoleLimit > 0 ? consoleLimit : DEFAULT_LIMIT, MAX_LIMIT);
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
        return writeResult(command.getRawText(), 0, System.currentTimeMillis());
    }

    private SQLQueryResult executeInsert(MongoCommand command) {
        long start = System.currentTimeMillis();
        InsertManyResult result = collectionOf(command).insertMany(command.getDocuments());
        long inserted = result.getInsertedIds() == null
                ? command.getDocuments().size()
                : result.getInsertedIds().size();
        return writeResult(command.getRawText(), inserted, start);
    }

    private SQLQueryResult executeUpdate(MongoCommand command) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);
        UpdateResult result = command.isMulti()
                ? collection.updateMany(command.getFilter(), command.getUpdate())
                : collection.updateOne(command.getFilter(), command.getUpdate());
        return writeResult(command.getRawText(), result.getModifiedCount(), start);
    }

    private SQLQueryResult executeDelete(MongoCommand command) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);
        DeleteResult result = command.isMulti()
                ? collection.deleteMany(command.getFilter())
                : collection.deleteOne(command.getFilter());
        return writeResult(command.getRawText(), result.getDeletedCount(), start);
    }

    private SQLQueryResult executeDrop(MongoCommand command) {
        long start = System.currentTimeMillis();
        collectionOf(command).drop();
        return writeResult(command.getRawText(), 0, start);
    }

    /**
     * Builds the "no result set" shape shared by every write and by
     * {@code use <db>}: {@link SQLQueryResult#getOutput()} renders it as
     * "Query OK, N rows affected" and the audit layer reads the same
     * {@code updateCount} as affected rows.
     */
    private SQLQueryResult writeResult(String sql, long affected, long start) {
        SQLQueryResult result = new SQLQueryResult(sql);
        result.setHasResultSet(false);
        result.setUpdateCount((int) Math.min(affected, Integer.MAX_VALUE));
        long now = System.currentTimeMillis();
        result.setStartTime(new java.sql.Time(start));
        result.setQueryFinishedTime(new java.sql.Time(now));
        result.setFetchFinishedTime(new java.sql.Time(now));
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

    private MongoCollection<Document> collectionOf(MongoCommand command) {
        return this.connectionManager.getDatabase(currentDatabase()).getCollection(command.getCollection());
    }

    private String currentDatabase() {
        String db = this.connectionManager.getCurrentDatabaseName();
        if (db == null || db.isEmpty()) {
            throw new MongoCommandException("No database selected. Use 'use <db>' first.");
        }
        return db;
    }
}
