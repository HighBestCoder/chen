package org.jumpserver.chen.framework.script;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * task-03 slice A: server-side validation for SQL script uploads.
 *
 * <p>Per the task spec the back end is the source of truth for both
 * the file extension and the size cap. The default cap is 10 MiB
 * (10 * 1024 * 1024 bytes) to match the customer-facing prompt
 * "文件大小超出限制（最大 10MB），请拆分后上传".</p>
 *
 * <p>The validator deliberately operates on byte counts rather than
 * character counts so it produces the same answer regardless of the
 * source charset declaration; the controller can therefore enforce
 * the cap before decoding the payload.</p>
 */
public final class SqlScriptValidator {

    /** Default 10 MiB. */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024L * 1024L;

    /** Only .sql is accepted. */
    public static final String REQUIRED_EXTENSION = ".sql";

    private final long maxBytes;

    public SqlScriptValidator() {
        this(DEFAULT_MAX_BYTES);
    }

    public SqlScriptValidator(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    /** Validate a raw byte payload that may not yet be decoded. */
    public Result validate(String filename, byte[] content) {
        Result extResult = validateExtension(filename);
        if (!extResult.isOk()) {
            return extResult;
        }
        long size = content == null ? 0L : content.length;
        return validateSize(size);
    }

    /** Validate a decoded text payload (re-encoded to UTF-8 for sizing). */
    public Result validate(String filename, String content) {
        Result extResult = validateExtension(filename);
        if (!extResult.isOk()) {
            return extResult;
        }
        long size = content == null ? 0L : content.getBytes(StandardCharsets.UTF_8).length;
        return validateSize(size);
    }

    public Result validateExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return Result.fail(Reason.MISSING_FILENAME, "Filename is required");
        }
        String lower = filename.trim().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(REQUIRED_EXTENSION)) {
            return Result.fail(Reason.UNSUPPORTED_EXTENSION,
                    "Only .sql files are accepted");
        }
        return Result.ok();
    }

    public Result validateSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return Result.fail(Reason.EMPTY_FILE, "Uploaded file is empty");
        }
        if (sizeBytes > maxBytes) {
            return Result.fail(Reason.SIZE_EXCEEDED, String.format(
                    "文件大小超出限制（最大 %d MB），请拆分后上传",
                    maxBytes / (1024L * 1024L)));
        }
        return Result.ok();
    }

    public enum Reason {
        OK,
        MISSING_FILENAME,
        UNSUPPORTED_EXTENSION,
        EMPTY_FILE,
        SIZE_EXCEEDED
    }

    @Getter
    public static final class Result {
        private final boolean ok;
        private final Reason reason;
        private final String message;

        private Result(boolean ok, Reason reason, String message) {
            this.ok = ok;
            this.reason = reason;
            this.message = message;
        }

        public static Result ok() {
            return new Result(true, Reason.OK, "");
        }

        public static Result fail(Reason reason, String message) {
            return new Result(false, reason, message);
        }
    }
}
