import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.session.*;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.ws.io.PacketIO;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

public class TestDataViewSelection {
    static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("s06-csv-");List<Path> files=new ArrayList<>();List<String> downloads=new ArrayList<>();
        Controller controller=(Controller)Proxy.newProxyInstance(Controller.class.getClassLoader(),new Class[]{Controller.class},(p,m,a)->{if(m.getName().equals("sendFile"))downloads.add((String)a[0]);return null;});
        Session session=(Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class[]{Session.class},(p,m,a)->switch(m.getName()){
            case "canDownload"->true;case "getController"->controller;case "createFile"->{Path f=Files.createFile(dir.resolve((String)a[0]));files.add(f);yield f.toFile();}default->null;});
        var token=SessionManager.registerSession(session);SessionManager.setContext(token);
        PacketIO io=new PacketIO(null){@Override public void sendPacket(String t,Object d){}};
        try {
            var view=new DataView("csv",io,new Logger(io));
            Field f=new Field();f.setName("value,中文");
            var values=List.of("9007199254740993.123456", "2026-09-09 12:34:56.123456", "quote\"\nline");
            view.setLoadDataInterface((params,sink)->{
                var result=new SQLQueryResult("q");result.setFields(List.of(f));result.setData(values.stream().map(x->List.<Object>of(x)).toList());result.setHasResultSet(true);
                if(sink!=null){sink.begin(List.of(f));for(String v:values)sink.accept(List.of(v));sink.finish();}return result;
            });view.loadData();
            view.export("current");view.export("all");view.export(Map.of("scope","selected","rowIndices",List.of(0,1,2,2),"revision",view.getData().getRevision()));
            require(Files.readString(files.get(0)).equals(Files.readString(files.get(1))),"current/all CSV differs");
            require(Files.readString(files.get(0)).equals(Files.readString(files.get(2))),"selection lost or duplicated values");
            for(Object selection:List.of(Map.of("rowIndices",List.of(3)),Map.of("rows",List.of(Map.of("value,中文","forged"))))) {
                Map<String,Object> request=new HashMap<>((Map<String,Object>)selection);request.put("scope","selected");request.put("revision",view.getData().getRevision());
                try{view.export(request);throw new AssertionError("invalid selection exported");}catch(java.sql.SQLException expected){}
            }
            try{view.export(Map.of("scope","selected","rowIndices",List.of(0),"revision",0));throw new AssertionError("stale result exported");}catch(java.sql.SQLException expected){}
            require(downloads.size()==3,"failed selection created download notification");
            String output=System.getenv("CHEN_S06_CSV_DIR");
            if(output!=null){Files.createDirectories(Path.of(output));for(int i=0;i<3;i++)Files.copy(files.get(i),Path.of(output,"scope"+i+".csv"),StandardCopyOption.REPLACE_EXISTING);}
        } finally {SessionManager.unregisterSession(token);SessionManager.setContext(null);for(Path file:files)Files.deleteIfExists(file);Files.deleteIfExists(dir);}
        System.out.println("OK: server-owned selection, deduplication, three-scope CSV parity and rejection cleanup");
    }
}
