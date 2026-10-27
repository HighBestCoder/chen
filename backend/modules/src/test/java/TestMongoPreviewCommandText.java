import org.jumpserver.chen.framework.datasource.sql.SQLQueryParams;
import org.jumpserver.chen.modules.mongodb.MongoSqlActuatorStub;

/** Collection-browse log and audit text must match the command that runs. */
public class TestMongoPreviewCommandText {
    public static void main(String[] args) throws Exception {
        var stub = new MongoSqlActuatorStub(null);

        var preview = stub.createPlan(null, "oplog.rs", null);
        require(preview.getTargetSQL().equals("db.oplog.rs.find({}).limit(50)"), "initial text: " + preview.getTargetSQL());

        SQLQueryParams page = new SQLQueryParams();
        page.setLimit(50);
        preview.setSqlQueryParams(page);
        preview.generateTargetSQL();
        require(preview.getTargetSQL().equals("db.oplog.rs.find({}).limit(50)"), "page text: " + preview.getTargetSQL());

        // DataView "export all" passes limit -1 and the query runs without limit(50).
        var export = stub.createPlan(null, "oplog.rs", null);
        SQLQueryParams all = new SQLQueryParams();
        all.setLimit(-1);
        export.setSqlQueryParams(all);
        export.generateTargetSQL();
        require(export.getTargetSQL().equals("db.oplog.rs.find({})"), "export-all text: " + export.getTargetSQL());

        System.out.println("TestMongoPreviewCommandText: OK");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
