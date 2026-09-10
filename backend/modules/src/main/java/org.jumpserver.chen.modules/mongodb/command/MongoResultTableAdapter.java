package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.jumpserver.chen.framework.audit.SizeCalculator;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.Time;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class MongoResultTableAdapter {
    private final java.util.function.Consumer<List<Document>> observer;
    public MongoResultTableAdapter() {this(null);}
    public MongoResultTableAdapter(java.util.function.Consumer<List<Document>> observer) {this.observer=observer;}


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
        try {
            Map<String, Long> sizes = MongoFieldPathExtractor.columnSizes(projection, documents);
            result.setStreamedImpactColumns(new ArrayList<>(sizes.keySet()));
            Map<String, Long> byColumn = new java.util.LinkedHashMap<>();
            String table = collection == null || collection.isBlank() ? "unknown" : collection.trim();
            sizes.forEach((path, bytes) -> byColumn.merge(table + "." + path, bytes, Long::sum));
            result.setStreamedSizeByColumn(byColumn);
            result.setStreamedSizeBytes(sizes.values().stream().mapToLong(Long::longValue).sum());
            result.setSizeStatsStatus(SizeCalculator.STATUS_OK);
            result.setSizeByColumnSourceStatus(SizeCalculator.STATUS_OK);
        } catch (RuntimeException e) {
            result.setStreamedSizeBytes(-1);
            result.setSizeStatsStatus(SizeCalculator.STATUS_UNAVAILABLE);
            result.setSizeStatsUnavailableReason(e.getClass().getSimpleName());
            result.setSizeByColumnSourceStatus(SizeCalculator.STATUS_UNAVAILABLE);
            result.setSizeByColumnSourceUnavailableReason(e.getClass().getSimpleName());
        }

        long fetchDone = System.currentTimeMillis();
        result.setStartTime(new Time(startMillis));
        result.setQueryFinishedTime(new Time(queryDoneMillis));
        result.setFetchFinishedTime(new Time(fetchDone));
        result.setEndTime(new Time(fetchDone));
        return result;
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
