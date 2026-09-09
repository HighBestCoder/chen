import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.console.DataViewConsole;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.state.*;
import org.jumpserver.chen.framework.datasource.*;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.jms.acl.ACLResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.jms.impl.ACLFilterImpl;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import org.jumpserver.chen.wisp.Common;
import org.springframework.web.socket.WebSocketSession;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public class TestQueryConsoleSecurity {
    static void require(boolean ok, String message) { if(!ok) throw new AssertionError(message); }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler));
    }
    public static void main(String[] args) throws Exception {
        int[] calls={0,0,0}; // ACL, count, execute
        ACLResult[] approval={null};
        List<CommandRecord> records=new ArrayList<>();
        List<String> failures=new ArrayList<>();
        Path dir=Files.createTempDirectory("sql-security-");
        Session session=proxy(Session.class,(p,m,v)->switch(m.getName()) {
            case "getConsoles" -> Map.of();
            case "checkACLBatch" -> {
                calls[0]++;
                if (((List<String>)v[1]).stream().anyMatch(text -> text.startsWith("DROP"))) {
                    ACLResult denied=new ACLResult();denied.setRiskLevel(Common.RiskLevel.Reject);yield denied;
                }
                yield approval[0];
            }
            case "checkACL" -> { calls[0]++; yield approval[0]; }
            case "recordCommand" -> { records.add((CommandRecord)v[0]); yield null; }
            case "canDownload" -> true;
            case "getController" -> proxy(org.jumpserver.chen.framework.session.controller.Controller.class,(x,y,z)->null);
            case "createFile" -> Files.createFile(dir.resolve((String)v[0])).toFile();
            default -> null;
        });
        String token=SessionManager.registerSession(session); SessionManager.setContext(token);
        Connection conn=proxy(Connection.class,(p,m,v)->null);
        SQLActuator[] actuator={null};
        actuator[0]=proxy(SQLActuator.class,(p,m,v)->switch(m.getName()) {
            case "withConnection" -> actuator[0];
            case "parseSQL" -> SQLUtils.parseStatements(((SQL)v[0]).getSql(),DbType.mysql).stream().map(Object::toString).toList();
            case "createPlan" -> {
                SQLExecutePlan plan=new SQLExecutePlan(v[0] instanceof SQL sql ? sql.getSql() : "SELECT * FROM fixture",DbType.mysql);
                plan.setConnection(conn); plan.setSqlActuator(actuator[0]); yield plan;
            }
            case "count" -> { calls[1]++; yield 100; }
            case "executeWithAudit" -> {
                calls[2]++;
                SQLExecutePlan plan=(SQLExecutePlan)v[0];
                SQLQueryResult result=new SQLQueryResult(plan.getTargetSQL());
                result.setStartTime(new Time(0)); result.setQueryFinishedTime(new Time(1));
                result.setFetchFinishedTime(new Time(2)); result.setEndTime(new Time(2));
                result.setTotal(100); result.setAclResult(plan.getAclResult());
                CommandRecord record=new CommandRecord(plan.getTargetSQL()); record.applyACL(plan.getAclResult()); records.add(record);
                yield result;
            }
            default -> null;
        });
        ConnectionManager manager=proxy(ConnectionManager.class,(p,m,v)->switch(m.getName()) {
            case "getPhysicalConnection" -> conn; case "getSqlActuator" -> actuator[0]; default -> null;
        });
        Datasource ds=proxy(Datasource.class,(p,m,v)->switch(m.getName()) {
            case "getConnectionManager" -> manager; case "getDruidDbType" -> DbType.mysql; default -> null;
        });
        WebSocketSession ws=proxy(WebSocketSession.class,(p,m,v)->null);
        QueryConsole console=new QueryConsole(ds,ws,"test");
        QueryConsoleState state=new QueryConsoleState("test");
        Field stateField=QueryConsole.class.getDeclaredField("stateManager"); stateField.setAccessible(true);
        stateField.set(console,new StateManager<>(state,new PacketIO(ws)));
        try {
            console.onSQL("SELECT * FROM");
            if(calls[2]!=0 || records.size()!=1 || !records.get(0).isError()
                    || records.get(0).getExecutionStats()==null) failures.add("parser failure missing single structured audit");
            require(!state.isInQuery(),"parser failure stuck in query");
            records.clear();
            console.onSQL("SELECT 1");
            require(calls[2]==1 && records.size()==1,"initial execution duplicated or failed");
            Field viewsField=QueryConsole.class.getDeclaredField("dataViews"); viewsField.setAccessible(true);
            DataView view=((Map<String,DataView>)viewsField.get(console)).values().iterator().next();
            ACLResult reject=new ACLResult(); reject.setRiskLevel(Common.RiskLevel.Reject); approval[0]=reject;
            for(String action:List.of("refresh","next","all")) {
                int count=calls[1], exec=calls[2], checks=calls[0];
                try {
                    if(action.equals("refresh")) view.refresh();
                    else if(action.equals("next")) view.nextPage();
                    else view.export("all");
                    failures.add(action+" accepted rejected ACL");
                } catch(SQLException expected) { }
                if(calls[1]!=count || calls[2]!=exec || calls[0]!=checks+1) failures.add(action+" performed DB work or skipped ACL");
            }
            ACLResult mismatch=new ACLResult(); mismatch.setApprovedCommandHash(ACLFilterImpl.commandHash("SELECT 2")); approval[0]=mismatch;
            int exec=calls[2];
            try { view.refresh(); failures.add("hash mismatch accepted"); } catch(SQLException expected) { }
            if(calls[2]!=exec) failures.add("hash mismatch executed SQL");
            ACLResult renewed=new ACLResult();
            renewed.setApprovedCommandHash(ACLFilterImpl.commandHash(view.getSql()));
            renewed.setTicketId("renewed-approval"); approval[0]=renewed;
            view.refresh();
            if(calls[2]!=exec+1 || state.isCanCancel()) failures.add("allowed refresh failed or stale cancel state");
            if(!"renewed-approval".equals(records.get(records.size()-1).getTicketId())) failures.add("renewed approval not applied to execution audit");
            approval[0]=null;
            int beforeBatch=calls[2],beforeCount=calls[1];
            console.onSQL("SELECT 1; DROP TABLE fixture");
            if(calls[2]!=beforeBatch || calls[1]!=beforeCount)failures.add("multi-statement ACL denial allowed earlier or later statement side effects");
            var preview=new DataViewConsole(ds,ws,"test");
            Field previewState=DataViewConsole.class.getDeclaredField("stateManager");previewState.setAccessible(true);
            previewState.set(preview,new StateManager<>(new State("preview"),new PacketIO(ws)));
            preview.createDataView("schema","fixture");
            Field previewView=DataViewConsole.class.getDeclaredField("tableDataView");previewView.setAccessible(true);
            var tableView=(DataView)previewView.get(preview);
            for(var risk:List.of(Common.RiskLevel.ReviewAccept,Common.RiskLevel.ReviewCancel)) {
                ACLResult wrong=new ACLResult();wrong.setRiskLevel(risk);
                wrong.setApprovedCommandHash(ACLFilterImpl.commandHash("SELECT * FROM different_table"));approval[0]=wrong;
                int beforePreview=calls[2],countPreview=calls[1];
                try{tableView.loadData();failures.add("preview accepted mismatched/cancelled approval");}catch(SQLException expected){}
                if(calls[2]!=beforePreview || calls[1]!=countPreview)failures.add("preview performed DB work before approval binding");
            }
            require(failures.isEmpty(),String.join("; ",failures));
            System.out.println("OK: parser audit, single initial execution, refresh/paging/export recheck before count, approval hash binding");
        } finally {
            SessionManager.unregisterSession(token); SessionManager.setContext(null);
            try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
