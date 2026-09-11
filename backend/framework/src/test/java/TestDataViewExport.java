import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.datasource.entity.resource.Field;
import org.jumpserver.chen.framework.datasource.sql.SQLQueryResult;
import org.jumpserver.chen.framework.jms.entity.CommandRecord;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TestDataViewExport {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("chen-export-test-");
        List<Path> files = new ArrayList<>();
        List<String> downloads = new ArrayList<>();
        List<CommandRecord> records = new ArrayList<>();
        boolean[] allowed = {true};
        Controller controller = (Controller) Proxy.newProxyInstance(Controller.class.getClassLoader(),
                new Class[]{Controller.class}, (p, method, values) -> {
                    if (method.getName().equals("sendFile")) downloads.add((String) values[0]);
                    return null;
                });
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class[]{Session.class},
                (p, method, values) -> {
                    return switch (method.getName()) {
                        case "canDownload" -> allowed[0];
                        case "getController" -> controller;
                        case "getUsername" -> "export-test";
                        case "createFile" -> {
                            Path file = Files.createFile(dir.resolve((String) values[0]));
                            files.add(file);
                            yield file.toFile();
                        }
                        case "recordCommand" -> { records.add((CommandRecord) values[0]); yield null; }
                        default -> null;
                    };
                });
        String token = SessionManager.registerSession(session);
        SessionManager.setContext(token);
        PacketIO packets = new PacketIO(null) {
            @Override public void sendPacket(String type, Object data) { }
        };
        try {
            DataView view = new DataView("CSV", packets, new Logger(packets));
            Field first = new Field(); first.setName("name,中文");
            Field second = new Field(); second.setName("quoted\"field");
            view.setLoadDataInterface((params, sink) -> {
                sink.begin(List.of(first, second));
                sink.accept(List.of("北京", "line1\nline2"));
                return new SQLQueryResult("test");
            });
            view.export("all");
            String csv = Files.readString(files.get(0));
            String expected = "\uFEFF\"name,中文\",\"quoted\"\"field\"" + System.lineSeparator()
                    + "北京,\"line1\nline2\"" + System.lineSeparator();
            require(expected.equals(csv), "streamed CSV header must escape names and have no extra column: " + csv);
            allowed[0] = false;
            view.export("all");
            require(files.size() == 1 && downloads.size() == 1, "denied export created or sent a file");
            require(records.size() == 2 && records.get(1).isError(), "denied export missing audit");
            allowed[0] = true;
            view.setLoadDataInterface((params, sink) -> {
                sink.begin(List.of(first));
                sink.accept(List.of("partial"));
                throw new java.sql.SQLException("query interrupted");
            });
            try {
                view.export("all");
                throw new AssertionError("failed export reported success");
            } catch (java.sql.SQLException expectedFailure) { }
            require(!Files.exists(files.get(1)) && downloads.size() == 1,
                    "failed export left a partial downloadable file");
            require(records.size() == 3 && records.get(2).isError(), "failed export missing audit");
            System.out.println("OK: streamed CSV header/data and denied download side effects");
        } finally {
            SessionManager.unregisterSession(token);
            SessionManager.setContext(null);
            for (Path file : files) Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
