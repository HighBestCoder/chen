import org.bson.Document;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.*;
import org.jumpserver.chen.wisp.Common;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class TestMongoQueryLoader {
    public static void main(String[] args) throws Exception {
        DBConnectInfo info = new DBConnectInfo();
        info.setDb("original");
        MongoConnectionManager manager = new MongoConnectionManager(info, null) {
            @Override public String getVersion() { return "test"; }
        };
        List<CommandRecord> audits = new ArrayList<>();
        int[] calls = {0};
        int[] aclChecks = {0};
        boolean[] reject = {false};
        String text = "db.order.find({});";
        ACLResult approved = new ACLResult();
        approved.setRiskLevel(Common.RiskLevel.ReviewAccept);
        approved.setApprovedCommandHash(ACLFilterImpl.commandHash(text));
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class[]{Session.class},
                (proxy, method, values) -> {
                    if (method.getName().equals("recordCommand")) { audits.add((CommandRecord) values[0]); }
                    if (method.getName().equals("checkACL")) {
                        aclChecks[0]++;
                        require(text.equals(values[0]), "ACL must see original text including semicolon");
                        ACLResult acl = new ACLResult();
                        acl.setRiskLevel(reject[0] ? Common.RiskLevel.Reject : Common.RiskLevel.Normal);
                        return acl;
                    }
                    return null;
                });
        MongoActuator actuator = new MongoActuator(manager) {
            @Override public SQLQueryResult execute(MongoCommand command, int offset, int limit) {
                calls[0]++;
                require("original".equals(manager.getCurrentDatabaseName()), "result reload switched database");
                return new MongoResultTableAdapter().toResult(text,
                        List.of(new Document("city", "北京")), 1L, 2L);
            }
        };
        MongoQueryLoader loader = new MongoQueryLoader(session, manager, actuator,
                new MongoCommandParser().parse(text), approved, text);
        loader.loadData(new SQLQueryParams(), null);
        require(aclChecks[0] == 0, "initial approval should not prompt twice");
        manager.setDatabaseContext("other");
        List<List<Object>> exported = new ArrayList<>();
        loader.loadData(new SQLQueryParams(), new RowConsumer() {
            public void begin(List<org.jumpserver.chen.framework.datasource.entity.resource.Field> fields) {
                require(fields.get(0).getName().equals("city"), "export header missing");
            }
            public void accept(List<Object> row) { exported.add(row); }
        });
        require(exported.equals(List.of(List.of("北京"))), "export sink must receive actual rows");
        require(aclChecks[0] == 1 && audits.size() == 2, "reload must recheck ACL and write new audit");
        require("other".equals(manager.getCurrentDatabaseName()), "reload must restore selected database");
        require(audits.stream().allMatch(r -> "original".equals(r.getExecutionStats().getNamespace())
                && text.equals(r.getExecutionStats().getRawCommand())), "audit namespace/text mismatch");
        reject[0] = true;
        try {
            loader.loadData(new SQLQueryParams(), null);
            throw new AssertionError("rejected refresh reached driver");
        } catch (MongoCommandException expected) { }
        require(calls[0] == 2 && audits.size() == 3 && audits.get(2).isError(), "rejection must audit without execution");
        require("other".equals(manager.getCurrentDatabaseName()), "rejection must restore context");
        reject[0] = false;
        try {
            loader.loadData(new SQLQueryParams(), new RowConsumer() {
                public void begin(List<org.jumpserver.chen.framework.datasource.entity.resource.Field> fields) { }
                public void accept(List<Object> row) throws SQLException { throw new SQLException("disk full"); }
            });
            throw new AssertionError("export error swallowed");
        } catch (SQLException expected) { }
        require(audits.get(3).isError() && "other".equals(manager.getCurrentDatabaseName()), "failed export must audit and restore context");
        System.out.println("OK: export rows, per-load ACL/audit, original text and database isolation");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
