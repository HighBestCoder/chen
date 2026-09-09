package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Full display columns and leaf statistics are deliberately separate. */
final class MongoFieldPathExtractor {
    private MongoFieldPathExtractor() { }

    static List<String> fieldPaths(Document projection, List<Document> documents) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Document document : documents) {
            paths.addAll(document.keySet());
        }
        // With no rows there is no actual BSON shape; retain explicit projection
        // names for an empty result. Never manufacture _id after its exclusion.
        if (documents.isEmpty() && projection != null) {
            projection.forEach((key, value) -> {
                if (Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() == 1) {
                    paths.add(key);
                }
            });
        }
        return new ArrayList<>(paths);
    }

    /**
     * Traverse every returned value once, with no row or array-value buffer.
     * Iterative traversal avoids dropping deep leaves or recursive stack overflow.
     * Object-array leaf paths have no numeric indexes; all elements contribute.
     */
    static Map<String, Long> columnSizes(Document projection, List<Document> documents) {
        Map<String, Long> sizes = new LinkedHashMap<>();
        if (documents.isEmpty()) {
            fieldPaths(projection, documents).forEach(path -> sizes.put(path, 0L));
        }
        for (Document document : documents) {
            Deque<Frame> pending = new ArrayDeque<>();
            pending.push(new Frame("", document));
            while (!pending.isEmpty()) {
                Frame frame = pending.pop();
                Object value = frame.value();
                if (value instanceof Map<?, ?> map && !map.isEmpty()) {
                    // Preserve BSON field order without retaining a copy of values.
                    pending.push(new Frame(frame.path(), map.entrySet().iterator()));
                } else if (value instanceof Collection<?> list && !list.isEmpty()) {
                    pending.push(new Frame(frame.path(), new ArrayValues(list.iterator())));
                } else if (value instanceof java.util.Iterator<?> entries) {
                    if (entries.hasNext()) {
                        Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entries.next();
                        pending.push(frame);
                        String path = frame.path().isEmpty() ? entry.getKey().toString()
                                : frame.path() + "." + entry.getKey();
                        pending.push(new Frame(path, entry.getValue()));
                    }
                } else if (value instanceof ArrayValues array) {
                    if (array.values().hasNext()) {
                        pending.push(frame);
                        pending.push(new Frame(frame.path(), array.values().next()));
                    }
                } else if (!frame.path().isEmpty()) {
                    Object rendered = MongoResultTableAdapter.renderValue(value);
                    long bytes = rendered == null ? 0 : rendered.toString().getBytes(StandardCharsets.UTF_8).length;
                    sizes.merge(frame.path(), bytes, Long::sum);
                }
            }
        }
        return sizes;
    }

    private record Frame(String path, Object value) { }
    private record ArrayValues(java.util.Iterator<?> values) { }
}
