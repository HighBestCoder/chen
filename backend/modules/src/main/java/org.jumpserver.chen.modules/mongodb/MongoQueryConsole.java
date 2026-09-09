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
import org.jumpserver.chen.modules.mongodb.command.MongoQueryLoader;
import org.jumpserver.chen.modules.mongodb.command.MongoCommand;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandException;
import org.jumpserver.chen.modules.mongodb.command.MongoCommandParser;
import org.jumpserver.chen.modules.mongodb.command.MongoExecutionStatsBuilder;
import org.jumpserver.chen.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class MongoQueryConsole extends AbstractConsole {

    private final MongoConnectionManager connectionManager;
    private final MongoCommandParser parser = new MongoCommandParser();
    private final MongoActuator actuator;
    private StateManager<QueryConsoleState> stateManager;
    private int selectedLimit = 50;
    private final Map<String, DataView> dataViews = new HashMap<>();

    public MongoQueryConsole(MongoDatasource datasource, WebSocketSession ws, String nodeKey) {
        super(datasource, ws, nodeKey);
        this.connectionManager = (MongoConnectionManager) datasource.getConnectionManager();
        this.actuator = new MongoActuator(this.connectionManager);
        this.setTitle(String.format("Query-%d", generateConsoleName()));
    }

    private static int generateConsoleName() {
        var titles = SessionManager.getCurrentSession().getConsoles().values().stream()
                .map(c -> c.getTitle()).collect(java.util.stream.Collectors.toSet());
        int num = 1;
        while (titles.contains("Query-" + num)) num++;
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
                try {
                    this.onCommand((String) action.getData());
                } finally {
                    this.getState().setInQuery(false);
                    this.stateManager.commit();
                }
            }
            case QueryConsoleAction.ACTION_CANCEL -> {
                this.getConsoleLogger().info("Cancel is not supported for MongoDB queries in this console");
            }
            case QueryConsoleAction.ACTION_CHANGE_CURRENT_CONTEXT -> {
                var db = (String) action.getData();
                this.connectionManager.setDatabaseContext(db);
                this.getState().setCurrentContext(db);
                this.stateManager.commit();
                this.recordContextSwitch(db);
            }
            default -> log.warn("Unsupported query console action {}", action.getAction());
        }
    }

    private void onCommand(String commandText) {
        var session = SessionManager.getCurrentSession();
        ACLResult aclResult = session.checkACL(commandText);
        if (aclResult != null) {
            // 高危命令排查的第一现场：命中了哪条规则、判成什么动作、
            // 走审批时对应哪张工单。
            log.info("Mongo ACL decision: user={} riskLevel={} action={} aclId={} groupId={} ticket={}",
                    session.getUsername(), aclResult.getRiskLevel(), aclResult.getRiskAction(),
                    aclResult.getCmdAclId(), aclResult.getCmdGroupId(), aclResult.getTicketId());
        }
        if (aclResult != null && !aclResult.allows(commandText)) {
            log.warn("Mongo command rejected by ACL: user={} riskLevel={} aclId={} command={}",
                    session.getUsername(), aclResult.getRiskLevel(), aclResult.getCmdAclId(), commandText);
            this.getConsoleLogger().error(aclResult.denialMessage());
            CommandRecord rejected = new CommandRecord(commandText);
            rejected.applyACL(aclResult);
            rejected.setError("Command rejected by ACL");
            rejected.setExecutionStats(
                    MongoExecutionStatsBuilder.fromFailure(this.connectionManager, commandText,
                            new MongoCommandException("Command rejected by ACL")));
            session.recordCommand(rejected);
            return;
        }
        final MongoCommand command;
        try {
            command = this.parser.parse(commandText);
        } catch (MongoCommandException e) {
            log.info("Mongo command rejected by parser: user={} reason={} command={}",
                    session.getUsername(), e.getMessage(), commandText);
            this.getConsoleLogger().error("parse error: %s", e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error("Parse error", e.getMessage()));
            CommandRecord parseFailed = new CommandRecord(commandText);
            parseFailed.applyACL(aclResult);
            parseFailed.setError(e.getMessage());
            parseFailed.setExecutionStats(
                    MongoExecutionStatsBuilder.fromFailure(this.connectionManager, commandText, e));
            session.recordCommand(parseFailed);
            return;
        }

        try {
            DataView dataView = new DataView(command.getRawText(), this.getPacketIO(), this.getConsoleLogger());
            dataView.setSql(command.getRawText());
            dataView.getStateManager().getState().setLimit(selectedLimit);
            MongoQueryLoader loader = new MongoQueryLoader(session, this.connectionManager,
                    this.actuator, command, aclResult, commandText);
            dataView.setLoadDataInterface((params, sink) -> {
                var result = loader.loadData(params, sink);
                if (!result.isHasResultSet() && result.getUpdateCount() < 0) {
                    this.getConsoleLogger().success(result.getOutput());
                } else {
                    this.getConsoleLogger().success(result);
                }
                if (result.isTruncated()) {
                    this.getConsoleLogger().warn("Result truncated at the MongoDB console row limit");
                }
                return result;
            });
            dataView.loadData();
            if (command.getType() == MongoCommand.Type.USE_DB) {
                this.getState().setCurrentContext(this.connectionManager.getCurrentDatabaseName());
                this.stateManager.commit();
            }

            if (!dataView.isHasTable()) {
                this.getConsoleLogger().success("Command OK");
            } else {
                this.sendDataView(dataView);
            }
        } catch (Exception e) {
            log.warn("Mongo command execution failed: user={} opType={} command={} error={}",
                    session.getUsername(), command.getType(), commandText, e.toString());
            this.getConsoleLogger().error("execute error: %s", e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error("Execute error", e.getMessage()));
        }
    }

    private void recordContextSwitch(String database) {
        if (database == null || database.isEmpty()) {
            return;
        }
        MongoCommand command = MongoCommand.useDb("use " + database, database);
        CommandRecord record = new CommandRecord(command.getRawText());
        record.setOutput("Current database changed to " + database);
        record.setExecutionStats(
                MongoExecutionStatsBuilder.fromSuccess(this.connectionManager, command, null));
        SessionManager.getCurrentSession().recordCommand(record);
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
            selectedLimit = dataView.getStateManager().getState().getLimit();
            this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(action.getDataView(), dataView.getData()));
        } catch (Exception e) {
            this.getMessager().send(Message.error("Fetch error", e.getMessage()));
        } finally {
            dataView.getStateManager().getState().setLoading(false);
            dataView.getStateManager().commit();
        }
    }

    private void sendDataView(DataView dataView) {
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
