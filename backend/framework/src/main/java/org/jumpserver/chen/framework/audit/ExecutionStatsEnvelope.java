package org.jumpserver.chen.framework.audit;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Wire envelope that piggybacks {@link ExecutionStats} on the existing
 * {@code CommandRequest.output} string field, avoiding a synchronous
 * wisp .proto regeneration across chen / wisp / core.
 *
 * <p>Format (single line, appended to the human-visible output):</p>
 * <pre>
 *   &lt;original output&gt;\n[[CHEN_EXEC_STATS_V1]]{json}[[/CHEN_EXEC_STATS_V1]]
 * </pre>
 *
 * <p>The marker is intentionally distinctive so that:
 * <ul>
 *   <li>core / entra-patch can locate, parse and strip it before persistence;</li>
 *   <li>any non-aware downstream that displays {@code output} as-is suffers
 *       only a single trailing line of noise rather than a parse failure.</li>
 * </ul>
 * </p>
 *
 * <p>The version segment ({@code V1}) is bound to {@link ExecutionStats#SCHEMA_VERSION}
 * and must be bumped together when the field set changes incompatibly.</p>
 */
@Slf4j
public final class ExecutionStatsEnvelope {

    public static final String OPEN_TAG = "[[CHEN_EXEC_STATS_V1]]";
    public static final String CLOSE_TAG = "[[/CHEN_EXEC_STATS_V1]]";

    private ExecutionStatsEnvelope() {
    }

    /**
     * Append the stats envelope to the given output string. If {@code stats}
     * is {@code null} the output is returned unchanged; on serialisation
     * failure the original output is returned and the error is logged so the
     * audit pipeline never breaks because of a stats issue.
     */
    public static String appendTo(String output, ExecutionStats stats) {
        if (stats == null) {
            return output == null ? "" : output;
        }
        String safeOutput = output == null ? "" : output;
        try {
            String json = JSON.toJSONString(stats);
            return safeOutput + "\n" + OPEN_TAG + json + CLOSE_TAG;
        } catch (Throwable t) {
            log.warn("ExecutionStatsEnvelope: failed to serialise stats, dropping envelope", t);
            return safeOutput;
        }
    }

    /**
     * Try to extract a stats envelope from a previously enveloped string.
     * Returns {@link Optional#empty()} when no envelope is present or the
     * payload is not a valid JSON object.
     */
    public static Optional<ExecutionStats> tryDecode(String enveloped) {
        if (enveloped == null || enveloped.isEmpty()) {
            return Optional.empty();
        }
        int start = envelopeStart(enveloped);
        if (start < 0) return Optional.empty();
        String json = enveloped.substring(start + OPEN_TAG.length(), enveloped.length() - CLOSE_TAG.length());
        try { return Optional.ofNullable(JSON.toJavaObject(JSON.parseObject(json), ExecutionStats.class)); }
        catch (RuntimeException invalid) { return Optional.empty(); }
    }

    private static int envelopeStart(String text) {
        if (text == null || !text.endsWith(CLOSE_TAG)) return -1;
        int end = text.length() - CLOSE_TAG.length();
        for (int start = text.lastIndexOf(OPEN_TAG, end - 1); start >= 0; start = text.lastIndexOf(OPEN_TAG, start - 1)) {
            try {
                String json = text.substring(start + OPEN_TAG.length(), end);
                if (json.stripLeading().startsWith("{") && JSON.parseObject(json) != null) return start;
            } catch (RuntimeException ignored) { }
        }
        return -1;
    }

    /**
     * Strip the envelope (if any) from a string, returning the original
     * human-visible output. Useful for replay or display surfaces that do
     * not understand the structured payload.
     */
    public static String strip(String enveloped) {
        if (enveloped == null || enveloped.isEmpty()) {
            return enveloped;
        }
        int start = envelopeStart(enveloped);
        if (start < 0) return enveloped;
        if (start > 0 && enveloped.charAt(start - 1) == '\n') start--;
        return enveloped.substring(0, start);
    }
}
