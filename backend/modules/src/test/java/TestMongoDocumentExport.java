import org.bson.Document;
import org.jumpserver.chen.modules.mongodb.command.MongoResultTableAdapter;
import org.jumpserver.chen.framework.console.component.Logger;
import org.jumpserver.chen.framework.console.dataview.DataView;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.session.controller.Controller;
import org.jumpserver.chen.framework.ws.io.PacketIO;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/** All three production CSV export paths must retain nested BSON values. */
public class TestMongoDocumentExport {
    public static void main(String[] args) throws Exception {
        Document document = new Document("items", Arrays.asList(
                new Document("note", "中文,\"引号\"\n换行"), null,
                List.of(1, 2), new Document("tail", "末尾")))
                .append("empty", new Document()).append("date", new Date(123456789))
                .append("binary", new org.bson.types.Binary(new byte[]{0, 1, 2, -1}))
                .append("decimal", new org.bson.types.Decimal128(new java.math.BigDecimal("123456789.0123456789")));
        var result = new MongoResultTableAdapter().toResult("find", "order", List.of(document), 1, 2, 1, false);
        Path dir = Files.createTempDirectory("chen-mongo-document-export-");
        List<Path> files = new ArrayList<>();
        Controller controller = (Controller) Proxy.newProxyInstance(Controller.class.getClassLoader(),
                new Class[]{Controller.class}, (p, m, v) -> null);
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class[]{Session.class},
                (p, method, values) -> switch (method.getName()) {
                    case "canDownload" -> true;
                    case "getUsername" -> "document-test";
                    case "getController" -> controller;
                    case "createFile" -> {
                        Path file = Files.createFile(dir.resolve((String) values[0]));
                        files.add(file); yield file.toFile();
                    }
                    default -> null;
                });
        String token = SessionManager.registerSession(session);
        SessionManager.setContext(token);
        PacketIO packets = new PacketIO(null) {
            @Override public void sendPacket(String type, Object data) { }
        };
        try {
            DataView view = new DataView("documents", packets, new Logger(packets));
            view.setLoadDataInterface((params, sink) -> {
                if (sink != null) {
                    sink.begin(result.getFields());
                    for (var row : result.getData()) sink.accept(row);
                }
                return result;
            });
            view.loadData();
            for (String scope : List.of("current", "selected", "all")) {
                view.export(Map.of("scope", scope, "rows", view.getData().getData()));
                String csv = Files.readString(files.get(files.size() - 1));
                require(csv.charAt(0) == '\uFEFF', "UTF-8 BOM missing");
                var rows = parseCsv(csv.substring(1));
                require(rows.size() == 2, "wrong exported row count for " + scope);
                for (int i = 0; i < rows.get(0).size(); i++) {
                    String field = rows.get(0).get(i);
                    Object decoded = Document.parse("{value:" + rows.get(1).get(i) + "}").get("value");
                    require(Objects.equals(document.get(field), decoded), scope + " changed " + field);
                }
            }
            System.out.println("OK: current/selected/all CSV preserve arrays, nulls, nested objects and dates");
        } finally {
            SessionManager.unregisterSession(token);
            SessionManager.setContext(null);
            for (Path file : files) Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"'); i++;
                } else quoted = !quoted;
            } else if (!quoted && (c == ',' || c == '\n')) {
                row.add(cell.toString()); cell.setLength(0);
                if (c == '\n') { rows.add(row); row = new ArrayList<>(); }
            } else if (c != '\r' || quoted) cell.append(c);
        }
        require(!quoted, "unclosed CSV quote");
        return rows;
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
