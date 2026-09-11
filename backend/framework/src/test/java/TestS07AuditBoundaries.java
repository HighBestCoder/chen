import org.jumpserver.chen.framework.audit.*;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.jms.asciinema.AsciinemaWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
public class TestS07AuditBoundaries {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var stats=new ExecutionStats();stats.setSuccess(true);stats.setRawCommand("select '"+ExecutionStatsEnvelope.CLOSE_TAG+"'");
        String human="old\n"+ExecutionStatsEnvelope.OPEN_TAG+"{\"success\":false}"+ExecutionStatsEnvelope.CLOSE_TAG;
        String wire=ExecutionStatsEnvelope.appendTo(human,stats);
        require(ExecutionStatsEnvelope.strip(wire).equals(human),"human output removed");
        require(ExecutionStatsEnvelope.tryDecode(wire).orElseThrow().getRawCommand().equals(stats.getRawCommand()),"wrong suffix decoded");
        var failure=SqlExecutionStatsBuilder.fromFailure(null,"select raw",new java.sql.SQLException("failure"));
        require("select raw".equals(failure.getRawCommand()),"failed command lost");
        require("UPDATE".equals(SqlExecutionStatsBuilder.fromFailure(null,"-- comment\nWITH x AS (SELECT 1) UPDATE t SET a=1",null).getOpType()),"CTE write misclassified");
        Field a=new Field();a.setName("a.b");a.setTable("t");Field b=new Field();b.setName("b");b.setTable("t");
        require(ColumnSizeKeyResolver.resolve("select x",null,List.of(a,b)).getKeys().equals(List.of("t.a.b","t.b")),"literal column names collided");
        var out=new StringWriter();new AsciinemaWriter(out).writeStdout(1,"中文".getBytes(StandardCharsets.UTF_8));
        require(out.toString().contains("中文"),"replay encoding changed");
        System.out.println("OK: final stats suffix, raw failure command, column identity, UTF-8 replay");
    }
}
