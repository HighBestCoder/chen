package org.jumpserver.chen.web.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.jumpserver.chen.framework.policy.QueryPolicy;
import org.jumpserver.chen.framework.policy.QueryPolicyHolder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring binding for the {@code query.*} block in {@code application.yml}.
 *
 * <p>Pushes the resolved values into {@link QueryPolicyHolder} as soon as
 * the bean is constructed so any thread that subsequently consults
 * {@link QueryPolicyHolder#current()} sees the configured policy.</p>
 *
 * <p>All keys are optional; missing keys keep the {@link QueryPolicy}
 * built-in defaults (task-02 §3.2 / §3.3 / §3.4 baselines).</p>
 *
 * <p>Example {@code application.yml}:</p>
 * <pre>
 * query:
 *   max-rows: 50000
 *   default-console-limit: 50
 *   default-preview-limit: 100
 *   default-timeout-seconds: 30
 *   max-timeout-seconds: 300
 *   export-max-rows: 100000
 *   console-limit-options: [50, 100, 500, 5000, 50000]
 * </pre>
 */
@Configuration
@Data
@ConfigurationProperties(prefix = "query")
public class QueryConfig {

    private Integer maxRows;
    private Integer defaultConsoleLimit;
    private Integer defaultPreviewLimit;
    private Integer defaultTimeoutSeconds;
    private Integer maxTimeoutSeconds;
    private Integer exportMaxRows;
    private List<Integer> consoleLimitOptions;

    @PostConstruct
    public void install() {
        QueryPolicy policy = new QueryPolicy();
        if (maxRows != null && maxRows > 0) {
            policy.setMaxRows(maxRows);
        }
        if (defaultConsoleLimit != null && defaultConsoleLimit > 0) {
            policy.setDefaultConsoleLimit(defaultConsoleLimit);
        }
        if (defaultPreviewLimit != null && defaultPreviewLimit > 0) {
            policy.setDefaultPreviewLimit(defaultPreviewLimit);
        }
        if (defaultTimeoutSeconds != null && defaultTimeoutSeconds > 0) {
            policy.setDefaultTimeoutSeconds(defaultTimeoutSeconds);
        }
        if (maxTimeoutSeconds != null && maxTimeoutSeconds > 0) {
            policy.setMaxTimeoutSeconds(maxTimeoutSeconds);
        }
        if (exportMaxRows != null && exportMaxRows > 0) {
            policy.setExportMaxRows(exportMaxRows);
        }
        if (consoleLimitOptions != null && !consoleLimitOptions.isEmpty()) {
            policy.setConsoleLimitOptions(List.copyOf(consoleLimitOptions));
        }
        QueryPolicyHolder.install(policy);
    }
}
