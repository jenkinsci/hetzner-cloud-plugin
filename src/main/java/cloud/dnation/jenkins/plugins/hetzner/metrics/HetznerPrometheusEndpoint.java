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
     * If {@code true}, non-loopback callers are also served. Default {@code false}.
     * Can be flipped via JVM property {@code -Dhetzner.prometheus.allowNonLoopback=true}
     * for environments that scrape via a trusted same-host reverse proxy (e.g. a
     * unix-socket front-end). Disables the loopback-only gate entirely; the endpoint
     * remains an {@link UnprotectedRootAction} and is then ACL-free, so only use this
     * when a separate authentication layer (mTLS, bearer-token sidecar) covers it.
     * Documented escape hatch; not recommended on shared infrastructure.
     */
    private static final boolean ALLOW_NON_LOOPBACK =
            Boolean.getBoolean("hetzner.prometheus.allowNonLoopback");

    /**
     * Proxy / forwarding headers that indicate a relayed request. If any of these
     * is present the request is refused even when the immediate peer is loopback:
     * a same-host reverse proxy (nginx, Apache, an in-cluster sidecar) shows up
     * here with a loopback {@code remoteAddr} while delivering arbitrary external
     * clients, and the proxy chain is opaque to us. Includes both client-IP
     * carriers (XFF/Forwarded/X-Real-IP) and host/path/scheme rewriters
     * (X-Forwarded-Host/Proto/Port, X-Original-URL, X-Rewrite-URL, Via) that
     * proxy configurations frequently set without also setting an X-Forwarded-For
     * header. Codex post-merge review CV1.
     */
    private static final String[] PROXY_HEADERS = {
            "X-Forwarded-For",
            "Forwarded",
            "X-Real-IP",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Port",
            "X-Original-URL",
            "X-Rewrite-URL",
            "Via",
    };

    /**
     * Rate-limit the "refused" WARN paths: a misconfigured proxy or external
     * scanner could otherwise spam WARN on every request (10-30 lines/min per
     * scraper). Allow at most one log per minute across both refusal paths.
     */
    private static final java.util.concurrent.atomic.AtomicLong LAST_REFUSAL_WARN_MS =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private static final long REFUSAL_WARN_INTERVAL_MS = 60_000L;

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
        if (!ALLOW_NON_LOOPBACK) {
            RefusalReason reason = checkAccess(req);
            if (reason != null) {
                long now = System.currentTimeMillis();
                long last = LAST_REFUSAL_WARN_MS.get();
                if (now - last >= REFUSAL_WARN_INTERVAL_MS
                        && LAST_REFUSAL_WARN_MS.compareAndSet(last, now)) {
                    // Do not log remoteHost or remoteAddr -- a misconfigured
                    // proxy or external scanner would leak its client/peer
                    // address into our logs.
                    log.warn("Refusing /hetzner-prometheus: {}. "
                            + "If a trusted same-host proxy is intentional, set "
                            + "-Dhetzner.prometheus.allowNonLoopback=true. "
                            + "(rate-limited to once per minute)", reason.message);
                }
                rsp.sendError(403, "metrics endpoint is loopback-only");
                return;
            }
        }
        rsp.setContentType(TextFormat.CONTENT_TYPE_004);
        rsp.setStatus(200);
        try (Writer writer = rsp.getWriter()) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }
    }

    /**
     * Reason a request was refused, or null if it should be served.
     * Encoded as a tiny tagged value so the WARN message is structured and
     * has no chance of carrying client-supplied data (header values, peer IPs).
     */
    private static final class RefusalReason {
        final String message;
        RefusalReason(String message) {
            this.message = message;
        }
    }

    /**
     * Returns null iff the request's immediate peer is a loopback IP AND there
     * are no proxy/forwarded headers indicating this is a relayed request.
     * Otherwise returns a {@link RefusalReason} suitable for a non-leaky log.
     *
     * Why both checks: a reverse proxy running on the same Jenkins host
     * (nginx, Apache, an in-cluster sidecar, the Jenkins-bundled connector
     * forwarder, etc.) has a loopback peer address while delivering arbitrary
     * external clients. Examining forwarded headers and bailing on their
     * presence is the conservative choice; operators with a trusted proxy
     * chain can set {@code -Dhetzner.prometheus.allowNonLoopback=true} to
     * opt out.
     */
    private static RefusalReason checkAccess(StaplerRequest2 req) {
        String remoteAddr = req.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isEmpty()) {
            return new RefusalReason("missing remote address");
        }
        boolean peerIsLoopback;
        try {
            // Strip a trailing ":port" or "[::1]:..." form before parsing.
            peerIsLoopback = InetAddress.getByName(normalizeAddr(remoteAddr)).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return new RefusalReason("remote address could not be parsed");
        }
        if (!peerIsLoopback) {
            return new RefusalReason("non-loopback peer");
        }
        // Reject any request that looks proxied. We don't try to parse and
        // validate the forwarded chain; absence is the only safe signal.
        String firstSeen = null;
        for (String h : PROXY_HEADERS) {
            if (req.getHeader(h) != null) {
                firstSeen = h;
                break;
            }
        }
        if (firstSeen != null) {
            return new RefusalReason("loopback peer but request carries proxy header " + firstSeen);
        }
        return null;
    }

    /**
     * Strip a trailing port from an IPv4 {@code 1.2.3.4:5} or bracketed IPv6
     * {@code [::1]:5}. Servlet containers normally return a bare address but
     * some custom Stapler front-ends include the port; InetAddress.getByName
     * would then fail and the gate would refuse legitimate scrapes.
     */
    static String normalizeAddr(String addr) {
        if (addr == null) {
            return null;
        }
        // Bracketed IPv6 with optional port: "[::1]" or "[::1]:54321"
        if (addr.startsWith("[")) {
            int end = addr.indexOf(']');
            if (end > 0) {
                return addr.substring(1, end);
            }
        }
        // IPv4 with port: "1.2.3.4:5". Naked IPv6 contains multiple colons so
        // we only strip when exactly one colon is present.
        int colon = addr.indexOf(':');
        if (colon > 0 && addr.indexOf(':', colon + 1) < 0) {
            return addr.substring(0, colon);
        }
        return addr;
    }
}
