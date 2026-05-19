/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Tests for DC breaker XmlFile persistence (PS-11173, v103.percona.21).
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.XmlFile;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class DcHealthPersistenceTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        DcHealthTracker.resetAll();
        HetznerMetricProvider.resetForTest();
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        if (xml.exists() && !xml.delete()) {
            throw new IllegalStateException("Could not delete leftover " + xml);
        }
    }

    @AfterEach
    void tearDown() {
        DcHealthTracker.resetAll();
    }

    /**
     * On a FAILURE_THRESHOLD-driven OPEN transition, the persistence layer
     * must produce hetzner-dc-health.xml on disk. Save is deferred via
     * Timer, so we poll with a bounded timeout.
     */
    @Test
    void savesOnFailure() {
        // Trip the breaker (FAILURE_THRESHOLD = 2).
        DcHealthTracker.recordFailure("fsn1");
        DcHealthTracker.recordFailure("fsn1");
        assertFalse(DcHealthTracker.isHealthy("fsn1"), "fsn1 should be OPEN after 2 failures");

        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        await().atMost(10, TimeUnit.SECONDS).until(xml::exists);
        assertTrue(xml.length() > 0, "xml file should not be empty");

        await().atMost(5, TimeUnit.SECONDS).until(
                () -> HetznerMetricProvider.DC_HEALTH_SAVES.get() >= 1);
        assertEquals(0.0, HetznerMetricProvider.DC_HEALTH_SAVE_FAILURES.get(),
                "no save failures expected on the happy path");
    }

    /**
     * A persisted OPEN breaker must be restored on init. Tests the
     * full save -> load round-trip end to end.
     */
    @Test
    void loadsOnInit() {
        // Phase 1: trip and persist
        DcHealthTracker.recordFailure("nbg1");
        DcHealthTracker.recordFailure("nbg1");
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        await().atMost(10, TimeUnit.SECONDS).until(xml::exists);

        // Phase 2: clear in-memory state (simulating a JVM restart at the
        // in-process layer) and call load() explicitly. Tests the
        // deserializer + afterLoad gauge restoration. JenkinsRule cannot
        // actually bounce the JVM mid-test, but DcHealthTracker.load() is
        // package-private and idempotent enough to invoke directly.
        DcHealthTracker.resetAll();
        assertTrue(DcHealthTracker.getAllBreakers().isEmpty());
        DcHealthTracker.load();

        // Phase 3: assert restored
        assertEquals(1, DcHealthTracker.getAllBreakers().size());
        DcCircuitBreaker restored = DcHealthTracker.getBreaker("nbg1");
        assertNotNull(restored);
        assertEquals(DcCircuitBreaker.State.OPEN, restored.getState());
        assertEquals(2, restored.getConsecutiveFailures());
    }

    /**
     * An OPEN breaker whose last failure is older than the 30-min TTL
     * must load as CLOSED. This prevents a transient incident from
     * pinning a DC out of rotation after a long master restart.
     *
     * Implementation: write a hand-crafted XmlFile with an artificially
     * old openedAt, then call load(); state should be CLOSED.
     */
    @Test
    void ttl_resetsStaleOpen() throws Exception {
        // Trip a breaker via the public API so the XML file exists with
        // the right XStream class registrations, then mutate openedAt
        // backwards via reflection to simulate "this breaker has been
        // OPEN for longer than the TTL".
        DcHealthTracker.recordFailure("hel1");
        DcHealthTracker.recordFailure("hel1");
        DcCircuitBreaker breaker = DcHealthTracker.getBreaker("hel1");
        assertEquals(DcCircuitBreaker.State.OPEN, breaker.getState());

        java.lang.reflect.Field openedAt = DcCircuitBreaker.class.getDeclaredField("openedAt");
        openedAt.setAccessible(true);
        long pastFortyMinutesAgo = System.currentTimeMillis() - (40L * 60 * 1000);
        openedAt.set(breaker, pastFortyMinutesAgo);

        // Manually save the mutated state.
        DcHealthTracker.save();
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        await().atMost(10, TimeUnit.SECONDS).until(() -> xml.exists() && xml.length() > 0);
        // Give the deferred Timer task a moment to flush our mutated state
        // (save() coalesces, so the disk file may still be the un-mutated
        // version). Force a fresh save by toggling state.
        Thread.sleep(500);
        DcHealthTracker.resetAll();
        // Re-trip so we definitely have a fresh file with the mutated
        // openedAt by going through the load path next.
        // (Simpler approach: write the XML directly via the same XmlFile
        // mechanism the production code uses.)
        DcHealthTracker.getBreaker("hel1");
        DcCircuitBreaker freshBreaker = DcHealthTracker.getBreaker("hel1");
        // Recreate "OPEN with stale openedAt" precisely via reflection.
        java.lang.reflect.Field stateField = DcCircuitBreaker.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(freshBreaker, DcCircuitBreaker.State.OPEN);
        openedAt.set(freshBreaker, pastFortyMinutesAgo);
        java.lang.reflect.Field consecutive = DcCircuitBreaker.class.getDeclaredField("consecutiveFailures");
        consecutive.setAccessible(true);
        consecutive.set(freshBreaker, 5);

        // Persist via the same Store path the production code uses.
        XmlFile xf = new XmlFile(Jenkins.XSTREAM2, xml);
        DcHealthTracker.Store store = new DcHealthTracker.Store(DcHealthTracker.getAllBreakers());
        xf.write(store);

        // Load. afterLoad() should detect the stale OPEN and reset to CLOSED.
        DcHealthTracker.resetAll();
        DcHealthTracker.load();

        DcCircuitBreaker loaded = DcHealthTracker.getBreaker("hel1");
        assertEquals(DcCircuitBreaker.State.CLOSED, loaded.getState(),
                "stale OPEN should load as CLOSED");
        assertEquals(0, loaded.getConsecutiveFailures(),
                "consecutiveFailures should reset on stale-OPEN load");
    }

    /**
     * Missing XML file is a silent no-op: registry stays empty, no
     * exception, no log error at WARN level. Critical for first-ever
     * deploy / clean install / downgrade scenarios.
     */
    @Test
    void missingFile_isNoOp() {
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        assertFalse(xml.exists(), "fixture should have no xml file");

        DcHealthTracker.resetAll();
        DcHealthTracker.load();

        assertTrue(DcHealthTracker.getAllBreakers().isEmpty(),
                "missing file must leave registry empty");
        assertFalse(xml.exists(),
                "load() must not create the file as a side effect");
    }
}
