import org.jumpserver.chen.framework.datasource.error.SqlPermissionErrorClassifier;
import java.sql.SQLException;
public class TestU05PermissionChains {
    public static void main(String[] args) {
        SQLException batch=new SQLException("batch failed");
        batch.setNextException(new SQLException("denied", "42501"));
        if(!SqlPermissionErrorClassifier.isPermissionDenied(batch))throw new AssertionError("JDBC nextException permission denial lost");
        SQLException auth=new SQLException("login failed","28000",1045);
        if(SqlPermissionErrorClassifier.isPermissionDenied(auth))throw new AssertionError("authentication confused with authorization");
        System.out.println("U05 JDBC nextException authorization and authentication distinction passed");
    }
}
