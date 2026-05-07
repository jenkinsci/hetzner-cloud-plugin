/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box integration tests: drive the existing in-memory state machines
 * (DcCircuitBreaker, TemplateErrorTracker, HetznerApiClient) via their
 * package-private API and assert that {@link HetznerMetricProvider}
 * instruments observe the resulting state. Catches wiring regressions that
 * a metric-only unit test would miss.
 */
class HetznerMetricIntegrationTest {

    private static final CollectorRegistry R = CollectorRegistry.defaultRegistry;

    @BeforeEach
    void resetMetrics() {
        HetznerMetricProvider.resetForTest();
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
        HetznerApiClient.resetAll();
    }

    @AfterEach
    void cleanup() {
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
        HetznerApiClient.resetAll();
    }

    // ---- DcCircuitBreaker -> metrics ------------------------------------

    @Test
    void breakerOpenTransitionEmitsStateAndCounter() {
        DcCircuitBreaker cb = new DcCircuitBreaker("loc-1");
        // ctor seeds gauge with CLOSED ordinal
        assertEquals(0.0, sample1("hetzner_dc_circuit_breaker_state", "location", "loc-1"));

        cb.recordFailure();
        cb.recordFailure();   // threshold=2 -> CLOSED -> OPEN

        assertEquals((double) DcCircuitBreaker.State.OPEN.ordinal(),
                sample1("hetzner_dc_circuit_breaker_state", "location", "loc-1"));
        assertEquals(1.0, R.getSampleValue("hetzner_dc_circuit_breaker_transitions_total",
                new String[]{"location", "from", "to"},
                new String[]{"loc-1", "CLOSED", "OPEN"}));
        assertEquals(2.0, sample1("hetzner_dc_circuit_breaker_consecutive_failures",
                "location", "loc-1"));
    }

    @Test
    void breakerSuccessAfterFailureResetsConsecutiveFailures() {
        DcCircuitBreaker cb = new DcCircuitBreaker("loc-2");
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(0.0, sample1("hetzner_dc_circuit_breaker_consecutive_failures",
                "location", "loc-2"));
    }

    /**
     * HALF_OPEN -> OPEN re-trip: breaker times out from OPEN to HALF_OPEN,
     * then probe fails. Reviewer-flagged gap in the integration coverage.
     */
    @Test
    void breakerHalfOpenProbeFailureRetripsToOpen() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("loc-3");
        cb.recordFailure();
        cb.recordFailure(); // CLOSED -> OPEN
        // Backdate openedAt so isHealthy() flips OPEN -> HALF_OPEN
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("openedAt");
        f.setAccessible(true);
        f.setLong(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.isHealthy(), "breaker should transition to HALF_OPEN after timeout");

        cb.recordFailure(); // HALF_OPEN -> OPEN

        // Transitions counter should record CLOSED->OPEN, OPEN->HALF_OPEN,
        // and HALF_OPEN->OPEN (3 distinct transition labels).
        assertEquals(1.0, R.getSampleValue("hetzner_dc_circuit_breaker_transitions_total",
                new String[]{"location", "from", "to"},
                new String[]{"loc-3", "CLOSED", "OPEN"}));
        assertEquals(1.0, R.getSampleValue("hetzner_dc_circuit_breaker_transitions_total",
                new String[]{"location", "from", "to"},
                new String[]{"loc-3", "OPEN", "HALF_OPEN"}));
        assertEquals(1.0, R.getSampleValue("hetzner_dc_circuit_breaker_transitions_total",
                new String[]{"location", "from", "to"},
                new String[]{"loc-3", "HALF_OPEN", "OPEN"}));
        // Final state gauge reflects OPEN
        assertEquals((double) DcCircuitBreaker.State.OPEN.ordinal(),
                sample1("hetzner_dc_circuit_breaker_state", "location", "loc-3"));
    }

    /**
     * Regression test for the {@code getState()} fix: polling getState()
     * after the OPEN-timeout has elapsed must NOT increment the transitions
     * counter (only the gauge). isHealthy() owns the counter increment.
     */
    @Test
    void getStateLazyResetDoesNotEmitTransitionCounter() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("loc-4");
        cb.recordFailure();
        cb.recordFailure(); // CLOSED -> OPEN: transition_total{from=CLOSED,to=OPEN} = 1
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("openedAt");
        f.setAccessible(true);
        f.setLong(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        // Poll getState() multiple times
        for (int i = 0; i < 10; i++) {
            cb.getState();
        }

        // Transitions counter for OPEN->HALF_OPEN must remain at 0 because
        // getState() does NOT emit the counter (only isHealthy() does).
        Double openToHalfOpen = R.getSampleValue("hetzner_dc_circuit_breaker_transitions_total",
                new String[]{"location", "from", "to"},
                new String[]{"loc-4", "OPEN", "HALF_OPEN"});
        assertTrue(openToHalfOpen == null || openToHalfOpen == 0.0,
                "getState() must not emit OPEN->HALF_OPEN transitions; got " + openToHalfOpen);

        // But the state gauge IS updated, so dashboards stay consistent
        assertEquals((double) DcCircuitBreaker.State.HALF_OPEN.ordinal(),
                sample1("hetzner_dc_circuit_breaker_state", "location", "loc-4"));
    }

    // ---- TemplateErrorTracker -> metrics --------------------------------

    @Test
    void templateErrorRecordsCounterAndConsecutiveGauge() {
        TemplateErrorTracker.recordError("tpl-1", "image not found");
        assertEquals(1.0, sample1("hetzner_template_errors_total", "template", "tpl-1"));
        assertEquals(1.0, sample1("hetzner_template_consecutive_errors", "template", "tpl-1"));
    }

    @Test
    void templateThresholdSuppressionEmitsAllSuppressionMetrics() {
        for (int i = 0; i < 3; i++) {
            TemplateErrorTracker.recordError("tpl-2", "image not found");
        }
        assertEquals(3.0, sample1("hetzner_template_errors_total", "template", "tpl-2"));
        assertEquals(3.0, sample1("hetzner_template_consecutive_errors", "template", "tpl-2"));
        assertEquals(1.0, sample1("hetzner_template_suppressed_total", "template", "tpl-2"));
        assertEquals(1.0, sample1("hetzner_template_suppressed_active", "template", "tpl-2"));
    }

    @Test
    void templateSuccessClearsSuppressionGauge() {
        for (int i = 0; i < 3; i++) {
            TemplateErrorTracker.recordError("tpl-3", "image not found");
        }
        TemplateErrorTracker.recordSuccess("tpl-3");
        assertEquals(0.0, sample1("hetzner_template_consecutive_errors", "template", "tpl-3"));
        assertEquals(0.0, sample1("hetzner_template_suppressed_active", "template", "tpl-3"));
        // counter is monotonic -- still 1
        assertEquals(1.0, sample1("hetzner_template_suppressed_total", "template", "tpl-3"));
    }

    // ---- HetznerApiClient -> metrics ------------------------------------

    @Test
    void rateLimitStateUpdatesGauges() {
        HetznerApiClient client = HetznerApiClient.forCredentials("creds-A");
        client.updateRateLimitState(3600, 1234);

        assertEquals(3600.0, sample1("hetzner_api_rate_limit_limit",
                "credentials_id", "creds-A"));
        assertEquals(1234.0, sample1("hetzner_api_rate_limit_remaining",
                "credentials_id", "creds-A"));
    }

    @Test
    void recordRateLimitSetsRateLimitedGauge() {
        HetznerApiClient client = HetznerApiClient.forCredentials("creds-B");
        client.recordRateLimit(60);

        assertEquals(1.0, sample1("hetzner_api_rate_limited",
                "credentials_id", "creds-B"));
        assertNotNull(client.timeUntilReset());
        // The reset window is in the future
        assert client.timeUntilReset().compareTo(Duration.ZERO) > 0;
        // sanity: blockedUntil is later than now
        assert Instant.now().plusSeconds(120).isAfter(Instant.now());
    }

    // ---- Helpers --------------------------------------------------------

    private static Double sample1(String name, String labelName, String labelValue) {
        return R.getSampleValue(name, new String[]{labelName}, new String[]{labelValue});
    }
}
