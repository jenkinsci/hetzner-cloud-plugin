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
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;

/**
 * Prometheus instruments for the Hetzner Cloud plugin (PS-10997).
 *
 * Metrics register with {@link CollectorRegistry#defaultRegistry}, which the
 * Jenkins prometheus-plugin scrapes verbatim from {@code /prometheus}. We do
 * not own state -- every instrument is driven from the existing in-memory
 * machinery (DcCircuitBreaker, TemplateErrorTracker, OrphanedNodesCleaner,
 * HetznerCloud, HetznerApiClient, NodeCallable). This class is a registry +
 * dispatch layer only.
 *
 * Naming follows the {@code hetzner_*} prefix convention; label sets follow
 * Prometheus best practice (low cardinality, no PII). The {@code provision_
 * underflow_total} and {@code provision_uncaught_exceptions_total} counters
 * are CRW-death canaries: any non-zero rate indicates structural fault and
 * should page.
 *
 * "Info" gauges (value=1, identity in labels) follow the standard Prometheus
 * pattern -- they exist so dashboards and alerts can {@code group_left} on
 * static metadata (plugin version, cloud config, template config) without
 * the data-plane metrics having to repeat it.
 */
public final class HetznerMetricProvider {

    private HetznerMetricProvider() {
    }

    // =====================================================================
    // Provisioning lifecycle (per-cloud)
    // =====================================================================

    /** In-flight provisions past the cap check, not yet completed. */
    public static final Gauge PROVISIONING_PENDING = Gauge.build()
            .name("hetzner_provisioning_pending")
            .help("In-flight Hetzner provisions past the cap check, not yet completed")
            .labelNames("cloud")
            .register();

    /** Current Hetzner-side running VM count for this cloud (per Hetzner API). */
    public static final Gauge RUNNING_SERVERS = Gauge.build()
            .name("hetzner_running_servers")
            .help("Current count of running Hetzner VMs visible to this cloud")
            .labelNames("cloud")
            .register();

    /** Configured {@code instanceCapStr} for this cloud. */
    public static final Gauge INSTANCE_CAP = Gauge.build()
            .name("hetzner_instance_cap")
            .help("Configured instanceCap (max parallel VMs) for this cloud")
            .labelNames("cloud")
            .register();

    /**
     * CRW-death canary. {@code pendingProvisions.getAndDecrement()} returning
     * {@code <= 0} means the increment/decrement contract was violated -- the
     * exact failure mode that historically killed the
     * {@code ComputerRetentionWork} timer. Any non-zero rate is a structural
     * bug; alert immediately.
     */
    public static final Counter PROVISION_UNDERFLOW = Counter.build()
            .name("hetzner_provision_underflow_total")
            .help("CRW death canary: pendingProvisions decrement-from-zero events")
            .labelNames("cloud")
            .register();

    /**
     * Defensive-guard canary. The catch-all in {@code HetznerCloud.provision()}
     * swallows uncaught exceptions to keep the CRW timer alive; this counter
     * records how often it fires.
     */
    public static final Counter PROVISION_UNCAUGHT = Counter.build()
            .name("hetzner_provision_uncaught_exceptions_total")
            .help("Uncaught exceptions caught by HetznerCloud.provision() defensive guard")
            .labelNames("cloud")
            .register();

    /**
     * Wall time of a single {@code NodeCallable.doProvision()} call, recorded
     * per attempt: a failover that succeeds on the second DC yields two
     * observations (one {@code outcome=failure}, one {@code outcome=success}).
     * Buckets target the operational range (15s healthy boot &rarr; 15min
     * worst-case Hetzner pull).
     */
    public static final Histogram PROVISION_DURATION = Histogram.build()
            .name("hetzner_provision_duration_seconds")
            .help("Wall time of NodeCallable.doProvision() per attempt")
            // Includes cloud so multi-cloud deployments don't collapse series
            // across instances with identically-named templates (e.g. two
            // masters both named "t1" in the same DC). All other PROVISION_*
            // metrics already carry cloud; this aligns it.
            .labelNames("cloud", "template", "dc", "outcome")
            // Bucket fill: typical healthy boot lands 60-90s on Hetzner; the
            // 90/180s rungs catch boot drift before the 300s cliff.
            .buckets(5, 15, 30, 60, 90, 120, 180, 300, 600, 900)
            .register();

    /**
     * Per-attempt outcome. Use one of the {@code OUTCOME_*} constants below as
     * the {@code outcome} label; the full enumeration is documented there.
     * Centralizing the values protects dashboards from silently drifting when
     * a new branch is added in NodeCallable.
     *
     * Bootstrap-phase breakdown (boot timeout vs. SSH connect vs. arch
     * mismatch vs. addNode failure) is split here: {@link #OUTCOME_BOOTSTRAP_IO}
     * is recorded when the launcher / SSH path failed (DC-attributable, used
     * for breaker tracking via {@code DcHealthTracker.recordFailure}), and
     * {@link #OUTCOME_BOOTSTRAP_OTHER} is recorded for non-IO bootstrap
     * failures (arch mismatch, addNode failure, runtime). Refining further
     * (e.g. a separate {@code bootstrap_addnode} bucket) is tracked as
     * follow-up.
     */
    public static final Counter PROVISION_ATTEMPTS = Counter.build()
            .name("hetzner_provision_attempts_total")
            .help("Per-attempt provisioning outcomes (one observation per template tried)")
            .labelNames("cloud", "template", "outcome")
            .register();

    /**
     * Authoritative set of {@code outcome} label values emitted on
     * {@link #PROVISION_ATTEMPTS}. Every call site uses one of these constants;
     * a new outcome must be added here so dashboards and alerting rules can
     * be updated atomically.
     */
    public static final String OUTCOME_SUCCESS = "success";
    /** Hetzner returned HTTP 429 with code {@code rate_limit_exceeded}. */
    public static final String OUTCOME_RATE_LIMITED = "rate_limited";
    /**
     * Hetzner returned HTTP 429 with an unclassified code (neither
     * {@code rate_limit_exceeded} nor our synthetic {@code instance_cap_reached}).
     * Treated as a token throttle for routing; surfaced as its own outcome so
     * unknown 429 codes do not silently merge with real rate-limit events.
     */
    public static final String OUTCOME_UNCLASSIFIED_THROTTLE = "unclassified_throttle";
    /** Template-scoped config error (bad image, malformed selector, etc). */
    public static final String OUTCOME_CONFIG_ERROR = "config_error";
    /**
     * Plugin-synthesized HTTP 429 with code {@code instance_cap_reached},
     * raised by the under-lock cap recheck in HetznerCloudResourceManager.
     * Not a DC health signal; treated as cloud cap bookkeeping.
     */
    public static final String OUTCOME_CAP_REACHED_UNDER_LOCK = "cap_reached_under_lock";
    /**
     * Next ranked template was not failover-compatible with the agent's
     * baseline template (different connector / credentials / labels /
     * executors / connection method). Treated as a final failure to avoid
     * wrong-metadata bootstraps.
     */
    public static final String OUTCOME_FAILOVER_INCOMPATIBLE = "failover_incompatible";
    /** Hetzner returned {@code resource_unavailable} for this DC. */
    public static final String OUTCOME_DC_UNAVAILABLE = "dc_unavailable";
    /**
     * Other plausibly-DC-attributable failure (unknown 422, 5xx). Recorded
     * as a soft DC failure for breaker tracking; failed over to the next DC
     * if available.
     */
    public static final String OUTCOME_DC_ATTRIBUTABLE = "dc_attributable";
    /** Non-retryable Hetzner failure or last template attempt. */
    public static final String OUTCOME_FAILURE = "failure";
    /**
     * Post-create launcher / SSH / remoting failure. Recorded as a soft DC
     * bootstrap failure so chronic DC-scoped launcher rot trips the breaker.
     */
    public static final String OUTCOME_BOOTSTRAP_IO = "bootstrap_io";
    /**
     * Post-create non-IO bootstrap failure (arch mismatch, addNode failure,
     * unexpected runtime). NOT recorded as a DC failure.
     */
    public static final String OUTCOME_BOOTSTRAP_OTHER = "bootstrap_other";

    /**
     * Pre-check / precheck failure: an outcome that exited before any real
     * boot work was performed. Used only on {@link #PROVISION_DURATION} so
     * boot-duration p50/p95 dashboards are not skewed by millisecond-elapsed
     * failures (rate-limit, cap-reached, config-error, etc).
     */
    public static final String OUTCOME_PRECHECK_FAILURE = "precheck_failure";

    /**
     * Set of {@link #PROVISION_ATTEMPTS} outcomes that exited before any real
     * boot work was attempted. {@link #PROVISION_DURATION} maps these to
     * {@link #OUTCOME_PRECHECK_FAILURE} instead of the generic
     * {@link #OUTCOME_FAILURE} so boot-duration percentile dashboards are
     * not polluted by these millisecond-elapsed failures.
     */
    public static final java.util.Set<String> PRECHECK_OUTCOMES = java.util.Set.of(
            OUTCOME_RATE_LIMITED,
            OUTCOME_UNCLASSIFIED_THROTTLE,
            OUTCOME_CONFIG_ERROR,
            OUTCOME_CAP_REACHED_UNDER_LOCK,
            OUTCOME_FAILOVER_INCOMPATIBLE
    );

    /**
     * Authoritative enumeration of {@link #PROVISION_ATTEMPTS} outcomes.
     * Tests assert that every emit site uses a value from this set;
     * dashboards / alerting rules should be cross-checked against it.
     */
    public static final java.util.Set<String> ALL_PROVISION_OUTCOMES = java.util.Set.of(
            OUTCOME_SUCCESS,
            OUTCOME_RATE_LIMITED,
            OUTCOME_UNCLASSIFIED_THROTTLE,
            OUTCOME_CONFIG_ERROR,
            OUTCOME_CAP_REACHED_UNDER_LOCK,
            OUTCOME_FAILOVER_INCOMPATIBLE,
            OUTCOME_DC_UNAVAILABLE,
            OUTCOME_DC_ATTRIBUTABLE,
            OUTCOME_FAILURE,
            OUTCOME_BOOTSTRAP_IO,
            OUTCOME_BOOTSTRAP_OTHER
    );

    /**
     * Provisioning short-circuited before submitting a NodeCallable.
     * {@code reason} is one of: {@code cap_reached}, {@code rate_limited},
     * {@code jenkins_quieting}, {@code template_suppressed}.
     */
    public static final Counter PROVISION_SKIPPED = Counter.build()
            .name("hetzner_provision_skipped_total")
            .help("Provisioning attempts skipped at the HetznerCloud.provision() entry checks")
            .labelNames("cloud", "reason")
            .register();

    /**
     * Server bootstrap failed (boot timeout / connect failure / arch mismatch /
     * etc.) AND the leaked Hetzner VM was successfully destroyed. Reads as
     * "we recovered from a leak", not "we leaked". Distinct from
     * {@link #PROVISION_ATTEMPTS} -- captures only the leak-recovery path.
     */
    public static final Counter PROVISION_LEAKED_SERVERS = Counter.build()
            .name("hetzner_provision_leaked_servers_total")
            .help("Hetzner VMs destroyed by NodeCallable's leak-cleanup catch (leak recovered)")
            .labelNames("cloud", "template")
            .register();

    /**
     * Hard failure: bootstrap failed AND we couldn't destroy the leaked VM.
     * Manual cleanup required. Any non-zero rate should page.
     */
    public static final Counter PROVISION_LEAK_DESTROY_FAILURES = Counter.build()
            .name("hetzner_provision_leak_destroy_failures_total")
            .help("Leaked VMs that NodeCallable could not destroy (manual cleanup required)")
            .labelNames("cloud", "template")
            .register();

    // =====================================================================
    // DC failover
    // =====================================================================

    /** Breaker state encoded as ordinal: 0=CLOSED, 1=OPEN, 2=HALF_OPEN. */
    public static final Gauge DC_BREAKER_STATE = Gauge.build()
            .name("hetzner_dc_circuit_breaker_state")
            .help("DC breaker state ordinal: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
            .labelNames("location")
            .register();

    /** Current consecutive failure count for the DC breaker. */
    public static final Gauge DC_BREAKER_CONSECUTIVE_FAILURES = Gauge.build()
            .name("hetzner_dc_circuit_breaker_consecutive_failures")
            .help("Consecutive failures for the DC since last success")
            .labelNames("location")
            .register();

    public static final Counter DC_BREAKER_TRANSITIONS = Counter.build()
            .name("hetzner_dc_circuit_breaker_transitions_total")
            .help("DC circuit breaker state transitions")
            .labelNames("location", "from", "to")
            .register();

    public static final Counter DC_FAILOVER = Counter.build()
            .name("hetzner_dc_failover_total")
            .help("NodeCallable failover events (DC unavailable, retried in next DC)")
            .labelNames("from_dc", "to_dc")
            .register();

    // =====================================================================
    // Orphan / ghost cleanup
    // =====================================================================

    public static final Counter ORPHAN_REAPED = Counter.build()
            .name("hetzner_orphan_servers_reaped_total")
            .help("Hetzner-side orphan VMs (no Jenkins peer) destroyed by OrphanedNodesCleaner")
            .labelNames("cloud")
            .register();

    public static final Counter GHOST_REMOVED = Counter.build()
            .name("hetzner_ghost_nodes_removed_total")
            .help("Jenkins-side ghost nodes (no Hetzner peer) removed by OrphanedNodesCleaner")
            .labelNames("cloud")
            .register();

    public static final Counter ORPHAN_CLEANUP_ERRORS = Counter.build()
            .name("hetzner_orphan_cleanup_errors_total")
            .help("Errors raised during orphan/ghost cleanup")
            .labelNames("cloud", "kind")
            .register();

    /**
     * Authoritative set of {@code kind} label values on
     * {@link #ORPHAN_CLEANUP_ERRORS}.
     */
    public static final String ORPHAN_KIND_FETCH_SERVERS = "fetch_servers";
    public static final String ORPHAN_KIND_DESTROY_SERVER = "destroy_server";
    public static final String ORPHAN_KIND_DESTROY_FAILED = "destroy_failed";
    public static final String ORPHAN_KIND_REMOVE_NODE = "remove_node";
    public static final String ORPHAN_KIND_RATE_LIMITED = "rate_limited";
    public static final String ORPHAN_KIND_UNEXPECTED = "unexpected";

    public static final java.util.Set<String> ALL_ORPHAN_CLEANUP_KINDS = java.util.Set.of(
            ORPHAN_KIND_FETCH_SERVERS,
            ORPHAN_KIND_DESTROY_SERVER,
            ORPHAN_KIND_DESTROY_FAILED,
            ORPHAN_KIND_REMOVE_NODE,
            ORPHAN_KIND_RATE_LIMITED,
            ORPHAN_KIND_UNEXPECTED
    );

    /**
     * Wall time of one OrphanedNodesCleaner pass per cloud. Buckets cover the
     * happy-path (sub-second) through degraded-API (tens of seconds).
     */
    public static final Histogram ORPHAN_CLEANUP_DURATION = Histogram.build()
            .name("hetzner_orphan_cleanup_duration_seconds")
            .help("Wall time of one OrphanedNodesCleaner pass per cloud")
            .labelNames("cloud")
            // 0.5s floor: a single Hetzner API roundtrip is ~0.2-0.5s, so
            // anything faster than 0.5s is noise. 2s rung catches typical
            // healthy passes; 5/10/30s catch degraded API conditions.
            .buckets(0.5, 1, 2, 5, 10, 30, 60, 120)
            .register();

    // =====================================================================
    // Template suppression
    // =====================================================================

    public static final Counter TEMPLATE_SUPPRESSED_TOTAL = Counter.build()
            .name("hetzner_template_suppressed_total")
            .help("Template entered suppression after recurring config errors")
            .labelNames("template")
            .register();

    /**
     * Gauge: 1 if template currently suppressed, 0 otherwise.
     *
     * Note the {@code _active} suffix: simpleclient's Counter class strips
     * the {@code _total} suffix from {@link #TEMPLATE_SUPPRESSED_TOTAL}
     * when it registers the metric family, leaving family name
     * {@code hetzner_template_suppressed}. A Gauge with the same family
     * name fails to register. The {@code _active} suffix avoids the clash
     * and reads naturally on dashboards.
     */
    public static final Gauge TEMPLATE_SUPPRESSED_ACTIVE = Gauge.build()
            .name("hetzner_template_suppressed_active")
            .help("1 if template currently suppressed, 0 otherwise")
            .labelNames("template")
            .register();

    /** Total errors per template -- monotonic; resets via {@code recordSuccess}. */
    public static final Counter TEMPLATE_ERRORS = Counter.build()
            .name("hetzner_template_errors_total")
            .help("Config errors recorded per template (every recordError call)")
            .labelNames("template")
            .register();

    /** Current consecutive error count per template (within the suppression window). */
    public static final Gauge TEMPLATE_CONSECUTIVE_ERRORS = Gauge.build()
            .name("hetzner_template_consecutive_errors")
            .help("Consecutive config errors per template (resets on success)")
            .labelNames("template")
            .register();

    // =====================================================================
    // Architecture validation
    // =====================================================================

    public static final Counter ARCH_VALIDATION_FAILURES = Counter.build()
            .name("hetzner_arch_validation_failures_total")
            .help("Architecture mismatch detections (Hetzner provisioned wrong arch)")
            .labelNames("phase", "requested", "actual")
            .register();

    // =====================================================================
    // API rate limit (per credentialsId)
    // =====================================================================

    /**
     * HTTP requests issued to the Hetzner API. {@code status_class} is one
     * of: {@code 2xx}, {@code 3xx}, {@code 4xx}, {@code 5xx},
     * {@code network_error}.
     */
    public static final Counter API_REQUESTS = Counter.build()
            .name("hetzner_api_requests_total")
            .help("HTTP requests to the Hetzner API by status class")
            .labelNames("credentials_id", "method", "status_class")
            .register();

    /**
     * HTTP request wall time. Buckets target the operational range of
     * Hetzner API latency: 50ms healthy &rarr; 30s degraded.
     */
    public static final Histogram API_REQUEST_DURATION = Histogram.build()
            .name("hetzner_api_request_duration_seconds")
            .help("Hetzner API HTTP request duration")
            .labelNames("credentials_id", "method")
            .buckets(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30)
            .register();

    /**
     * Retry attempts triggered by the {@code RetryInterceptor}. Fired BEFORE
     * the retry sleep, so a single request that ultimately succeeds after 2
     * retries adds 2 to this counter. {@code reason} is one of:
     * {@code http_502}, {@code http_504}, {@code timeout}.
     */
    public static final Counter API_RETRIES = Counter.build()
            .name("hetzner_api_retries_total")
            .help("Hetzner API retry attempts (incremented before each retry sleep)")
            .labelNames("credentials_id", "reason")
            .register();

    /**
     * Retries exhausted: request failed after MAX_RETRIES attempts.
     * Distinct from {@link #API_RETRIES} which counts every attempt;
     * {@code _exhausted_total} fires at most once per failed request.
     */
    public static final Counter API_RETRIES_EXHAUSTED = Counter.build()
            .name("hetzner_api_retries_exhausted_total")
            .help("Hetzner API retry budget exhausted (request gave up)")
            .labelNames("credentials_id", "reason")
            .register();

    /**
     * HTTP 401 fired on a Hetzner request, prompting client invalidation
     * (token rotation detection). Any non-zero rate suggests a Jenkins
     * credential rotation regression.
     */
    public static final Counter API_TOKEN_INVALIDATED = Counter.build()
            .name("hetzner_api_token_invalidated_total")
            .help("HetznerApiClient invalidated due to HTTP 401 (token rotation detection)")
            .labelNames("credentials_id")
            .register();

    public static final Gauge API_RATE_LIMIT_REMAINING = Gauge.build()
            .name("hetzner_api_rate_limit_remaining")
            .help("Remaining Hetzner API quota for the credentialsId")
            .labelNames("credentials_id")
            .register();

    public static final Gauge API_RATE_LIMIT_LIMIT = Gauge.build()
            .name("hetzner_api_rate_limit_limit")
            .help("Hetzner API quota limit (per token, per hour) for the credentialsId")
            .labelNames("credentials_id")
            .register();

    public static final Gauge API_RATE_LIMITED = Gauge.build()
            .name("hetzner_api_rate_limited")
            .help("1 if Hetzner API token is currently blocked, 0 otherwise")
            .labelNames("credentials_id")
            .register();

    // =====================================================================
    // Static info / runtime metadata
    // =====================================================================

    /**
     * Fork identity. Value=1 with the plugin version + Jenkins baseline as
     * labels. Standard Prometheus {@code _info} pattern -- query via
     * {@code group_left} to enrich any other metric with the version.
     */
    public static final Gauge PLUGIN_INFO = Gauge.build()
            .name("hetzner_plugin_info")
            .help("Hetzner Cloud plugin identity: 1 if plugin loaded; labels carry version metadata")
            .labelNames("plugin_version", "jenkins_baseline", "fork")
            .register();

    /**
     * Per-cloud identity. Set once per cloud at {@code readResolve()} time.
     * Use {@code count(hetzner_cloud_info)} to count configured Hetzner clouds
     * regardless of whether they've provisioned anything.
     */
    public static final Gauge CLOUD_INFO = Gauge.build()
            .name("hetzner_cloud_info")
            .help("Per-cloud configuration metadata (1 = cloud configured)")
            .labelNames("cloud", "credentials_id")
            .register();

    /**
     * Per-template identity. Set once per template at {@code readResolve()}
     * time. Use to enrich data-plane metrics with template config:
     * {@code hetzner_provision_attempts_total * on(template) group_left(image, location)
     * hetzner_template_info}.
     */
    public static final Gauge TEMPLATE_INFO = Gauge.build()
            .name("hetzner_template_info")
            .help("Per-template configuration metadata (1 = template configured)")
            .labelNames("cloud", "template", "image", "server_type", "location")
            .register();

    /**
     * Jenkins master runtime metadata: host OS / arch / JVM / EC2 instance
     * type. Static, set once at class init. Use to correlate plugin behavior
     * with master-host topology (e.g., does breaker open more often on
     * arm64 masters than x86_64?). EC2 instance type comes from the
     * {@code EC2_INSTANCE_TYPE} env var or {@code ec2.instance.type} JVM
     * property; populate via systemd unit file when running on EC2.
     */
    public static final Gauge RUNTIME_INFO = Gauge.build()
            .name("hetzner_runtime_info")
            .help("Jenkins master runtime metadata (1 = master loaded)")
            .labelNames("os_name", "os_arch", "os_version", "java_version", "ec2_instance_type")
            .register();

    /** Configured executors per template (numeric, separate from TEMPLATE_INFO). */
    public static final Gauge TEMPLATE_EXECUTORS = Gauge.build()
            .name("hetzner_template_executors")
            .help("Configured numExecutors per template")
            .labelNames("cloud", "template")
            .register();

    /** Number of templates configured per cloud. */
    public static final Gauge CLOUD_TEMPLATE_COUNT = Gauge.build()
            .name("hetzner_cloud_template_count")
            .help("Number of server templates configured under this cloud")
            .labelNames("cloud")
            .register();

    // =====================================================================
    // Static initialization: emit version info once at class load.
    // =====================================================================

    static {
        String version = HetznerMetricProvider.class.getPackage().getImplementationVersion();
        if (version == null || version.isEmpty()) {
            version = "snapshot";
        }
        PLUGIN_INFO.labels(version, "2.479", "percona").set(1);

        String osName = sanitize(System.getProperty("os.name", "unknown"));
        String osArch = sanitize(System.getProperty("os.arch", "unknown"));
        String osVersion = sanitize(System.getProperty("os.version", "unknown"));
        String javaVersion = sanitize(System.getProperty("java.version", "unknown"));
        String ec2 = System.getenv("EC2_INSTANCE_TYPE");
        if (ec2 == null || ec2.isEmpty()) {
            ec2 = System.getProperty("ec2.instance.type", "unknown");
        }
        RUNTIME_INFO.labels(osName, osArch, osVersion, javaVersion, sanitize(ec2)).set(1);
    }

    /**
     * Normalize a label value: lowercase, replace whitespace with
     * underscores, cap to 64 chars. Defends against unbounded cardinality
     * if some upstream returns long version strings or build-stamps in
     * {@code System.getProperty}.
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String s = raw.trim().toLowerCase().replaceAll("\\s+", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    // =====================================================================
    // Test helpers
    // =====================================================================

    /**
     * Reset every counter / gauge / histogram registered by this class.
     * Tests call this in {@code @BeforeEach} to isolate observations across
     * cases that share the JVM-wide default registry.
     */
    public static void resetForTest() {
        PROVISIONING_PENDING.clear();
        RUNNING_SERVERS.clear();
        INSTANCE_CAP.clear();
        PROVISION_UNDERFLOW.clear();
        PROVISION_UNCAUGHT.clear();
        PROVISION_DURATION.clear();
        PROVISION_ATTEMPTS.clear();
        PROVISION_SKIPPED.clear();
        PROVISION_LEAKED_SERVERS.clear();
        PROVISION_LEAK_DESTROY_FAILURES.clear();
        DC_BREAKER_STATE.clear();
        DC_BREAKER_CONSECUTIVE_FAILURES.clear();
        DC_BREAKER_TRANSITIONS.clear();
        DC_FAILOVER.clear();
        ORPHAN_REAPED.clear();
        GHOST_REMOVED.clear();
        ORPHAN_CLEANUP_ERRORS.clear();
        ORPHAN_CLEANUP_DURATION.clear();
        TEMPLATE_SUPPRESSED_TOTAL.clear();
        TEMPLATE_SUPPRESSED_ACTIVE.clear();
        TEMPLATE_ERRORS.clear();
        TEMPLATE_CONSECUTIVE_ERRORS.clear();
        ARCH_VALIDATION_FAILURES.clear();
        API_RATE_LIMIT_REMAINING.clear();
        API_RATE_LIMIT_LIMIT.clear();
        API_RATE_LIMITED.clear();
        API_REQUESTS.clear();
        API_REQUEST_DURATION.clear();
        API_RETRIES.clear();
        API_RETRIES_EXHAUSTED.clear();
        API_TOKEN_INVALIDATED.clear();
        // Note: PLUGIN_INFO is intentionally not cleared here -- it is set
        // once at class init from the JAR manifest, not driven by hooks.
        CLOUD_INFO.clear();
        TEMPLATE_INFO.clear();
        TEMPLATE_EXECUTORS.clear();
        CLOUD_TEMPLATE_COUNT.clear();
    }
}
