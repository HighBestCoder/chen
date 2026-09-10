import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.policy.*;
import java.sql.SQLException;
import java.util.*;

public class TestU07Limits {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void previewDefaults()throws Exception {
        var ws=(org.springframework.web.socket.WebSocketSession)java.lang.reflect.Proxy.newProxyInstance(
                TestU07Limits.class.getClassLoader(),new Class[]{org.springframework.web.socket.WebSocketSession.class},(p,m,a)->null);
        var field=org.jumpserver.chen.framework.console.DataViewConsole.class.getDeclaredField("tableDataView");field.setAccessible(true);
        for(int cap:List.of(50000,20)) {
            var policy=new QueryPolicy();policy.setMaxRows(cap);QueryPolicyHolder.install(policy);
            for(String type:List.of("postgresql","mysql","sqlserver","mongodb")) {
                var ds=(org.jumpserver.chen.framework.datasource.Datasource)java.lang.reflect.Proxy.newProxyInstance(
                        TestU07Limits.class.getClassLoader(),new Class[]{org.jumpserver.chen.framework.datasource.Datasource.class},(p,m,a)->m.getName().equals("getName")?type:null);
                var console=new org.jumpserver.chen.framework.console.DataViewConsole(ds,ws,"fixture");console.setTitle("fixture");
                console.createDataView("schema","table");var view=(DataView)field.get(console);
                require(view.getState().getLimit()==Math.min(cap,type.equals("mongodb")?50:100),type+" preview default/cap mismatch");
            }
        }
    }
    public static void main(String[] args)throws Exception {
        QueryPolicy original=QueryPolicyHolder.current();
        try {
            previewDefaults();
            QueryPolicy policy=new QueryPolicy();policy.setMaxRows(100);QueryPolicyHolder.install(policy);
            DataView view=new DataView("limits",null,null);
            require(view.getState().getLimit()==50,"console default must be 50");
            require(view.getState().getMaxDisplayLimit()==100,"display cap must match effective backend cap");
            List<String> loads=new ArrayList<>();
            view.setLoadDataInterface((p,s)->{loads.add(p.getOffset()+":"+p.getLimit());var r=new SQLQueryResult("q");r.setTotal(250);r.setPaged(true);return r;});
            view.loadData();view.changeLimit(100);view.nextPage();view.refresh();
            require(loads.equals(List.of("0:50","0:100","100:100","100:100")),"selection/refresh/page offsets disagree: "+loads);
            int calls=loads.size();
            try{view.changeLimit(500);throw new AssertionError("over-cap selection accepted");}catch(SQLException expected){}
            require(calls==loads.size()&&view.getState().getLimit()==100&&view.getState().getPage()==2,"rejected selection changed state or queried");
            view.setLoadDataInterface((p,s)->{throw new SQLException("fixture unavailable");});
            try{view.changeLimit(50);throw new AssertionError("failed load succeeded");}catch(SQLException expected){}
            require(view.getState().getLimit()==100&&view.getState().getPage()==2,"failed reload lost selection/page");
            policy.setMaxRows(20);var small=new DataView("small",null,null);
            require(small.getState().getLimit()==20&&small.getState().getMaxDisplayLimit()==20,"default exceeds configured cap");
            policy.setMaxRows(50000);var mongo=new DataView("mongo",null,null);mongo.getState().setMaxDisplayLimit(1000);
            mongo.setLoadDataInterface((p,s)->{throw new AssertionError("invalid Mongo limit reached loader");});
            try{mongo.changeLimit(5000);throw new AssertionError("Mongo cap bypassed");}catch(SQLException expected){}
            mongo.getState().setLimit(5000);
            try{mongo.loadData();throw new AssertionError("load bypassed display cap");}catch(SQLException expected){}
            System.out.println("U07 limits: defaults, caps, contiguous offsets, refresh and failure preservation passed");
        }finally{QueryPolicyHolder.install(original);}
    }
}
