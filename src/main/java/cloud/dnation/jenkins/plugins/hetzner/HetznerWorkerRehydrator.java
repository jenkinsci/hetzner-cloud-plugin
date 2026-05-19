/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * PS-11173 Phase 4b (v103.percona.22): rehydrate-by-label.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.hetznerclient.DatacenterDetail;
import cloud.dnation.hetznerclient.LocationDetail;
import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.hetznerclient.ServerType;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Node;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Re-adopt the master's own Hetzner VMs as Jenkins agents after a JVM restart.
 *
 * <p>Mechanics: at boot, after {@link InitMilestone#JOB_LOADED} (so
 * {@code Jenkins.get().clouds} is populated from {@code config.xml}) but
 * before {@link InitMilestone#COMPLETED} (so this runs before the deferred
 * {@code OrphanedNodesCleaner.doCleanup()} that {@code ControllerListener}
 * schedules under the same feature flag), enumerate each {@link HetznerCloud}'s
 * VMs via {@link HetznerCloudResourceManager#fetchAllServers(String)} (which
 * filters by {@code jenkins.io/managed-by=hetzner-jenkins-plugin} +
 * {@code jenkins.io/cloud-name=<cloud>}), match each VM to a
 * {@link HetznerServerTemplate}, and {@link Jenkins#addNode(Node)} a
 * reconstructed {@link HetznerServerAgent}. Per-VM errors are caught,
 * counted, and logged at WARN; the rehydrate pass never bubbles to the boot
 * sequence.
 *
 * <p>Feature flag: gated on {@code -Dhetzner.rehydrate.enabled=true}; default
 * off so dropping {@code hetzner-cloud-103.percona.22+} on any master that
 * has not opted in is a no-op for behavior. ps3 canary opt-in lives in the
 * master's JVM args.
 *
 * <p>Template matching: first try {@link HetznerConstants#LABEL_TEMPLATE_NAME}
 * for an exact match (set at provision time in v103.percona.22+). For VMs
 * provisioned before this version, fall back to (serverType + location-or-DC
 * + name prefix). Ambiguous matches log WARN, increment
 * {@code rehydrate_failures_total{reason=ambiguous}}, and skip the VM; the
 * next hourly {@code OrphanedNodesCleaner} pass will evaluate it.
 *
 * <p>Single-master scope. No external shared state. See PS-11177 for any
 * future cross-master DC-breaker or fleet adoption work.
 */
@Slf4j
public final class HetznerWorkerRehydrator {

    private HetznerWorkerRehydrator() {
    }

    /**
     * Public so callers (tests, Script Console) can force a pass. The
     * {@code @Initializer} entry point is {@link #rehydrate()}.
     */
    @Initializer(after = InitMilestone.JOB_LOADED, before = InitMilestone.COMPLETED)
    public static void rehydrate() {
        if (!Boolean.getBoolean(ControllerListener.REHYDRATE_ENABLED_PROP)) {
            return;
        }
        final Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            log.warn("HetznerWorkerRehydrator: no Jenkins instance available; skipping pass");
            return;
        }
        log.info("HetznerWorkerRehydrator: rehydrate pass starting");
        for (Cloud c : j.clouds) {
            if (!(c instanceof HetznerCloud cloud)) {
                continue;
            }
            try {
                rehydrateCloud(cloud, j);
            } catch (RuntimeException e) {
                // Defence in depth; per-cloud errors must not abort other clouds.
                log.warn("HetznerWorkerRehydrator: pass failed for cloud={}", cloud.name, e);
            }
        }
    }

    private static void rehydrateCloud(HetznerCloud cloud, Jenkins j) {
        final String cloudName = cloud.name;
        final List<ServerDetail> vms;
        try {
            // SERVER_LIST_CACHE is static + JVM-fresh after restart; no need
            // to invalidate before the first call.
            vms = cloud.getResourceManager().fetchAllServers(cloudName);
        } catch (Exception e) {
            log.warn("HetznerWorkerRehydrator: fetchAllServers failed (cloud={}); skipping cloud",
                    cloudName, e);
            HetznerMetricProvider.REHYDRATE_FAILURES
                    .labels(cloudName, HetznerMetricProvider.REHYDRATE_REASON_OTHER).inc();
            return;
        }
        if (vms == null || vms.isEmpty()) {
            log.info("HetznerWorkerRehydrator: cloud={} has 0 VMs; nothing to rehydrate", cloudName);
            HetznerMetricProvider.REHYDRATED_WORKERS.labels(cloudName).set(0);
            return;
        }

        final List<HetznerServerTemplate> templates =
                cloud.getServerTemplates() == null
                        ? Collections.emptyList() : cloud.getServerTemplates();

        int rehydrated = 0;
        int skippedExisting = 0;
        int noMatch = 0;
        int ambiguous = 0;
        int otherFailures = 0;

        for (ServerDetail vm : vms) {
            HetznerMetricProvider.REHYDRATE_ATTEMPTS.labels(cloudName).inc();
            final String vmName = vm.getName();

            if (j.getNode(vmName) != null) {
                // Already adopted (e.g., re-entrant call) or pre-existing
                // non-rehydrate-managed node with the same name. Either way,
                // do not double-add.
                skippedExisting++;
                continue;
            }

            final MatchResult m = findTemplate(templates, vm);
            if (m == MatchResult.NONE) {
                log.warn("HetznerWorkerRehydrator: no template match for VM (cloud={}, vm={}, type={}, dc={})",
                        cloudName, vmName,
                        vm.getServerType() != null ? vm.getServerType().getName() : "?",
                        vm.getDatacenter() != null ? vm.getDatacenter().getName() : "?");
                HetznerMetricProvider.REHYDRATE_FAILURES
                        .labels(cloudName, HetznerMetricProvider.REHYDRATE_REASON_NO_MATCH).inc();
                noMatch++;
                continue;
            }
            if (m == MatchResult.AMBIGUOUS) {
                log.warn("HetznerWorkerRehydrator: ambiguous template match for VM "
                                + "(cloud={}, vm={}, type={}, dc={}); skipping. Add {} label "
                                + "to disambiguate at provision time.",
                        cloudName, vmName,
                        vm.getServerType() != null ? vm.getServerType().getName() : "?",
                        vm.getDatacenter() != null ? vm.getDatacenter().getName() : "?",
                        HetznerConstants.LABEL_TEMPLATE_NAME);
                HetznerMetricProvider.REHYDRATE_FAILURES
                        .labels(cloudName, HetznerMetricProvider.REHYDRATE_REASON_AMBIGUOUS).inc();
                ambiguous++;
                continue;
            }

            final HetznerServerTemplate template = m.template;
            try {
                final ProvisioningActivity.Id provisioningId =
                        new ProvisioningActivity.Id(cloudName, template.getName(), vmName);
                final HetznerServerAgent agent = template.createAgent(provisioningId, vmName);
                // HetznerServerInfo.sshKeyDetail is unused at runtime (launcher
                // resolves SSH credentials via Helper.assertSshKey at connect
                // time from the Jenkins credentials store). Passing null
                // avoids an unnecessary Hetzner API call to look the SSH key
                // up by name.
                final HetznerServerInfo info = new HetznerServerInfo(null);
                info.setServerDetail(vm);
                agent.setServerInstance(info);
                j.addNode(agent);
                HetznerMetricProvider.REHYDRATE_SUCCESSES
                        .labels(cloudName, template.getName()).inc();
                rehydrated++;
                log.info("HetznerWorkerRehydrator: rehydrated agent (cloud={}, template={}, vm={})",
                        cloudName, template.getName(), vmName);
            } catch (java.io.IOException ioe) {
                HetznerMetricProvider.REHYDRATE_FAILURES
                        .labels(cloudName, HetznerMetricProvider.REHYDRATE_REASON_ADD_NODE).inc();
                otherFailures++;
                log.warn("HetznerWorkerRehydrator: addNode failed (cloud={}, template={}, vm={})",
                        cloudName, template.getName(), vmName, ioe);
            } catch (Exception other) {
                HetznerMetricProvider.REHYDRATE_FAILURES
                        .labels(cloudName, HetznerMetricProvider.REHYDRATE_REASON_OTHER).inc();
                otherFailures++;
                log.warn("HetznerWorkerRehydrator: rehydrate failed (cloud={}, template={}, vm={})",
                        cloudName, template.getName(), vmName, other);
            }
        }

        HetznerMetricProvider.REHYDRATED_WORKERS.labels(cloudName).set(rehydrated);
        log.info("HetznerWorkerRehydrator: cloud={} rehydrated={}/{} vms "
                        + "(skipped_existing={}, no_match={}, ambiguous={}, other={})",
                cloudName, rehydrated, vms.size(),
                skippedExisting, noMatch, ambiguous, otherFailures);
    }

    /**
     * Match a VM to one of the cloud's templates. Label-first (exact match on
     * {@link HetznerConstants#LABEL_TEMPLATE_NAME}), then heuristic fallback
     * (serverType + location/datacenter + name prefix). Returns
     * {@link MatchResult#NONE} or {@link MatchResult#AMBIGUOUS} on failure.
     *
     * <p>Package-private for unit testing.
     */
    static MatchResult findTemplate(List<HetznerServerTemplate> templates, ServerDetail vm) {
        if (templates == null || templates.isEmpty()) {
            return MatchResult.NONE;
        }

        final Map<String, String> labels = vm.getLabels();
        if (labels != null) {
            final String labelTemplate = labels.get(HetznerConstants.LABEL_TEMPLATE_NAME);
            if (labelTemplate != null && !labelTemplate.isEmpty()) {
                final List<HetznerServerTemplate> exact = new ArrayList<>();
                for (HetznerServerTemplate t : templates) {
                    if (labelTemplate.equals(t.getName())) {
                        exact.add(t);
                    }
                }
                if (exact.size() == 1) {
                    return MatchResult.of(exact.get(0));
                }
                // Label present but no matching template (renamed or removed):
                // fall through to the heuristic. The user may have edited
                // template names between provision and restart.
            }
        }

        final String vmType = (vm.getServerType() != null) ? vm.getServerType().getName() : null;
        final String vmDc = (vm.getDatacenter() != null) ? vm.getDatacenter().getName() : null;
        final String vmLoc = locationName(vm.getDatacenter());
        final String vmName = vm.getName() != null ? vm.getName() : "";

        final List<HetznerServerTemplate> candidates = new ArrayList<>();
        for (HetznerServerTemplate t : templates) {
            if (!matchesType(t, vmType)) {
                continue;
            }
            if (!matchesLocation(t, vmDc, vmLoc)) {
                continue;
            }
            if (!matchesPrefix(t, vmName)) {
                continue;
            }
            candidates.add(t);
        }
        if (candidates.size() == 1) {
            return MatchResult.of(candidates.get(0));
        }
        if (candidates.size() > 1) {
            return MatchResult.AMBIGUOUS;
        }
        return MatchResult.NONE;
    }

    private static boolean matchesType(HetznerServerTemplate t, String vmType) {
        return vmType != null && vmType.equalsIgnoreCase(t.getServerType());
    }

    /**
     * Template's {@code location} is the user-supplied string. By
     * {@code createServer} convention (HetznerCloudResourceManager.java line
     * 536-540), a value containing {@code -} is treated as a datacenter
     * (e.g., {@code fsn1-dc8}); otherwise as a location (e.g., {@code fsn1}).
     * Match against both vm's datacenter name and its location name so we
     * cover either kind of template configuration.
     */
    private static boolean matchesLocation(HetznerServerTemplate t, String vmDc, String vmLoc) {
        final String tLoc = t.getLocation();
        if (tLoc == null || tLoc.isEmpty()) {
            return false;
        }
        if (vmDc != null && tLoc.equalsIgnoreCase(vmDc)) {
            return true;
        }
        return vmLoc != null && tLoc.equalsIgnoreCase(vmLoc);
    }

    private static boolean matchesPrefix(HetznerServerTemplate t, String vmName) {
        if (vmName == null || vmName.isEmpty()) {
            return false;
        }
        final String prefix = t.getPrefix();
        // generateNodeName uses "hcloud" when prefix is invalid/empty.
        final String effective = (prefix == null || prefix.isEmpty()) ? "hcloud" : prefix.toLowerCase(Locale.ROOT);
        return vmName.toLowerCase(Locale.ROOT).startsWith(effective + "-");
    }

    private static String locationName(DatacenterDetail dc) {
        if (dc == null) {
            return null;
        }
        final LocationDetail loc = dc.getLocation();
        return loc != null ? loc.getName() : null;
    }

    /**
     * Result of {@link #findTemplate(List, ServerDetail)}. Sentinel-style
     * for readability; only the {@code template != null} case carries data.
     */
    static final class MatchResult {
        static final MatchResult NONE = new MatchResult(null);
        static final MatchResult AMBIGUOUS = new MatchResult(null);
        final HetznerServerTemplate template;

        private MatchResult(HetznerServerTemplate template) {
            this.template = template;
        }

        static MatchResult of(HetznerServerTemplate t) {
            return new MatchResult(t);
        }
    }
}
