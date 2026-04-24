package org.jumpserver.chen.framework.audit;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern ENVELOPE_PATTERN =
            Pattern.compile(Pattern.quote(OPEN_TAG) + "(.*?)" + Pattern.quote(CLOSE_TAG), Pattern.DOTALL);

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
        Matcher m = ENVELOPE_PATTERN.matcher(enveloped);
        if (!m.find()) {
            return Optional.empty();
        }
        String json = m.group(1);
        try {
            JSONObject obj = JSON.parseObject(json);
            return Optional.ofNullable(JSON.toJavaObject(obj, ExecutionStats.class));
        } catch (Throwable t) {
            log.warn("ExecutionStatsEnvelope: failed to decode stats payload", t);
            return Optional.empty();
        }
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
        Matcher m = ENVELOPE_PATTERN.matcher(enveloped);
        if (!m.find()) {
            return enveloped;
        }
        String stripped = m.replaceAll("");
        // Drop the trailing newline we inserted before the envelope.
        if (stripped.endsWith("\n")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
