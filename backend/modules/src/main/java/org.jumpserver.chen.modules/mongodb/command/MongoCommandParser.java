package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a restricted MongoDB command DSL.
 *
 * <p>Read shapes (find / aggregate / show dbs / show collections / use) and
 * data-modifying shapes (insertOne / insertMany / updateOne / updateMany /
 * deleteOne / deleteMany / drop) are accepted. Writes reach the driver only
 * after {@code MongoQueryConsole} has run the JMS ACL gate, which is the same
 * approval path the relational console applies to arbitrary DML/DDL — the two
 * engines are deliberately symmetric here.</p>
 *
 * <p>What stays rejected is <em>server-side JavaScript execution</em>:
 * {@code $where}, {@code $function}, {@code $accumulator}, {@code mapReduce}
 * and {@code eval}. That is a different boundary from data modification and is
 * not relaxed.</p>
 *
 * <p>Note for operators: aggregation stages {@code $out} and {@code $merge}
 * write to a collection. They parse as an AGGREGATE command, so a high-risk
 * ACL rule that is meant to catch every write must cover them explicitly in
 * addition to the {@code drop|update|delete|insert} verbs.</p>
 */
public class MongoCommandParser {

    private static final Pattern CALL = Pattern.compile(
            "^db\\.([A-Za-z0-9_.$-]+)\\.([A-Za-z][A-Za-z0-9]*)\\s*\\(");
    private static final Pattern MODIFIER = Pattern.compile("^\\.(sort|limit|skip)\\s*\\(");
    private static final String ALLOWED =
            "Allowed: getCollection / find / findOne / countDocuments / distinct / aggregate / insertOne / insertMany / updateOne / updateMany"
                    + " / deleteOne / deleteMany / drop / show dbs / show collections / use <db>";

    public MongoCommand parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            throw new MongoCommandException("Empty command");
        }
        String lower = text.toLowerCase();
        if (lower.equals("show dbs") || lower.equals("show databases")) {
            return MongoCommand.showDbs(text);
        }
        if (lower.equals("show collections") || lower.equals("show tables")) {
            return MongoCommand.showCollections(text);
        }
        if (lower.startsWith("use ")) {
            String db = text.substring(4).trim();
            if (db.isEmpty()) {
                throw new MongoCommandException("Database name required for 'use'");
            }
            return MongoCommand.useDb(text, db);
        }
        if (lower.startsWith("db.")) {
            return parseCall(text);
        }
        throw new MongoCommandException("Unsupported command. " + ALLOWED);
    }

    private MongoCommand parseCall(String text) {
        String callText = text;
        String selectedCollection = null;
        Matcher selector = Pattern.compile("^db\\.getCollection\\s*\\(").matcher(text);
        if (selector.find()) {
            int close = closingParenthesis(text, selector.end() - 1);
            List<String> names = splitTopLevelArgs(text.substring(selector.end(), close));
            if (names.size() != 1) throw new MongoCommandException("getCollection requires one string name");
            selectedCollection = parseString(names.get(0), "collection");
            callText = "db.__selected__" + text.substring(close + 1).trim();
        }
        Matcher callMatcher = CALL.matcher(callText);
        if (!callMatcher.find()) {
            throw new MongoCommandException("Unsupported command. " + ALLOWED);
        }
        if (selectedCollection != null && !callMatcher.group(1).equals("__selected__")) {
            throw new MongoCommandException("getCollection must be followed directly by a supported method");
        }
        String collection = selectedCollection == null ? callMatcher.group(1) : selectedCollection;
        String op = callMatcher.group(2);
        if (op.equalsIgnoreCase("mapReduce") || op.equalsIgnoreCase("eval")) {
            throw new MongoCommandException("Operator not allowed in restricted console: " + op);
        }
        int end = closingParenthesis(callText, callMatcher.end() - 1);
        List<String> args = splitTopLevelArgs(callText.substring(callMatcher.end(), end).trim());
        String remaining = callText.substring(end + 1).trim();
        Integer limit = null;
        Document sort = null;
        Integer skip = null;
        while (!remaining.isEmpty()) {
            Matcher modifier = MODIFIER.matcher(remaining);
            if (!modifier.find()) {
                throw new MongoCommandException("Unsupported cursor modifier: " + remaining);
            }
            String name = modifier.group(1);
            int close = closingParenthesis(remaining, modifier.end() - 1);
            String value = remaining.substring(modifier.end(), close).trim();
            if (name.equals("sort")) {
                if (!op.equals("find") || sort != null) {
                    throw new MongoCommandException("sort is only supported once on find");
                }
                sort = parseJson(value, "sort");
            } else if (name.equals("skip")) {
                if (!op.equals("find") || skip != null) throw new MongoCommandException("skip is only supported once on find");
                skip = nonNegativeInteger(parseValue(value), "skip");
            } else {
                if (!(op.equals("find") || op.equals("aggregate")) || limit != null) {
                    throw new MongoCommandException("limit is only supported once on find or aggregate");
                }
                try {
                    if (!value.matches("[0-9]+")) {
                        throw new NumberFormatException();
                    }
                    limit = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new MongoCommandException("Invalid limit: expected an integer from 0 to 2147483647");
                }
            }
            remaining = remaining.substring(close + 1).trim();
        }

        return switch (op) {
            case "find" -> buildFind(text, collection, args, sort, limit).withSkip(skip == null ? 0 : skip);
            case "findOne" -> buildFindOne(text, collection, args);
            case "countDocuments" -> buildCount(text, collection, args);
            case "distinct" -> buildDistinct(text, collection, args);
            case "aggregate" -> buildAggregate(text, collection, args, limit);
            case "insertOne" -> buildInsert(text, collection, args, false);
            case "insertMany" -> buildInsert(text, collection, args, true);
            case "updateOne" -> buildUpdate(text, collection, args, false);
            case "updateMany" -> buildUpdate(text, collection, args, true);
            case "deleteOne" -> buildDelete(text, collection, args, false);
            case "deleteMany" -> buildDelete(text, collection, args, true);
            case "drop" -> buildDrop(text, collection, args);
            default -> throw new MongoCommandException(
                    "Unsupported operation 'db." + collection + "." + op + "()'. " + ALLOWED);
        };
    }

    private MongoCommand buildFind(String text, String collection, List<String> args,
                                   Document sort, Integer limit) {
        requireAtMost(args, 2, "find(filter, projection)");
        Document filter = args.isEmpty() ? new Document() : parseJson(args.get(0), "filter");
        Document projection = args.size() > 1 ? parseJson(args.get(1), "projection") : null;
        return MongoCommand.find(text, collection, filter, projection, sort, limit);
    }

    private MongoCommand buildAggregate(String text, String collection, List<String> args, Integer limit) {
        if (args.isEmpty()) {
            throw new MongoCommandException("aggregate(pipeline) requires a pipeline array");
        }
        requireAtMost(args, 2, "aggregate(pipeline, options)");
        List<Document> pipeline = parseDocumentArray(args.get(0), "pipeline");
        if (pipeline.stream().anyMatch(stage -> stage == null)) {
            throw new MongoCommandException("Invalid pipeline array: null stage");
        }
        if (limit != null && !pipeline.isEmpty()) {
            Document last = pipeline.get(pipeline.size() - 1);
            if (last.containsKey("$out") || last.containsKey("$merge")) {
                throw new MongoCommandException("limit cannot follow a terminal $out or $merge stage");
            }
        }
        return MongoCommand.aggregate(text, collection, pipeline, limit).withOptions(options(args, 1, "allowDiskUse", "maxTimeMS"));
    }

    private MongoCommand buildInsert(String text, String collection, List<String> args, boolean many) {
        String shape = many ? "insertMany(documents)" : "insertOne(document)";
        if (args.isEmpty()) {
            throw new MongoCommandException(shape + " requires a document argument");
        }
        requireAtMost(args, many ? 2 : 1, shape);
        List<Document> documents = many
                ? parseDocumentArray(args.get(0), "documents")
                : List.of(parseJson(args.get(0), "document"));
        if (documents.isEmpty()) {
            throw new MongoCommandException(shape + " requires at least one document");
        }
        return MongoCommand.insert(text, collection, documents).withOptions(options(args, 1, "ordered"));
    }

    private MongoCommand buildUpdate(String text, String collection, List<String> args, boolean multi) {
        String shape = (multi ? "updateMany" : "updateOne") + "(filter, update)";
        if (args.size() < 2) {
            throw new MongoCommandException(shape + " requires both a filter and an update document");
        }
        requireAtMost(args, 3, shape);
        Document filter = parseJson(args.get(0), "filter");
        Document update = parseJson(args.get(1), "update");
        if (update.isEmpty()) {
            throw new MongoCommandException(shape + " requires a non-empty update document");
        }
        return MongoCommand.update(text, collection, filter, update, multi).withOptions(options(args, 2, "upsert", "arrayFilters"));
    }

    private MongoCommand buildDelete(String text, String collection, List<String> args, boolean multi) {
        String shape = (multi ? "deleteMany" : "deleteOne") + "(filter)";
        if (args.isEmpty()) {
            throw new MongoCommandException(shape + " requires a filter document");
        }
        requireAtMost(args, 1, shape);
        return MongoCommand.delete(text, collection, parseJson(args.get(0), "filter"), multi);
    }

    private MongoCommand buildDrop(String text, String collection, List<String> args) {
        if (!args.isEmpty()) {
            throw new MongoCommandException("drop() does not take arguments");
        }
        return MongoCommand.dropCollection(text, collection);
    }

    private MongoCommand buildFindOne(String text, String collection, List<String> args) {
        requireAtMost(args, 3, "findOne(filter, projection, options)");
        return MongoCommand.read(MongoCommand.Type.FIND_ONE, text, collection,
                args.isEmpty() ? new Document() : parseJson(args.get(0), "filter"),
                args.size() < 2 ? null : parseJson(args.get(1), "projection"), null)
                .withOptions(options(args, 2, "sort", "maxTimeMS"));
    }

    private MongoCommand buildCount(String text, String collection, List<String> args) {
        requireAtMost(args, 2, "countDocuments(filter, options)");
        return MongoCommand.read(MongoCommand.Type.COUNT, text, collection,
                args.isEmpty() ? new Document() : parseJson(args.get(0), "filter"), null, null)
                .withOptions(options(args, 1, "skip", "limit", "maxTimeMS"));
    }

    private MongoCommand buildDistinct(String text, String collection, List<String> args) {
        if (args.isEmpty()) throw new MongoCommandException("distinct requires a field name");
        requireAtMost(args, 2, "distinct(field, filter)");
        return MongoCommand.read(MongoCommand.Type.DISTINCT, text, collection,
                args.size() < 2 ? new Document() : parseJson(args.get(1), "filter"), null,
                parseString(args.get(0), "field"));
    }

    private Object parseValue(String text) {
        if (splitTopLevelArgs(text).size() != 1) throw new MongoCommandException("Expected one BSON value");
        try { return parseCompleteDocument("{value:" + text + "}").get("value"); }
        catch (RuntimeException e) { throw new MongoCommandException("Invalid BSON value"); }
    }

    private String parseString(String text, String label) {
        Object value = parseValue(text);
        if (!(value instanceof String s) || s.isEmpty() || s.indexOf('\0') >= 0)
            throw new MongoCommandException("Expected a non-empty " + label + " string");
        return (String) value;
    }

    private int nonNegativeInteger(Object value, String label) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0
                || ((Number) value).longValue() > Integer.MAX_VALUE)
            throw new MongoCommandException(label + " must be an integer from 0 to 2147483647");
        return ((Number) value).intValue();
    }

    private Document options(List<String> args, int index, String... allowed) {
        if (args.size() <= index) return new Document();
        Document options = parseJson(args.get(index), "options");
        for (var entry : options.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (!java.util.Set.of(allowed).contains(key)) throw new MongoCommandException("Unsupported option: " + key);
            switch (key) {
                case "skip", "limit", "maxTimeMS" -> entry.setValue(nonNegativeInteger(value, key));
                case "sort" -> { if (!(value instanceof Document)) throw new MongoCommandException("sort must be a document"); }
                case "arrayFilters" -> {
                    if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof Document)))
                        throw new MongoCommandException("arrayFilters must be an array of documents");
                }
                default -> { if (!(value instanceof Boolean)) throw new MongoCommandException(key + " must be boolean"); }
            }
        }
        return options;
    }

    private void requireAtMost(List<String> args, int max, String shape) {
        if (args.size() > max) {
            throw new MongoCommandException("Too many arguments for " + shape);
        }
    }

    /** Locate a call boundary without interpreting parentheses inside BSON strings. */
    private int closingParenthesis(String text, int open) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0) {
                return i;
            }
        }
        throw new MongoCommandException("Unclosed command or string");
    }

    private List<String> splitTopLevelArgs(String args) {
        List<String> parts = new ArrayList<>();
        if (args.isEmpty()) {
            return parts;
        }
        List<Character> stack = new ArrayList<>();
        int start = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            switch (ch) {
                case '"', '\'' -> quote = ch;
                case '{', '[', '(' -> stack.add(ch);
                case '}', ']', ')' -> {
                    char expected = ch == '}' ? '{' : ch == ']' ? '[' : '(';
                    if (stack.isEmpty() || stack.remove(stack.size() - 1) != expected) {
                        throw new MongoCommandException("Unbalanced command arguments");
                    }
                }
                case ',' -> {
                    if (stack.isEmpty()) {
                        addArgument(parts, args.substring(start, i));
                        start = i + 1;
                    }
                }
                default -> { }
            }
        }
        if (quote != 0 || !stack.isEmpty()) {
            throw new MongoCommandException("Unclosed command arguments");
        }
        addArgument(parts, args.substring(start));
        return parts;
    }

    private void addArgument(List<String> parts, String value) {
        if (value.trim().isEmpty()) {
            throw new MongoCommandException("Empty command argument");
        }
        parts.add(value.trim());
    }

    private Document parseJson(String json, String what) {
        try {
            Document document = parseCompleteDocument(json);
            rejectExecutableOperators(document);
            return document;
        } catch (RuntimeException e) {
            throw new MongoCommandException("Invalid " + what + " document: " + e.getMessage());
        }
    }

    private Document parseCompleteDocument(String json) {
        try (var reader = new org.bson.json.JsonReader(json)) {
            Document value = new org.bson.codecs.DocumentCodec().decode(reader,
                    org.bson.codecs.DecoderContext.builder().build());
            if (reader.readBsonType() != org.bson.BsonType.END_OF_DOCUMENT) {
                throw new MongoCommandException("Unexpected text after BSON document");
            }
            return value;
        }
    }

    private void rejectExecutableOperators(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (java.util.Set.of("$where", "$function", "$accumulator").contains(entry.getKey())) {
                    throw new MongoCommandException("Operator not allowed in restricted console: " + entry.getKey());
                }
                rejectExecutableOperators(entry.getValue());
            }
        } else if (value instanceof Iterable<?> values) {
            for (Object child : values) {
                rejectExecutableOperators(child);
            }
        }
    }

    /**
     * Parses a JSON array of documents by wrapping it in a throwaway object,
     * so shell-mode extended JSON (e.g. {@code ISODate(...)}) keeps working.
     */
    private List<Document> parseDocumentArray(String json, String what) {
        List<Document> list;
        try {
            Document wrapper = parseCompleteDocument("{\"__array__\": " + json + "}");
            rejectExecutableOperators(wrapper);
            list = wrapper.getList("__array__", Document.class);
        } catch (RuntimeException e) {
            throw new MongoCommandException("Invalid " + what + " array: " + e.getMessage());
        }
        if (list == null || list.stream().anyMatch(java.util.Objects::isNull)) {
            throw new MongoCommandException("Invalid " + what + " array: expected a JSON array");
        }
        return list;
    }
}
