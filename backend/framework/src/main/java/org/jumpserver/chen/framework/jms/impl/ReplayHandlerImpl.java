package org.jumpserver.chen.framework.jms.impl;

import lombok.extern.slf4j.Slf4j;
import org.jumpserver.chen.framework.jms.ReplayHandler;
import org.jumpserver.chen.framework.jms.asciinema.AsciinemaWriter;
import org.jumpserver.chen.framework.jms.exception.ReplayException;
import org.jumpserver.chen.wisp.Common;
import org.jumpserver.chen.wisp.ServiceGrpc;
import org.jumpserver.chen.wisp.ServiceOuterClass;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Slf4j
public class ReplayHandlerImpl implements ReplayHandler {
    private static final String REPLAY_DIR = "data/replay";
    private final Common.Session session;
    private final ServiceGrpc.ServiceBlockingStub serviceBlockingStub;
    private AsciinemaWriter replayWriter;
    private FileWriter fileWriter;
    private File file;
    private boolean released;

    public ReplayHandlerImpl(Common.Session session, ServiceGrpc.ServiceBlockingStub serviceBlockingStub) {
        this.session = session;
        this.serviceBlockingStub = serviceBlockingStub;
    }

    @Override
    public void init() {
        ensureReplayDir();

        var filePath = Path.of(REPLAY_DIR, String.format("%s.cast", this.session.getId()));
        File file = new File(filePath.toString());
        try {

            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();

            this.file = file;
            this.fileWriter = new FileWriter(this.file, StandardCharsets.UTF_8);
            this.replayWriter = new AsciinemaWriter(this.fileWriter);
            this.replayWriter.writeHeader();
        } catch (Exception e) {
            if (this.fileWriter != null) try { this.fileWriter.close(); } catch (IOException ignored) { }
            throw new ReplayException(file.getName(), "create replay file error:" + e.getMessage());
        }
    }

    private static void ensureReplayDir() {
        var dir = new File(REPLAY_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }


    @Override
    public void release() throws ReplayException {
        synchronized (lock) {
            if (released || fileWriter == null) return;
            released = true;
            try {
                this.fileWriter.close();
            } catch (IOException e) {
                throw new ReplayException(this.file.getName(), "close replay file error:" + e.getMessage());
            }
        }
        var req = ServiceOuterClass.ReplayRequest.newBuilder()
                .setSessionId(this.session.getId()).setReplayFilePath(this.file.getAbsolutePath()).build();
        var resp = this.serviceBlockingStub.withDeadlineAfter(30, java.util.concurrent.TimeUnit.SECONDS).uploadReplayFile(req);
        if (!resp.getStatus().getOk()) throw new ReplayException(this.file.getName(), "upload failed: " + resp.getStatus().getErr());
    }

    private final Object lock = new Object();

    @Override
    public void writeRow(String row) {
        synchronized (lock) {
            if (released || replayWriter == null) return;
            row = row == null ? "" : row;
            row = row.replaceAll("\n", "\r\n");
            row = row.replaceAll("\r\r\n", "\r\n");
            var content = String.format("%s \r\n", row);

            var rows = content.split("\r\n");

            try {
                int sum = 0;
                for (var r : rows) {
                    r += "\r\n";
                    this.replayWriter.writeRow(r.getBytes(StandardCharsets.UTF_8));
                    if (++sum % 100 == 0) {
                        Thread.sleep(100);
                        sum = 0;
                    }
                }
            } catch (IOException e) {
                log.error("write replay row failed: {}", e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void writeInput(String input) {
        input = String.format("[Input]: \r\n %s", input);
        this.writeRow(input);
    }

    @Override
    public void writeOutput(String output) {
        output = String.format("[Output]: \r\n %s \r\n", output);
        this.writeRow(output);
    }
}
