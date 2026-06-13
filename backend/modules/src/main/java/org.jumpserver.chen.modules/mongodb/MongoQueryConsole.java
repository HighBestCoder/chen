package org.jumpserver.chen.modules.mongodb;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.console.AbstractConsole;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.action.QueryConsoleAction;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.state.QueryConsoleState;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.modules.mongodb.command.MongoActuator;
import org.jumpserver.chen.modules.mongodb.command.MongoCommand;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandException;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandParser;
import org.jumpserver.chen.modules.mongodb.command.MongoExecutionStatsBuilder;
import org.jumpserver.chen.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class MongoQueryConsole extends AbstractConsole {

    private final MongoConnectionManager connectionManager;
    private final MongoCommandParser parser = new MongoCommandParser();
    private final MongoActuator actuator;
    private StateManager<QueryConsoleState> stateManager;
    private final Map<String, DataView> dataViews = new HashMap<>();

    public MongoQueryConsole(MongoDatasource datasource, WebSocketSession ws, String nodeKey) {
        super(datasource, ws, nodeKey);
        this.connectionManager = (MongoConnectionManager) datasource.getConnectionManager();
        this.actuator = new MongoActuator(this.connectionManager);
        this.setTitle(String.format("Query-%d", generateConsoleName()));
    }

    private static int generateConsoleName() {
        int num = 1;
        for (var console : SessionManager.getCurrentSession().getConsoles().values()) {
            if (console instanceof MongoQueryConsole) {
                ++num;
            }
        }
        return num;
    }

    @Override
    public void onInit(Connect connect) {
        super.onInit(connect);
        this.stateManager = new StateManager<>(new QueryConsoleState(this.getTitle()), this.getPacketIO());
        this.getState().setLoading(true);
        this.stateManager.commit();
        try {
            this.getState().setContexts(this.connectionManager.listDatabases());
            this.getState().setCurrentContext(this.connectionManager.getCurrentDatabaseName());
        } catch (RuntimeException e) {
            this.getConsoleLogger().error("connect error: %s", e.getMessage());
        }
        this.getState().setLoading(false);
        this.stateManager.commit();
    }

    @Override
    public void handle(Packet packet) {
        // ConsoleWebSocketHandler resets the connection-manager DB context from
        // this console's node key before every packet (needed for relational
        // multi-DB reconnects). For Mongo that would clobber a user's `use <db>`
        // / dropdown switch, so re-apply the console's own selected context here.
        String selected = this.getState().getCurrentContext();
        if (selected != null && !selected.isEmpty()) {
            this.connectionManager.setDatabaseContext(selected);
        }
        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case "close_data_view" -> this.dataViews.remove((String) packet.getData());
            case Packet.TYPE_QUERY_CONSOLE_ACTION -> {
                var action = JSON.parseObject(JSON.toJSONString(packet.getData()), QueryConsoleAction.class);
                this.onAction(action);
            }
            case Packet.TYPE_DATA_VIEW_ACTION -> {
                var action = JSON.parseObject(JSON.toJSONString(packet.getData()), DataViewAction.class);
                this.onDataViewAction(action);
            }
            default -> log.warn("Unknown packet type {}", packet.getType());
        }
    }

    private void onAction(QueryConsoleAction action) {
        switch (action.getAction()) {
            case QueryConsoleAction.ACTION_RUN_SQL -> {
                this.getState().setInQuery(true);
                this.stateManager.commit();
                this.onCommand((String) action.getData());
                this.getState().setInQuery(false);
                this.stateManager.commit();
            }
            case QueryConsoleAction.ACTION_CANCEL -> {
                this.getConsoleLogger().info("Cancel is not supported for MongoDB queries in this console");
                this.getState().setInQuery(false);
                this.stateManager.commit();
            }
            case QueryConsoleAction.ACTION_CHANGE_CURRENT_CONTEXT -> {
                var db = (String) action.getData();
                this.connectionManager.setDatabaseContext(db);
                this.getState().setCurrentContext(db);
                this.stateManager.commit();
            }
            default -> log.warn("Unsupported query console action {}", action.getAction());
        }
    }

    private void onCommand(String commandText) {
        var session = SessionManager.getCurrentSession();
        ACLResult aclResult = session.checkACL(commandText);
        if (aclResult != null
                && (aclResult.getRiskLevel() == Common.RiskLevel.Reject
                || aclResult.getRiskLevel() == Common.RiskLevel.ReviewReject)) {
            this.getConsoleLogger().error("Command rejected by ACL");
            CommandRecord rejected = new CommandRecord(commandText);
            rejected.applyACL(aclResult);
            session.recordCommand(rejected);
            return;
        }
        if (aclResult != null && aclResult.getApprovedCommandHash() != null
                && !aclResult.getApprovedCommandHash().equals(ACLFilterImpl.commandHash(commandText))) {
            this.getConsoleLogger().error("Approved command hash mismatch");
            CommandRecord rejected = new CommandRecord(commandText);
            rejected.applyACL(aclResult);
            rejected.setError("approved command hash mismatch");
            session.recordCommand(rejected);
            return;
        }

        final MongoCommand command;
        try {
            command = this.parser.parse(commandText);
        } catch (MongoCommandException e) {
            this.getConsoleLogger().error("parse error: %s", e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error("Parse error", e.getMessage()));
            CommandRecord parseFailed = new CommandRecord(commandText);
            parseFailed.applyACL(aclResult);
            parseFailed.setError(e.getMessage());
            session.recordCommand(parseFailed);
            return;
        }

        if (command.getType() == MongoCommand.Type.USE_DB) {
            this.getState().setCurrentContext(command.getTargetDatabase());
            this.stateManager.commit();
        }

        CommandRecord record = new CommandRecord(commandText);
        record.applyACL(aclResult);
        try {
            DataView dataView = new DataView(command.getRawText(), this.getPacketIO(), this.getConsoleLogger());
            dataView.setSql(command.getRawText());
            dataView.setLoadDataInterface((params) -> {
                var result = this.actuator.execute(command, params.getOffset(), params.getLimit());
                this.getConsoleLogger().success(result);
                record.setExecutionStats(
                        MongoExecutionStatsBuilder.fromSuccess(this.connectionManager, command, result));
                return result;
            });
            dataView.loadData();

            if (!dataView.isHasTable()) {
                this.getConsoleLogger().success("Command OK");
            } else {
                this.sendDataView(dataView);
            }
        } catch (Exception e) {
            this.getConsoleLogger().error("execute error: %s", e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error("Execute error", e.getMessage()));
            record.setError(e.getMessage());
            record.setExecutionStats(
                    MongoExecutionStatsBuilder.fromFailure(this.connectionManager, command, e));
        } finally {
            session.recordCommand(record);
        }
    }

    private void onDataViewAction(DataViewAction action) {
        var dataView = this.dataViews.get(action.getDataView());
        if (dataView == null) {
            log.error("data view {} not found", action.getDataView());
            return;
        }
        try {
            dataView.getStateManager().getState().setLoading(true);
            dataView.getStateManager().commit();
            dataView.doAction(action);
            this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(action.getDataView(), dataView.getData()));
        } catch (Exception e) {
            this.getMessager().send(Message.error("Fetch error", e.getMessage()));
        } finally {
            dataView.getStateManager().getState().setLoading(false);
            dataView.getStateManager().commit();
        }
    }

    private void sendDataView(DataView dataView) {
        var forDelete = new ArrayList<String>();
        for (var title : this.dataViews.keySet()) {
            if (!dataView.getTitle().equals(title)
                    && !this.dataViews.get(title).getStateManager().getState().isPinned()) {
                forDelete.add(title);
            }
        }
        forDelete.forEach(this.dataViews.keySet()::remove);
        this.getPacketIO().sendPacket("close_data_view", forDelete);

        if (!this.dataViews.containsKey(dataView.getTitle())) {
            this.getPacketIO().sendPacket("new_data_view", Map.of("title", dataView.getTitle()));
        }
        this.dataViews.put(dataView.getTitle(), dataView);
        this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(dataView.getTitle(), dataView.getData()));
        dataView.getStateManager().commit();
    }

    private QueryConsoleState getState() {
        return this.stateManager.getState();
    }

    @Override
    public void close() {
        log.info("mongo console closed");
    }
}
