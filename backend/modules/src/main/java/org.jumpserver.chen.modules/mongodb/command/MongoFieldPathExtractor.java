package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MongoFieldPathExtractor {

    private static final String ID_FIELD = "_id";
    private static final int MAX_DEPTH = 32;
    private static final int MAX_FIELD_PATHS = 512;
    private static final int MAX_ARRAY_ITEMS = 256;

    private MongoFieldPathExtractor() {
    }

    static List<String> fieldPaths(Document projection, List<Document> documents) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        boolean hasDocuments = documents != null && !documents.isEmpty();
        if (isInclusionProjection(projection)) {
            for (Map.Entry<String, Object> entry : projection.entrySet()) {
                String key = entry.getKey();
                if (ID_FIELD.equals(key) && isExcluded(entry.getValue())) {
                    continue;
                }
                if (isIncluded(entry.getValue())) {
                    paths.add(key);
                }
            }
        }
        if (documents != null) {
            for (Document document : documents) {
                flattenDocument("", document, paths, 0);
                if (paths.size() >= MAX_FIELD_PATHS) {
                    break;
                }
            }
        }
        if (hasDocuments) {
            removeParentPaths(paths);
        }
        if (paths.isEmpty()) {
            paths.add(ID_FIELD);
        }
        return new ArrayList<>(paths);
    }

    static Object valueAtPath(Document document, String path) {
        if (document == null || path == null || path.isEmpty()) {
            return null;
        }
        if (document.containsKey(path)) {
            return document.get(path);
        }
        return valueAtPath(document, List.of(path.split("\\.")), 0, 0);
    }

    static final class FlattenedValues {
        private final List<Object> values;

        private FlattenedValues(List<Object> values) {
            this.values = values;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Object value : values) {
                if (value != null) {
                    sb.append(value);
                }
            }
            return sb.toString();
        }
    }

    private static Object valueAtPath(Object value, List<String> parts, int index, int depth) {
        if (value == null || index >= parts.size()) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return null;
        }
        String part = parts.get(index);
        if (value instanceof Document document) {
            if (!document.containsKey(part)) {
                return null;
            }
            return valueAtPath(document.get(part), parts, index + 1, depth + 1);
        }
        if (value instanceof Map<?, ?> map) {
            if (!map.containsKey(part)) {
                return null;
            }
            return valueAtPath(map.get(part), parts, index + 1, depth + 1);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> values = new ArrayList<>();
            int scanned = 0;
            for (Object item : collection) {
                if (scanned++ >= MAX_ARRAY_ITEMS) {
                    break;
                }
                Object extracted = valueAtPath(item, parts, index, depth + 1);
                if (extracted == null) {
                    continue;
                }
                if (extracted instanceof Collection<?> nested) {
                    values.addAll(nested);
                } else {
                    values.add(extracted);
                }
            }
            return values.isEmpty() ? null : new FlattenedValues(values);
        }
        return null;
    }

    private static void flattenDocument(String prefix, Document document, Set<String> paths, int depth) {
        if (document == null) {
            return;
        }
        if (depth >= MAX_DEPTH) {
            if (prefix != null && !prefix.isEmpty()) {
                addPath(paths, prefix);
            }
            return;
        }
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (!addOrContinue(paths)) {
                return;
            }
            flattenValue(join(prefix, entry.getKey()), entry.getValue(), paths, depth + 1);
        }
    }

    private static void flattenMap(String prefix, Map<?, ?> map, Set<String> paths, int depth) {
        if (depth >= MAX_DEPTH) {
            if (prefix != null && !prefix.isEmpty()) {
                addPath(paths, prefix);
            }
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            if (!addOrContinue(paths)) {
                return;
            }
            flattenValue(join(prefix, entry.getKey().toString()), entry.getValue(), paths, depth + 1);
        }
    }

    private static void flattenValue(String path, Object value, Set<String> paths, int depth) {
        if (value instanceof Document document) {
            flattenDocument(path, document, paths, depth);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            flattenMap(path, map, paths, depth);
            return;
        }
        if (value instanceof Collection<?> collection) {
            flattenCollection(path, collection, paths, depth);
            return;
        }
        addPath(paths, path);
    }

    private static void flattenCollection(String path, Collection<?> collection, Set<String> paths, int depth) {
        if (collection.isEmpty()) {
            addPath(paths, path);
            return;
        }
        if (depth >= MAX_DEPTH) {
            addPath(paths, path);
            return;
        }
        boolean allDocuments = true;
        int scanned = 0;
        for (Object item : collection) {
            if (scanned++ >= MAX_ARRAY_ITEMS) {
                break;
            }
            if (!(item instanceof Document) && !(item instanceof Map<?, ?>)) {
                allDocuments = false;
                break;
            }
        }
        if (!allDocuments) {
            addPath(paths, path);
            return;
        }
        scanned = 0;
        for (Object item : collection) {
            if (scanned++ >= MAX_ARRAY_ITEMS || paths.size() >= MAX_FIELD_PATHS) {
                break;
            }
            if (item instanceof Document document) {
                flattenDocument(path, document, paths, depth + 1);
            } else if (item instanceof Map<?, ?> map) {
                flattenMap(path, map, paths, depth + 1);
            }
        }
    }

    private static boolean addOrContinue(Set<String> paths) {
        return paths.size() < MAX_FIELD_PATHS;
    }

    private static void addPath(Set<String> paths, String path) {
        if (path != null && !path.isEmpty() && paths.size() < MAX_FIELD_PATHS) {
            paths.add(path);
        }
    }

    private static void removeParentPaths(LinkedHashSet<String> paths) {
        List<String> all = new ArrayList<>(paths);
        for (String candidate : all) {
            String prefix = candidate + ".";
            for (String other : all) {
                if (!candidate.equals(other) && other.startsWith(prefix)) {
                    paths.remove(candidate);
                    break;
                }
            }
        }
    }

    private static String join(String prefix, String key) {
        return prefix == null || prefix.isEmpty() ? key : prefix + "." + key;
    }

    private static boolean isInclusionProjection(Document projection) {
        if (projection == null || projection.isEmpty()) {
            return false;
        }
        boolean hasInclude = false;
        for (Map.Entry<String, Object> entry : projection.entrySet()) {
            if (ID_FIELD.equals(entry.getKey()) && isExcluded(entry.getValue())) {
                continue;
            }
            if (isIncluded(entry.getValue())) {
                hasInclude = true;
                continue;
            }
            return false;
        }
        return hasInclude;
    }

    private static boolean isIncluded(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == 1;
        }
        return Boolean.TRUE.equals(value);
    }

    private static boolean isExcluded(Object value) {
        if (value instanceof Number number) {
            return number.intValue() == 0;
        }
        return Boolean.FALSE.equals(value);
    }
}
