package org.jumpserver.chen.modules.mongodb.command;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.*;
import java.util.concurrent.TimeUnit;
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
    private final MongoResultTableAdapter adapter;
    private final java.util.function.Consumer<List<Document>> observer;

    public MongoActuator(MongoConnectionManager connectionManager) {
        this(connectionManager,null);
    }
    public MongoActuator(MongoConnectionManager connectionManager,java.util.function.Consumer<List<Document>> observer) {
        this.connectionManager = connectionManager;
        this.observer=observer;this.adapter=new MongoResultTableAdapter(observer);
    }

    public SQLQueryResult execute(MongoCommand command, int offset, int limit) {
        return switch (command.getType()) {
            case SCRIPT -> MongoScriptRunner.execute(command,connectionManager,org.jumpserver.chen.framework.session.SessionManager.getCurrentSession(),limit);
            case COMMAND -> executeDatabaseCommand(command,limit);
            case REPLACE, FIND_AND_UPDATE, FIND_AND_REPLACE, FIND_AND_DELETE -> executeMutation(command);
            case BULK_WRITE -> executeBulk(command);
            case CREATE_INDEX, LIST_INDEXES, DROP_INDEX -> executeIndex(command,limit);
            case FIND -> executeFind(command, offset, limit);
            case FIND_ONE -> executeFindOne(command);
            case COUNT -> executeCount(command);
            case DISTINCT -> executeDistinct(command, limit);
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

    private SQLQueryResult unacknowledged(MongoCommand command,long start) {
        SQLQueryResult result=writeResult(command.getRawText(),-1,start);
        return new SQLQueryResult(command.getRawText()) {
            {setHasResultSet(false);setUpdateCount(-1);setStartTime(result.getStartTime());setQueryFinishedTime(result.getQueryFinishedTime());setFetchFinishedTime(result.getFetchFinishedTime());setEndTime(result.getEndTime());}
            @Override public String getOutput(){return "Command submitted without acknowledgement; database outcome and affected rows are unknown";}
        };
    }
    private SQLQueryResult executeMutation(MongoCommand command) {
        long start=System.currentTimeMillis();var collection=collectionOf(command);Document value;
        if(command.getType()==MongoCommand.Type.REPLACE) {
            var result=collection.replaceOne(command.getFilter(),command.getUpdate(),MongoOptions.apply(new ReplaceOptions(),command.getOptions()));
            if(!result.wasAcknowledged())return unacknowledged(command,start);
            observeUpdate(result);
            return writeResult(command.getRawText(),result.getModifiedCount()+(result.getUpsertedId()==null?0:1),start);
        }
        if(command.getType()==MongoCommand.Type.FIND_AND_DELETE)value=collection.findOneAndDelete(command.getFilter(),MongoOptions.apply(new FindOneAndDeleteOptions(),command.getOptions()));
        else if(command.getType()==MongoCommand.Type.FIND_AND_REPLACE)value=collection.findOneAndReplace(command.getFilter(),command.getUpdate(),MongoOptions.apply(new FindOneAndReplaceOptions(),command.getOptions()));
        else {
            var options=MongoOptions.apply(new FindOneAndUpdateOptions(),command.getOptions());
            value=command.getUpdatePipeline()==null?collection.findOneAndUpdate(command.getFilter(),command.getUpdate(),options):collection.findOneAndUpdate(command.getFilter(),command.getUpdatePipeline(),options);
        }
        // A returned before/after document is not a reliable modified-row count.
        return adapter.toResult(command.getRawText(),command.getCollection(),value==null?List.of():List.of(value),start,System.currentTimeMillis(),value==null?0:1,false);
    }
    private SQLQueryResult executeBulk(MongoCommand command) {
        long start=System.currentTimeMillis();
        var result=collectionOf(command).bulkWrite(MongoBulkSupport.models(command.getDocuments()),MongoOptions.apply(new BulkWriteOptions(),command.getOptions()));
        if(!result.wasAcknowledged())return unacknowledged(command,start);
        if (observer != null) {
            Document upserts = new Document();
            result.getUpserts().forEach(upsert -> upserts.put(Integer.toString(upsert.getIndex()), upsert.getId()));
            observer.accept(List.of(new Document("acknowledged", true).append("insertedCount", result.getInsertedCount())
                    .append("matchedCount", result.getMatchedCount()).append("modifiedCount", result.getModifiedCount())
                    .append("deletedCount", result.getDeletedCount()).append("upsertedCount", result.getUpserts().size()).append("upsertedIds", upserts)));
        }
        return writeResult(command.getRawText(),(long)result.getInsertedCount()+result.getModifiedCount()+result.getDeletedCount()+result.getUpserts().size(),start);
    }
    private SQLQueryResult executeIndex(MongoCommand command,int limit) {
        long start=System.currentTimeMillis();var collection=collectionOf(command);
        if(command.getType()==MongoCommand.Type.CREATE_INDEX) {
            String name=collection.createIndex(command.getFilter(),MongoOptions.apply(new IndexOptions(),command.getOptions()));
            return adapter.toResult(command.getRawText(),List.of(new Document("name",name)),start,System.currentTimeMillis());
        }
        if(command.getType()==MongoCommand.Type.DROP_INDEX) {
            Object key=command.getOptions().get("index");
            if(key instanceof String name)collection.dropIndex(name);else if(key instanceof Document d)collection.dropIndex(d);else throw new MongoCommandException("dropIndex requires name or key document");
            return writeResult(command.getRawText(),-1,start);
        }
        List<Document> rows=new ArrayList<>();int cap=resolveLimit(null,limit);boolean truncated;
        try(var cursor=collection.listIndexes().iterator()){while(rows.size()<cap&&cursor.hasNext())rows.add(cursor.next());truncated=cursor.hasNext();}
        var result=adapter.toResult(command.getRawText(),rows,start,System.currentTimeMillis());result.setTruncated(truncated);return result;
    }
    private SQLQueryResult executeDatabaseCommand(MongoCommand command,int limit) {
        long start=System.currentTimeMillis();MongoDatabase database=connectionManager.getDatabase(currentDatabase());
        Document response=database.runCommand(command.getDatabaseCommand());
        if(response.containsKey("writeErrors") || response.containsKey("writeConcernError"))throw new MongoCommandException("Database command reported write errors: "+response.toJson());
        if (command.getDatabaseCommand().containsKey("writeConcern")
                && !MongoOptions.writeConcern(command.getDatabaseCommand().get("writeConcern", Document.class)).isAcknowledged()) return unacknowledged(command,start);
        Document cursor=response.get("cursor",Document.class);
        if(cursor==null)return adapter.toResult(command.getRawText(),List.of(response),start,System.currentTimeMillis());
        List<Document> rows=new ArrayList<>();int cap=resolveLimit(null,limit);long id=0;String collection=null;boolean truncated=false;
        try {
            for(;;) {
                id=((Number)cursor.get("id")).longValue();String namespace=cursor.getString("ns");
                String prefix=currentDatabase()+".";
                if(!namespace.startsWith(prefix))throw new MongoCommandException("Command cursor does not belong to the current database");
                collection=namespace.substring(prefix.length());
                List<Document> batch=cursor.getList(cursor.containsKey("firstBatch")?"firstBatch":"nextBatch",Document.class);
                for(Document row:batch) {if(rows.size()==cap){truncated=true;break;}rows.add(row);}
                if(truncated||id==0)break;
                if(batch.isEmpty() && cursor.containsKey("nextBatch")){truncated=true;break;}
                cursor=database.runCommand(new Document("getMore",id).append("collection",collection).append("batchSize",Math.min(1000,cap-rows.size()+1))).get("cursor",Document.class);
            }
        } finally {if(id!=0&&collection!=null)database.runCommand(new Document("killCursors",collection).append("cursors",List.of(id)));}
        var result=adapter.toResult(command.getRawText(),rows,start,System.currentTimeMillis());result.setTruncated(truncated);return result;
    }

    private SQLQueryResult executeFindOne(MongoCommand command) {
        long start = System.currentTimeMillis();
        FindIterable<Document> query = collectionOf(command).find(command.getFilter());
        if (command.getProjection() != null) query = query.projection(command.getProjection());
        query=MongoOptions.apply(query,command.getOptions());
        Document document = query.first();
        List<Document> rows = document == null ? List.of() : List.of(document);
        SQLQueryResult result = adapter.toResult(command.getRawText(), command.getCollection(), rows,
                command.getProjection(), start, System.currentTimeMillis(), rows.size(), false);
        result.setManualLimitDetected(true);
        return result;
    }

    private SQLQueryResult executeCount(MongoCommand command) {
        long start = System.currentTimeMillis();
        Document opts = command.getOptions();
        CountOptions options = new CountOptions();
        long count = collectionOf(command).countDocuments(command.getFilter(), MongoOptions.apply(options,opts));
        return adapter.toResult(command.getRawText(), command.getCollection(), List.of(new Document("count", count)),
                start, System.currentTimeMillis(), 1, false);
    }

    private SQLQueryResult executeDistinct(MongoCommand command, int limit) {
        long start = System.currentTimeMillis();
        int cap = resolveLimit(null, limit);
        List<Document> rows = new ArrayList<>();
        boolean truncated;
        try (var cursor = MongoOptions.apply(collectionOf(command).distinct(command.getDistinctField(), command.getFilter(), org.bson.BsonValue.class),command.getOptions()).iterator()) {
            while (rows.size() < cap && cursor.hasNext()) {
                // Decode BSON directly: avoid JSON round trips changing int64 / dates / binary values.
                try (var reader = new org.bson.BsonDocumentReader(new org.bson.BsonDocument("value", cursor.next()))) {
                    rows.add(new org.bson.codecs.DocumentCodec().decode(reader, org.bson.codecs.DecoderContext.builder().build()));
                }
            }
            truncated = cursor.hasNext();
        }
        SQLQueryResult result = adapter.toResult(command.getRawText(), command.getCollection(), rows,
                start, System.currentTimeMillis(), rows.size(), false);
        result.setTruncated(truncated);
        return result;
    }

    private SQLQueryResult executeFind(MongoCommand command, int offset, int limit) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);

        FindIterable<Document> iterable = collection.find(command.getFilter());
        if (command.getProjection() != null) {
            iterable = iterable.projection(command.getProjection());
        }
        iterable = iterable.sort(command.getSort() != null ? command.getSort() : STABLE_SORT);
        iterable = MongoOptions.apply(iterable, command.getOptions());

        boolean explicitLimit = command.getLimit() != null;
        // CountOptions cannot express index bounds or let variables. Retain bounded results
        // without publishing a count for a different query.
        boolean countCompatible = !command.getOptions().containsKey("min") && !command.getOptions().containsKey("max") && !command.getOptions().containsKey("let");
        int effectiveLimit = resolveLimit(command.getLimit(), limit);
        long skipped = (long) Math.max(0, offset) + command.getSkip();
        if (skipped > Integer.MAX_VALUE) throw new MongoCommandException("Combined skip exceeds 2147483647");
        if (skipped > 0) iterable = iterable.skip((int) skipped);
        boolean boundedBySafetyCap = command.getLimit() == null ? limit < 0 || !countCompatible
                : command.getLimit() == 0 || command.getLimit() > effectiveLimit;
        iterable = iterable.limit(effectiveLimit + (boundedBySafetyCap ? 1 : 0));

        List<Document> documents = new ArrayList<>();
        boolean truncated;
        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while (documents.size() < effectiveLimit && cursor.hasNext()) {
                documents.add(cursor.next());
            }
            truncated = boundedBySafetyCap && cursor.hasNext();
        }
        long queryDone = System.currentTimeMillis();

        long total;
        boolean paged;
        if (explicitLimit || !countCompatible) {
            total = documents.size();
            paged = false;
        } else {
            total = Math.max(0, collection.countDocuments(command.getFilter(), MongoOptions.apply(new CountOptions(),command.getOptions(),"batchSize","let","min","max","returnKey","showRecordId")) - command.getSkip());
            paged = limit >= 0;
        }

        SQLQueryResult result = this.adapter.toResult(command.getRawText(), command.getCollection(), documents,
                command.getProjection(), start, queryDone, total, paged);
        result.setManualLimitDetected(explicitLimit);
        result.setTruncated(truncated);
        return result;
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
        boolean writesCollection = command.writesCollection();
        if (!writesCollection && (command.getLimit() != null || !hasLimitStage(pipeline))) {
            int resolved = resolveLimit(command.getLimit(), limit);
            boolean safetyBound = command.getLimit() == null ? limit < 0
                    : command.getLimit() == 0 || command.getLimit() > resolved;
            pipeline.add(new Document(LIMIT_STAGE, resolved + (safetyBound ? 1 : 0)));
        }

        AggregateIterable<Document> iterable = collection.aggregate(pipeline);
        iterable=MongoOptions.apply(iterable,command.getOptions());
        if (writesCollection) {
            iterable.toCollection();
            SQLQueryResult result = writeResult(command.getRawText(), -1, start);
            return result;
        }
        List<Document> documents = new ArrayList<>();
        boolean truncated;
        // An earlier $limit does not bound the output of later $unwind/$unionWith.
        // Bound retained results independently, without rewriting terminal writes.
        int cap = command.getLimit() != null ? resolveLimit(command.getLimit(), limit)
                : limit < 0 ? EXPORT_MAX : MAX_LIMIT;
        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while (documents.size() < cap && cursor.hasNext()) {
                documents.add(cursor.next());
            }
            truncated = cursor.hasNext();
        }
        long queryDone = System.currentTimeMillis();

        // An aggregation has no cheap total-count equivalent, so the result is
        // always reported as a complete (non-paged) set of what the pipeline
        // produced rather than inventing a page count.
        SQLQueryResult result = this.adapter.toResult(command.getRawText(), command.getCollection(), documents,
                null, start, queryDone, documents.size(), false);
        result.setManualLimitDetected(command.getLimit() != null || hasLimitStage(command.getPipeline()));
        result.setTruncated(truncated);
        return result;
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
            // Mongo limit(0) means unlimited, not zero rows. Never pass 0 to
            // the driver on a GUI path that materializes its result in memory.
            return commandLimit == 0 ? MAX_LIMIT : Math.min(commandLimit, MAX_LIMIT);
        }
        // consoleLimit < 0 is the DataView "export all" signal; cap it at
        // EXPORT_MAX rather than collapsing to DEFAULT_LIMIT so exports are
        // not silently truncated to a page, while still bounding memory.
        if (consoleLimit < 0) {
            return EXPORT_MAX;
        }
        if (consoleLimit > MAX_LIMIT) {
            throw new MongoCommandException("MongoDB console supports at most 1000 rows per load; select 50, 100 or 500, or use export all");
        }
        return consoleLimit > 0 ? consoleLimit : DEFAULT_LIMIT;
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
        InsertManyResult result = collectionOf(command).insertMany(command.getDocuments(),
                MongoOptions.apply(new InsertManyOptions(),command.getOptions()));
        if(!result.wasAcknowledged())return unacknowledged(command,start);
        if(observer!=null) {
            Document metadata=new Document("acknowledged",true);
            if(command.isMulti()){Document ids=new Document();result.getInsertedIds().forEach((i,id)->ids.put(i.toString(),id));metadata.put("insertedIds",ids);}
            else metadata.put("insertedId",result.getInsertedIds().get(0));
            observer.accept(List.of(metadata));
        }
        long inserted = result.getInsertedIds() == null
                ? command.getDocuments().size()
                : result.getInsertedIds().size();
        return writeResult(command.getRawText(), inserted, start);
    }

    private SQLQueryResult executeUpdate(MongoCommand command) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);
        UpdateOptions options = MongoOptions.apply(new UpdateOptions(),command.getOptions());
        UpdateResult result;
        if(command.getUpdatePipeline()!=null) result=command.isMulti()?collection.updateMany(command.getFilter(),command.getUpdatePipeline(),options):collection.updateOne(command.getFilter(),command.getUpdatePipeline(),options);
        else result=command.isMulti()?collection.updateMany(command.getFilter(),command.getUpdate(),options):collection.updateOne(command.getFilter(),command.getUpdate(),options);
        if(!result.wasAcknowledged())return unacknowledged(command,start);
        observeUpdate(result);
        return writeResult(command.getRawText(), result.getModifiedCount() + (result.getUpsertedId() == null ? 0 : 1), start);
    }

    private void observeUpdate(UpdateResult result) {
        if (observer != null) observer.accept(List.of(new Document("acknowledged", true)
                .append("matchedCount", result.getMatchedCount()).append("modifiedCount", result.getModifiedCount())
                .append("upsertedCount", result.getUpsertedId() == null ? 0 : 1).append("upsertedId", result.getUpsertedId())));
    }

    private SQLQueryResult executeDelete(MongoCommand command) {
        long start = System.currentTimeMillis();
        MongoCollection<Document> collection = collectionOf(command);
        DeleteResult result = command.isMulti()
                ? collection.deleteMany(command.getFilter(),MongoOptions.apply(new DeleteOptions(),command.getOptions()))
                : collection.deleteOne(command.getFilter(),MongoOptions.apply(new DeleteOptions(),command.getOptions()));
        if(!result.wasAcknowledged())return unacknowledged(command,start);
        if(observer!=null)observer.accept(List.of(new Document("acknowledged",true).append("deletedCount",(double)result.getDeletedCount())));
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
        SQLQueryResult result = affected < 0 ? new SQLQueryResult(sql) {
            @Override public String getOutput() {
                return "Command OK; affected row count is not reported by MongoDB";
            }
        } : new SQLQueryResult(sql);
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
        MongoCollection<Document> collection=this.connectionManager.getDatabase(currentDatabase()).getCollection(command.getCollection());
        if(command.getOptions().containsKey("writeConcern"))collection=collection.withWriteConcern(MongoOptions.writeConcern(command.getOptions().get("writeConcern",Document.class)));
        return collection;
    }

    private String currentDatabase() {
        String db = this.connectionManager.getCurrentDatabaseName();
        if (db == null || db.isEmpty()) {
            throw new MongoCommandException("No database selected. Use 'use <db>' first.");
        }
        return db;
    }
}
