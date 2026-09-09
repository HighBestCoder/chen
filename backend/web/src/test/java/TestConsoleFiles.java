import org.jumpserver.chen.framework.console.QueryConsole;
import org.jumpserver.chen.framework.session.Session;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.web.controller.ConsoleController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.socket.WebSocketSession;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class TestConsoleFiles {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("console-files-");
        Path dir = Files.createDirectory(root.resolve("session"));
        Path outside = Files.writeString(root.resolve("outside.sql"), "SELECT outside");
        boolean[] upload = {true};
        Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class[]{Session.class},
            (p,m,v) -> switch(m.getName()) {
                case "canUpload" -> upload[0]; case "canDownload" -> true;
                case "getTempPath" -> dir; case "getConsoles" -> Map.of(); default -> null;
            });
        String token = SessionManager.registerSession(session);
        SessionManager.setContext(token);
        WebSocketSession ws = (WebSocketSession) Proxy.newProxyInstance(WebSocketSession.class.getClassLoader(),
            new Class[]{WebSocketSession.class}, (p,m,v) -> null);
        List<String> executed = new ArrayList<>();
        QueryConsole console = new QueryConsole(null, ws, "test") {
            @Override public void onSQL(String sql) { executed.add(sql); }
        };
        ConsoleController controller = new ConsoleController();
        List<String> failures = new ArrayList<>();
        try {
            for (String key : List.of("../outside.sql", outside.toString(), "sql_link.sql")) {
                Files.writeString(outside, "SELECT outside");
                if (key.equals("sql_link.sql")) Files.createSymbolicLink(dir.resolve(key), outside);
                int before = executed.size();
                console.onSQLFile(key);
                if (executed.size() != before || !Files.exists(outside)) failures.add("execute escaped session: " + key);
            }
            Files.writeString(outside, "SELECT outside");
            for (String key : List.of("../outside.sql", outside.toString(), "sql_link.sql")) {
                try { controller.exportData(key); failures.add("download accepted external file: " + key); }
                catch (RuntimeException expected) { }
            }
            Path own = Files.writeString(dir.resolve("sql_owned.sql"), "SELECT owned");
            upload[0] = false;
            int before = executed.size(); console.onSQLFile(own.getFileName().toString());
            if (executed.size() != before || !Files.exists(own)) failures.add("execution ignored upload permission");
            upload[0] = true;
            Files.writeString(own, "SELECT owned");
            console.onSQLFile(own.getFileName().toString());
            require(executed.get(executed.size()-1).equals("SELECT owned") && !Files.exists(own), "valid upload not consumed");
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<Map.Entry<String,String>>> futures = new ArrayList<>();
            try {
                for (int i=0; i<64; i++) {
                    String sql = "SELECT " + i;
                    futures.add(pool.submit(() -> {
                        SessionManager.setContext(token);
                        try {
                            MultipartFile file = (MultipartFile) Proxy.newProxyInstance(MultipartFile.class.getClassLoader(),
                                new Class[]{MultipartFile.class}, (p,m,v) -> m.getName().equals("getBytes") ? sql.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
                            return Map.entry(controller.uploadData(file).getPath(), sql);
                        } finally { SessionManager.setContext(null); }
                    }));
                }
                Set<String> keys = new HashSet<>();
                for (var future : futures) {
                    var entry = future.get(); keys.add(entry.getKey());
                    if (!Files.readString(dir.resolve(entry.getKey())).equals(entry.getValue())) failures.add("upload content overwritten");
                }
                if (keys.size()!=64) failures.add("concurrent uploads reused file keys");
                String key = keys.iterator().next();
                require(controller.exportData(key).getBody().exists(), "valid file download rejected");
            } finally { pool.shutdownNow(); }
            require(failures.isEmpty(), String.join("; ",failures));
            System.out.println("OK: path escape/symlink/download denial, upload permission, valid consumption, 64 independent uploads");
        } finally {
            SessionManager.unregisterSession(token); SessionManager.setContext(null);
            try(var paths=Files.walk(root)) { for(Path p: paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
