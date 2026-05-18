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
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import io.prometheus.client.Collector;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Verifies the v103.percona.17/18 {@link HungBuildDetector}: the cron-driven
 * idle-master upgrade campaign stalled on 2026-05-15/16 because
 * {@code Run.isBuilding()} was lying for builds hung up to 6.4 days. The
 * detector adds an age threshold on top of {@code isBuilding()} and emits
 * Prometheus metrics so Mimir alerts fire well before such builds accrete
 * into a fleet-wide outage.
 *
 * <p>Each test owns its own {@code MockedStatic<Jenkins>} so the static
 * {@code Jenkins.getInstanceOrNull()} call inside {@code HungBuildDetector}
 * returns a deterministic mock. The metrics registry is reset in
 * {@code @BeforeEach} to isolate observations.
 *
 * <p>v18 changes verified here:
 * <ul>
 *   <li>{@code scan()} is synchronized: two concurrent ticks do not
 *       double-count {@code STUCK_BUILDS_TOTAL}.</li>
 *   <li>Stale {@code OLDEST_BUILD_AGE_SECONDS} children are removed when the
 *       template stops showing hung builds.</li>
 *   <li>TTL expiry re-arms the dedup cache.</li>
 *   <li>Gauges no longer carry a {@code master} label (Alloy adds it via
 *       relabel config downstream).</li>
 *   <li>{@code hetzner_executor_busy_real} renamed to
 *       {@code hetzner_jenkins_real_busy_executors}.</li>
 * </ul>
 */
class HungBuildDetectorTest {

    private static final String THRESHOLD_PROP = "hetzner.hung-build.threshold-hours";
    private static final String PERIOD_PROP = "hetzner.hung-build.poll-period-minutes";

    private MockedStatic<Jenkins> jenkinsMock;
    private Jenkins jenkins;

    @BeforeEach
    void setUp() {
        HetznerMetricProvider.STUCK_BUILDS_TOTAL.clear();
        HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.clear();
        HetznerMetricProvider.JENKINS_REAL_BUSY_EXECUTORS.clear();

        jenkinsMock = mockStatic(Jenkins.class);
        jenkins = mock(Jenkins.class);
        jenkinsMock.when(Jenkins::getInstanceOrNull).thenReturn(jenkins);
        jenkinsMock.when(Jenkins::get).thenReturn(jenkins);
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
        System.clearProperty(THRESHOLD_PROP);
        System.clearProperty(PERIOD_PROP);
    }

    /**
     * The whole point of the dedup cache: a build that has been hung for 25h
     * still appears in every detector tick, but the counter must increment
     * exactly once. Without dedup the counter would over-count by the number
     * of ticks since the build crossed the threshold and dashboards would
     * report a false hung-build surge that disappears the moment the build
     * finishes.
     */
    @Test
    void counterIncrementsOnceAcrossThreeTicks() {
        long now = System.currentTimeMillis();
        Run<?, ?> run = mockBuildingRun("hung-job", 42, now - TimeUnit.HOURS.toMillis(25));
        Computer computer = mockComputerWithRun(run);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        HungBuildDetector detector = new HungBuildDetector();
        detector.scan();
        detector.scan();
        detector.scan();

        double count = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(1.0, count, 0.0001,
                "Stuck counter must increment exactly once per build, not once per tick");
    }

    /**
     * The oldest-age gauge takes the max across concurrent builds on the same
     * template. Verified with three synthetic runs at 1h / 3h / 5h on the
     * same template; gauge value must equal 5h (in seconds).
     */
    @Test
    void oldestAgeGaugeTracksMax() {
        long now = System.currentTimeMillis();
        Run<?, ?> oneHour = mockBuildingRun("j1", 1, now - TimeUnit.HOURS.toMillis(1));
        Run<?, ?> threeHour = mockBuildingRun("j2", 2, now - TimeUnit.HOURS.toMillis(3));
        Run<?, ?> fiveHour = mockBuildingRun("j3", 3, now - TimeUnit.HOURS.toMillis(5));
        Computer c1 = mockComputerWithRun(oneHour);
        Computer c2 = mockComputerWithRun(threeHour);
        Computer c3 = mockComputerWithRun(fiveHour);
        when(jenkins.getComputers()).thenReturn(new Computer[]{c1, c2, c3});

        new HungBuildDetector().scan();

        double oldest = HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE).get();
        double expected = 5.0 * 3600.0;
        assertTrue(Math.abs(oldest - expected) <= 1.0,
                "OLDEST_BUILD_AGE_SECONDS must equal max elapsed (5h); got " + oldest);

        // jenkins_real_busy_executors counts all three real builds.
        assertEquals(3.0, HetznerMetricProvider.JENKINS_REAL_BUSY_EXECUTORS.get(),
                0.0001, "JENKINS_REAL_BUSY_EXECUTORS must count every isBuilding=true executor");
    }

    /**
     * A build just under the threshold (23h59m at a 24h cap) must NOT register
     * as hung. Off-by-one regressions here would flood the counter on every
     * long-but-legitimate build and burn the alert pager.
     */
    @Test
    void skipsBuildsUnderThreshold() {
        long now = System.currentTimeMillis();
        long elapsedMs = TimeUnit.HOURS.toMillis(23) + TimeUnit.MINUTES.toMillis(59);
        Run<?, ?> run = mockBuildingRun("not-hung", 7, now - elapsedMs);
        Computer computer = mockComputerWithRun(run);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        new HungBuildDetector().scan();

        double count = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(0.0, count, 0.0001,
                "Build under threshold must not increment stuck counter");

        // Oldest-age gauge still reflects the build (it's still "the oldest").
        double oldest = HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE).get();
        assertTrue(oldest > TimeUnit.HOURS.toSeconds(23),
                "Oldest-age gauge must still track sub-threshold builds; got " + oldest);
    }

    /**
     * A Jenkins-core exception (defensive sanity check; should not happen in
     * production) must NOT propagate out of {@code doRun()}: the PeriodicWork
     * timer would die and we'd lose all subsequent detection. Mirrors the
     * {@code HetznerMetricsRefresher.doRun_swallowsException} contract.
     */
    @Test
    void survivesUnexpectedException() {
        when(jenkins.getComputers()).thenThrow(
                new RuntimeException("simulated Jenkins-core failure"));

        HungBuildDetector detector = new HungBuildDetector();
        // Must NOT throw. If this assertion fires, the production timer
        // is at risk of dying on a transient Jenkins-core glitch.
        detector.doRun();

        // Scan failed before reaching the gauge update, so the gauge keeps
        // its initial value (0.0). The contract here is "timer survives",
        // not "gauge gets set".
        assertEquals(0.0, HetznerMetricProvider.JENKINS_REAL_BUSY_EXECUTORS.get(),
                0.0001, "Failed scan must not have set JENKINS_REAL_BUSY_EXECUTORS");
    }

    /**
     * Operator override via {@code hetzner.hung-build.threshold-hours=1}: a
     * 90-minute-old build must register as hung at the 1h boundary, and the
     * {@code threshold_hours} label must reflect the override so a runtime
     * threshold change does not collide with the default-24h series.
     */
    @Test
    void respectsSystemPropertyThresholdOverride() {
        System.setProperty(THRESHOLD_PROP, "1");
        long now = System.currentTimeMillis();
        Run<?, ?> run = mockBuildingRun("ninety-min", 90,
                now - TimeUnit.MINUTES.toMillis(90));
        Computer computer = mockComputerWithRun(run);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        new HungBuildDetector().scan();

        // Series keyed by the OVERRIDDEN threshold (1h), not the default 24h.
        double count = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "1").get();
        assertEquals(1.0, count, 0.0001,
                "1h-threshold override must reclassify the 90-min build as hung");

        // The default (24h) series must NOT have been touched.
        double defaultSeries = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(0.0, defaultSeries, 0.0001,
                "Default 24h series must not pick up the 1h-threshold hit");
    }

    // =======================================================================
    // v103.percona.18 codex review follow-ups
    // =======================================================================

    /**
     * v18 blocker 1: {@code scan()} is {@code synchronized(this)}. Two
     * concurrent threads calling {@code scan()} on the same detector instance
     * must serialize, so the stuck-build counter for the same hung build
     * still increments exactly once.
     *
     * <p>Without the lock, the {@code seen.putIfAbsent} check-then-write
     * sequence could race: both threads observe absent, both call
     * {@code .inc()}, and the counter doubles.
     *
     * <p>Implementation note: Mockito's {@code MockedStatic} is thread-local,
     * so the {@code Jenkins.getInstanceOrNull()} stub registered in
     * {@code @BeforeEach} on the test thread is not visible to worker threads.
     * Each worker re-establishes its own thread-local stub pointing at the
     * same shared {@code jenkins} mock; that mock IS shared so both workers
     * see the same hung-build executor.
     */
    @Test
    void concurrent_scan_does_not_double_count() throws InterruptedException {
        long now = System.currentTimeMillis();
        Run<?, ?> run = mockBuildingRun("hung-shared", 1, now - TimeUnit.HOURS.toMillis(25));
        Computer computer = mockComputerWithRun(run);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        HungBuildDetector detector = new HungBuildDetector();

        // Close the test-thread's mock-static so worker-thread mocks can
        // register their own. We reset it in setUp() for any subsequent
        // tests; @AfterEach handles the final close.
        jenkinsMock.close();
        try {
            final CountDownLatch start = new CountDownLatch(1);
            Runnable worker = () -> {
                try (MockedStatic<Jenkins> localMock = mockStatic(Jenkins.class)) {
                    localMock.when(Jenkins::getInstanceOrNull).thenReturn(jenkins);
                    localMock.when(Jenkins::get).thenReturn(jenkins);
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    detector.scan();
                }
            };
            Thread t1 = new Thread(worker, "scan-thread-1");
            Thread t2 = new Thread(worker, "scan-thread-2");
            t1.start();
            t2.start();
            start.countDown();
            t1.join(TimeUnit.SECONDS.toMillis(5));
            t2.join(TimeUnit.SECONDS.toMillis(5));

            double count = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                    .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
            assertEquals(1.0, count, 0.0001,
                    "Concurrent ticks must increment stuck counter exactly once, got " + count);
        } finally {
            // Re-establish the test-thread mock so @AfterEach's close() is
            // matched against a live MockedStatic and does not throw.
            jenkinsMock = mockStatic(Jenkins.class);
            jenkinsMock.when(Jenkins::getInstanceOrNull).thenReturn(jenkins);
            jenkinsMock.when(Jenkins::get).thenReturn(jenkins);
        }
    }

    /**
     * v18 blocker 2: stale gauge children get cleared. Tick 1 sees a hung
     * build on template X; tick 2 sees no hung builds. The
     * {@code OLDEST_BUILD_AGE_SECONDS} family must contain NO samples after
     * tick 2 -- the prior pinned value of "5h" would otherwise show forever
     * on the dashboard, scaring the operator into investigating a stuck
     * build that has already finished.
     */
    @Test
    void oldest_age_gauge_clears_when_build_finishes() {
        long now = System.currentTimeMillis();
        Run<?, ?> hung = mockBuildingRun("transient-hung", 5,
                now - TimeUnit.HOURS.toMillis(5));
        Computer computer = mockComputerWithRun(hung);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        HungBuildDetector detector = new HungBuildDetector();
        detector.scan();

        // Sanity: gauge populated.
        double populated = HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE).get();
        assertTrue(populated > 0.0,
                "Sanity check: gauge must be populated after first tick; got " + populated);

        // Tick 2: build finished, no executors are busy.
        when(jenkins.getComputers()).thenReturn(new Computer[0]);
        detector.scan();

        // After clearStaleAgeChildren, the "unknown" template child should be
        // gone entirely. Easiest assertion: enumerate samples on the family.
        List<Collector.MetricFamilySamples> families =
                HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.collect();
        boolean unknownPresent = families.stream()
                .flatMap(f -> f.samples.stream())
                .anyMatch(s -> {
                    int idx = s.labelNames.indexOf("template");
                    return idx >= 0 && HungBuildDetector.UNKNOWN_TEMPLATE
                            .equals(s.labelValues.get(idx));
                });
        assertEquals(false, unknownPresent,
                "Stale 'unknown' template child must be removed when no hung "
                        + "builds are observed; otherwise the gauge pins forever");
    }

    /**
     * Variant of the stale-cleanup test: a hung build moves from template X
     * to template Y (e.g. the operator reconfigures the template label). The
     * gauge family must not retain template X.
     */
    @Test
    void oldest_age_gauge_clears_when_template_no_longer_observed() {
        long now = System.currentTimeMillis();
        // Tick 1: hung build, mockComputerWithRun -> template "unknown".
        Run<?, ?> run1 = mockBuildingRun("moved-job", 1, now - TimeUnit.HOURS.toMillis(3));
        Computer computer = mockComputerWithRun(run1);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        HungBuildDetector detector = new HungBuildDetector();
        detector.scan();
        // Manually inject a stale child for a synthetic template "old-tpl"
        // simulating the case where the build previously ran on a different
        // template before the operator renamed it.
        HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.labels("old-tpl").set(7200);

        // Tick 2: still hung on "unknown". The "old-tpl" child should be
        // cleared since it's not in templatesObserved this tick.
        detector.scan();

        List<Collector.MetricFamilySamples> families =
                HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.collect();
        boolean oldTplPresent = families.stream()
                .flatMap(f -> f.samples.stream())
                .anyMatch(s -> {
                    int idx = s.labelNames.indexOf("template");
                    return idx >= 0 && "old-tpl".equals(s.labelValues.get(idx));
                });
        assertEquals(false, oldTplPresent,
                "Stale template child (no longer observed) must be removed");

        // The current template's child must still be present.
        double unknown = HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE).get();
        assertTrue(unknown > 0.0,
                "Current template's gauge child must remain after stale cleanup");
    }

    /**
     * v18 nice-to-have: TTL expiry re-arms the dedup counter. Inject a fake
     * clock, register a hung build, advance the clock past the 7-day TTL,
     * and run scan() again. The build is still hung, so the counter must
     * increment a second time (the dedup entry has expired).
     *
     * <p>Without the {@code seen.entrySet().removeIf(...)} TTL sweep, a
     * pathological case is a stuck build the operator never cleaned up for
     * 8+ days: it would silently stop counting and dashboards would lose
     * the signal.
     */
    @Test
    void ttl_expiry_re_arms_dedup() {
        AtomicLong fakeClock = new AtomicLong(System.currentTimeMillis());
        long base = fakeClock.get();

        Run<?, ?> run = mockBuildingRun("forever-hung", 99, base - TimeUnit.HOURS.toMillis(25));
        Computer computer = mockComputerWithRun(run);
        when(jenkins.getComputers()).thenReturn(new Computer[]{computer});

        HungBuildDetector detector = new HungBuildDetector();
        detector.clock = fakeClock::get;
        detector.scan();

        double first = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(1.0, first, 0.0001,
                "Sanity: first tick increments stuck counter once");

        // Advance the clock 8 days; the dedup entry's age now exceeds the
        // 7-day TTL and should be reaped at the start of the next scan().
        fakeClock.set(base + TimeUnit.DAYS.toMillis(8));
        detector.scan();

        double second = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels(HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(2.0, second, 0.0001,
                "After TTL expiry the same hung build must re-arm the counter; got " + second);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * Build a mock {@link Run} that additionally satisfies
     * {@link Queue.Executable} so it can be returned from
     * {@link Executor#getCurrentExecutable()}. In production, the runtime
     * type is always {@code AbstractBuild} or {@code WorkflowRun}, both of
     * which extend {@code Run} and implement {@code Queue.Executable};
     * {@code Run} itself does not, so the mock has to bridge both.
     */
    private static Run<?, ?> mockBuildingRun(String jobName, int buildNumber, long startMs) {
        Run<?, ?> run = mock(Run.class, withSettings().extraInterfaces(Queue.Executable.class));
        Job<?, ?> job = mock(Job.class);
        when(job.getFullName()).thenReturn(jobName);
        // doReturn-style stubbing avoids the unfinished-stubbing trap when
        // Mockito sees the raw cast inside when(...).
        org.mockito.Mockito.doReturn(job).when(run).getParent();
        when(run.getNumber()).thenReturn(buildNumber);
        when(run.isBuilding()).thenReturn(true);
        when(run.getStartTimeInMillis()).thenReturn(startMs);
        return run;
    }

    /** Build a mock {@link Computer} whose executor is running {@code run}. */
    private static Computer mockComputerWithRun(Run<?, ?> run) {
        Computer computer = mock(Computer.class);
        Executor executor = mock(Executor.class);
        when(executor.getCurrentExecutable()).thenReturn((Queue.Executable) run);
        when(computer.getExecutors()).thenReturn(java.util.List.of(executor));
        when(computer.getNode()).thenReturn(null); // -> template = "unknown"
        return computer;
    }
}
