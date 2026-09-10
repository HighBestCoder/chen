package org.jumpserver.chen.modules.mongodb.command;

import com.mongodb.client.model.*;
import org.bson.Document;
import java.util.*;

final class MongoBulkSupport {
    private MongoBulkSupport() { }
    static List<WriteModel<Document>> models(List<Document> operations) {
        List<WriteModel<Document>> models=new ArrayList<>();
        for(Document op:operations) {
            if(op.size()!=1 || !(op.values().iterator().next() instanceof Document))throw new MongoCommandException("Invalid bulk operation");
            String name=op.keySet().iterator().next();Document value=(Document)op.get(name);Document opts=new Document(value);
            switch(name) {
                case "insertOne" -> {
                    if(value.size()!=1)throw new MongoCommandException("insertOne bulk operation requires only document");
                    models.add(new InsertOneModel<>(document(value,"document")));
                }
                case "deleteOne", "deleteMany" -> {
                    Document filter=document(value,"filter");opts.remove("filter");validate(opts,"hint","collation");
                    DeleteOptions options=MongoOptions.apply(new DeleteOptions(),opts);
                    models.add(name.equals("deleteOne")?new DeleteOneModel<>(filter,options):new DeleteManyModel<>(filter,options));
                }
                case "replaceOne" -> {
                    Document filter=document(value,"filter"),replacement=document(value,"replacement");opts.remove("filter");opts.remove("replacement");
                    validate(opts,"upsert","hint","collation");models.add(new ReplaceOneModel<>(filter,replacement,MongoOptions.apply(new ReplaceOptions(),opts)));
                }
                case "updateOne", "updateMany" -> {
                    Document filter=document(value,"filter");Object update=value.get("update");opts.remove("filter");opts.remove("update");
                    validate(opts,"upsert","hint","collation","arrayFilters");UpdateOptions options=MongoOptions.apply(new UpdateOptions(),opts);
                    if(update instanceof Document d)models.add(name.equals("updateOne")?new UpdateOneModel<>(filter,d,options):new UpdateManyModel<>(filter,d,options));
                    else if(update instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Document.class::isInstance) && !opts.containsKey("arrayFilters")) {
                        List<Document> pipeline=list.stream().map(Document.class::cast).toList();
                        models.add(name.equals("updateOne")?new UpdateOneModel<>(filter,pipeline,options):new UpdateManyModel<>(filter,pipeline,options));
                    } else throw new MongoCommandException("Invalid bulk update");
                }
                default -> throw new MongoCommandException("Unsupported bulk operation: "+name);
            }
        }
        return models;
    }
    private static Document document(Document value,String name) {
        if(!(value.get(name) instanceof Document d))throw new MongoCommandException("Bulk operation requires "+name+" document");return d;
    }
    private static void validate(Document opts,String... allowed) {
        if(!Set.of(allowed).containsAll(opts.keySet()))throw new MongoCommandException("Unsupported bulk operation option");
        opts.forEach(MongoOptions::validate);
    }
}
