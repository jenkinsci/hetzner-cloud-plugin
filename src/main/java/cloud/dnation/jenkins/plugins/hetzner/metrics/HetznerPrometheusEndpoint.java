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
import hudson.model.RootAction;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.IOException;
import java.io.Writer;

/**
 * Stapler endpoint that exposes {@link HetznerMetricProvider}'s metrics at
 * {@code /hetzner-prometheus} in Prometheus 0.0.4 text format.
 *
 * Self-contained -- does not depend on the Jenkins {@code prometheus} plugin.
 * The plugin bundles {@code io.prometheus:simpleclient} + {@code _common} so
 * the endpoint works on any Jenkins master, including those that have not
 * installed the Jenkins community Prometheus plugin.
 *
 * Access requires {@link Jenkins#SYSTEM_READ} so an unauthenticated client
 * cannot harvest cloud / template / credentials_id labels. The
 * kube-prometheus-stack scrape job authenticates as the {@code prom-scraper}
 * Jenkins user (Phase 2 of PS-10997 -- see {@code resources/addons/prometheus/}
 * in percona-ci-platform).
 */
@Extension
public class HetznerPrometheusEndpoint implements RootAction {

    private static final String URL_NAME = "hetzner-prometheus";

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
     */
    public void doIndex(@NonNull StaplerRequest2 req, @NonNull StaplerResponse2 rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        rsp.setContentType(TextFormat.CONTENT_TYPE_004);
        rsp.setStatus(200);
        try (Writer writer = rsp.getWriter()) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        }
    }
}
