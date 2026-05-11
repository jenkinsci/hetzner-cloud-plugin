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

import cloud.dnation.jenkins.plugins.hetzner.launcher.AbstractHetznerSshConnector;
import com.google.common.collect.Lists;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeCallableRetryTest {

    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrMock;
    private MockedStatic<HetznerApiClient> apiClientMock;
    private HetznerCloudResourceManager mgr;

    @BeforeEach
    void setUp() {
        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrMock = mockStatic(HetznerCloudResourceManager.class);
        apiClientMock = mockStatic(HetznerApiClient.class);

        Jenkins jenkins = mock(Jenkins.class);
        doAnswer(inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);

        mgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(mgr);

        // Mock HetznerApiClient for rate-limit tests
        HetznerApiClient mockApiClient = mock(HetznerApiClient.class);
        when(mockApiClient.getRemaining()).thenReturn(0);
        when(mockApiClient.timeUntilReset()).thenReturn(java.time.Duration.ofSeconds(60));
        when(HetznerApiClient.forCredentials(anyString())).thenReturn(mockApiClient);

        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
    }

    @AfterEach
    void tearDown() {
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
        apiClientMock.close();
        jenkinsMock.close();
        rsrcMgrMock.close();
    }

    @Test
    void retryOnResourceUnavailable() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        AbstractHetznerSshConnector connector = mock(AbstractHetznerSshConnector.class);
        t1.setConnector(connector);
        t2.setConnector(connector);

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // First call (fsn1) throws resource_unavailable
        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        // Should fail because second DC also fails (mock always throws)
        assertThrows(HetznerProvisioningException.class, callable::call);

        // fsn1 should have a failure recorded
        assertFalse(DcHealthTracker.isHealthy("fsn1") && DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures() == 0,
                "fsn1 should have at least one failure recorded");
    }

    @Test
    void noRetryOnAuthError() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Auth error: should NOT retry
        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("Unauthorized", 401, "unauthorized", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertEquals(401, ex.getHttpStatus());
        // fsn1 failure recorded, but nbg1 should NOT have been tried
        assertEquals(1, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
    }

    @Test
    void allDcsFailThrowsLast() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Both DCs fail with resource_unavailable
        when(mgr.createServer(any(), any()))
                .thenThrow(new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"))
                .thenThrow(new HetznerProvisioningException("DC full", 422, "resource_unavailable", "nbg1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        // Last exception should be from nbg1
        assertEquals("nbg1", ex.getLocation());
        // Both should have failures
        assertTrue(DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures() >= 1);
        assertTrue(DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures() >= 1);
    }

    /**
     * Regression: prior implementation called {@code createServer(agent)} where
     * the agent's template was final and set to the first ranked template. The
     * DC failover loop iterated rankedTemplates but every API call still used
     * the first template's image/DC/server-type. The fix introduces a 2-arg
     * overload {@code createServer(agent, template)} and routes per-iteration
     * templates through it. This test pins that contract: when failover occurs,
     * createServer is called with t1 on iteration 1 AND t2 on iteration 2.
     */
    @Test
    void failoverActuallyUsesEachRankedTemplate() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        when(mgr.createServer(any(), any()))
                .thenThrow(new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"))
                .thenThrow(new HetznerProvisioningException("DC full", 422, "resource_unavailable", "nbg1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        assertThrows(HetznerProvisioningException.class, callable::call);

        // The critical assertion: createServer must be invoked with each
        // ranked template in turn. The previous bug would have called it
        // twice with t1 only.
        verify(mgr, times(1)).createServer(any(), eq(t1));
        verify(mgr, times(1)).createServer(any(), eq(t2));
    }

    @Test
    void singleTemplateNoRetry() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        assertThrows(HetznerProvisioningException.class, callable::call);
        assertEquals(1, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
    }

    @Test
    void rateLimitedAbortsImmediately() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Rate-limit error: should abort immediately, no DC failover
        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("Rate limited", 429, "rate_limit_exceeded", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertTrue(ex.isRateLimited());
        // Rate limit throws BEFORE recordFailure, so neither DC should have failures
        assertEquals(0, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
        // Verify only ONE provisioning attempt was made (no DC failover)
        verify(mgr, times(1)).createServer(any(), any());
    }

    @Test
    void configErrorAbortsImmediately() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Config error: should abort immediately, no DC failover
        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("Invalid image", 422, "invalid_input", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertTrue(ex.isConfigError());
        // Config error throws BEFORE recordFailure, so neither DC should have failures
        assertEquals(0, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
        // Verify only ONE provisioning attempt was made (no DC failover)
        verify(mgr, times(1)).createServer(any(), any());
    }

    /**
     * Regression: cloud-cap exhaustion under burst is reported by the
     * resource manager as HTTP 429 with synthetic code "instance_cap_reached".
     * This is cap bookkeeping, not a DC health signal - all DCs share the
     * same cap, so other DCs cannot help. We must NOT bump
     * DcHealthTracker.recordFailure for this case; otherwise burst traffic
     * would open the breaker for healthy DCs.
     */
    @Test
    void instanceCapReachedDoesNotPoisonDcHealth() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("Cap reached under lock", 429,
                        "instance_cap_reached", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        assertThrows(HetznerProvisioningException.class, callable::call);
        // No DC failures recorded - cap is cloud-wide, not DC-scoped
        assertEquals(0, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
        // No failover either
        verify(mgr, times(1)).createServer(any(), eq(t1));
        verify(mgr, times(0)).createServer(any(), eq(t2));
    }

    /**
     * Regression: when the second ranked template has a different SSH
     * credential ID, failover must be REFUSED (because the agent embeds the
     * original template's launcher, which has the original credential). The
     * code logs and treats this as a final failure rather than churning a
     * second VM that nobody can SSH into.
     */
    @Test
    void failoverRefusedWhenTemplateIncompatible() throws Exception {
        // t1 uses connector with credential "cred-A"; t2 uses "cred-B"
        AbstractHetznerSshConnector connA = mock(AbstractHetznerSshConnector.class);
        when(connA.getSshCredentialsId()).thenReturn("cred-A");
        when(connA.getSshPort()).thenReturn(22);
        AbstractHetznerSshConnector connB = mock(AbstractHetznerSshConnector.class);
        when(connB.getSshCredentialsId()).thenReturn("cred-B");
        when(connB.getSshPort()).thenReturn(22);

        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1", connA);
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1", connB);

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        when(mgr.createServer(any(), any())).thenThrow(
                new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertEquals("fsn1", ex.getLocation());
        // Critical: createServer must NOT have been called on t2 because the
        // compatibility check refused failover.
        verify(mgr, times(1)).createServer(any(), eq(t1));
        verify(mgr, times(0)).createServer(any(), eq(t2));
        // fsn1 failure is still recorded (the API call failed before the
        // compatibility check refused failover).
        assertEquals(1, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
    }

    /**
     * Shared connector for the failover tests so {@link
     * HetznerServerTemplate#isFailoverCompatibleWith(HetznerServerTemplate)}
     * returns true. Templates that differ only in DC/server-type/image are
     * the canonical Percona pattern; the compatibility check refuses failover
     * when connector identity differs, so each scenario must use the same
     * connector instance.
     */
    private AbstractHetznerSshConnector sharedConnector() {
        AbstractHetznerSshConnector c = mock(AbstractHetznerSshConnector.class);
        when(c.getSshCredentialsId()).thenReturn("shared-cred");
        when(c.getSshPort()).thenReturn(22);
        when(c.getUsernameOverride()).thenReturn(null);
        return c;
    }

    private HetznerServerTemplate makeTemplate(String name, String location) {
        return makeTemplate(name, location, sharedConnector());
    }

    private HetznerServerTemplate makeTemplate(String name, String location,
                                               AbstractHetznerSshConnector connector) {
        HetznerServerTemplate t = new HetznerServerTemplate(name, "label1", "img1", location, "cpx32");
        t.setConnector(connector);
        return t;
    }
}
