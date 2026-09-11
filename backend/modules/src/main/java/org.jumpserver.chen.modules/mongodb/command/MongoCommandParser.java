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
    private static final Pattern MODIFIER = Pattern.compile("^\\.(sort|limit|skip|hint|collation|maxTimeMS|batchSize)\\s*\\(");
    private static final String ALLOWED =
            "Allowed: getCollection / find / findOne / countDocuments / distinct / aggregate / insertOne / insertMany / updateOne / updateMany"
                    + " / deleteOne / deleteMany / replaceOne / findOneAndUpdate / findOneAndReplace / findOneAndDelete"
                    + " / bulkWrite / createIndex / getIndexes / dropIndex / runCommand / createCollection / dropDatabase / stats"
                    + " / drop / show dbs / show collections / use <db> / JavaScript statements";

    public MongoCommand parse(String input) {
        String text = input == null ? "" : input.trim();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            throw new MongoCommandException("Empty command");
        }
        if(isScript(text))return MongoCommand.script(input);
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
        Matcher databaseCall=Pattern.compile("^db\\.(runCommand|createCollection|dropDatabase|stats)\\s*\\(").matcher(text);
        if(databaseCall.find()) {
            int end=closingParenthesis(text,databaseCall.end()-1);
            if(!text.substring(end+1).trim().isEmpty())throw new MongoCommandException("Unexpected database command suffix");
            List<String> args=splitTopLevelArgs(text.substring(databaseCall.end(),end));
            String op=databaseCall.group(1);
            if(op.equals("runCommand")) {
                if(args.size()!=1)throw new MongoCommandException("runCommand requires one document");
                Document command=parseJson(args.get(0),"command");
                if(command.isEmpty() || command.containsKey("$db"))throw new MongoCommandException("Command must use the current database");
                if(java.util.Set.of("eval","mapreduce","getmore","killcursors","authenticate","saslstart","saslcontinue","logout").contains(command.keySet().iterator().next().toLowerCase(java.util.Locale.ROOT)))
                    throw new MongoCommandException("Command changes authentication, cursor ownership, or executes server JavaScript");
                return MongoCommand.databaseCommand(text,command);
            }
            if(op.equals("createCollection")) {
                if(args.isEmpty())throw new MongoCommandException("createCollection requires a name");
                requireAtMost(args,2,op);Document command=new Document("create",parseString(args.get(0),"collection"));
                if(args.size()==2) {Document opts=parseJson(args.get(1),"options"); if(opts.containsKey("create") || opts.containsKey("$db"))throw new MongoCommandException("Reserved command field");command.putAll(opts);}
                return MongoCommand.databaseCommand(text,command);
            }
            requireAtMost(args,0,op);
            return MongoCommand.databaseCommand(text,new Document(op.equals("stats")?"dbStats":"dropDatabase",1));
        }
        if (lower.startsWith("db.")) {
            return parseCall(text);
        }
        throw new MongoCommandException("Unsupported command. " + ALLOWED);
    }

    private boolean isScript(String text) {
        if(Pattern.compile("^(?:const|let|var|for|while|if|function|try|do|print|printjson)\\b").matcher(text).find())return true;
        StringBuilder code = new StringBuilder();
        char quote=0;boolean escaped=false;int depth=0;
        for(char c:text.toCharArray()) {
            if(quote!=0){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c==quote)quote=0;continue;}
            code.append(c);
            if(c=='\''||c=='"'||c=='`')quote=c;
            else if(c=='('||c=='['||c=='{')depth++;
            else if(c==')'||c==']'||c=='}')depth--;
            else if((c==';'||c=='\n'||c=='\r')&&depth==0)return true;
        }
        String visible = code.toString();
        return visible.contains("//") || visible.contains("/*")
                || Pattern.compile("\\.(?:toArray|forEach|map|hasNext|next|getSiblingDB)\\s*\\(").matcher(visible).find()
                || Pattern.compile("^db\\s*\\[").matcher(visible).find();
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
        Document cursorOptions=new Document();
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
            } else if (!name.equals("limit")) {
                if(!op.equals("find") || cursorOptions.containsKey(name))throw new MongoCommandException("Duplicate or invalid cursor option: "+name);
                Object option=parseValue(value);rejectExecutableOperators(option);MongoOptions.validate(name,option);cursorOptions.put(name,option);
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

        MongoCommand parsed = switch (op) {
            case "replaceOne", "findOneAndUpdate", "findOneAndReplace", "findOneAndDelete" -> buildMutation(text,collection,args,op);
            case "bulkWrite" -> buildBulk(text,collection,args);
            case "createIndex", "getIndexes", "dropIndex" -> buildIndex(text,collection,args,op);
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
        for(var e:cursorOptions.entrySet()) {
            if(parsed.getOptions().containsKey(e.getKey()))throw new MongoCommandException("Duplicate cursor option: "+e.getKey());
            parsed.getOptions().put(e.getKey(),e.getValue());
        }
        return parsed;
    }

    private MongoCommand buildFind(String text, String collection, List<String> args,
                                   Document sort, Integer limit) {
        requireAtMost(args, 3, "find(filter, projection, options)");
        Document filter = args.isEmpty() ? new Document() : parseJson(args.get(0), "filter");
        Document projection = args.size() > 1 ? parseJson(args.get(1), "projection") : null;
        return MongoCommand.find(text, collection, filter, projection, sort, limit).withOptions(options(args,2,"hint","collation","maxTimeMS","batchSize","comment","let","min","max","returnKey","showRecordId"));
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
        return MongoCommand.aggregate(text, collection, pipeline, limit).withOptions(options(args, 1, "allowDiskUse", "maxTimeMS", "hint", "collation", "comment", "let", "batchSize", "bypassDocumentValidation", "writeConcern"));
    }

    private MongoCommand buildInsert(String text, String collection, List<String> args, boolean many) {
        String shape = many ? "insertMany(documents)" : "insertOne(document)";
        if (args.isEmpty()) {
            throw new MongoCommandException(shape + " requires a document argument");
        }
        requireAtMost(args, 2, shape);
        List<Document> documents = many
                ? parseDocumentArray(args.get(0), "documents")
                : List.of(parseJson(args.get(0), "document"));
        if (documents.isEmpty()) {
            throw new MongoCommandException(shape + " requires at least one document");
        }
        return MongoCommand.insert(text, collection, documents).withMulti(many).withOptions(options(args, 1, many ? "ordered" : "bypassDocumentValidation", "bypassDocumentValidation", "comment", "writeConcern"));
    }

    private MongoCommand buildUpdate(String text, String collection, List<String> args, boolean multi) {
        String shape = (multi ? "updateMany" : "updateOne") + "(filter, update)";
        if (args.size() < 2) {
            throw new MongoCommandException(shape + " requires both a filter and an update document");
        }
        requireAtMost(args, 3, shape);
        Document filter = parseJson(args.get(0), "filter");
        if(args.get(1).trim().startsWith("[")) {
            List<Document> pipeline=parseDocumentArray(args.get(1),"update pipeline");
            if(pipeline.isEmpty())throw new MongoCommandException("Update pipeline must not be empty");
            return MongoCommand.update(text,collection,filter,null,multi).withUpdatePipeline(pipeline)
                    .withOptions(options(args,2,"upsert","hint","collation","writeConcern","bypassDocumentValidation","let","comment"));
        }
        Document update = parseJson(args.get(1), "update");
        if (update.isEmpty()) {
            throw new MongoCommandException(shape + " requires a non-empty update document");
        }
        return MongoCommand.update(text, collection, filter, update, multi).withOptions(options(args, 2, "upsert", "arrayFilters", "hint", "collation", "writeConcern", "bypassDocumentValidation", "let", "comment"));
    }

    private MongoCommand buildDelete(String text, String collection, List<String> args, boolean multi) {
        String shape = (multi ? "deleteMany" : "deleteOne") + "(filter)";
        if (args.isEmpty()) {
            throw new MongoCommandException(shape + " requires a filter document");
        }
        requireAtMost(args, 2, shape);
        return MongoCommand.delete(text, collection, parseJson(args.get(0), "filter"), multi).withOptions(options(args,1,"hint","collation","writeConcern","let","comment"));
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
                .withOptions(options(args, 2, "sort", "maxTimeMS", "hint", "collation", "comment", "let"));
    }

    private MongoCommand buildCount(String text, String collection, List<String> args) {
        requireAtMost(args, 2, "countDocuments(filter, options)");
        return MongoCommand.read(MongoCommand.Type.COUNT, text, collection,
                args.isEmpty() ? new Document() : parseJson(args.get(0), "filter"), null, null)
                .withOptions(options(args, 1, "skip", "limit", "maxTimeMS", "hint", "collation", "comment"));
    }

    private MongoCommand buildDistinct(String text, String collection, List<String> args) {
        if (args.isEmpty()) throw new MongoCommandException("distinct requires a field name");
        requireAtMost(args, 3, "distinct(field, filter, options)");
        return MongoCommand.read(MongoCommand.Type.DISTINCT, text, collection,
                args.size() < 2 ? new Document() : parseJson(args.get(1), "filter"), null,
                parseString(args.get(0), "field")).withOptions(options(args,2,"collation","maxTimeMS","comment"));
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
            if (!new java.util.HashSet<>(java.util.List.of(allowed)).contains(key)) throw new MongoCommandException("Unsupported option: " + key);
            MongoOptions.validate(key,value);
        }
        return options;
    }

    private MongoCommand buildMutation(String text,String collection,List<String> args,String op) {
        boolean delete=op.equals("findOneAndDelete");
        int index=delete?1:2;
        if(args.size()<index)throw new MongoCommandException(op+" requires filter"+(delete?"":" and update/replacement"));
        requireAtMost(args,index+1,op);
        Document opts=options(args,index,delete?new String[]{"projection","sort","maxTimeMS","hint","collation","writeConcern","let","comment"}:
                op.equals("replaceOne")?new String[]{"upsert","hint","collation","writeConcern","bypassDocumentValidation","let","comment"}:
                new String[]{"projection","sort","maxTimeMS","hint","collation","writeConcern","bypassDocumentValidation","let","comment","upsert","returnDocument","returnNewDocument","arrayFilters"});
        if(opts.containsKey("returnDocument") && opts.containsKey("returnNewDocument"))throw new MongoCommandException("Choose one return-document option");
        if(!op.equals("findOneAndUpdate") && opts.containsKey("arrayFilters"))throw new MongoCommandException("arrayFilters requires update");
        MongoCommand.Type type=switch(op) {case "replaceOne"->MongoCommand.Type.REPLACE;case "findOneAndUpdate"->MongoCommand.Type.FIND_AND_UPDATE;case "findOneAndReplace"->MongoCommand.Type.FIND_AND_REPLACE;default->MongoCommand.Type.FIND_AND_DELETE;};
        boolean pipeline=!delete && args.get(1).trim().startsWith("[");
        if(pipeline && (!op.equals("findOneAndUpdate") || opts.containsKey("arrayFilters")))throw new MongoCommandException("Invalid pipeline update options");
        var c=MongoCommand.operation(type,text,collection,parseJson(args.get(0),"filter"),delete||pipeline?null:parseJson(args.get(1),"update"),opts);
        if(pipeline)c.withUpdatePipeline(parseDocumentArray(args.get(1),"update pipeline"));
        return c;
    }

    private MongoCommand buildBulk(String text,String collection,List<String> args) {
        if(args.isEmpty())throw new MongoCommandException("bulkWrite requires operations");
        requireAtMost(args,2,"bulkWrite");List<Document> operations=parseDocumentArray(args.get(0),"operations");
        if(operations.isEmpty())throw new MongoCommandException("bulkWrite requires at least one operation");
        MongoBulkSupport.models(operations); // validate the entire batch before any write
        return MongoCommand.operation(MongoCommand.Type.BULK_WRITE,text,collection,null,null,
                options(args,1,"ordered","bypassDocumentValidation","writeConcern","let","comment")).withDocuments(operations);
    }
    private MongoCommand buildIndex(String text,String collection,List<String> args,String op) {
        if(op.equals("getIndexes")) {requireAtMost(args,0,op);return MongoCommand.operation(MongoCommand.Type.LIST_INDEXES,text,collection,null,null,new Document());}
        if(args.isEmpty())throw new MongoCommandException(op+" requires a key or name");
        if(op.equals("dropIndex")) {requireAtMost(args,1,op);return MongoCommand.operation(MongoCommand.Type.DROP_INDEX,text,collection,null,null,new Document("index",parseValue(args.get(0))));}
        requireAtMost(args,2,op);
        return MongoCommand.operation(MongoCommand.Type.CREATE_INDEX,text,collection,parseJson(args.get(0),"index keys"),null,
                options(args,1,"name","unique","sparse","background","expireAfterSeconds","partialFilterExpression","collation","wildcardProjection","hidden","version","textVersion","sphereVersion","weights","defaultLanguage","languageOverride","storageEngine"));
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
