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

import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the loopback gate + proxy-header refusal on
 * {@link HetznerPrometheusEndpoint}. The gate is the entire trust boundary on
 * the master-side Alloy push pipeline (ADR 0013); regressions here would leak
 * credential-id labels and Hetzner topology to anyone who reaches the endpoint.
 */
class HetznerPrometheusEndpointTest {

    // =====================================================================
    // normalizeAddr: handle IPv4-with-port and bracketed-IPv6-with-port
    // =====================================================================

    @Test
    void normalizeAddr_bareIpv4_unchanged() {
        assertEquals("127.0.0.1", HetznerPrometheusEndpoint.normalizeAddr("127.0.0.1"));
    }

    @Test
    void normalizeAddr_ipv4WithPort_stripsPort() {
        assertEquals("127.0.0.1", HetznerPrometheusEndpoint.normalizeAddr("127.0.0.1:54321"));
    }

    @Test
    void normalizeAddr_bareIpv6_unchanged() {
        assertEquals("::1", HetznerPrometheusEndpoint.normalizeAddr("::1"));
    }

    @Test
    void normalizeAddr_bracketedIpv6_unwraps() {
        assertEquals("::1", HetznerPrometheusEndpoint.normalizeAddr("[::1]"));
    }

    @Test
    void normalizeAddr_bracketedIpv6WithPort_unwrapsAndStrips() {
        assertEquals("::1", HetznerPrometheusEndpoint.normalizeAddr("[::1]:54321"));
    }

    @Test
    void normalizeAddr_nullSafe() {
        assertNull(HetznerPrometheusEndpoint.normalizeAddr(null));
    }

    // =====================================================================
    // doIndex: refusal matrix
    //
    // The system property hetzner.prometheus.allowNonLoopback is read at class
    // load (static final), so allowNonLoopback=true cannot be exercised here
    // without classloader gymnastics. The default-false behavior is what
    // matters for the security boundary; all tests below run with that.
    // =====================================================================

    @Test
    void loopbackPeerNoProxyHeaders_servesMetrics() throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        // All checked headers return null by default

        StaplerResponse2 rsp = mock(StaplerResponse2.class);
        StringWriter sw = new StringWriter();
        when(rsp.getWriter()).thenReturn(new PrintWriter(sw));

        endpoint.doIndex(req, rsp);

        verify(rsp, never()).sendError(anyInt(), anyString());
        verify(rsp).setStatus(200);
        verify(rsp).setContentType(anyString());
    }

    @Test
    void ipv6LoopbackPeer_servesMetrics() throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getRemoteAddr()).thenReturn("::1");

        StaplerResponse2 rsp = mock(StaplerResponse2.class);
        StringWriter sw = new StringWriter();
        when(rsp.getWriter()).thenReturn(new PrintWriter(sw));

        endpoint.doIndex(req, rsp);

        verify(rsp, never()).sendError(anyInt(), anyString());
        verify(rsp).setStatus(200);
    }

    @Test
    void ipv4PeerWithPort_servesMetrics() throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        // Some Stapler front-ends include the port; normalizeAddr handles it.
        when(req.getRemoteAddr()).thenReturn("127.0.0.1:54321");

        StaplerResponse2 rsp = mock(StaplerResponse2.class);
        StringWriter sw = new StringWriter();
        when(rsp.getWriter()).thenReturn(new PrintWriter(sw));

        endpoint.doIndex(req, rsp);

        verify(rsp, never()).sendError(anyInt(), anyString());
        verify(rsp).setStatus(200);
    }

    @Test
    void nonLoopbackPeer_refused() throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");

        StaplerResponse2 rsp = mock(StaplerResponse2.class);

        endpoint.doIndex(req, rsp);

        verify(rsp).sendError(403, "metrics endpoint is loopback-only");
        verify(rsp, never()).setStatus(200);
    }

    @Test
    void emptyRemoteAddr_refused() throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getRemoteAddr()).thenReturn("");

        StaplerResponse2 rsp = mock(StaplerResponse2.class);

        endpoint.doIndex(req, rsp);

        verify(rsp).sendError(403, "metrics endpoint is loopback-only");
    }

    /**
     * Parameterized via individual test methods: each refused proxy header
     * triggers the gate even with a loopback peer.
     */
    @Test
    void loopbackPeerWithXForwardedFor_refused() throws Exception {
        assertRefusedWithHeader("X-Forwarded-For", "1.2.3.4");
    }

    @Test
    void loopbackPeerWithForwarded_refused() throws Exception {
        assertRefusedWithHeader("Forwarded", "for=1.2.3.4");
    }

    @Test
    void loopbackPeerWithXRealIp_refused() throws Exception {
        assertRefusedWithHeader("X-Real-IP", "1.2.3.4");
    }

    /**
     * CV1 (post-merge): the prior gate refused only XFF / Forwarded /
     * X-Real-IP. A same-host nginx with proxy_pass http://127.0.0.1:8080 and
     * proxy_set_header X-Forwarded-Host $host -- without an X-Forwarded-For --
     * would slip past the gate while delivering arbitrary external clients.
     */
    @Test
    void loopbackPeerWithXForwardedHost_refused() throws Exception {
        assertRefusedWithHeader("X-Forwarded-Host", "example.com");
    }

    @Test
    void loopbackPeerWithXForwardedProto_refused() throws Exception {
        assertRefusedWithHeader("X-Forwarded-Proto", "https");
    }

    @Test
    void loopbackPeerWithXForwardedPort_refused() throws Exception {
        assertRefusedWithHeader("X-Forwarded-Port", "443");
    }

    @Test
    void loopbackPeerWithXOriginalUrl_refused() throws Exception {
        assertRefusedWithHeader("X-Original-URL", "/hetzner-prometheus/");
    }

    @Test
    void loopbackPeerWithXRewriteUrl_refused() throws Exception {
        assertRefusedWithHeader("X-Rewrite-URL", "/hetzner-prometheus/");
    }

    @Test
    void loopbackPeerWithVia_refused() throws Exception {
        assertRefusedWithHeader("Via", "1.1 example.com");
    }

    /**
     * Defense-in-depth: malformed remote address (not a parseable IP) must
     * NOT serve metrics. InetAddress.getByName would have to perform DNS
     * resolution on a non-literal string, which is undesirable on the hot
     * scrape path and could in some Java configurations succeed for a hostname
     * that happens to resolve to a loopback IP.
     */
    @Test
    void unparseableRemoteAddr_refused() throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getRemoteAddr()).thenReturn("not-an-ip-address-!!!");

        StaplerResponse2 rsp = mock(StaplerResponse2.class);

        endpoint.doIndex(req, rsp);

        verify(rsp).sendError(403, "metrics endpoint is loopback-only");
        verify(rsp, never()).setStatus(200);
    }

    // =====================================================================
    // metadata
    // =====================================================================

    @Test
    void rootAction_metadata() {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        assertEquals("hetzner-prometheus", endpoint.getUrlName());
        // Sidebar icon and display name are intentionally null so the
        // endpoint does not show up in the Jenkins UI.
        assertNull(endpoint.getIconFileName());
        assertNull(endpoint.getDisplayName());
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private void assertRefusedWithHeader(String headerName, String headerValue) throws Exception {
        HetznerPrometheusEndpoint endpoint = new HetznerPrometheusEndpoint();
        StaplerRequest2 req = mock(StaplerRequest2.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader(headerName)).thenReturn(headerValue);

        StaplerResponse2 rsp = mock(StaplerResponse2.class);

        endpoint.doIndex(req, rsp);

        verify(rsp).sendError(403, "metrics endpoint is loopback-only");
        verify(rsp, never()).setStatus(200);
    }
}
