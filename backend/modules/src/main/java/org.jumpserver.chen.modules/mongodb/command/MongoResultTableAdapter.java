package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;

import java.sql.Time;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class MongoResultTableAdapter {

    private static final String ID_FIELD = "_id";
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
        SQLQueryResult result = new SQLQueryResult(sql);

        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(ID_FIELD);
        for (Document doc : documents) {
            keys.addAll(doc.keySet());
        }

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
                row.add(renderValue(doc.containsKey(key) ? doc.get(key) : null));
            }
            rows.add(row);
        }
        result.setData(rows);

        result.setHasResultSet(true);
        result.setTotal((int) Math.min(total, Integer.MAX_VALUE));
        result.setPaged(paged);

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

    private Object renderValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ObjectId oid) {
            return oid.toHexString();
        }
        if (value instanceof Document doc) {
            return doc.toJson(RELAXED);
        }
        if (value instanceof Collection<?> collection) {
            return new Document("_", new ArrayList<>(collection)).toJson(RELAXED);
        }
        if (value instanceof Map<?, ?> map) {
            return new Document().append("_", map).toJson(RELAXED);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value.toString();
    }
}
