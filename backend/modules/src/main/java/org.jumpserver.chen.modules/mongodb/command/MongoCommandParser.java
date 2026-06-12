package org.jumpserver.chen.modules.mongodb.command;

import org.bson.Document;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a restricted MongoDB command DSL. This is a security boundary:
 * only read-only shapes are accepted (find / show dbs / show collections
 * / use). Arbitrary JavaScript, aggregation, and any write/admin command
 * are rejected so the console cannot be used to mutate data or run code.
 */
public class MongoCommandParser {

    private static final Pattern FIND = Pattern.compile(
            "^db\\.([A-Za-z0-9_.$-]+)\\.find\\((.*)\\)$",
            Pattern.DOTALL);
    private static final Pattern SORT_CLAUSE = Pattern.compile(
            "\\.sort\\((\\{.*?})\\)\\s*$", Pattern.DOTALL);
    private static final Pattern LIMIT_CLAUSE = Pattern.compile(
            "\\.limit\\((\\d+)\\)\\s*$");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\$where|\\$function|\\$accumulator|mapReduce|\\beval\\b",
            Pattern.CASE_INSENSITIVE);

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
            return parseFind(text);
        }
        throw new MongoCommandException("Unsupported command. Allowed: find / show dbs / show collections / use <db>");
    }

    private MongoCommand parseFind(String text) {
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

        Matcher findMatcher = FIND.matcher(work);
        if (!findMatcher.matches()) {
            throw new MongoCommandException("Only db.<collection>.find(...) is supported");
        }
        String collection = findMatcher.group(1);
        String args = findMatcher.group(2).trim();

        Document filter = new Document();
        Document projection = null;
        if (!args.isEmpty()) {
            int split = topLevelComma(args);
            if (split < 0) {
                filter = parseJson(args, "filter");
            } else {
                filter = parseJson(args.substring(0, split).trim(), "filter");
                String projPart = args.substring(split + 1).trim();
                if (!projPart.isEmpty()) {
                    projection = parseJson(projPart, "projection");
                }
            }
        }
        return MongoCommand.find(text, collection, filter, projection, sort, limit);
    }

    private int topLevelComma(String args) {
        int depth = 0;
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
                        return i;
                    }
                }
                default -> {
                }
            }
        }
        return -1;
    }

    private Document parseJson(String json, String what) {
        try {
            return Document.parse(json);
        } catch (RuntimeException e) {
            throw new MongoCommandException("Invalid " + what + " document: " + e.getMessage());
        }
    }
}
