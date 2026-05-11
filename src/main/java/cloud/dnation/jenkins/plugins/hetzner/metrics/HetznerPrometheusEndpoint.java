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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import hudson.model.UnprotectedRootAction;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Stapler endpoint that exposes {@link HetznerMetricProvider}'s metrics at
 * {@code /hetzner-prometheus} in Prometheus 0.0.4 text format.
 *
 * Self-contained -- does not depend on the Jenkins {@code prometheus} plugin.
 * The plugin bundles {@code io.prometheus:simpleclient} + {@code _common} so
 * the endpoint works on any Jenkins master, including those that have not
 * installed the Jenkins community Prometheus plugin.
 *
 * Localhost-only endpoint. Jenkins binds 8080 to 127.0.0.1 on Percona
 * masters, so this endpoint is unreachable from external clients. The
 * master-side Grafana Alloy systemd unit scrapes it and forwards to the
 * in-cluster alloy-gateway over a bearer-authenticated outbound HTTPS
 * connection (PS-10997 Phase 2, ADR 0013). The endpoint implements
 * {@link UnprotectedRootAction} so Jenkins core's authorization layer
 * does not require {@code Jenkins.READ} for anonymous local callers; the
 * push-model trust boundary is the loopback bind, not core ACLs.
 */
@Extension
@Slf4j
public class HetznerPrometheusEndpoint implements UnprotectedRootAction {

    private static final String URL_NAME = "hetzner-prometheus";

    /**
     * If {@code true}, only loopback callers are served. Default {@code true}.
     * Can be overridden by JVM property {@code hetzner.prometheus.allowNonLoopback=true}
     * for environments that proxy via a trusted reverse proxy (e.g. unix socket).
     * Documented escape hatch; not recommended.
     */
    private static final boolean ALLOW_NON_LOOPBACK =
            Boolean.getBoolean("hetzner.prometheus.allowNonLoopback");

    /**
     * Rate-limit the "refused proxied request" WARN: a misconfigured proxy
     * could otherwise spam a WARN on every Alloy scrape (10-30 lines/min).
     * Allow at most one log per minute.
     */
    private static final java.util.concurrent.atomic.AtomicLong LAST_PROXY_WARN_MS =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private static final long PROXY_WARN_INTERVAL_MS = 60_000L;

    @Override
    @Nullable
    public String getIconFileName() {
        // Hide from the Jenkins sidebar -- this is a machine-only endpoint.
        return null;
    }

    @Override
    @Nullable
    public String getDisplayName() {
        return null;
    }

    @Override
    @NonNull
    public String getUrlName() {
        return URL_NAME;
    }

    /**
     * GET /hetzner-prometheus -- emit text-format metrics from
     * {@link CollectorRegistry#defaultRegistry}.
     *
     * Enforces loopback origin to avoid leaking cloud/template/credential-id
     * topology to anyone who happens to reach Jenkins 8080 (CDN bypass,
     * misconfigured reverse proxy, etc.). The UnprotectedRootAction marker
     * removed Jenkins core's anonymous-deny gate; this check restores a
     * narrower one based on the request peer address.
     */
    public void doIndex(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp) throws IOException {
        if (!ALLOW_NON_LOOPBACK && !isLoopbackRequest(req)) {
            log.warn("Refusing /hetzner-prometheus from non-loopback peer '{}' (remoteAddr='{}')",
                    req.getRemoteHost(), req.getRemoteAddr());
            rsp.sendError(403, "metrics endpoint is loopback-only");
            return;
        }
        rsp.setContentType(TextFormat.CONTENT_TYPE_004);
        rsp.setStatus(200);
        try (Writer writer = rsp.getWriter()) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }
    }

    /**
     * Returns true iff the request's immediate peer is a loopback IP AND there
     * are no proxy/forwarded headers indicating this is a relayed request.
     *
     * Why both: a reverse proxy running on the same Jenkins host (nginx,
     * Apache, the Jenkins-bundled connector forwarder, etc.) has a loopback
     * peer address while delivering arbitrary external clients. Examining
     * forwarded headers and bailing on their presence is the conservative
     * choice; operators with a trusted proxy chain can set
     * {@code -Dhetzner.prometheus.allowNonLoopback=true} to opt out.
     */
    private static boolean isLoopbackRequest(StaplerRequest2 req) {
        String remoteAddr = req.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return false;
        }
        boolean peerIsLoopback;
        try {
            peerIsLoopback = InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            log.warn("Could not parse remote address '{}'; treating as non-loopback", remoteAddr);
            return false;
        }
        if (!peerIsLoopback) {
            return false;
        }
        // Reject any request that looks proxied. We don't try to parse and
        // validate the forwarded chain; absence is the only safe signal.
        String xff = req.getHeader("X-Forwarded-For");
        String forwarded = req.getHeader("Forwarded");
        String xRealIp = req.getHeader("X-Real-IP");
        if (xff != null || forwarded != null || xRealIp != null) {
            long now = System.currentTimeMillis();
            long last = LAST_PROXY_WARN_MS.get();
            if (now - last >= PROXY_WARN_INTERVAL_MS
                    && LAST_PROXY_WARN_MS.compareAndSet(last, now)) {
                // Only header names (not values) to avoid leaking client IPs
                // from misconfigured proxies into our logs.
                log.warn("Refusing /hetzner-prometheus: peer is loopback but request carries "
                        + "proxy headers (xff={} forwarded={} x_real_ip={}). "
                        + "If a trusted same-host proxy is intentional, set "
                        + "-Dhetzner.prometheus.allowNonLoopback=true. "
                        + "(rate-limited to once per minute)",
                        xff != null ? "present" : "absent",
                        forwarded != null ? "present" : "absent",
                        xRealIp != null ? "present" : "absent");
            }
            return false;
        }
        return true;
    }
}
