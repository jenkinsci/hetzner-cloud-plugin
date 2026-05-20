/*
 *     Copyright 2021 https://dnation.cloud
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

import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.Extension;
import hudson.model.PeriodicWork;
import io.prometheus.client.Histogram;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.Symbol;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Extension
@Symbol("OrphanedNodesCleaner")
@Slf4j
public class OrphanedNodesCleaner extends PeriodicWork {
    @Override
    public long getRecurrencePeriod() {
        return HOUR;
    }

    private static Set<HetznerCloud> getHetznerClouds() {
        return Jenkins.get().clouds.stream()
                .filter(HetznerCloud.class::isInstance)
                .map(HetznerCloud.class::cast)
                .collect(Collectors.toSet());
    }

    @Override
    protected void doRun() {
        try {
            doCleanup();
        } catch (Exception e) {
            // Catch-all to prevent killing this PeriodicWork timer.
            log.error("Orphaned node cleanup failed unexpectedly", e);
        }
    }

    static void doCleanup() {
        getHetznerClouds().forEach(OrphanedNodesCleaner::cleanCloud);
    }

    private static void cleanCloud(HetznerCloud cloud) {
        if (HetznerApiClient.forCredentials(cloud.getCredentialsId()).isRateLimited()) {
            log.warn("Token rate-limited for cloud '{}', skipping orphan cleanup this cycle", cloud.name);
            // Surface the throttle on the same dashboard panel that monitors
            // cleanup health, so "cleanup stuck" alerts fire on this path
            // instead of silently showing zero. Opus post-merge LC5.
            HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS
                    .labels(cloud.name, HetznerMetricProvider.ORPHAN_KIND_RATE_LIMITED).inc();
            return;
        }
        Histogram.Timer cleanupTimer = HetznerMetricProvider.ORPHAN_CLEANUP_DURATION
                .labels(cloud.name).startTimer();
        try {
            final List<ServerDetail> allInstances = cloud.getResourceManager()
                    .fetchAllServers(cloud.name);
            final List<HetznerServerAgent> hetznerAgents = Helper.getHetznerAgents();
            final List<String> jenkinsNodeNames = hetznerAgents
                    .stream()
                    .map(HetznerServerAgent::getNodeName)
                    .toList();
            final Set<String> hetznerVmNames = allInstances.stream()
                    .map(ServerDetail::getName)
                    .collect(Collectors.toSet());

            // Direction 1: VMs without Jenkins nodes (orphan VMs) -- destroy them.
            // Grace period: skip VMs younger than 15 minutes to avoid destroying
            // servers that are actively being provisioned by NodeCallable (which
            // creates the server before calling Jenkins.get().addNode()).
            allInstances.stream()
                    .filter(server -> !jenkinsNodeNames.contains(server.getName()))
                    .filter(server -> isOlderThan(server, Duration.ofMinutes(15)))
                    .forEach(serverDetail -> terminateOrphanedServer(serverDetail, cloud));

            // Direction 2: Jenkins nodes without VMs (ghost nodes) -- remove them.
            // Match by node name prefix (hcloud-) rather than transient cloud field,
            // which is null after deserialization (exactly the scenario ghost nodes
            // arise from). All Hetzner nodes use the "hcloud-" naming convention.
            hetznerAgents.stream()
                    .filter(agent -> !hetznerVmNames.contains(agent.getNodeName()))
                    .forEach(agent -> removeGhostNode(agent, cloud));

        } catch (IOException e) {
            log.warn("Error fetching servers from cloud '{}': {}", cloud.name, e.getMessage(), e);
            HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS
                    .labels(cloud.name, HetznerMetricProvider.ORPHAN_KIND_FETCH_SERVERS).inc();
        } catch (Exception e) {
            log.error("Unexpected error cleaning cloud '{}': {}", cloud.name, e.getMessage(), e);
            HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS
                    .labels(cloud.name, HetznerMetricProvider.ORPHAN_KIND_UNEXPECTED).inc();
        } finally {
            cleanupTimer.observeDuration();
        }
    }

    private static void terminateOrphanedServer(ServerDetail serverDetail, HetznerCloud cloud) {
        log.info("Terminating orphaned server {} (id={}) from cloud '{}'",
                serverDetail.getName(), serverDetail.getId(), cloud.name);
        try {
            // destroyServer returns false when its internal try/catch swallowed
            // an exception (rate-limit, HTTP 5xx, etc.). Bumping ORPHAN_REAPED
            // unconditionally falsely reports recovery while servers stay alive.
            boolean reaped = cloud.getResourceManager().destroyServer(serverDetail);
            if (reaped) {
                final String arch = HetznerMetricProvider.archOf(
                        serverDetail.getServerType() != null
                                ? serverDetail.getServerType().getName()
                                : null);
                HetznerMetricProvider.ORPHAN_REAPED.labels(cloud.name, arch).inc();
            } else {
                log.warn("Orphan termination failed for server {} (id={}, cloud={}); "
                        + "will retry on next cycle",
                        serverDetail.getName(), serverDetail.getId(), cloud.name);
                HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS
                        .labels(cloud.name, HetznerMetricProvider.ORPHAN_KIND_DESTROY_FAILED).inc();
            }
        } catch (Exception e) {
            log.error("Failed to terminate orphaned server {} (id={}) from cloud '{}': {}",
                    serverDetail.getName(), serverDetail.getId(), cloud.name, e.getMessage(), e);
            HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS
                    .labels(cloud.name, HetznerMetricProvider.ORPHAN_KIND_DESTROY_SERVER).inc();
        }
    }

    /**
     * Check if a server was created more than the given duration ago.
     * Returns true if the creation time cannot be parsed (fail-safe: clean up).
     */
    private static boolean isOlderThan(ServerDetail server, Duration minAge) {
        try {
            String created = server.getCreated();
            if (created == null || created.isEmpty()) {
                return true; // no timestamp means we can't tell; assume old
            }
            OffsetDateTime createdAt = OffsetDateTime.parse(created);
            return Duration.between(createdAt, OffsetDateTime.now()).compareTo(minAge) > 0;
        } catch (DateTimeParseException e) {
            log.warn("Could not parse creation time for server '{}': {}", server.getName(), e.getMessage());
            return true; // fail-safe: treat unparseable as old
        }
    }

    private static void removeGhostNode(HetznerServerAgent agent, HetznerCloud cloud) {
        String name = agent.getNodeName();
        var computer = agent.toComputer();
        if (computer != null && !computer.isOffline()) {
            // Node is online but VM is gone -- it will fail on next build.
            // Mark offline first to prevent scheduling.
            log.warn("Ghost node {} is online but VM is gone, marking offline before removal", name);
        }
        log.info("Removing ghost node {} (Jenkins node without Hetzner VM)", name);
        try {
            Jenkins.get().removeNode(agent);
            HetznerMetricProvider.GHOST_REMOVED.labels(cloud.name).inc();
        } catch (Exception e) {
            log.error("Failed to remove ghost node {}: {}", name, e.getMessage(), e);
            HetznerMetricProvider.ORPHAN_CLEANUP_ERRORS
                    .labels(cloud.name, HetznerMetricProvider.ORPHAN_KIND_REMOVE_NODE).inc();
        }
    }
}
