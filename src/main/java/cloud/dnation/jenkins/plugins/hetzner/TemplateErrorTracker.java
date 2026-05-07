/*
 * Template-level error tracker for persistent configuration errors.
 *
 * When a template fails repeatedly with the same non-transient error
 * (e.g., "image not found" after Hetzner deprecates an image), this
 * tracker suppresses provisioning for that template to avoid wasting
 * API calls on requests that will never succeed.
 *
 * Separate from DcCircuitBreaker (which handles transient DC capacity
 * issues). Config errors are template-scoped, not DC-scoped.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
class TemplateErrorTracker {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long SUPPRESSION_MINUTES = 30;

    private static final ConcurrentHashMap<String, TemplateState> STATES = new ConcurrentHashMap<>();

    private TemplateErrorTracker() {
    }

    /**
     * Record a config error for a template. After FAILURE_THRESHOLD consecutive
     * errors, the template is suppressed for SUPPRESSION_MINUTES.
     */
    static void recordError(String templateName, String errorMessage) {
        HetznerMetricProvider.TEMPLATE_ERRORS.labels(templateName).inc();
        STATES.compute(templateName, (name, state) -> {
            if (state == null) {
                state = new TemplateState();
            }
            state.consecutiveErrors++;
            state.lastError = errorMessage;
            state.lastErrorAt = Instant.now();
            HetznerMetricProvider.TEMPLATE_CONSECUTIVE_ERRORS.labels(name).set(state.consecutiveErrors);
            if (state.consecutiveErrors >= FAILURE_THRESHOLD && !state.isSuppressed()) {
                state.suppressedUntil = Instant.now().plusSeconds(SUPPRESSION_MINUTES * 60);
                log.warn("Template '{}' suppressed for {}m after {} consecutive config errors: {}",
                        name, SUPPRESSION_MINUTES, state.consecutiveErrors, errorMessage);
                HetznerMetricProvider.TEMPLATE_SUPPRESSED_TOTAL.labels(name).inc();
                HetznerMetricProvider.TEMPLATE_SUPPRESSED_ACTIVE.labels(name).set(1);
            }
            return state;
        });
    }

    /**
     * Record a successful provisioning, clearing any error state.
     */
    static void recordSuccess(String templateName) {
        TemplateState removed = STATES.remove(templateName);
        if (removed != null && removed.consecutiveErrors > 0) {
            log.info("Template '{}' error state cleared after successful provisioning", templateName);
            HetznerMetricProvider.TEMPLATE_SUPPRESSED_ACTIVE.labels(templateName).set(0);
            HetznerMetricProvider.TEMPLATE_CONSECUTIVE_ERRORS.labels(templateName).set(0);
        }
    }

    /**
     * Check if a template is currently suppressed.
     * If the suppression window has elapsed, transitions to probe mode
     * (allows one attempt, like circuit breaker HALF_OPEN).
     */
    static boolean isSuppressed(String templateName) {
        TemplateState state = STATES.get(templateName);
        if (state == null) {
            return false;
        }
        if (!state.isSuppressed()) {
            return false;
        }
        if (Instant.now().isAfter(state.suppressedUntil)) {
            // Suppression expired; allow one probe attempt
            log.info("Template '{}' suppression expired, allowing probe attempt", templateName);
            state.suppressedUntil = null;
            HetznerMetricProvider.TEMPLATE_SUPPRESSED_ACTIVE.labels(templateName).set(0);
            return false;
        }
        return true;
    }

    /**
     * Get a summary of all tracked templates. For Script Console observability.
     */
    static String getStatus() {
        if (STATES.isEmpty()) {
            return "No template errors tracked";
        }
        StringBuilder sb = new StringBuilder();
        STATES.forEach((name, state) -> {
            String status = state.isSuppressed()
                    ? "SUPPRESSED (until " + state.suppressedUntil + ")"
                    : "errors=" + state.consecutiveErrors;
            sb.append(String.format("%-40s %s  lastAt: %s  last: %s%n", name, status,
                    state.lastErrorAt != null ? state.lastErrorAt.toString() : "n/a",
                    state.lastError != null ? state.lastError.substring(0, Math.min(80, state.lastError.length())) : ""));
        });
        return sb.toString();
    }

    /** Visible for testing. */
    static void resetAll() {
        STATES.clear();
    }

    private static class TemplateState {
        int consecutiveErrors;
        String lastError;
        Instant lastErrorAt;
        Instant suppressedUntil;

        boolean isSuppressed() {
            return suppressedUntil != null && Instant.now().isBefore(suppressedUntil);
        }
    }
}
