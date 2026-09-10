package org.jumpserver.chen.modules.mongodb;

import com.mongodb.MongoException;
import java.util.Collections;
import java.util.IdentityHashMap;

public final class MongoPermissionErrorClassifier {
    private MongoPermissionErrorClassifier() { }
    public static boolean isPermissionDenied(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && seen.add(current); current = current.getCause()) {
            // Unauthorized (13), distinct from AuthenticationFailed (18).
            if (current instanceof MongoException mongo && mongo.getCode() == 13) return true;
        }
        return false;
    }
}
