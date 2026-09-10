import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.datasource.sql.*;
import org.jumpserver.chen.framework.policy.*;
import org.jumpserver.chen.modules.mongodb.MongoConnectionManager;
import org.jumpserver.chen.modules.mongodb.command.*;
import org.bson.Document;
import java.util.*;

public class TestU07Integration extends TestConnectionTlsIntegration {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void verifyRows(SQLQueryResult result,int offset,int limit,String field) {
        require(result.getData().size()==Math.min(limit,250-offset),"wrong returned row count");
        int col=-1;for(int i=0;i<result.getFields().size();i++)if(result.getFields().get(i).getName().equalsIgnoreCase(field))col=i;
        require(col>=0,"missing identity column");
        for(int i=0;i<result.getData().size();i++)require(Integer.parseInt(result.getData().get(i).get(col).toString())==offset+i,"page skipped/duplicated row");
    }
    public static void main(String[] args)throws Exception {
        register("postgresql","drivers/postgresql/postgresql-42.7.13.jar");
        register("mysql","drivers/mysql/mysql-connector-java-8.0.30.jar");
        register("sqlserver","drivers/sqlserver/mssql-jdbc-12.8.1.jre11.jar");
        var original=QueryPolicyHolder.current();
        try {
            for(String type:List.of("postgresql","mysql","sqlserver")) {
                var manager=manager(info(type));
                try {
                    try(var connection=manager.getConnection();var stmt=connection.createStatement()) {
                        stmt.execute("CREATE TABLE u07_rows (id INT PRIMARY KEY)");
                        for(int i=0;i<250;i++)stmt.addBatch("INSERT INTO u07_rows VALUES ("+i+")");stmt.executeBatch();
                    }
                    QueryPolicy policy=new QueryPolicy();policy.setMaxRows(100);QueryPolicyHolder.install(policy);
                    var view=new DataView(type,null,null);view.getState().setLimit(100);
                    view.setLoadDataInterface((params,sink)->{
                        var plan=manager.getSqlActuator().createPlan(type.equals("postgresql")?"public":type.equals("mysql")?"fixture":"dbo","u07_rows",params);
                        try {
                            plan.setSqlQueryParams(params);plan.generateTargetSQL();var result=plan.execute();
                            verifyRows(result,params.getOffset(),params.getLimit(),"id");return result;
                        }finally{plan.close();}
                    });
                    view.loadData();view.nextPage();require(view.getState().getPage()==2,"preview not pageable");view.refresh();view.nextPage();view.changeLimit(50);view.nextPage();
                    QueryPolicyHolder.install(new QueryPolicy());
                    for(int limit:List.of(50,500)) {
                        String sql=type.equals("sqlserver")?"SELECT TOP 200 id FROM u07_rows ORDER BY id":"SELECT id FROM u07_rows ORDER BY id LIMIT 200";
                        var plan=manager.getSqlActuator().createPlan(SQL.of(sql));
                        try {
                            var params=new SQLQueryParams();params.setLimit(limit);plan.setSqlQueryParams(params);plan.generateTargetSQL();
                            verifyRows(plan.execute(),0,200,"id");require(plan.isManualLimitDetected(),"manual limit not identified");
                        }finally{plan.close();}
                    }
                    System.out.println("PASS U07 "+type+": 250 rows, preview100/selection50/contiguous pages/refresh and manual200 override50/500");
                }finally{manager.close();}
            }
            var manager=(MongoConnectionManager)manager(info("mongodb"));
            try {
                manager.setDatabaseContext("u07_limits");var docs=new ArrayList<Document>();for(int i=0;i<250;i++)docs.add(new Document("_id",i));manager.getDatabase("u07_limits").getCollection("items").insertMany(docs);
                var view=new DataView("mongo",null,null);
                view.setLoadDataInterface((params,sink)->{var plan=manager.getSqlActuator().createPlan("u07_limits","items",params);try{plan.generateTargetSQL();var result=plan.execute();verifyRows(result,params.getOffset(),params.getLimit(),"_id");return result;}finally{plan.close();}});
                view.loadData();view.nextPage();view.refresh();view.changeLimit(100);view.nextPage();view.nextPage();
                var actuator=new MongoActuator(manager);var parser=new MongoCommandParser();
                for(int limit:List.of(50,500))verifyRows(actuator.execute(parser.parse("db.items.find({}).sort({_id:1}).limit(200)"),0,limit),0,200,"_id");
                System.out.println("PASS U07 Mongo: preview50/selection100/contiguous pages/refresh and manual200 override50/500");
            }finally{manager.close();}
        }finally{QueryPolicyHolder.install(original);}
    }
}
