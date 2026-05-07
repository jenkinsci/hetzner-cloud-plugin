/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner.metrics;

import io.prometheus.client.CollectorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies every {@link HetznerMetricProvider} instrument registers with the
 * default registry and responds to its primary mutation site. Hook-site
 * integration (DcCircuitBreaker, TemplateErrorTracker, HetznerApiClient,
 * etc.) is exercised via the existing in-memory state machines so we do not
 * have to stand up a full Jenkins runtime here.
 */
class HetznerMetricProviderTest {

    private static final CollectorRegistry R = CollectorRegistry.defaultRegistry;

    @BeforeEach
    void resetMetrics() {
        HetznerMetricProvider.resetForTest();
    }

    // ---- Provisioning lifecycle ------------------------------------------

    @Test
    void pendingProvisionsGaugeReflectsSetCalls() {
        HetznerMetricProvider.PROVISIONING_PENDING.labels("test-cloud").set(3);
        assertEquals(3.0, sample("hetzner_provisioning_pending", "cloud", "test-cloud"));
    }

    @Test
    void runningServersGaugeReflectsSetCalls() {
        HetznerMetricProvider.RUNNING_SERVERS.labels("test-cloud").set(7);
        assertEquals(7.0, sample("hetzner_running_servers", "cloud", "test-cloud"));
    }

    @Test
    void instanceCapGaugeReflectsSetCalls() {
        HetznerMetricProvider.INSTANCE_CAP.labels("test-cloud").set(50);
        assertEquals(50.0, sample("hetzner_instance_cap", "cloud", "test-cloud"));
    }

    @Test
    void provisionUnderflowCounterIncrements() {
        HetznerMetricProvider.PROVISION_UNDERFLOW.labels("test-cloud").inc();
        HetznerMetricProvider.PROVISION_UNDERFLOW.labels("test-cloud").inc();
        assertEquals(2.0, sample("hetzner_provision_underflow_total", "cloud", "test-cloud"));
    }

    @Test
    void provisionUncaughtCounterIncrements() {
        HetznerMetricProvider.PROVISION_UNCAUGHT.labels("test-cloud").inc();
        assertEquals(1.0, sample("hetzner_provision_uncaught_exceptions_total",
                "cloud", "test-cloud"));
    }

    @Test
    void provisionAttemptsCounterIncrements() {
        HetznerMetricProvider.PROVISION_ATTEMPTS
                .labels("test-cloud", "docker-x64-min", "success").inc();
        HetznerMetricProvider.PROVISION_ATTEMPTS
                .labels("test-cloud", "docker-x64-min", "success").inc();
        assertEquals(2.0, sample("hetzner_provision_attempts_total",
                new String[]{"cloud", "template", "outcome"},
                new String[]{"test-cloud", "docker-x64-min", "success"}));
    }

    @Test
    void provisionSkippedCounterIncrements() {
        HetznerMetricProvider.PROVISION_SKIPPED.labels("test-cloud", "cap_reached").inc();
        assertEquals(1.0, sample("hetzner_provision_skipped_total",
                new String[]{"cloud", "reason"},
                new String[]{"test-cloud", "cap_reached"}));
    }

    @Test
    void provisionDurationHistogramExposesSumCountAndBuckets() {
        HetznerMetricProvider.PROVISION_DURATION
                .labels("docker-x64-min", "fsn1", "success").observe(42.0);
        // _count: number of observations
        Double count = R.getSampleValue("hetzner_provision_duration_seconds_count",
                new String[]{"template", "dc", "outcome"},
                new String[]{"docker-x64-min", "fsn1", "success"});
        // _sum: sum of observations
        Double sum = R.getSampleValue("hetzner_provision_duration_seconds_sum",
                new String[]{"template", "dc", "outcome"},
                new String[]{"docker-x64-min", "fsn1", "success"});
        // _bucket: at least the +Inf bucket should count this observation
        Double bucketInf = R.getSampleValue("hetzner_provision_duration_seconds_bucket",
                new String[]{"template", "dc", "outcome", "le"},
                new String[]{"docker-x64-min", "fsn1", "success", "+Inf"});
        assertEquals(1.0, count);
        assertEquals(42.0, sum);
        assertEquals(1.0, bucketInf);
    }

    @Test
    void provisionLeakedAndDestroyFailureCountersIncrement() {
        HetznerMetricProvider.PROVISION_LEAKED_SERVERS
                .labels("test-cloud", "docker-x64-min").inc();
        HetznerMetricProvider.PROVISION_LEAK_DESTROY_FAILURES
                .labels("test-cloud", "docker-x64-min").inc();
        assertEquals(1.0, sample("hetzner_provision_leaked_servers_total",
                new String[]{"cloud", "template"},
                new String[]{"test-cloud", "docker-x64-min"}));
        assertEquals(1.0, sample("hetzner_provision_leak_destroy_failures_total",
                new String[]{"cloud", "template"},
                new String[]{"test-cloud", "docker-x64-min"}));
    }

    // ---- DC failover -----------------------------------------------------

    @Test
    void dcBreakerStateGaugeReflectsOrdinal() {
        HetznerMetricProvider.DC_BREAKER_STATE.labels("fsn1").set(1); // OPEN
        assertEquals(1.0, sample("hetzner_dc_circuit_breaker_state", "location", "fsn1"));
    }

    @Test
    void dcBreakerTransitionCounterIncrements() {
        HetznerMetricProvider.DC_BREAKER_TRANSITIONS.labels("fsn1", "CLOSED", "OPEN").inc();
        assertEquals(1.0, sample("hetzner_dc_circuit_breaker_transitions_total",
                new String[]{"location", "from", "to"},
                new String[]{"fsn1", "CLOSED", "OPEN"}));
    }

    @Test
    void dcBreakerConsecutiveFailuresGaugeReflectsSet() {
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels("fsn1").set(5);
        assertEquals(5.0, sample("hetzner_dc_circuit_breaker_consecutive_failures",
                "location", "fsn1"));
    }

    @Test
    void dcFailoverCounterIncrements() {
        HetznerMetricProvider.DC_FAILOVER.labels("fsn1", "nbg1").inc();
        assertEquals(1.0, sample("hetzner_dc_failover_total",
                new String[]{"from_dc", "to_dc"},
                new String[]{"fsn1", "nbg1"}));
    }

    // ---- Orphan / ghost cleanup ------------------------------------------

    @Test
    void orphanReapedAndGhostRemovedCountersIncrement() {
        HetznerMetricProvider.ORPHAN_REAPED.labels("hetzner-cloud").inc();
        HetznerMetricProvider.GHOST_REMOVED.labels("hetzner-cloud").inc();
        assertEquals(1.0, sample("hetzner_orphan_servers_reaped_total",
                "cloud", "hetzner-cloud"));
        assertEquals(1.0, sample("hetzner_ghost_nodes_removed_total",
                "cloud", "hetzner-cloud"));
    }

    @Test
    void orphanCleanupErrorCounterIncrements() {
        HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS.labels("hetzner-cloud", "fetch_servers").inc();
        assertEquals(1.0, sample("hetzner_orphan_cleanup_errors_total",
                new String[]{"cloud", "kind"},
                new String[]{"hetzner-cloud", "fetch_servers"}));
    }

    @Test
    void orphanCleanupDurationHistogramObservesValues() {
        HetznerMetricProvider.ORPHAN_CLEANUP_DURATION.labels("hetzner-cloud").observe(0.5);
        Double count = R.getSampleValue("hetzner_orphan_cleanup_duration_seconds_count",
                new String[]{"cloud"}, new String[]{"hetzner-cloud"});
        Double sum = R.getSampleValue("hetzner_orphan_cleanup_duration_seconds_sum",
                new String[]{"cloud"}, new String[]{"hetzner-cloud"});
        assertEquals(1.0, count);
        assertEquals(0.5, sum);
    }

    // ---- Template suppression --------------------------------------------

    @Test
    void templateSuppressionCountersAndGaugesUpdate() {
        HetznerMetricProvider.TEMPLATE_SUPPRESSED_TOTAL.labels("docker-x64-min").inc();
        HetznerMetricProvider.TEMPLATE_SUPPRESSED_ACTIVE.labels("docker-x64-min").set(1);
        HetznerMetricProvider.TEMPLATE_ERRORS.labels("docker-x64-min").inc();
        HetznerMetricProvider.TEMPLATE_CONSECUTIVE_ERRORS.labels("docker-x64-min").set(2);

        assertEquals(1.0, sample("hetzner_template_suppressed_total",
                "template", "docker-x64-min"));
        assertEquals(1.0, sample("hetzner_template_suppressed_active",
                "template", "docker-x64-min"));
        assertEquals(1.0, sample("hetzner_template_errors_total",
                "template", "docker-x64-min"));
        assertEquals(2.0, sample("hetzner_template_consecutive_errors",
                "template", "docker-x64-min"));
    }

    // ---- Architecture validation -----------------------------------------

    @Test
    void archValidationFailureCounterIncrements() {
        HetznerMetricProvider.ARCH_VALIDATION_FAILURES.labels("api", "cax41", "cpx62").inc();
        assertEquals(1.0, sample("hetzner_arch_validation_failures_total",
                new String[]{"phase", "requested", "actual"},
                new String[]{"api", "cax41", "cpx62"}));
    }

    // ---- API rate limit --------------------------------------------------

    @Test
    void apiRateLimitGaugesReflectSet() {
        HetznerMetricProvider.API_RATE_LIMIT_REMAINING.labels("creds-1").set(123);
        HetznerMetricProvider.API_RATE_LIMIT_LIMIT.labels("creds-1").set(3600);
        HetznerMetricProvider.API_RATE_LIMITED.labels("creds-1").set(1);

        assertEquals(123.0, sample("hetzner_api_rate_limit_remaining",
                "credentials_id", "creds-1"));
        assertEquals(3600.0, sample("hetzner_api_rate_limit_limit",
                "credentials_id", "creds-1"));
        assertEquals(1.0, sample("hetzner_api_rate_limited",
                "credentials_id", "creds-1"));
    }

    @Test
    void apiRequestsCounterIncrements() {
        HetznerMetricProvider.API_REQUESTS.labels("creds-1", "GET", "2xx").inc();
        HetznerMetricProvider.API_REQUESTS.labels("creds-1", "POST", "5xx").inc();
        assertEquals(1.0, sample("hetzner_api_requests_total",
                new String[]{"credentials_id", "method", "status_class"},
                new String[]{"creds-1", "GET", "2xx"}));
        assertEquals(1.0, sample("hetzner_api_requests_total",
                new String[]{"credentials_id", "method", "status_class"},
                new String[]{"creds-1", "POST", "5xx"}));
    }

    @Test
    void apiRequestDurationHistogramObservesValues() {
        HetznerMetricProvider.API_REQUEST_DURATION.labels("creds-1", "GET").observe(0.25);
        Double count = R.getSampleValue("hetzner_api_request_duration_seconds_count",
                new String[]{"credentials_id", "method"},
                new String[]{"creds-1", "GET"});
        Double sum = R.getSampleValue("hetzner_api_request_duration_seconds_sum",
                new String[]{"credentials_id", "method"},
                new String[]{"creds-1", "GET"});
        assertEquals(1.0, count);
        assertEquals(0.25, sum);
    }

    @Test
    void apiRetryAndExhaustedCountersIncrement() {
        HetznerMetricProvider.API_RETRIES.labels("creds-1", "http_502").inc();
        HetznerMetricProvider.API_RETRIES.labels("creds-1", "timeout").inc();
        HetznerMetricProvider.API_RETRIES_EXHAUSTED.labels("creds-1", "timeout").inc();
        HetznerMetricProvider.API_TOKEN_INVALIDATED.labels("creds-1").inc();

        assertEquals(1.0, sample("hetzner_api_retries_total",
                new String[]{"credentials_id", "reason"},
                new String[]{"creds-1", "http_502"}));
        assertEquals(1.0, sample("hetzner_api_retries_total",
                new String[]{"credentials_id", "reason"},
                new String[]{"creds-1", "timeout"}));
        assertEquals(1.0, sample("hetzner_api_retries_exhausted_total",
                new String[]{"credentials_id", "reason"},
                new String[]{"creds-1", "timeout"}));
        assertEquals(1.0, sample("hetzner_api_token_invalidated_total",
                "credentials_id", "creds-1"));
    }

    // ---- Static info / runtime metadata ----------------------------------

    @Test
    void pluginInfoIsSetAtClassInit() {
        // Plugin info is set in a static block; it's the only metric not
        // cleared by resetForTest(). At least one sample should exist with
        // the configured labels (we don't pin the version since it depends on
        // the JAR manifest at build time).
        var families = R.metricFamilySamples();
        boolean foundInfo = false;
        while (families.hasMoreElements()) {
            var family = families.nextElement();
            if ("hetzner_plugin_info".equals(family.name)) {
                foundInfo = !family.samples.isEmpty();
                break;
            }
        }
        assertTrue(foundInfo, "hetzner_plugin_info should be registered with at least one sample");
    }

    @Test
    void cloudInfoGaugeRespondsToSet() {
        HetznerMetricProvider.CLOUD_INFO.labels("hetzner-cloud", "creds-1").set(1);
        assertEquals(1.0, sample("hetzner_cloud_info",
                new String[]{"cloud", "credentials_id"},
                new String[]{"hetzner-cloud", "creds-1"}));
    }

    @Test
    void templateInfoGaugeRespondsToSet() {
        HetznerMetricProvider.TEMPLATE_INFO.labels(
                "hetzner-cloud", "docker-x64-min", "ubuntu-22.04", "cpx62", "fsn1"
        ).set(1);
        assertEquals(1.0, sample("hetzner_template_info",
                new String[]{"cloud", "template", "image", "server_type", "location"},
                new String[]{"hetzner-cloud", "docker-x64-min", "ubuntu-22.04", "cpx62", "fsn1"}));
    }

    @Test
    void templateExecutorsGaugeRespondsToSet() {
        HetznerMetricProvider.TEMPLATE_EXECUTORS.labels("hetzner-cloud", "docker-x64-min").set(2);
        assertEquals(2.0, sample("hetzner_template_executors",
                new String[]{"cloud", "template"},
                new String[]{"hetzner-cloud", "docker-x64-min"}));
    }

    @Test
    void cloudTemplateCountGaugeRespondsToSet() {
        HetznerMetricProvider.CLOUD_TEMPLATE_COUNT.labels("hetzner-cloud").set(5);
        assertEquals(5.0, sample("hetzner_cloud_template_count",
                "cloud", "hetzner-cloud"));
    }

    @Test
    void runtimeInfoIsSetAtClassInit() {
        // Like PLUGIN_INFO, set once in the static block. Labels are populated
        // from System.getProperty(os.name/os.arch/os.version/java.version)
        // and the EC2_INSTANCE_TYPE env var.
        var families = R.metricFamilySamples();
        boolean foundInfo = false;
        while (families.hasMoreElements()) {
            var family = families.nextElement();
            if ("hetzner_runtime_info".equals(family.name)) {
                foundInfo = !family.samples.isEmpty();
                if (foundInfo) {
                    var sample = family.samples.get(0);
                    // labels are: os_name, os_arch, os_version, java_version, ec2_instance_type
                    assertEquals(5, sample.labelValues.size(),
                            "RUNTIME_INFO must have exactly 5 label values");
                    // Sanity: os_name should be a known platform string
                    String osName = sample.labelValues.get(0);
                    assertTrue(osName.equals("linux") || osName.equals("mac_os_x")
                                    || osName.equals("windows") || osName.startsWith("mac")
                                    || osName.startsWith("linux"),
                            "os_name should be a recognized platform, got: " + osName);
                }
                break;
            }
        }
        assertTrue(foundInfo, "hetzner_runtime_info should be registered with at least one sample");
    }

    // ---- Coverage sanity check -------------------------------------------

    /**
     * Lightweight smoke test that all the well-known metric family names are
     * reachable from {@link CollectorRegistry#defaultRegistry}. Catches typos
     * and accidental .register() removals.
     */
    @Test
    void allExpectedMetricFamiliesAreRegistered() {
        String[] expected = {
                "hetzner_provisioning_pending",
                "hetzner_running_servers",
                "hetzner_instance_cap",
                "hetzner_provision_underflow_total",
                "hetzner_provision_uncaught_exceptions_total",
                "hetzner_provision_duration_seconds",
                "hetzner_provision_attempts_total",
                "hetzner_provision_skipped_total",
                "hetzner_provision_leaked_servers_total",
                "hetzner_provision_leak_destroy_failures_total",
                "hetzner_dc_circuit_breaker_state",
                "hetzner_dc_circuit_breaker_consecutive_failures",
                "hetzner_dc_circuit_breaker_transitions_total",
                "hetzner_dc_failover_total",
                "hetzner_orphan_servers_reaped_total",
                "hetzner_ghost_nodes_removed_total",
                "hetzner_orphan_cleanup_errors_total",
                "hetzner_orphan_cleanup_duration_seconds",
                "hetzner_template_suppressed_total",
                "hetzner_template_suppressed_active",
                "hetzner_template_errors_total",
                "hetzner_template_consecutive_errors",
                "hetzner_arch_validation_failures_total",
                "hetzner_api_rate_limit_remaining",
                "hetzner_api_rate_limit_limit",
                "hetzner_api_rate_limited",
                "hetzner_api_requests_total",
                "hetzner_api_request_duration_seconds",
                "hetzner_api_retries_total",
                "hetzner_api_retries_exhausted_total",
                "hetzner_api_token_invalidated_total",
                "hetzner_plugin_info",
                "hetzner_cloud_info",
                "hetzner_template_info",
                "hetzner_template_executors",
                "hetzner_cloud_template_count",
                "hetzner_runtime_info",
        };
        java.util.Set<String> registered = new java.util.HashSet<>();
        var iter = R.metricFamilySamples();
        while (iter.hasMoreElements()) {
            registered.add(iter.nextElement().name);
        }
        for (String name : expected) {
            // simpleclient strips the "_total" suffix from a Counter's family
            // name, so a Counter declared as "foo_total" appears in the registry
            // under family name "foo". Histograms / Summaries also expose
            // {_count, _sum, _bucket} samples under the base family name.
            String baseFamily = name.endsWith("_total")
                    ? name.substring(0, name.length() - "_total".length())
                    : name;
            assertTrue(registered.contains(name)
                            || registered.contains(baseFamily)
                            || registered.contains(name + "_count")
                            || registered.contains(name + "_sum"),
                    "expected metric family not registered: " + name
                            + " (also tried: " + baseFamily + ")");
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private static Double sample(String name, String labelName, String labelValue) {
        return R.getSampleValue(name, new String[]{labelName}, new String[]{labelValue});
    }

    private static Double sample(String name, String[] labelNames, String[] labelValues) {
        return R.getSampleValue(name, labelNames, labelValues);
    }
}
