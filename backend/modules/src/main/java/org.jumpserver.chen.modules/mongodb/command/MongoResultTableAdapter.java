package org.jumpserver.chen.modules.mongodb.command;

import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.RowConsumer;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.SQLException;
import java.sql.Time;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class MongoResultTableAdapter {
    private final java.util.function.Consumer<List<Document>> observer;
    public MongoResultTableAdapter() {this(null);}
    public MongoResultTableAdapter(java.util.function.Consumer<List<Document>> observer) {this.observer=observer;}


    public static final String EXPORT_LIMIT_MESSAGE = "Export exceeds the MongoDB row limit; narrow the query before exporting";

    private static final JsonWriterSettings RELAXED =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    public SQLQueryResult toResult(String sql, List<Document> documents, long startMillis, long queryDoneMillis) {
        return toResult(sql, null, documents, startMillis, queryDoneMillis, documents.size(), false);
    }

    public SQLQueryResult toResult(String sql, List<Document> documents, long startMillis,
                                   long queryDoneMillis, long total, boolean paged) {
        return toResult(sql, null, documents, startMillis, queryDoneMillis, total, paged);
    }

    public SQLQueryResult toResult(String sql, String collection, List<Document> documents, long startMillis,
                                   long queryDoneMillis, long total, boolean paged) {
        return toResult(sql, collection, documents, null, startMillis, queryDoneMillis, total, paged);
    }

    public SQLQueryResult toResult(String sql, String collection, List<Document> documents, Document projection,
                                   long startMillis, long queryDoneMillis, long total, boolean paged) {
        if(observer!=null)observer.accept(documents);
        SQLQueryResult result = new SQLQueryResult(sql);

        List<String> keys = MongoFieldPathExtractor.fieldPaths(projection, documents);

        List<Field> fields = new ArrayList<>();
        for (String key : keys) {
            Field field = new Field();
            field.setName(key);
            field.setTable(collection);
            field.setType(inferType(documents, key));
            fields.add(field);
        }
        result.setFields(fields);

        List<List<Object>> rows = new ArrayList<>();
        for (Document doc : documents) {
            List<Object> row = new ArrayList<>(keys.size());
            for (String key : keys) {
                row.add(renderValue(doc.get(key)));
            }
            rows.add(row);
        }
        result.setData(rows);

        result.setHasResultSet(true);
        result.setTotal((int) Math.min(total, Integer.MAX_VALUE));
        result.setPaged(paged);

        // Statistics follow actual BSON leaves, not JSON punctuation or UI
        // flattening. Accumulate only totals; do not retain a second row set.
        Map<String, Long> sizes = null;
        RuntimeException sizeError = null;
        try {
            sizes = MongoFieldPathExtractor.columnSizes(projection, documents);
        } catch (RuntimeException e) {
            sizeError = e;
        }
        applySizes(result, collection, sizes, sizeError);
        setTimes(result, startMillis, queryDoneMillis);
        return result;
    }

    /**
     * Streams documents to {@code sink} in two passes over {@code source} so no
     * document is retained (DEF-27). The first pass collects columns, types and
     * size statistics, and rejects an export over {@code cap} before any row is
     * written. The second pass writes the rows. A second-pass document that is
     * over the cap or brings a column the first pass did not see means the data
     * changed in between; the export fails instead of dropping values.
     */
    public SQLQueryResult export(String sql, String collection, Document projection,
                                 Supplier<MongoCursor<Document>> source, int cap, RowConsumer sink,
                                 long startMillis) throws SQLException {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        Map<String, String> types = new HashMap<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        RuntimeException sizeError = null;
        long count = 0;
        try (MongoCursor<Document> cursor = source.get()) {
            while (cursor.hasNext()) {
                Document document = cursor.next();
                if (++count > cap) {
                    throw new SQLException(EXPORT_LIMIT_MESSAGE);
                }
                keys.addAll(document.keySet());
                document.forEach((key, value) -> {
                    if (value != null) types.putIfAbsent(key, typeName(value));
                });
                if (sizeError == null) {
                    try {
                        MongoFieldPathExtractor.columnSizes(projection, List.of(document))
                                .forEach((path, bytes) -> sizes.merge(path, bytes, Long::sum));
                    } catch (RuntimeException e) {
                        sizeError = e;
                    }
                }
            }
        }
        if (count == 0) {
            keys.addAll(MongoFieldPathExtractor.fieldPaths(projection, List.of()));
            sizes.putAll(MongoFieldPathExtractor.columnSizes(projection, List.of()));
        }
        long queryDone = System.currentTimeMillis();

        SQLQueryResult result = new SQLQueryResult(sql);
        List<Field> fields = new ArrayList<>();
        for (String key : keys) {
            Field field = new Field();
            field.setName(key);
            field.setTable(collection);
            field.setType(types.getOrDefault(key, "null"));
            fields.add(field);
        }
        result.setFields(fields);
        sink.begin(fields);
        long written = 0;
        try (MongoCursor<Document> cursor = source.get()) {
            while (cursor.hasNext()) {
                Document document = cursor.next();
                if (++written > count || !keys.containsAll(document.keySet())) {
                    throw new SQLException("Result changed during export; export again");
                }
                List<Object> row = new ArrayList<>(keys.size());
                for (String key : keys) {
                    row.add(renderValue(document.get(key)));
                }
                sink.accept(row);
            }
        }
        sink.finish();

        result.setHasResultSet(true);
        result.setTotal((int) Math.min(written, Integer.MAX_VALUE));
        result.setTrueReturnedRows(written);
        result.setPaged(false);
        applySizes(result, collection, sizeError == null ? sizes : null, sizeError);
        setTimes(result, startMillis, queryDone);
        return result;
    }

    private static void applySizes(SQLQueryResult result, String collection, Map<String, Long> sizes,
                                   RuntimeException sizeError) {
        if (sizeError != null) {
            result.setStreamedSizeBytes(-1);
            result.setSizeStatsStatus(SizeCalculator.STATUS_UNAVAILABLE);
            result.setSizeStatsUnavailableReason(sizeError.getClass().getSimpleName());
            result.setSizeByColumnSourceStatus(SizeCalculator.STATUS_UNAVAILABLE);
            result.setSizeByColumnSourceUnavailableReason(sizeError.getClass().getSimpleName());
            return;
        }
        result.setStreamedImpactColumns(new ArrayList<>(sizes.keySet()));
        Map<String, Long> byColumn = new LinkedHashMap<>();
        String table = collection == null || collection.isBlank() ? "unknown" : collection.trim();
        sizes.forEach((path, bytes) -> byColumn.merge(table + "." + path, bytes, Long::sum));
        result.setStreamedSizeByColumn(byColumn);
        result.setStreamedSizeBytes(sizes.values().stream().mapToLong(Long::longValue).sum());
        result.setSizeStatsStatus(SizeCalculator.STATUS_OK);
        result.setSizeByColumnSourceStatus(SizeCalculator.STATUS_OK);
    }

    private static void setTimes(SQLQueryResult result, long startMillis, long queryDoneMillis) {
        long fetchDone = System.currentTimeMillis();
        result.setStartTime(new Time(startMillis));
        result.setQueryFinishedTime(new Time(queryDoneMillis));
        result.setFetchFinishedTime(new Time(fetchDone));
        result.setEndTime(new Time(fetchDone));
    }

    private String inferType(List<Document> documents, String key) {
        for (Document doc : documents) {
            Object value = doc.get(key);
            if (value != null) {
                return typeName(value);
            }
        }
        return "null";
    }

    private String typeName(Object value) {
        if (value instanceof ObjectId) {
            return "objectId";
        }
        if (value instanceof Document) {
            return "object";
        }
        if (value instanceof Collection || value instanceof Object[]) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Integer || value instanceof Long) {
            return "int";
        }
        if (value instanceof Double || value instanceof Float) {
            return "double";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        return value.getClass().getSimpleName().toLowerCase();
    }

    static Object renderValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectId oid) {
            return oid.toHexString();
        }
        if (value instanceof Long number) {
            // JavaScript numbers cannot represent every BSON int64 exactly.
            return number.toString();
        }
        if (value instanceof String || value instanceof Integer
                || value instanceof Double number && Double.isFinite(number)
                || value instanceof Boolean) {
            return value;
        }
        // Use the BSON codec for nested arrays, nulls, dates, binary and decimal
        // values. Strip only our wrapper so arrays remain JSON arrays in CSV/UI.
        if (value instanceof Document doc) {
            return doc.toJson(RELAXED);
        }
        String json = new Document("value", value).toJson(RELAXED);
        return json.substring(json.indexOf(':') + 1, json.length() - 1).trim();
    }
}
