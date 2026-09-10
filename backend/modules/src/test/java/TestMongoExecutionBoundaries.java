import org.jumpserver.chen.modules.mongodb.command.*;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.framework.datasource.entity.DBConnectInfo;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.bson.Document;
import java.lang.reflect.Proxy;
import java.util.*;

/** Deterministic parser and driver-boundary regressions; no database. */
public class TestMongoExecutionBoundaries {
    static final List<String> failures = new ArrayList<>();
    interface Test { void run() throws Exception; }
    static void check(String label, Test test) {
        try { test.run(); System.out.println("PASS " + label); }
        catch (Throwable e) { failures.add(label + ": " + e); }
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        for (String input : List.of("db.c.deleteMany({} garbage)", "db.c.insertOne({x:1} {x:2})",
                "db.c.find({}).sort({x:1} garbage)", "db.c.insertMany([null])",
                "db.c.insertMany([{}], {unknown:false})")) {
            check("reject " + input, () -> {
                try { new MongoCommandParser().parse(input); throw new AssertionError("accepted partial/invalid input"); }
                catch (MongoCommandException expected) { }
            });
        }
        check("quoted BSON values and constructors remain accepted", () -> {
            var c = new MongoCommandParser().parse("db.c.find({x: ISODate('2024-01-01'), note: '{} garbage'})");
            require(c.getFilter().get("x") instanceof Date, "date lost");
        });
        check("preview retains exact collection identity", () -> {
            var info = new DBConnectInfo(); info.setDb("s05");
            List<String> selected = new ArrayList<>();
            var database = (com.mongodb.client.MongoDatabase) Proxy.newProxyInstance(
                    TestMongoExecutionBoundaries.class.getClassLoader(), new Class[]{com.mongodb.client.MongoDatabase.class},
                    (p,m,a) -> { if(m.getName().equals("getCollection")) {selected.add((String)a[0]); throw new IllegalStateException("stop before I/O");} return null; });
            var manager = new MongoConnectionManager(info,null) {
                @Override public com.mongodb.client.MongoDatabase getDatabase(String name) { return database; }
            };
            var plan = manager.getSqlActuator().createPlan(null,"orders.find_archive",new SQLQueryParams());
            try { plan.execute(); } catch (Exception expected) { }
            require(selected.equals(List.of("orders.find_archive")), "wrong collection: " + selected);
        });
        check("driver errors restore page and selected limit", () -> {
            var view = new org.jumpserver.chen.framework.console.dataview.DataView("mongo",null,null);
            view.getState().setPage(2);
            view.setLoadDataInterface((params,sink) -> { throw new MongoCommandException("server rejected query"); });
            try { view.changeLimit(5000); } catch(MongoCommandException expected) { }
            require(view.getState().getLimit()==50 && view.getState().getPage()==2,"failed limit changed state");
            try { view.nextPage(); } catch(MongoCommandException expected) { }
            require(view.getState().getPage()==2,"failed page changed state");
        });
        if(!failures.isEmpty()) throw new AssertionError(String.join("\n",failures));
    }
}
