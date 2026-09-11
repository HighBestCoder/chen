package org.jumpserver.chen.modules.mongodb.command;

import org.jumpserver.chen.framework.console.dataview.LoadDataInterface;
import org.jumpserver.chen.framework.datasource.sql.RowConsumer;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.wisp.Common;

import java.sql.SQLException;

/** Each refresh/export is an execution in the result view's original database. */
public final class MongoQueryLoader implements LoadDataInterface {
    private final Session session;
    private final MongoConnectionManager manager;
    private final MongoActuator actuator;
    private final MongoCommand command;
    private final String database;
    private final String commandText;
    private final ACLResult initialApproval;
    private boolean firstLoad = true;

    public MongoQueryLoader(Session session, MongoConnectionManager manager, MongoActuator actuator,
                            MongoCommand command, ACLResult initialApproval, String commandText) {
        this.session = session;
        this.manager = manager;
        this.actuator = actuator;
        this.command = command;
        this.commandText = commandText;
        this.database = manager.getCurrentDatabaseName();
        this.initialApproval = initialApproval;
    }

    @Override
    public SQLQueryResult loadData(SQLQueryParams params, RowConsumer sink) throws SQLException {
        String previous = manager.getCurrentDatabaseName();
        CommandRecord record = new CommandRecord(commandText);
        session.beginCommand(record);
        manager.setDatabaseContext(database);
        try {
            boolean initial = firstLoad;
            firstLoad = false;
            ACLResult acl = initial ? initialApproval : session.checkACL(commandText);
            record.applyACL(acl);
            if (acl != null && !acl.allows(commandText)) {
                throw new MongoCommandException("Command rejected by ACL");
            }
            if (!initial && (command.writesCollection() || command.getType() == MongoCommand.Type.USE_DB)) {
                throw new MongoCommandException("This command cannot be refreshed or exported again; execute it explicitly in the console");
            }
            String expanded=command.authorizationText();
            if(!expanded.equals(command.getRawText())) {
                ACLResult expandedAcl=session.checkACL(expanded);
                if(expandedAcl!=null&&!expandedAcl.allows(expanded)) {
                    record.applyACL(expandedAcl);throw new MongoCommandException("Decoded command rejected by ACL");
                }
            }
            SQLQueryResult result = actuator.execute(command, params.getOffset(), params.getLimit());
            if (sink != null && result.isHasResultSet()) {
                if (result.isTruncated()) {
                    throw new MongoCommandException("Export exceeds the MongoDB row limit; narrow the query before exporting");
                }
                sink.begin(result.getFields());
                for (var row : result.getData()) {
                    sink.accept(row);
                }
                sink.finish();
            }
            record.setOutput(result);
            record.setExecutionStats(MongoExecutionStatsBuilder.fromSuccess(manager, command, result));
            record.getExecutionStats().setRawCommand(commandText);
            return result;
        } catch (RuntimeException | SQLException e) {
            record.setError(e.getMessage());
            record.setExecutionStats(MongoExecutionStatsBuilder.fromFailure(manager, command, e));
            record.getExecutionStats().setRawCommand(commandText);
            throw e;
        } finally {
            try {
                session.recordCommand(record);
            } finally {
                // USE intentionally changes the console context; other loads must
                // not move a console that has since switched to another database.
                if (command.getType() != MongoCommand.Type.USE_DB) {
                    manager.setDatabaseContext(previous);
                }
            }
        }
    }
}
