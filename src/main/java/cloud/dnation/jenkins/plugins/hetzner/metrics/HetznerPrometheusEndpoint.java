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
     * Returns true iff the request's remote address is a loopback IP.
     * Uses {@link StaplerRequest2#getRemoteAddr()} which Stapler populates from
     * the underlying servlet container. Behind a reverse proxy this is the
     * proxy's address, so loopback-with-frontend-proxy works; X-Forwarded-For
     * spoofing is irrelevant because we ignore that header.
     */
    private static boolean isLoopbackRequest(StaplerRequest2 req) {
        String remoteAddr = req.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            log.warn("Could not parse remote address '{}'; treating as non-loopback", remoteAddr);
            return false;
        }
    }
}
