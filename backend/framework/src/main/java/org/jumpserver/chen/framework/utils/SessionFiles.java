package org.jumpserver.chen.framework.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** File keys are single names, never client-provided paths. */
public final class SessionFiles {
    private SessionFiles() { }

    public static Path existing(Path directory, String key) throws IOException {
        if (key == null || !key.matches("[A-Za-z0-9_.-]+") || key.equals(".") || key.equals("..")) {
            throw new IOException("Invalid session file key");
        }
        Path base = directory.toRealPath();
        Path file = base.resolve(key).normalize();
        if (!file.getParent().equals(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Session file not found or not a regular file");
        }
        return file;
    }
}
