package org.jumpserver.chen.modules.mongodb.command;

import com.mongodb.WriteConcern;
import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Only parser-approved option names reach fixed Mongo driver option objects. */
public final class MongoOptions {
    private MongoOptions() { }
    public static void validate(String key, Object value) {
        switch (key) {
            case "hint" -> { if (!(value instanceof Document) && !(value instanceof String s && !s.isEmpty())) fail(key); }
            case "collation" -> { if (!(value instanceof Document)) fail(key); collation((Document)value); }
            case "writeConcern" -> { if (!(value instanceof Document)) fail(key); writeConcern((Document)value); }
            case "sort", "projection", "let", "min", "max", "partialFilterExpression", "wildcardProjection", "weights", "storageEngine" -> { if (!(value instanceof Document)) fail(key); }
            case "arrayFilters" -> { if (!(value instanceof List<?> l) || l.stream().anyMatch(v -> !(v instanceof Document))) fail(key); }
            case "returnDocument" -> { if (!("before".equals(value) || "after".equals(value))) fail(key); }
            case "name", "comment", "defaultLanguage", "languageOverride" -> { if (!(value instanceof String)) fail(key); }
            case "skip", "limit", "maxTimeMS", "batchSize", "expireAfterSeconds", "version", "textVersion", "sphereVersion" -> integer(key,value);
            default -> { if (!(value instanceof Boolean)) fail(key); }
        }
    }
    private static void fail(String key) { throw new MongoCommandException("Invalid option: " + key); }
    private static int integer(String key,Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue()<0 || ((Number)value).longValue()>Integer.MAX_VALUE) fail(key);
        return ((Number)value).intValue();
    }
    public static WriteConcern writeConcern(Document d) {
        if (!Set.of("w", "j", "wtimeout").containsAll(d.keySet())) fail("writeConcern");
        WriteConcern w=WriteConcern.ACKNOWLEDGED;
        if(d.containsKey("w")) {
            Object v=d.get("w");
            if(v instanceof String s && !s.isEmpty()) w=new WriteConcern(s);
            else w=new WriteConcern(integer("writeConcern.w",v));
        }
        if(d.containsKey("j")) { if(!(d.get("j") instanceof Boolean))fail("writeConcern.j"); w=w.withJournal(d.getBoolean("j")); }
        if(d.containsKey("wtimeout"))w=w.withWTimeout(integer("writeConcern.wtimeout",d.get("wtimeout")),TimeUnit.MILLISECONDS);
        return w;
    }
    public static Collation collation(Document d) {
        if (!(d.get("locale") instanceof String s) || s.isEmpty()) fail("collation.locale");
        var b=Collation.builder().locale(d.getString("locale"));
        for(var e:d.entrySet()) {
            Object v=e.getValue();
            try {
                switch(e.getKey()) {
                    case "locale" -> { }
                    case "strength" -> b.collationStrength(CollationStrength.fromInt(integer("strength",v)));
                    case "caseFirst" -> b.collationCaseFirst(CollationCaseFirst.fromString((String)v));
                    case "alternate" -> b.collationAlternate(CollationAlternate.fromString((String)v));
                    case "maxVariable" -> b.collationMaxVariable(CollationMaxVariable.fromString((String)v));
                    case "caseLevel" -> b.caseLevel((Boolean)v);
                    case "numericOrdering" -> b.numericOrdering((Boolean)v);
                    case "normalization" -> b.normalization((Boolean)v);
                    case "backwards" -> b.backwards((Boolean)v);
                    default -> fail("collation."+e.getKey());
                }
                if(v==null)fail("collation."+e.getKey());
            } catch(RuntimeException ex) { throw new MongoCommandException("Invalid collation option: "+e.getKey()); }
        }
        return b.build();
    }
    public static <T> T apply(T target, Document options, String... excluded) {
        Set<String> exclusions=new HashSet<>(List.of(excluded));exclusions.add("writeConcern");
        // Driver implementations can be package-private: invoke their public API.
        Class<?> api = target instanceof com.mongodb.client.FindIterable ? com.mongodb.client.FindIterable.class
                : target instanceof com.mongodb.client.AggregateIterable ? com.mongodb.client.AggregateIterable.class
                : target instanceof com.mongodb.client.DistinctIterable ? com.mongodb.client.DistinctIterable.class : target.getClass();
        for(var entry:options.entrySet()) {
            String name=entry.getKey();Object value=entry.getValue();
            if(exclusions.contains(name))continue;
            try {
                if(name.equals("maxTimeMS")) { api.getMethod("maxTime",long.class,TimeUnit.class).invoke(target,((Number)value).longValue(),TimeUnit.MILLISECONDS);continue; }
                if(name.equals("expireAfterSeconds")) { api.getMethod("expireAfter",Long.class,TimeUnit.class).invoke(target,((Number)value).longValue(),TimeUnit.SECONDS);continue; }
                if(name.equals("collation"))value=collation((Document)value);
                if(name.equals("hint") && value instanceof String)name="hintString";
                if(name.equals("returnNewDocument")) {name="returnDocument";value=(Boolean)value?ReturnDocument.AFTER:ReturnDocument.BEFORE;}
                else if(name.equals("returnDocument"))value=value.equals("after")?ReturnDocument.AFTER:ReturnDocument.BEFORE;
                if(value instanceof Long && !name.equals("expireAfterSeconds"))value=((Long)value).intValue();
                boolean applied=false;
                for(var method:api.getMethods()) {
                    if(!method.getName().equals(name) || method.getParameterCount()!=1)continue;
                    Class<?> type=method.getParameterTypes()[0];
                    if(type.isInstance(value) || (type==int.class && value instanceof Integer) || (type==boolean.class && value instanceof Boolean)) {
                        method.invoke(target,value);applied=true;break;
                    }
                }
                if(!applied)fail(entry.getKey());
            } catch(ReflectiveOperationException ex) { throw new MongoCommandException("Cannot apply option: "+entry.getKey()); }
        }
        return target;
    }
}
