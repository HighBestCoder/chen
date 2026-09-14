import org.jumpserver.chen.framework.datasource.sql.SQL;
import org.jumpserver.chen.framework.datasource.sql.SQLExecutePlan;
import java.util.concurrent.*;

/** Actual JDBC cancel and an independent autocommit observer (no cached statistics snapshot). */
public class TestQaCancellationIntegration extends TestConnectionTlsIntegration {
    public static void main(String[] args) throws Exception {
        register("postgresql","drivers/postgresql/postgresql-42.7.13.jar");
        var manager=manager(info("postgresql"));
        var pool=Executors.newSingleThreadExecutor();
        try (var connection=manager.getConnection(); var observer=manager.getConnection()) {
            observer.setAutoCommit(true);
            int pid;
            try(var s=connection.createStatement();var r=s.executeQuery("SELECT pg_backend_pid()")){r.next();pid=r.getInt(1);}
            SQLExecutePlan plan=manager.getSqlActuator().withConnection(connection).createPlan(SQL.of("SELECT pg_sleep(20)"));
            plan.beginExecution(); plan.generateTargetSQL();
            var future=pool.submit(()->{try{plan.execute();throw new AssertionError("sleep completed instead of cancelling");}catch(java.sql.SQLException expected){if(!"57014".equals(expected.getSQLState()))throw new RuntimeException(expected);}});
            boolean active=false;
            for(int i=0;i<100;i++){
                try(var s=observer.createStatement();var r=s.executeQuery("SELECT state,query FROM pg_stat_activity WHERE pid="+pid)){
                    active=r.next()&&"active".equals(r.getString(1))&&r.getString(2).contains("pg_sleep");
                }
                if(active)break;Thread.sleep(50);
            }
            if(!active)throw new AssertionError("query never became active");
            plan.cancel(); future.get(5,TimeUnit.SECONDS);
            try(var s=observer.createStatement();var r=s.executeQuery("SELECT state FROM pg_stat_activity WHERE pid="+pid)){
                if(r.next()&&"active".equals(r.getString(1)))throw new AssertionError("database query still active after cancel");
            }
            System.out.println("DEF-14: real PostgreSQL JDBC cancellation ended server query within 5 seconds; fresh observer snapshot");
        }finally{pool.shutdownNow();manager.close();}
    }
}
