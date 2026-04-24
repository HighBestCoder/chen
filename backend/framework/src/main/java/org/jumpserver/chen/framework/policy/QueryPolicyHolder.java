package org.jumpserver.chen.framework.policy;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide singleton accessor for {@link QueryPolicy}.
 *
 * <p>Keeps {@code framework} free from Spring while still letting any
 * code path (including {@code SQLExecutePlan}, {@code BaseSQLActuator},
 * {@code DataViewConsole}) reach the active policy.</p>
 *
 * <p>The {@code backend/web} layer is expected to install a
 * configuration-bound instance during application startup; if no
 * instance is installed, callers transparently fall back to
 * {@code new QueryPolicy()} with the conservative built-in defaults.
 * This keeps tests and CLI tools that do not boot Spring fully
 * functional.</p>
 *
 * <p>Reassignment via {@link #install(QueryPolicy)} is allowed at any
 * point so an operator can hot-reload limits without restarting,
 * but every reference to the policy is fetched anew per call to
 * {@link #current()} so a swap takes effect immediately.</p>
 */
@Slf4j
public final class QueryPolicyHolder {

    private static final AtomicReference<QueryPolicy> ACTIVE = new AtomicReference<>(new QueryPolicy());

    private QueryPolicyHolder() {
    }

    public static QueryPolicy current() {
        QueryPolicy p = ACTIVE.get();
        if (p == null) {
            p = new QueryPolicy();
            ACTIVE.set(p);
        }
        return p;
    }

    public static void install(QueryPolicy policy) {
        if (policy == null) {
            log.warn("QueryPolicyHolder.install(null) ignored, keeping current policy");
            return;
        }
        ACTIVE.set(policy);
        log.info(
                "QueryPolicy installed: maxRows={}, consoleLimit={}, previewLimit={}, defaultTimeout={}s, maxTimeout={}s",
                policy.getMaxRows(),
                policy.getDefaultConsoleLimit(),
                policy.getDefaultPreviewLimit(),
                policy.getDefaultTimeoutSeconds(),
                policy.getMaxTimeoutSeconds()
        );
    }
}
