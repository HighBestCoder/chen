package org.jumpserver.chen.framework.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Durable command intent/completion delivery. Never re-executes database SQL. */
@Slf4j
public final class AuditOutbox implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Transport transport;
    private final ScheduledExecutorService worker;
    private final Set<String> failedCompletions = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> nextAttempt = new HashMap<>();
    private static AuditOutbox singleton;
    private volatile boolean closed;

    @FunctionalInterface
    public interface Transport { boolean send(byte[] body) throws Exception; }

    public static synchronized AuditOutbox configured() {
        if (singleton != null) return singleton;
        String key = System.getenv("CHEN_AUDIT_SIGNING_KEY");
        if (key == null || key.isBlank()) {
            if ("true".equalsIgnoreCase(System.getenv("CHEN_AUDIT_REQUIRED")))
                throw new IllegalStateException("Durable audit signing key is required");
            return null; // Explicitly documented compatibility mode for older Core.
        }
        if (key.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("Audit signing key must contain at least 32 bytes");
        String host = System.getenv("CORE_HOST");
        if (host == null) throw new IllegalStateException("CORE_HOST is required for audit delivery");
        URI endpoint = URI.create(host.replaceAll("/$", "") + "/api/v1/terminal/commands/chen-audit/");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getUserInfo() != null || endpoint.getFragment() != null)
            throw new IllegalStateException("Invalid audit Core URL");
        try {
            singleton = new AuditOutbox(Path.of(System.getenv().getOrDefault("CHEN_AUDIT_OUTBOX_DIR", "data/audit-outbox")), http(endpoint, key), true);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { singleton.close(); } catch (Exception ignored) { } }, "audit-outbox-close"));
            return singleton;
        } catch (IOException e) { throw new IllegalStateException("Durable audit outbox is unavailable", e); }
    }

    public AuditOutbox(Path directory, Transport transport, boolean startWorker) throws IOException {
        this.directory = directory.toAbsolutePath();
        this.transport = transport;
        Files.createDirectories(this.directory);
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        lockChannel = FileChannel.open(this.directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); }
        catch (IOException | RuntimeException e) { lockChannel.close(); throw e; }
        if (lock == null) { lockChannel.close(); throw new IOException("Audit outbox is already owned by another process"); }
        try { recover(); }
        catch (IOException e) { lock.release(); lockChannel.close(); throw e; }
        worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "audit-outbox"); t.setDaemon(true); return t; });
        if (startWorker) worker.scheduleWithFixedDelay(() -> { try { drain(); } catch (Exception e) { log.error("Audit outbox scan failed: {}", e.getClass().getSimpleName()); } }, 0, 5, TimeUnit.SECONDS);
    }

    public synchronized void begin(Map<String, Object> event) {
        if (closed) throw new IllegalStateException("Audit outbox is closed");
        try {
            checkCapacity();
            write(event, ".intent.json");
        } catch (IOException e) { throw new IllegalStateException("Cannot persist audit intent; command was not executed", e); }
    }

    public synchronized void complete(Map<String, Object> event) {
        if (closed) throw new IllegalStateException("Audit outbox is closed");
        try {
            if (!Files.exists(file(event.get("id"), ".intent.json")) && !Files.exists(file(event.get("id"), ".ready.json"))) checkCapacity();
            write(event, ".ready.json");
            Files.deleteIfExists(file(event.get("id"), ".intent.json"));
            syncDirectory();
        } catch (IOException e) {
            failedCompletions.add(String.valueOf(event.get("id")));
            throw new IllegalStateException("Command outcome may have committed, but audit completion could not be persisted; do not automatically retry SQL", e);
        }
    }

    private void checkCapacity() throws IOException {
        try (var files = Files.list(directory)) {
            if (files.filter(p -> p.toString().endsWith(".json")).limit(10000).count() >= 10000)
                throw new IOException("Audit outbox capacity reached; restore Core delivery before executing commands");
        }
    }
    private Path file(Object id, String suffix) { return directory.resolve(UUID.fromString(String.valueOf(id)) + suffix); }
    private void write(Map<String, Object> event, String suffix) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(event);
        int budget = suffix.equals(".intent.json") ? MAX_BYTES / 3 : MAX_BYTES;
        if (bytes.length > budget) throw new IOException("Audit event exceeds size budget (intent 682 KiB, completion 2 MiB); narrow command size");
        Path target = file(event.get("id"), suffix), temp = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        } finally { Files.deleteIfExists(temp); }
    }
    private void syncDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private void recover() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.intent.json")) {
            for (Path path : files) {
                recoverIntent(path, "unknown_after_restart");
            }
        }
        // Temp files cannot represent an acknowledged intent/completion.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, ".pending-*.tmp")) {
            for (Path path : files) Files.delete(path);
        }
        syncDirectory();
    }

    private synchronized void recoverIntent(Path path, String outcome) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.size(path) > MAX_BYTES) throw new IOException("Oversized audit intent file");
        Map<String, Object> event = JSON.readValue(Files.readAllBytes(path), new TypeReference<>() {});
        if (!Files.exists(file(event.get("id"), ".ready.json"))) {
            ExecutionStats stats = new ExecutionStats();
            stats.setRawCommand(String.valueOf(event.get("input")));
            stats.putExtra("execution_outcome", outcome);
            event.put("output", ExecutionStatsEnvelope.appendTo("Audit completion unavailable; confirm database state before retrying", stats));
            write(event, ".ready.json");
        }
        Files.delete(path);
        syncDirectory();
    }

    public void drain() throws IOException {
        if (closed) return;
        // No network call holds the begin/complete lock or delays SQL execution.
        for (String id : failedCompletions) {
            try { recoverIntent(file(id, ".intent.json"), "unknown_after_completion_failure"); failedCompletions.remove(id); }
            catch (IOException e) { log.warn("Audit completion {} awaiting disk recovery", id); }
        }
        int visited = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.ready.json")) {
            for (Path path : files) {
                if (closed) return;
                String name = path.getFileName().toString();
                if (nextAttempt.getOrDefault(name, 0L) > System.currentTimeMillis()) continue;
                if (++visited > 100) break;
                try {
                    if (Files.size(path) > MAX_BYTES) throw new IOException("Oversized audit queue file");
                    byte[] bytes = Files.readAllBytes(path);
                    if (transport.send(bytes)) {
                        synchronized (this) { if (closed) return; Files.delete(path); syncDirectory(); }
                        nextAttempt.remove(name);
                    } else {
                        nextAttempt.put(name, System.currentTimeMillis() + 30000);
                        log.warn("Audit event {} retained: Core has not acknowledged persistence", name);
                    }
                } catch (Exception e) {
                    nextAttempt.put(name, System.currentTimeMillis() + 30000);
                    log.warn("Audit event {} retained for retry ({})", name, e.getClass().getSimpleName());
                }
            }
        }
    }

    public static Transport http(URI endpoint, String key) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return body -> {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "\n").getBytes(StandardCharsets.US_ASCII));
            String signature = HexFormat.of().formatHex(mac.doFinal(body));
            HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("X-Chen-Audit-Time", timestamp)
                    .header("X-Chen-Audit-Signature", signature).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            Map<String, Object> receipt = JSON.readValue(response.body(), new TypeReference<>() {});
            Map<String, Object> event = JSON.readValue(body, new TypeReference<>() {});
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            return Boolean.TRUE.equals(receipt.get("committed")) && event.get("id").equals(receipt.get("id")) && digest.equals(receipt.get("digest"));
        };
    }
    @Override public void close() throws IOException {
        synchronized (this) { if (closed) return; closed = true; }
        worker.shutdownNow();
        try { if (!worker.awaitTermination(20, TimeUnit.SECONDS)) throw new IOException("Audit worker is still stopping; lock retained"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        lock.release(); lockChannel.close();
    }
}
