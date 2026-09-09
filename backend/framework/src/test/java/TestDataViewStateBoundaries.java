import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import java.sql.SQLException;
import java.util.*;

public class TestDataViewStateBoundaries {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        var view=new DataView("bounds",null,null);int[] calls={0};
        view.setLoadDataInterface((p,s)->{calls[0]++;var r=new SQLQueryResult("q");r.setTotal(100);r.setPaged(true);return r;});
        view.loadData();view.prevPage();require(view.getState().getPage()==1,"page became zero");
        view.getState().setPage(2);view.nextPage();require(view.getState().getPage()==2,"page exceeded known total");
        for(int limit:List.of(0,-1,Integer.MAX_VALUE)) {
            int before=calls[0];
            try{view.changeLimit(limit);throw new AssertionError("invalid display limit accepted: "+limit);}catch(SQLException expected){}
            require(calls[0]==before,"invalid limit reached query loader");
        }
        view.getState().setPage(Integer.MAX_VALUE);
        try{view.loadData();throw new AssertionError("overflowed offset reached loader");}catch(SQLException expected){}
        System.out.println("OK: bounded pages, invalid limit rejection and checked offsets");
    }
}
