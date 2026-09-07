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

    /** {@code db.<collection>.<op>(<args>)} — group 1 is greedy so the last {@code .op(} wins. */
    private static final Pattern CALL = Pattern.compile(
            "^db\\.([A-Za-z0-9_.$-]+)\\.([A-Za-z][A-Za-z0-9]*)\\s*\\((.*)\\)$",
            Pattern.DOTALL);
    private static final Pattern SORT_CLAUSE = Pattern.compile(
            "\\.sort\\((\\{.*?})\\)\\s*$", Pattern.DOTALL);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile(
            "\\.limit\\((\\d+)\\)\\s*$");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\$where|\\$function|\\$accumulator|mapReduce|\\beval\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String ALLOWED =
            "Allowed: find / aggregate / insertOne / insertMany / updateOne / updateMany"
                    + " / deleteOne / deleteMany / drop / show dbs / show collections / use <db>";

    public MongoCommand parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            throw new MongoCommandException("Empty command");
        }
        if (FORBIDDEN.matcher(text).find()) {
            throw new MongoCommandException("Operator not allowed in restricted console: " + text);
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
        String work = text;
        Integer limit = null;
        Document sort = null;

        Matcher limitMatcher = LIMIT_CLAUSE.matcher(work);
        if (limitMatcher.find()) {
            limit = Integer.parseInt(limitMatcher.group(1));
            work = work.substring(0, limitMatcher.start()).trim();
        }

        Matcher sortMatcher = SORT_CLAUSE.matcher(work);
        if (sortMatcher.find()) {
            sort = parseJson(sortMatcher.group(1), "sort");
            work = work.substring(0, sortMatcher.start()).trim();
        }

        Matcher callMatcher = CALL.matcher(work);
        if (!callMatcher.matches()) {
            throw new MongoCommandException("Unsupported command. " + ALLOWED);
        }
        String collection = callMatcher.group(1);
        String op = callMatcher.group(2);
        List<String> args = splitTopLevelArgs(callMatcher.group(3).trim());

        return switch (op) {
            case "find" -> buildFind(text, collection, args, sort, limit);
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
        requireAtMost(args, 1, "aggregate(pipeline)");
        return MongoCommand.aggregate(text, collection, parseDocumentArray(args.get(0), "pipeline"), limit);
    }

    private MongoCommand buildInsert(String text, String collection, List<String> args, boolean many) {
        String shape = many ? "insertMany(documents)" : "insertOne(document)";
        if (args.isEmpty()) {
            throw new MongoCommandException(shape + " requires a document argument");
        }
        requireAtMost(args, 1, shape);
        List<Document> documents = many
                ? parseDocumentArray(args.get(0), "documents")
                : List.of(parseJson(args.get(0), "document"));
        if (documents.isEmpty()) {
            throw new MongoCommandException(shape + " requires at least one document");
        }
        return MongoCommand.insert(text, collection, documents);
    }

    private MongoCommand buildUpdate(String text, String collection, List<String> args, boolean multi) {
        String shape = (multi ? "updateMany" : "updateOne") + "(filter, update)";
        if (args.size() < 2) {
            throw new MongoCommandException(shape + " requires both a filter and an update document");
        }
        requireAtMost(args, 2, shape);
        Document filter = parseJson(args.get(0), "filter");
        Document update = parseJson(args.get(1), "update");
        if (update.isEmpty()) {
            throw new MongoCommandException(shape + " requires a non-empty update document");
        }
        return MongoCommand.update(text, collection, filter, update, multi);
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

    private void requireAtMost(List<String> args, int max, String shape) {
        if (args.size() > max) {
            throw new MongoCommandException("Too many arguments for " + shape);
        }
    }

    /**
     * Splits a call's argument list on commas that sit outside any brace,
     * bracket or quoted string. An empty argument string yields an empty list.
     */
    private List<String> splitTopLevelArgs(String args) {
        List<String> parts = new ArrayList<>();
        if (args.isEmpty()) {
            return parts;
        }
        int depth = 0;
        int start = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (inString) {
                if (ch == stringChar && args.charAt(i - 1) != '\\') {
                    inString = false;
                }
                continue;
            }
            switch (ch) {
                case '"', '\'' -> {
                    inString = true;
                    stringChar = ch;
                }
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        parts.add(args.substring(start, i).trim());
                        start = i + 1;
                    }
                }
                default -> {
                }
            }
        }
        parts.add(args.substring(start).trim());
        parts.removeIf(String::isEmpty);
        return parts;
    }

    private Document parseJson(String json, String what) {
        try {
            return Document.parse(json);
        } catch (RuntimeException e) {
            throw new MongoCommandException("Invalid " + what + " document: " + e.getMessage());
        }
    }

    /**
     * Parses a JSON array of documents by wrapping it in a throwaway object,
     * so shell-mode extended JSON (e.g. {@code ISODate(...)}) keeps working.
     */
    private List<Document> parseDocumentArray(String json, String what) {
        List<Document> list;
        try {
            Document wrapper = Document.parse("{\"__array__\": " + json + "}");
            list = wrapper.getList("__array__", Document.class);
        } catch (RuntimeException e) {
            throw new MongoCommandException("Invalid " + what + " array: " + e.getMessage());
        }
        if (list == null) {
            throw new MongoCommandException("Invalid " + what + " array: expected a JSON array");
        }
        return list;
    }
}
