package org.jumpserver.chen.modules.mongodb;

import org.jumpserver.chen.framework.datasource.hints.SQLHintsHandler;

import java.util.List;
import java.util.Map;

/**
 * No-op hints handler for Mongo. ResourceController.getHints calls
 * getResourceBrowser().getSQLHintsHandler() directly, so this must be
 * non-null. Mongo autocomplete is a later phase; P1 returns no hints.
 */
public class MongoSqlHintsHandlerStub implements SQLHintsHandler {

    @Override
    public Map<String, List<String>> getHints(String nodeKey, String context) {
        return Map.of();
    }
}
