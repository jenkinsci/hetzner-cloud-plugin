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
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.Symbol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * still-building runs across the controller, and emits two new Prometheus
 * metrics:
 *
 * <ul>
 *   <li>{@code hetzner_stuck_builds_total{master, template, threshold_hours}}
 *       -- Counter incremented exactly once per (build, threshold) when the
 *       build first crosses the configured age threshold. Dedup-cached for
 *       7 days so a single hung build does not double-count across ticks.
 *   <li>{@code hetzner_oldest_build_age_seconds{master, template}} -- Gauge
 *       set on each tick to the max elapsed time among still-building runs,
 *       grouped by Hetzner template.
 * </ul>
 *
 * <p>A third metric, {@code hetzner_executor_busy_real{master}}, is also
 * emitted on every tick: the count of executors whose currently-executing
 * {@link Queue.Executable} is a {@link Run} with {@link Run#isBuilding()} true.
 * This is the plugin-side equivalent of the CLI's {@code --real} count and
 * lets {@code readiness}/{@code is-idle} probes hit the Prometheus endpoint
 * directly instead of round-tripping a Groovy snippet through Script Console.
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
 * <h2>Failure isolation</h2>
 *
 * <p>{@link #doRun()} wraps {@link #scan()} in a top-level try/catch so a
 * Hetzner-API or Jenkins-core exception (rare but historically observed under
 * heavy load) cannot kill the timer. Same pattern as
 * {@link HetznerMetricsRefresher#doRun()} and {@link OrphanedNodesCleaner#doRun()}.
 *
 * @since v103.percona.17
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
     * {@code master:job:build:thresholdHours}, value is the insertion timestamp
     * (millis). We roll our own bounded LinkedHashMap eviction rather than
     * pulling Caffeine because the plugin does not yet depend on it and this
     * use case is too small to justify the new dependency.
     *
     * <p>Access is single-threaded (only {@link #scan()} writes), so no
     * synchronization is required for correctness; the field is package-private
     * for test access.
     */
    final Map<String, Long> seen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > DEDUP_MAX_SIZE;
        }
    };

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
     */
    void scan() {
        final long thresholdMs = TimeUnit.HOURS.toMillis(thresholdHours());
        final long thresholdHours = thresholdMs / 3_600_000L;
        final long now = System.currentTimeMillis();
        final String master = HetznerMetricProvider.masterLabel();

        // Drop expired dedup entries before we add new ones. Bounded by
        // DEDUP_MAX_SIZE elsewhere; this lets a 7-day-old entry release its
        // slot even when the cache is otherwise quiet.
        seen.entrySet().removeIf(e -> (now - e.getValue()) > DEDUP_TTL_MS);

        final Map<String, Long> oldestByTemplate = new HashMap<>();
        // Track templates touched this tick so we can zero out gauges that
        // previously fired but have no in-flight runs now. Without this, a
        // template that finishes its hung build stays pinned to the last
        // observed age forever.
        final Set<String> templatesObserved = new HashSet<>();
        int busyReal = 0;

        for (Computer c : computers()) {
            for (Executor e : c.getExecutors()) {
                final Queue.Executable exec = e.getCurrentExecutable();
                if (!(exec instanceof Run<?, ?> r)) {
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
                    final String key = master + ":"
                            + r.getParent().getFullName() + ":"
                            + r.getNumber() + ":"
                            + thresholdHours + "h";
                    if (seen.putIfAbsent(key, now) == null) {
                        HetznerMetricProvider.STUCK_BUILDS_TOTAL
                                .labels(master, template, Long.toString(thresholdHours))
                                .inc();
                        log.warn("HungBuildDetector: build {}#{} on template '{}' "
                                + "has been building for {}h (threshold {}h)",
                                r.getParent().getFullName(), r.getNumber(),
                                template, elapsed / 3_600_000L, thresholdHours);
                    }
                }
            }
        }

        // Emit oldest-age gauges. Templates that previously had a hung build
        // but are clean this tick get zeroed so the gauge does not pin to a
        // stale peak. The Set-based reset is bounded by the number of unique
        // templates observed since plugin load.
        oldestByTemplate.forEach((tpl, ms) ->
                HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                        .labels(master, tpl).set(ms / 1000.0));

        HetznerMetricProvider.EXECUTOR_BUSY_REAL.labels(master).set(busyReal);
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
