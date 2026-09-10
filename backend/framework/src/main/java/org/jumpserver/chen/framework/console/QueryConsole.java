package org.jumpserver.chen.framework.console;

import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.fastjson.JSON;
import org.jumpserver.chen.framework.audit.SqlExecutionStatsBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.action.QueryConsoleAction;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.state.QueryConsoleState;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.datasource.error.SqlPermissionErrorClassifier;
import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLActuator;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.SessionFiles;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class QueryConsole extends AbstractConsole {

    private final Datasource datasource;
    private Connection conn;
    private Integer selectedLimit;
    private volatile SQLExecutePlan currentPlan;
    private StateManager<QueryConsoleState> stateManager;
    private final Map<String, DataView> dataViews = new HashMap<>();

    public QueryConsole(Datasource datasource, WebSocketSession ws, String nodeKey) {
        super(datasource, ws, nodeKey);
        this.setTitle(String.format(MessageUtils.get("title.query") + "-%d", generateConsoleName()));
        this.datasource = datasource;
    }

    private static int generateConsoleName() {
        int num = 1;
        var consoles = SessionManager
                .getCurrentSession()
                .getConsoles();

        var usedTitles = new java.util.HashSet<String>();
        consoles.values().forEach(console -> usedTitles.add(console.getTitle()));
        while (usedTitles.contains(String.format(MessageUtils.get("title.query") + "-%d", num))) num++;
        return num;
    }

    @Override
    public void onInit(Connect connect) {
        super.onInit(connect);
        this.onConnect(connect);
    }

    public void onConnect(Connect connect) {
        this.getConsoleLogger().info("Websocket" + MessageUtils.get("state.connected"));

        this.stateManager = new StateManager<>(new QueryConsoleState(this.getTitle())
                , this.getPacketIO());
        this.getState().setLoading(true);
        this.stateManager.commit();

        var context = TreeUtils.getValue(connect.getNodeKey(), this.getDatasource().getConnectionManager().getContextKey());
        try {
            var currentContext = this.getSqlActuator().getCurrentSchema();


            if (StringUtils.isEmpty(context)) {
                context = currentContext;
            }

            if (currentContext != null && !currentContext.equals(context)) {
                this.getSqlActuator().changeSchema(context);
            }
            var schemas = this.getSqlActuator().getSchemas();
            this.getState().setContexts(schemas);
            this.getState().setCurrentContext(context);

        } catch (SQLException e) {
            this.getConsoleLogger().error(MessageUtils.get("msg.error.connect_error") + ": %s", e.getMessage());
        }

        this.getState().setLoading(false);
        this.stateManager.commit();

    }

    private Connection getConnection() {
        if (this.conn == null) {
            try {
                this.conn = this.getDatasource().getConnectionManager().getPhysicalConnection();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return this.conn;
    }


    @Override
    public void handle(Packet packet) {

        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case "close_data_view" -> {
                var name = (String) packet.getData();
                this.dataViews.remove(name);
                log.info("close data view {}", name);
            }

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

                var sql = (String) action.getData();
                this.onSQL(sql);

                this.getState().setInQuery(false);
                this.stateManager.commit();
            }
            case QueryConsoleAction.ACTION_RUN_SQL_FILE -> {
                this.getState().setInQuery(true);
                this.stateManager.commit();

                var sqlFile = (String) action.getData();
                this.onSQLFile(sqlFile);

                this.getState().setInQuery(false);
                this.stateManager.commit();
            }


            case QueryConsoleAction.ACTION_CANCEL -> {
                this.onCancel();
                this.getState().setInQuery(false);
                this.stateManager.commit();
            }
            case QueryConsoleAction.ACTION_CHANGE_CURRENT_CONTEXT -> {
                var schema = (String) action.getData();
                this.onManualChangeContext(schema);
            }
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
            if (DataViewAction.ACTION_CHANGE_LIMIT.equals(action.getAction())) {
                selectedLimit = org.jumpserver.chen.framework.policy.QueryPolicyHolder.current().clampLimit(dataView.getState().getLimit());
            }

            this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(action.getDataView(), dataView.getData()));

        } catch (SQLException e) {
            this.getMessager().send(Message.error(MessageUtils.get("msg.error.fetch_error"), e));
        } finally {
            dataView.getStateManager().getState().setLoading(false);
            dataView.getStateManager().commit();
        }
    }

    public void onCancel() {
        try {
            var plan = this.currentPlan;
            if (plan != null) {
                plan.cancel();
                this.getConsoleLogger().error("cancel query: %s", plan.getTargetSQL());
            }
        } catch (SQLException e) {
            log.error("cancel failed ", e);
        }
    }

    public void onManualChangeContext(String context) {
        if (StringUtils.equals(this.getState().getCurrentContext(), context)) {
            return;
        }
        try {
            this.getState().setEditorLoading(true);
            this.stateManager.commit();

            this.getSqlActuator().changeSchema(context);
            this.getState().setCurrentContext(context);

        } catch (SQLException e) {
            this.getConsoleLogger().error(MessageUtils.get("msg.error.change_context_error") + ": %s", e.getMessage());
        } finally {
            this.getState().setEditorLoading(false);
            this.stateManager.commit();
        }

    }


    public void onSQLFile(String filename) {
        var session = SessionManager.getCurrentSession();
        if (!session.canUpload()) {
            this.getConsoleLogger().error("%s", MessageUtils.get("msg.error.no_permission"));
            return;
        }
        try {
            var filePath = SessionFiles.existing(session.getTempPath(), filename);
            if (!filename.startsWith("sql_") || !filename.endsWith(".sql")) {
                throw new IOException("Invalid SQL upload key");
            }
            try {
                this.onSQL(Files.readString(filePath));
            } finally {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException e) {
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.file_read_error"), e.getMessage());
        }
    }

    public void onSQL(String sql) {
        this.getState().setInQuery(true);
        this.stateManager.commit();
        var session = SessionManager.getCurrentSession();

        ACLResult aclResult = null;
        try {
            var stmts = this.getSqlActuator().parseSQL(SQL.of(sql));
            aclResult = session.checkACLBatch(sql, stmts, this.getConnection());
            if (aclResult != null) {
                if (!aclResult.allows(sql)) {
                    this.getConsoleLogger().error("%s", aclResult.denialMessage());
                    CommandRecord commandRecord = new CommandRecord(sql);
                    commandRecord.applyACL(aclResult);
                    commandRecord.setError(aclResult.denialMessage());
                    commandRecord.setExecutionStats(SqlExecutionStatsBuilder.fromFailure(this.datasource, sql,
                            new SQLException(aclResult.denialMessage())));
                    session.recordCommand(commandRecord);

                    this.getState().setInQuery(false);
                    this.stateManager.commit();
                    return;
                }

            }


            var clearOthers = true;
            for (String stmt : stmts) {
                var dataView = this.runSingleSQL(stmt, aclResult);
                if (!dataView.isHasTable()) {
                    this.getConsoleLogger().success("%s , %s: %d",
                            MessageUtils.get("msg.success.execute_success"),
                            MessageUtils.get("msg.info.affected_rows"), dataView.getUpdateCount());
                } else {
                    this.sendDataView(dataView, clearOthers);
                    clearOthers = false;
                }
                this.ensureCurrentSchema();
            }
        } catch (ParserException e) {
            recordPreExecutionFailure(sql, aclResult, e);
            this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.parse_error"), e.getMessage());
            this.getPacketIO().sendPacket("message", Message.error(MessageUtils.get("msg.error.parse_error"), e));
        } catch (SQLException e) {
            if (SqlPermissionErrorClassifier.isPermissionDenied(e)) {
                String msg = MessageUtils.get("msg.error.no_operation_permission");
                this.getConsoleLogger().error("%s", msg);
                this.getPacketIO().sendPacket("message", Message.error(msg, e));
            } else {
                this.getConsoleLogger().error("%s: %s", MessageUtils.get("msg.error.execute_error"), e.getMessage());
                this.getPacketIO().sendPacket("message", Message.error(MessageUtils.get("msg.error.execute_error"), e));
            }
        } finally {
            this.getState().setInQuery(false);
            this.getState().setCanCancel(false);
            this.stateManager.commit();
        }
    }

    private void recordPreExecutionFailure(String sql, ACLResult acl, Throwable error) {
        CommandRecord record = new CommandRecord(sql);
        record.applyACL(acl);
        record.setError(error.getMessage());
        var stats = SqlExecutionStatsBuilder.fromFailure(this.datasource, sql, error);
        stats.setRawCommand(sql);
        record.setExecutionStats(stats);
        SessionManager.getCurrentSession().recordCommand(record);
    }

    private SQLActuator getSqlActuator() {
        return this.getDatasource()
                .getConnectionManager()
                .getSqlActuator()
                .withConnection(this.getConnection());
    }


    private QueryConsoleState getState() {
        return this.stateManager.getState();
    }

    private void ensureCurrentSchema() {
        try {
            var schema = this.getSqlActuator().getCurrentSchema();

            if (!StringUtils.equals(schema, this.getState().getCurrentContext())) {
                this.getState().setCurrentContext(schema);
                this.stateManager.commit();
            }
        } catch (SQLException e) {
            log.error("get current schema failed {}", e.getMessage(), e);
        }
    }

    private DataView runSingleSQL(String sql, ACLResult aclResult) throws SQLException {

        SQLExecutePlan plan = this.datasource
                .getConnectionManager()
                .getSqlActuator()
                .withConnection(this.getConnection())
                .createPlan(SQL.of(sql));

        plan.setAclResult(aclResult);
        DataView dataView = new DataView(plan.getSourceSQL(), this.getPacketIO(), this.getConsoleLogger());
        dataView.setSql(plan.getSourceSQL());
        dataView.getState().setLimit(selectedLimit != null ? selectedLimit
                : org.jumpserver.chen.framework.policy.QueryPolicyHolder.current().clampLimit(0));

        // The submitted batch was approved once by onSQL. Every later load
        // is a new execution and must be checked before generateTargetSQL counts rows.
        String resultContext = this.getState().getCurrentContext();
        boolean[] firstLoad = {true};
        dataView.setLoadDataInterface((sqlQueryParams, sink) -> {
            boolean initial = firstLoad[0];
            firstLoad[0] = false;
            if (!initial) {
                if (!plan.isReloadableResult()) {
                    throw new SQLException("This result came from a write command. Run the command explicitly to execute it again; "
                            + "export the current results instead of reloading all rows.");
                }
                if (!java.util.Objects.equals(resultContext, this.getState().getCurrentContext())) {
                    throw new SQLException("Result belongs to context " + resultContext
                            + ". Switch back to that context or run the query again.");
                }
                ACLResult fresh = null;
                try {
                    fresh = SessionManager.getCurrentSession().checkACL(plan.getSourceSQL(), this.getConnection());
                    if (fresh != null && !fresh.allows(plan.getSourceSQL())) {
                        throw new SQLException("Command rejected by ACL or approved command hash mismatch");
                    }
                    plan.setAclResult(fresh);
                } catch (RuntimeException | SQLException e) {
                    recordPreExecutionFailure(plan.getSourceSQL(), fresh, e);
                    throw new SQLException(e.getMessage(), e);
                }
            }
            sqlQueryParams.setTimeout(this.getState().getTimeout());

            plan.setSqlQueryParams(sqlQueryParams);
            plan.setRowConsumer(sink);
            plan.beginExecution();
            this.currentPlan = plan;

            this.getState().setCanCancel(true);
            this.stateManager.commit();

            try {
                plan.generateTargetSQL();
                this.getConsoleLogger().info("execute sql: %s", plan.getTargetSQL());
                var result = plan.executeWithAudit();
                this.getConsoleLogger().success(result);
                return result;
            } finally {
                this.currentPlan = null;
                this.getState().setCanCancel(false);
                this.stateManager.commit();
            }
        });


        dataView.loadData();

        this.getState().setCanCancel(false);
        this.stateManager.commit();

        return dataView;
    }


    private void sendDataView(DataView dataView, boolean clearOthers) {
        if (clearOthers) {
            var forDeleteDataViewTitles = new ArrayList<String>();
            for (var title : this.dataViews.keySet()) {
                if (!dataView.getTitle().equals(title) && !this.dataViews.get(title).getStateManager().getState().isPinned()) {
                    forDeleteDataViewTitles.add(title);
                }
            }
            forDeleteDataViewTitles.forEach(this.dataViews.keySet()::remove);
            this.getPacketIO().sendPacket("close_data_view", forDeleteDataViewTitles);
        }

        if (!this.dataViews.containsKey(dataView.getTitle())) {
            this.getPacketIO().sendPacket("new_data_view", Map.of("title", dataView.getTitle()));
        }

        this.dataViews.put(dataView.getTitle(), dataView);
        this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(dataView.getTitle(), dataView.getData()));
        dataView.getStateManager().commit();
    }


    @Override
    public void close() {
        try {
            var plan = this.currentPlan;
            if (plan != null) plan.cancel();
        } catch (SQLException e) {
            log.warn("Cancel on console close failed", e);
        } finally {
            try { if (this.conn != null) this.conn.close(); }
            catch (SQLException e) { log.warn("Close console connection failed", e); }
        }
        log.info("console closed");
    }
}
