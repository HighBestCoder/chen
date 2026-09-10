import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jumpserver.chen.framework.audit.AuditOutbox;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class TestS07AuditOutbox {
    static final ObjectMapper JSON = new ObjectMapper();
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static Map<String,Object> event() {
        var event = new LinkedHashMap<String,Object>();
        event.put("id", UUID.randomUUID().toString()); event.put("input", "update demo set value='中'");
        event.put("output", "done"); return event;
    }
    static long ready(Path p) throws Exception { try(var files=Files.list(p)){return files.filter(f->f.toString().endsWith(".ready.json")).count();} }
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("crash-intent")) {
            AuditOutbox child=new AuditOutbox(Path.of(args[1]),bytes->false,false);
            child.begin(event());Runtime.getRuntime().halt(17);
        }
        Path directory=Files.createTempDirectory("s07-outbox-");
        var first=event(); AtomicInteger calls=new AtomicInteger();
        try(AuditOutbox outbox=new AuditOutbox(directory, bytes->{calls.incrementAndGet();return false;}, false)) {
            outbox.begin(first);outbox.complete(first);outbox.drain();check(ready(directory)==1);check(calls.get()==1);
        }
        // Lost ACK/process restart: the exact same event bytes survive and are retried.
        try(AuditOutbox outbox=new AuditOutbox(directory, bytes->{check(JSON.readValue(bytes,Map.class).equals(first));return true;},false)) {
            outbox.drain();check(ready(directory)==0);
        }
        var interrupted=event();
        try(AuditOutbox outbox=new AuditOutbox(directory, bytes->{throw new AssertionError("Active intent must not upload");},false)) {
            outbox.begin(interrupted);outbox.drain();check(ready(directory)==0);
        }
        try(AuditOutbox outbox=new AuditOutbox(directory, bytes->{
            var recovered=JSON.readValue(bytes,Map.class);check(recovered.get("id").equals(interrupted.get("id")));
            check(recovered.get("output").toString().contains("unknown_after_restart"));return true;
        },false)) {outbox.drain();check(ready(directory)==0);}
        // Failure to persist an intent is synchronous and blocks the caller.
        try(AuditOutbox outbox=new AuditOutbox(directory, bytes->true,false)) {
            var oversized=event();oversized.put("input","x".repeat(2*1024*1024));
            try{outbox.begin(oversized);throw new AssertionError();}catch(IllegalStateException expected){}
        }
        try(AuditOutbox outbox=new AuditOutbox(directory,bytes->true,false)) {
            Path moved=directory.resolveSibling(directory.getFileName()+"-moved");
            Files.move(directory,moved);Files.createFile(directory);
            try {try{outbox.begin(event());throw new AssertionError();}catch(IllegalStateException expected){}}
            finally {Files.delete(directory);Files.move(moved,directory);}
        }
        try(AuditOutbox outbox=new AuditOutbox(directory,bytes->{check(new String(bytes,StandardCharsets.UTF_8).contains("unknown_after_completion_failure"));return true;},false)) {
            var tooLarge=event();outbox.begin(tooLarge);tooLarge.put("output","x".repeat(2*1024*1024));
            try{outbox.complete(tooLarge);throw new AssertionError();}catch(IllegalStateException expected){}
            outbox.drain();check(ready(directory)==0);
        }
        Process crash=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"TestS07AuditOutbox","crash-intent",directory.toString()).inheritIO().start();
        check(crash.waitFor()==17);
        try(AuditOutbox outbox=new AuditOutbox(directory,bytes->{check(new String(bytes,StandardCharsets.UTF_8).contains("unknown_after_restart"));return true;},false)) {
            outbox.drain();check(ready(directory)==0);
        }
        // Real HTTP: authenticate bytes and refuse an ACK for another payload.
        String key="isolated-audit-signing-key-0000000000";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger mode=new AtomicInteger();
        server.createContext("/audit",exchange->{try {
            byte[] body=exchange.getRequestBody().readAllBytes();
            String timestamp=exchange.getRequestHeaders().getFirst("X-Chen-Audit-Time");
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+"\n").getBytes(StandardCharsets.US_ASCII));
            check(HexFormat.of().formatHex(mac.doFinal(body)).equals(exchange.getRequestHeaders().getFirst("X-Chen-Audit-Signature")));
            var data=JSON.readValue(body,Map.class);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            byte[] response=JSON.writeValueAsBytes(Map.of("id",data.get("id"),"digest",mode.get()==0?"wrong":digest,"committed",true));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
        } catch(Exception e){throw new RuntimeException(e);} finally {exchange.close();}});
        server.start();
        try {
            var transport=AuditOutbox.http(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/audit"),key);
            byte[] body=JSON.writeValueAsBytes(event());check(!transport.send(body));mode.set(1);check(transport.send(body));
        } finally {server.stop(0);}
        try(var files=Files.walk(directory)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
        System.out.println("PASS S07 audit outbox: disk intent, restart, retained failed delivery, unknown outcome, fail closed, HMAC and exact receipt");
    }
}
