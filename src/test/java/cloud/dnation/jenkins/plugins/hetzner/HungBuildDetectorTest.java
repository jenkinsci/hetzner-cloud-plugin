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
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Verifies the v103.percona.17 {@link HungBuildDetector}: the cron-driven
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
 */
class HungBuildDetectorTest {

    private static final String THRESHOLD_PROP = "hetzner.hung-build.threshold-hours";
    private static final String PERIOD_PROP = "hetzner.hung-build.poll-period-minutes";
    private static final String MASTER_PROP = "hetzner.master.label";

    private MockedStatic<Jenkins> jenkinsMock;
    private Jenkins jenkins;

    @BeforeEach
    void setUp() {
        HetznerMetricProvider.STUCK_BUILDS_TOTAL.clear();
        HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS.clear();
        HetznerMetricProvider.EXECUTOR_BUSY_REAL.clear();

        // Pin the master label so the dedup key is deterministic across tests.
        System.setProperty(MASTER_PROP, "ps3.cd");

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
        System.clearProperty(MASTER_PROP);
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
                .labels("ps3.cd", HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
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
                .labels("ps3.cd", HungBuildDetector.UNKNOWN_TEMPLATE).get();
        double expected = 5.0 * 3600.0;
        assertTrue(Math.abs(oldest - expected) <= 1.0,
                "OLDEST_BUILD_AGE_SECONDS must equal max elapsed (5h); got " + oldest);

        // executor_busy_real counts all three real builds.
        assertEquals(3.0, HetznerMetricProvider.EXECUTOR_BUSY_REAL.labels("ps3.cd").get(),
                0.0001, "EXECUTOR_BUSY_REAL must count every isBuilding=true executor");
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
                .labels("ps3.cd", HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(0.0, count, 0.0001,
                "Build under threshold must not increment stuck counter");

        // Oldest-age gauge still reflects the build (it's still "the oldest").
        double oldest = HetznerMetricProvider.OLDEST_BUILD_AGE_SECONDS
                .labels("ps3.cd", HungBuildDetector.UNKNOWN_TEMPLATE).get();
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

        double busy = 0.0;
        try {
            busy = HetznerMetricProvider.EXECUTOR_BUSY_REAL.labels("ps3.cd").get();
        } catch (Exception ignored) {
            // Label series may not have been created; that's fine -- the
            // contract here is "timer survives", not "gauge gets set".
        }
        assertEquals(0.0, busy, 0.0001,
                "Failed scan must not have set EXECUTOR_BUSY_REAL");
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
                .labels("ps3.cd", HungBuildDetector.UNKNOWN_TEMPLATE, "1").get();
        assertEquals(1.0, count, 0.0001,
                "1h-threshold override must reclassify the 90-min build as hung");

        // The default (24h) series must NOT have been touched.
        double defaultSeries = HetznerMetricProvider.STUCK_BUILDS_TOTAL
                .labels("ps3.cd", HungBuildDetector.UNKNOWN_TEMPLATE, "24").get();
        assertEquals(0.0, defaultSeries, 0.0001,
                "Default 24h series must not pick up the 1h-threshold hit");
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
