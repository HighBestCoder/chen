package org.jumpserver.chen.framework.console;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.jumpserver.chen.framework.console.action.DataViewAction;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.dataview.UpdateDataView;
import org.jumpserver.chen.framework.console.entity.request.Connect;
import org.jumpserver.chen.framework.console.entity.response.Message;
import org.jumpserver.chen.framework.console.state.State;
import org.jumpserver.chen.framework.console.state.StateManager;
import org.jumpserver.chen.framework.datasource.Datasource;
import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.policy.QueryPolicyHolder;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.TreeUtils;
import org.jumpserver.chen.framework.ws.io.Packet;
import org.jumpserver.chen.wisp.Common;
import org.springframework.web.socket.WebSocketSession;

import java.sql.SQLException;
import java.util.Map;

public class DataViewConsole extends AbstractConsole {

    private DataView tableDataView;
    private StateManager<State> stateManager;

    private String schema;
    private String table;

    public DataViewConsole(Datasource datasource, WebSocketSession ws, String nodeKey) {
        super(datasource, ws, nodeKey);
    }


    @Override
    public void onInit(Connect connect) {
        this.schema = TreeUtils.getValue(connect.getNodeKey(), "schema");
        this.table = StringUtils.isEmpty(TreeUtils.getValue(connect.getNodeKey(), "table")) ?
                TreeUtils.getValue(connect.getNodeKey(), "view") : TreeUtils.getValue(connect.getNodeKey(), "table");

        var title = String.format("DataView: %s%s.%s",
                StringUtils.isEmpty(TreeUtils.getValue(this.getNodeKey(), "database")) ? ""
                        : TreeUtils.getValue(this.getNodeKey(), "database") + ".", this.schema, this.table);
        try {
            title = this.generateConsoleName();
        } catch (RuntimeException e) {
            this.getPacketIO().sendPacket("active_console", title);
            this.getPacketIO().sendPacket("close", null);
            return;
        }
        this.setTitle(title);
        this.stateManager = new StateManager<>(new State(title), this.getPacketIO());
        super.onInit(connect);
        this.onConnect(connect);
    }


    private String generateConsoleName() {
        String database = TreeUtils.getValue(this.getNodeKey(), "database");
        var name = String.format("DataView: %s%s.%s", StringUtils.isEmpty(database) ? "" : database + ".", this.schema, this.table);
        if (SessionManager.getCurrentSession().getConsoles().values().stream().anyMatch(console -> name.equals(console.getTitle()))) {
            throw new RuntimeException("console already exists");
        }

        return name;
    }

    @Override
    public void handle(Packet packet) {
        switch (packet.getType()) {
            case "ping" -> this.getPacketIO().sendPacket("pong", null);
            case Packet.TYPE_DATA_VIEW_ACTION -> {
                var action = JSON.parseObject(JSON.toJSONString(packet.getData()), DataViewAction.class);
                this.onDataViewAction(action);
            }
        }
    }

    public void onConnect(Connect connect) {
        this.getConsoleLogger().info("Websocket" + MessageUtils.get("state.connected"));
        this.getConsoleLogger().info("view table: %s", this.getTitle());

        this.createDataView(this.schema, this.table);

        try {
            this.tableDataView.getStateManager().getState().setLoading(true);
            this.tableDataView.getStateManager().commit();

            this.tableDataView.loadData();
        } catch (SQLException e) {
            this.getMessager().send(Message.error(MessageUtils.get("msg.error.fetch_error"), e));
        } finally {
            this.tableDataView.getStateManager().getState().setLoading(false);
            this.tableDataView.getStateManager().commit();
        }
        this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(this.tableDataView.getTitle(), this.tableDataView.getData()));
        this.tableDataView.getStateManager().commit();

    }

    public void createDataView(String schemaName, String tableName) {

        var viewTitle = this.getTitle() + "child";
        this.getPacketIO().sendPacket("new_data_view", Map.of("title", viewTitle));
        var dataView = new DataView(viewTitle, this.getPacketIO(), this.getConsoleLogger());

        // Collection previews default to 50; relational previews default to 100.
        // The advertised size and pagination stride must match the execution cap.
        int previewLimit = "mongodb".equals(this.getDatasource().getName())
                ? 50 : QueryPolicyHolder.current().getDefaultPreviewLimit();
        dataView.getState().setLimit(Math.min(previewLimit, dataView.getState().getMaxDisplayLimit()));

        var session = SessionManager.getCurrentSession();
        dataView.setLoadDataInterface((sqlQueryParams, sink) -> {
            var plan = this.getDatasource()
                    .getConnectionManager()
                    .getSqlActuator()
                    .createPlan(schemaName, tableName, null);
            try {
                var sql = plan.getTargetSQL();
                var aclResult = session.checkACL(sql);
                if (aclResult != null && !aclResult.allows(sql)) {
                    this.getConsoleLogger().error("%s", aclResult.denialMessage());
                    CommandRecord commandRecord = new CommandRecord(sql);
                    commandRecord.applyACL(aclResult);
                    commandRecord.setError(aclResult.denialMessage());
                    commandRecord.setExecutionStats(org.jumpserver.chen.framework.audit.SqlExecutionStatsBuilder.fromFailure(
                            this.getDatasource(), sql, new SQLException(aclResult.denialMessage())));
                    session.recordCommand(commandRecord);

                    this.stateManager.getState().setLoading(false);
                    this.stateManager.commit();
                    throw new SQLException(aclResult.denialMessage());
                }
                plan.setSqlQueryParams(sqlQueryParams);
                plan.setRowConsumer(sink);
                plan.generateTargetSQL();

                plan.setAclResult(aclResult);
                this.getConsoleLogger().info("execute sql: %s", plan.getTargetSQL());
                var result = plan.executeWithAudit();

                this.getConsoleLogger().success(result);
                return result;
            } finally { plan.close(); }
        });

        this.tableDataView = dataView;
    }


    public void onDataViewAction(DataViewAction action) {
        try {
            this.tableDataView.getStateManager().getState().setLoading(true);
            this.tableDataView.getStateManager().commit();

            this.tableDataView.doAction(action);

            this.getPacketIO().sendPacket("update_data_view", new UpdateDataView(this.tableDataView.getTitle(), this.tableDataView.getData()));
            this.tableDataView.getStateManager().commit();
        } catch (SQLException e) {
            this.getMessager().send(Message.error(MessageUtils.get("msg.error.fetch_error"), e));
        } finally {
            this.tableDataView.getStateManager().getState().setLoading(false);
            this.tableDataView.getStateManager().commit();
        }
    }

    @Override
    public void close() {
    }
}
