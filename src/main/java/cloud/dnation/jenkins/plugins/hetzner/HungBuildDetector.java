/*
 *     Copyright 2026 Percona, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.PeriodicWork;
import hudson.model.Queue;
import hudson.model.Run;
import io.prometheus.client.Collector;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.Symbol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Detect long-running ("hung") Jenkins builds where {@code Run.isBuilding()}
 * still returns {@code true} long past any plausible build duration.
 *
 * <p>Background: the {@code jenkins admin <inst> executors --real} CLI (shipped
 * today, commit {@code c382a76}) trusted {@code Run.isBuilding()} to filter out
 * zombie executors. Production traffic on rel.cd and pmm.cd revealed that some
 * runs report {@code isBuilding=true} for multiple days while doing no actual
 * work; the idle-master upgrade cron stalled for 6+ hours waiting for those
 * runs to "finish".
 *
 * <p>This {@link PeriodicWork} ticks every 10 minutes by default, iterates
 * still-building runs across the controller, and emits three Prometheus
 * metrics:
 *
 * <ul>
 *   <li>{@code hetzner_stuck_builds_total{template, threshold_hours}}
 *       -- Counter incremented exactly once per (build, threshold) when the
 *       build first crosses the configured age threshold. Dedup-cached for
 *       7 days so a single hung build does not double-count across ticks.
 *   <li>{@code hetzner_oldest_build_age_seconds{template}} -- Gauge set on
 *       each tick to the max elapsed time among still-building runs, grouped
 *       by Hetzner template. Stale {@code template} children are removed at
 *       the end of every tick (see {@code clearStaleAgeChildren}) so the
 *       gauge does not pin to the last-known peak after a hung build finishes.
 *   <li>{@code hetzner_jenkins_real_busy_executors} -- Gauge of executors
 *       whose currently-executing {@link Queue.Executable} is a {@link Run}
 *       with {@link Run#isBuilding()} true. Plugin-side equivalent of the
 *       CLI's {@code --real} count; renamed in v18 from
 *       {@code hetzner_executor_busy_real}.
 * </ul>
 *
 * <p>v18 also dropped the in-plugin {@code master} label from all three
 * metrics: master-side Grafana Alloy injects {@code master="<inst>.cd"} via
 * relabel config on the push pipeline (ADR 0013), so the plugin-side value
 * was redundant; the prior empty-string fallback caused dashboards filtering
 * {@code master=~".+\\.cd"} to silently drop series.
 *
 * <p>The detector is independent of {@link HetznerCloud}: it scans the whole
 * Jenkins controller, not just Hetzner-provisioned agents. The {@code template}
 * label is best-effort: when the run executes on a {@link HetznerServerAgent},
 * the template name is used; otherwise the label is {@code "unknown"} so that
 * non-Hetzner work (controller built-ins, EC2 agents, etc.) is observable but
 * does not pollute per-template panels (which filter {@code template!="unknown"}).
 *
 * <h2>System property knobs</h2>
 *
 * <ul>
 *   <li>{@code hetzner.hung-build.threshold-hours} (default {@code 24}) --
 *       a build older than this is considered hung. Honored fresh on each
 *       tick; a runtime tweak does not require restart.
 *   <li>{@code hetzner.hung-build.poll-period-minutes} (default {@code 10}) --
 *       PeriodicWork recurrence period. Read by the Jenkins scheduler on each
 *       reschedule, so changes apply at the next tick boundary.
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>{@link #scan()} is fully {@code synchronized(this)}. The PeriodicWork
 * scheduler invokes {@code doRun} sequentially in production, but Jenkins
 * does not contractually forbid an overlap if a single tick runs longer than
 * the recurrence period (e.g. an executor walk that hangs on a stuck node
 * lookup). Two overlapping ticks racing on the {@code seen} dedup map could
 * double-count {@link HetznerMetricProvider#STUCK_BUILDS_TOTAL}; the
 * synchronized block keeps that contract honest.
 *
 * <h2>Failure isolation</h2>
 *
 * <p>{@link #doRun()} wraps {@link #scan()} in a top-level try/catch so a
 * Hetzner-API or Jenkins-core exception (rare but historically observed under
 * heavy load) cannot kill the timer. Same pattern as
 * {@link HetznerMetricsRefresher#doRun()} and {@link OrphanedNodesCleaner#doRun()}.
 *
 * @since v103.percona.17 (v18: synchronized scan, stale gauge cleanup,
 *        dropped master label, renamed executor_busy_real; v19: extract Run
 *        from Pipeline PlaceholderExecutable via reflection so the detector
 *        sees pipeline node-step executions, not just freestyle builds.
 *        Pre-v19, all three metrics were silently empty for Pipeline jobs;
 *        Percona's fleet is 99% Pipeline, so v17/v18 metrics were effectively
 *        non-functional in production)
 */
@Extension
@Symbol("HungBuildDetector")
@Slf4j
public class HungBuildDetector extends PeriodicWork {

    /** Default age threshold for classifying a build as hung. */
    static final long DEFAULT_THRESHOLD_HOURS = 24L;

    /** Default poll period. 10 minutes balances signal latency against scan cost. */
    static final long DEFAULT_PERIOD_MINUTES = 10L;

    /** TTL for the per-build dedup cache. 7 days covers the longest plausible
     *  stuck-build lifetime before manual intervention. */
    static final long DEDUP_TTL_MS = TimeUnit.DAYS.toMillis(7);

    /** Cap on the dedup map. Hard-bounds memory if a misconfigured threshold
     *  causes every build to register simultaneously. */
    static final int DEDUP_MAX_SIZE = 10_000;

    /** Sentinel template label for non-Hetzner agents and unattached runs. */
    static final String UNKNOWN_TEMPLATE = "unknown";

    /**
     * Dedup cache for {@link HetznerMetricProvider#STUCK_BUILDS_TOTAL}: key is
     * {@code job:build:thresholdHours}, value is the insertion timestamp
     * (millis). We roll our own bounded LinkedHashMap eviction rather than
     * pulling Caffeine because the plugin does not yet depend on it and this
     * use case is too small to justify the new dependency.
     *
     * <p>All access is guarded by {@code synchronized(this)} inside
     * {@link #scan()}; the field is package-private for test access.
     */
    final Map<String, Long> seen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > DEDUP_MAX_SIZE;
        }
    };

    /**
     * Clock indirection so unit tests can advance time past
     * {@link #DEDUP_TTL_MS} without sleeping. Production uses
     * {@link System#currentTimeMillis}; tests can swap in a fake.
     */
    LongSupplier clock = System::currentTimeMillis;

    @Override
    public long getRecurrencePeriod() {
        long minutes = Long.getLong(
                "hetzner.hung-build.poll-period-minutes", DEFAULT_PERIOD_MINUTES);
        return TimeUnit.MINUTES.toMillis(Math.max(1L, minutes));
    }

    @Override
    protected void doRun() {
        try {
            scan();
        } catch (Throwable t) {
            // Catch-all (including Errors propagated from misbehaving plugins)
            // to keep the PeriodicWork timer alive across unexpected failures.
            // Same defensive pattern as HetznerMetricsRefresher and
            // OrphanedNodesCleaner.
            log.error("HungBuildDetector tick failed (timer survives)", t);
        }
    }

    /**
     * Scan all in-flight runs, emit metrics, dedupe stuck-build counter.
     * Package-private so unit tests can drive it directly without depending on
     * the PeriodicWork scheduler.
     *
     * <p>{@code synchronized(this)} so overlapping ticks (allowed by Jenkins
     * PeriodicWork in degenerate cases) cannot race the {@code seen} dedup
     * map and double-count {@link HetznerMetricProvider#STUCK_BUILDS_TOTAL}.
     */
    synchronized void scan() {
        final long thresholdMs = TimeUnit.HOURS.toMillis(thresholdHours());
        final long thresholdHours = thresholdMs / 3_600_000L;
        final long now = clock.getAsLong();

        // Drop expired dedup entries before we add new ones. Bounded by
        // DEDUP_MAX_SIZE elsewhere; this lets a 7-day-old entry release its
        // slot even when the cache is otherwise quiet.
        seen.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_TTL_MS);

        final Map<String, Long> oldestByTemplate = new HashMap<>();
        // Track templates touched this tick so we can clear gauge children
        // for templates that previously had a hung build but do not this
        // tick. Without this, a template whose hung build finishes leaves the
        // gauge pinned at the last observed age forever.
        final Set<String> templatesObserved = new HashSet<>();
        int busyReal = 0;

        for (Computer c : computers()) {
            for (Executor e : c.getExecutors()) {
                final Queue.Executable exec = e.getCurrentExecutable();
                final Run<?, ?> r = extractRun(exec);
                if (r == null) {
                    continue;
                }
                // Run.isBuilding() is the same signal the CLI's --real flag
                // trusts. Phase 1 layers an age threshold ON TOP of it; the
                // counter increments only when both (a) isBuilding=true AND
                // (b) elapsed > threshold.
                if (!r.isBuilding()) {
                    continue;
                }
                busyReal++;

                final long started = r.getStartTimeInMillis();
                final long elapsed = started > 0 ? (now - started) : 0L;
                final String template = templateOf(c);
                templatesObserved.add(template);
                oldestByTemplate.merge(template, elapsed, Math::max);

                if (elapsed > thresholdMs) {
                    final String key = r.getParent().getFullName() + ":"
                            + r.getNumber() + ":"
                            + thresholdHours + "h";
                    if (seen.putIfAbsent(key, now) == null) {
                        HetznerMetricProvider.STUCK_BUILDS_TOTAL
                                .labels(template, Long.toString(thresholdHours))
                                .inc();
                        log.warn("HungBuildDetector: build {}#{} on template '{}' "
                                + "has been building for {}h (threshold {}h)",
                                r.getParent().getFullName(), r.getNumber(),
                                template, elapsed / 3_600_000L, thresholdHours);
                    }
                }
            }
        }

        // Emit oldest-age gauges for templates with in-flight builds this tick.
        oldestByTemplate.forEach((tpl, ms) ->
                HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                        .labels(tpl).set(ms / 1000.0));

        // Clear stale children: any template present in the gauge family but
        // not observed this tick. Prevents the gauge from pinning to the
        // last-known peak after a hung build finishes or moves templates.
        clearStaleAgeChildren(templatesObserved);

        HetznerMetricProvider.JENKINS_REAL_BUSY_EXECUTORS.set(busyReal);
    }

    /**
     * Remove gauge children for templates not observed in the current tick.
     *
     * <p>Iterates {@link HetznerMetricProvider#OLDEST_BUILD_AGE_SECONDS}'s
     * existing samples via the simpleclient {@code collect()} API (the only
     * public way to enumerate label combinations from outside the
     * {@code io.prometheus.client} package), then calls {@code remove()} on
     * each child whose {@code template} label is not in
     * {@code templatesObserved}.
     */
    private static void clearStaleAgeChildren(Set<String> templatesObserved) {
        List<Collector.MetricFamilySamples> families =
                HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.collect();
        if (families.isEmpty()) {
            return;
        }
        for (Collector.MetricFamilySamples family : families) {
            for (Collector.MetricFamilySamples.Sample sample : family.samples) {
                // Single label: "template". Defensive on index to keep this
                // robust if the label schema is ever extended.
                int idx = sample.labelNames.indexOf("template");
                if (idx < 0 || idx >= sample.labelValues.size()) {
                    continue;
                }
                String template = sample.labelValues.get(idx);
                if (!templatesObserved.contains(template)) {
                    HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.remove(template);
                }
            }
        }
    }

    /**
     * Resolve the {@link Run} that an executor is currently working on.
     *
     * <p>Two shapes need to be handled because virtually all Percona builds are
     * Pipeline jobs (not freestyle):
     *
     * <ul>
     *   <li>Freestyle / Matrix / Maven jobs: {@code currentExecutable} is the
     *       {@link Run} directly. Pre-v19 logic.
     *   <li>Pipeline {@code node {}} steps: {@code currentExecutable} is a
     *       {@code ExecutorStepExecution.PlaceholderTask.PlaceholderExecutable}
     *       whose {@code getParentExecutable()} returns the building
     *       {@code WorkflowRun}. v18 missed this case entirely, leaving
     *       {@code hetzner_jenkins_real_busy_executors} pinned at 0 fleet-wide
     *       and {@code hetzner_oldest_build_age_seconds} /
     *       {@code hetzner_stuck_builds_total} non-functional for the
     *       pipeline-hung-build use case the metrics were built for.
     * </ul>
     *
     * <p>Uses {@link Queue.Executable#getParentExecutable()} directly: it
     * is part of Jenkins core API (since 2.313), {@code PlaceholderExecutable}
     * overrides it to return the owning Run, and other shapes inherit the
     * default {@code null} return. Mirrors the CLI's {@code GROOVY_INCIDENTS}
     * pattern in {@code rust/jenkins/src/client.rs}.
     *
     * @return the building Run, or {@code null} if the executable cannot be
     *         resolved to one.
     */
    static Run<?, ?> extractRun(Queue.Executable exec) {
        if (exec == null) {
            return null;
        }
        if (exec instanceof Run<?, ?> r) {
            return r;
        }
        try {
            Queue.Executable parent = exec.getParentExecutable();
            if (parent instanceof Run<?, ?> pr) {
                return pr;
            }
        } catch (Throwable ignored) {
            // Defensive: a misbehaving Queue.Executable implementation must
            // never break the metrics tick. Any failure is treated as
            // "cannot resolve to a Run".
        }
        return null;
    }

    /** Honor the system-property threshold override, with a sane floor. */
    private static long thresholdHours() {
        long hours = Long.getLong(
                "hetzner.hung-build.threshold-hours", DEFAULT_THRESHOLD_HOURS);
        return Math.max(1L, hours);
    }

    /**
     * Resolve the template name attached to the agent currently running the
     * build. Falls back to {@link #UNKNOWN_TEMPLATE} for non-Hetzner agents
     * (built-in controller, EC2, manually-added nodes) and when the node has
     * been deserialized after a restart (template field is transient).
     */
    private static String templateOf(Computer computer) {
        try {
            Node node = computer.getNode();
            if (node instanceof HetznerServerAgent agent) {
                HetznerServerTemplate template = agent.getTemplate();
                if (template != null && template.getName() != null
                        && !template.getName().isEmpty()) {
                    return template.getName();
                }
            }
        } catch (Throwable ignored) {
            // Defensive: never fail a metrics tick on a node-lookup edge case.
        }
        return UNKNOWN_TEMPLATE;
    }

    /**
     * Enumerate controller computers. Split out for testability; the production
     * path delegates to {@link Jenkins#getComputers()}.
     */
    private static Computer[] computers() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return new Computer[0];
        }
        Computer[] all = j.getComputers();
        return all != null ? all : new Computer[0];
    }
}
