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

import cloud.dnation.hetznerclient.ServerType;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
class NodeCallable implements Callable<Node> {
    private final HetznerServerAgent agent;
    private final HetznerCloud cloud;
    private final List<HetznerServerTemplate> rankedTemplates;

    /**
     * Constructor with DC failover support.
     * @param agent the agent being provisioned (uses first template)
     * @param cloud the cloud instance
     * @param rankedTemplates templates sorted by DC health (healthy first)
     */
    NodeCallable(HetznerServerAgent agent, HetznerCloud cloud,
                 List<HetznerServerTemplate> rankedTemplates) {
        this.agent = agent;
        this.cloud = cloud;
        this.rankedTemplates = rankedTemplates;
    }

    /** Backward-compatible constructor (no failover). */
    NodeCallable(HetznerServerAgent agent, HetznerCloud cloud) {
        this(agent, cloud, Collections.singletonList(agent.getTemplate()));
    }

    @Override
    public Node call() throws Exception {
        try {
            Computer computer = agent.getComputer();
            if (computer != null && computer.isOnline()) {
                return agent;
            }

            // Try provisioning with failover across DCs
            Exception lastException = null;
            for (int i = 0; i < rankedTemplates.size(); i++) {
                HetznerServerTemplate template = rankedTemplates.get(i);
                String location = template.getLocation();
                try {
                    Node result = doProvisionAndTime(template);
                    DcHealthTracker.recordSuccess(location);
                    TemplateErrorTracker.recordSuccess(template.getName());
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(),
                            HetznerMetricProvider.OUTCOME_SUCCESS).inc();
                    return result;
                } catch (HetznerProvisioningException e) {
                    lastException = e;
                    final String nodeName = agent.getNodeName();
                    if (e.isRateLimited()) {
                        // Rate limit is token-scoped, not DC-scoped. All DCs share
                        // the same token; retrying another DC just wastes quota.
                        // isRateLimited() requires BOTH HTTP 429 AND code
                        // "rate_limit_exceeded" so instance_cap_reached (also 429)
                        // no longer false-aborts failover.
                        HetznerApiClient client = HetznerApiClient.forCredentials(cloud.getCredentialsId());
                        log.warn("Token rate-limited during provisioning "
                                + "(cloud={}, template={}, dc={}, node={}, "
                                + "remaining={}, resets in {}s); aborting failover",
                                cloud.name, template.getName(), location, nodeName,
                                client.getRemaining(), client.timeUntilReset().toSeconds());
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(),
                                HetznerMetricProvider.OUTCOME_RATE_LIMITED).inc();
                        throw e;
                    }
                    // Bare HTTP 429 with no/unrecognized error code: Hetzner
                    // returned a throttling status without enough information
                    // to classify it. Treat as token throttle (the dominant
                    // cause of 429) rather than recording a DC failure.
                    if (e.getHttpStatus() == 429
                            && !"instance_cap_reached".equals(e.getHetznerErrorCode())) {
                        log.warn("Unclassified HTTP 429 from Hetzner during provisioning "
                                + "(cloud={}, template={}, dc={}, node={}, code={}); "
                                + "treating as token throttle, aborting failover",
                                cloud.name, template.getName(), location, nodeName,
                                e.getHetznerErrorCode());
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(),
                                HetznerMetricProvider.OUTCOME_UNCLASSIFIED_THROTTLE).inc();
                        throw e;
                    }
                    if (e.isConfigError()) {
                        // Config errors are template-scoped, not DC-scoped.
                        // Trying another DC with the same bad image/config won't help.
                        TemplateErrorTracker.recordError(template.getName(), e.getMessage());
                        log.error("Template config error -- DC failover skipped; "
                                + "check Hetzner changelog for image deprecation "
                                + "(cloud={}, template={}, dc={}, node={}, image={}, "
                                + "code={}, msg={})",
                                cloud.name, template.getName(), location, nodeName,
                                template.getImage(), e.getHetznerErrorCode(), e.getMessage());
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(),
                                HetznerMetricProvider.OUTCOME_CONFIG_ERROR).inc();
                        throw e;
                    }
                    if ("instance_cap_reached".equals(e.getHetznerErrorCode())) {
                        // Cloud-level capacity bookkeeping (HTTP 429 with our own
                        // synthetic "instance_cap_reached" code from createServer's
                        // under-lock cap recheck). Not a DC health signal: another
                        // DC will hit the same cloud-wide cap. Skip DC failure
                        // recording so capacity bursts don't poison healthy DCs.
                        log.warn("Cloud cap reached during burst provisioning "
                                + "(cloud={}, template={}, dc={}, node={}); "
                                + "skipping DC health record and failover",
                                cloud.name, template.getName(), location, nodeName);
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(),
                                HetznerMetricProvider.OUTCOME_CAP_REACHED_UNDER_LOCK).inc();
                        throw e;
                    }
                    // Now categorize by whether this is DC-attributable. Auth /
                    // not-found / forbidden / unknown-client are NOT DC-scoped;
                    // recording them in DcHealthTracker would poison the breaker
                    // on credential outages or client misconfiguration. Codex
                    // post-merge review H2: previously recordFailure(location)
                    // ran unconditionally before this gate.
                    if (e.isPlausiblyDcAttributable()) {
                        DcHealthTracker.recordFailure(location);
                        final String dcOutcome = e.isResourceUnavailable()
                                ? HetznerMetricProvider.OUTCOME_DC_UNAVAILABLE
                                : HetznerMetricProvider.OUTCOME_DC_ATTRIBUTABLE;

                        // Failover gate: a compatible neighbour template lets us
                        // retry on another DC. The agent was built from the FIRST
                        // ranked template, so swapping to a template with different
                        // labels / connector / executors / remoteFs creates a
                        // server whose Jenkins-side metadata is wrong (wrong SSH
                        // credentials, wrong workspace, wrong queue matcher).
                        if (i < rankedTemplates.size() - 1) {
                            HetznerServerTemplate next = rankedTemplates.get(i + 1);
                            HetznerServerTemplate baseline = agent.getTemplate();
                            if (baseline != null && !baseline.isFailoverCompatibleWith(next)) {
                                log.warn("DC failover aborted -- next template is not "
                                        + "failover-compatible (differs on labels / executors / "
                                        + "remoteFs / mode / connector / connectionMethod) "
                                        + "(cloud={}, template={}, dc={}, node={}, "
                                        + "next_template={}, next_dc={}); "
                                        + "treating as final failure",
                                        cloud.name, baseline.getName(), location, nodeName,
                                        next.getName(), next.getLocation());
                                HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                        cloud.name, template.getName(),
                                        HetznerMetricProvider.OUTCOME_FAILOVER_INCOMPATIBLE).inc();
                                throw e;
                            }
                            log.warn("DC-attributable failure -- trying next DC "
                                    + "(cloud={}, template={}, dc={}, node={}, "
                                    + "status={}, code={}, attempt={}/{}, next_dc={})",
                                    cloud.name, template.getName(), location, nodeName,
                                    e.getHttpStatus(), e.getHetznerErrorCode(),
                                    i + 1, rankedTemplates.size(), next.getLocation());
                            HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                    cloud.name, template.getName(), dcOutcome).inc();
                            HetznerMetricProvider.DC_FAILOVER.labels(
                                    location != null ? location : "",
                                    next.getLocation() != null ? next.getLocation() : "").inc();
                            continue;
                        }
                        // Last template; DC outcome is still the right label.
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(), dcOutcome).inc();
                        throw e;
                    }
                    // Non-DC-attributable error (auth / not-found / unknown client).
                    // Do NOT call DcHealthTracker.recordFailure; the breaker only
                    // tracks DC-scoped issues.
                    log.warn("Non-DC-attributable Hetzner failure -- aborting "
                            + "(cloud={}, template={}, dc={}, node={}, "
                            + "status={}, code={}, msg={})",
                            cloud.name, template.getName(), location, nodeName,
                            e.getHttpStatus(), e.getHetznerErrorCode(), e.getMessage());
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(),
                            HetznerMetricProvider.OUTCOME_FAILURE).inc();
                    throw e;
                } catch (java.io.IOException ioe) {
                    // Launcher / SSH / remoting failures during the post-create
                    // bootstrap phase. These can be one-off (slow sshd boot) OR
                    // DC-level (network blip, DC-scoped firewall/ssh outage). We
                    // record a soft DC failure so chronic DC-level launcher rot
                    // can trip the breaker over time, while a single slow boot
                    // doesn't (threshold is 2 consecutive).
                    lastException = ioe;
                    DcHealthTracker.recordFailure(location);
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(),
                            HetznerMetricProvider.OUTCOME_BOOTSTRAP_IO).inc();
                    throw ioe;
                } catch (Exception other) {
                    // Non-IO bootstrap failures: arch mismatch, addNode failure,
                    // unexpected runtime. NOT recorded as DC failure - these are
                    // typically Jenkins-side or config issues, not DC health.
                    lastException = other;
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(),
                            HetznerMetricProvider.OUTCOME_BOOTSTRAP_OTHER).inc();
                    throw other;
                }
            }
            // Should not reach here, but just in case
            throw lastException != null ? lastException
                    : new IllegalStateException("No templates available for provisioning");
        } finally {
            // Decrement pending counter so subsequent cap checks are accurate.
            // Must run on both success and failure paths.
            cloud.provisionCompleted();
        }
    }

    /**
     * Wrap {@link #doProvision(HetznerServerTemplate)} with a Histogram timer
     * so per-attempt wall time is observable in Prometheus.
     *
     * Outcome label is one of:
     * <ul>
     *   <li>{@link HetznerMetricProvider#OUTCOME_SUCCESS} -- VM created and
     *       bootstrap completed.</li>
     *   <li>{@link HetznerMetricProvider#OUTCOME_PRECHECK_FAILURE} -- exited
     *       before any real boot work (Hetzner API rejected the create_server
     *       request or the under-lock cap recheck synthesized an error). Wall
     *       time is the API roundtrip, not the boot duration; split out so
     *       p50/p95 boot-duration dashboards are not skewed during throttle
     *       bursts. Opus post-merge review H1.</li>
     *   <li>{@link HetznerMetricProvider#OUTCOME_FAILURE} -- bootstrap was
     *       attempted (VM created, boot polled, maybe SSH'd) and failed.</li>
     * </ul>
     */
    private Node doProvisionAndTime(HetznerServerTemplate template) throws Exception {
        final long startNanos = System.nanoTime();
        String outcome = HetznerMetricProvider.OUTCOME_FAILURE;
        try {
            Node result = doProvision(template);
            outcome = HetznerMetricProvider.OUTCOME_SUCCESS;
            return result;
        } catch (HetznerProvisioningException e) {
            // The Hetzner API rejected the create_server request, or our own
            // under-lock cap recheck synthesized an error. No bootstrap work
            // was performed; record as precheck rather than mixing with real
            // boot durations.
            outcome = HetznerMetricProvider.OUTCOME_PRECHECK_FAILURE;
            throw e;
        } finally {
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            String dc = template.getLocation() != null ? template.getLocation() : "";
            HetznerMetricProvider.PROVISION_DURATION
                    .labels(cloud.name, template.getName(), dc, outcome).observe(seconds);
        }
    }

    /**
     * Provision a single server using the given template.
     * Extracted from the original call() method for retry support.
     */
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",
            justification = "Broad catch ensures leaked servers are destroyed on any failure type")
    private Node doProvision(HetznerServerTemplate template) throws Exception {
        // Pass the iteration template explicitly to createServer so DC failover
        // actually targets a different DC/image/server-type. agent.getTemplate()
        // is final and set to the FIRST ranked template; without this overload
        // the failover loop is cosmetic - every iteration would create a server
        // in the original DC regardless of which template the loop is on.
        final HetznerServerInfo serverInfo = cloud.getResourceManager().createServer(agent, template);
        final String serverName = serverInfo.getServerDetail().getName();
        // Track Jenkins-side state for cleanup: a ghost node would block queue
        // routing if we destroyed the Hetzner server but left the Node entry.
        boolean nodeAddedToJenkins = false;
        try {
            agent.setServerInstance(serverInfo);
            boolean running = false;
            final int bootDeadline = template.getBootDeadline();
            //wait for status == "running", but at most bootDeadline minutes
            final WaitStrategy waitStrategy = new WaitStrategy(bootDeadline, 45, 15);
            while (!waitStrategy.isDeadLineOver()) {
                waitStrategy.waitNext();
                HetznerApiClient bootClient = HetznerApiClient.forCredentials(cloud.getCredentialsId());
                if (bootClient.isRateLimited()) {
                    log.debug("Rate-limited, skipping boot status poll for '{}' (resets in {}s)",
                            serverName, bootClient.timeUntilReset().toSeconds());
                    continue;
                }
                if (agent.isAlive()) {
                    log.info("Server '{}' is now running, waiting 3 seconds before proceeding", serverName);
                    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);
                    running = true;
                    break;
                }
            }
            Preconditions.checkState(running,
                    "Server '%s' (id=%s) didn't reach 'running' state within %s minute(s), giving up",
                    serverName, serverInfo.getServerDetail().getId(), bootDeadline);

            // Option A: Pre-boot architecture validation via API response.
            validateArchitectureFromApi(template.getServerType(),
                    serverInfo.getServerDetail().getServerType(), serverName);

            Jenkins.get().addNode(agent);
            nodeAddedToJenkins = true;
            Computer computer = agent.toComputer();
            if (computer != null) {
                // One bounded connect attempt with the full remaining boot
                // budget. HetznerServerComputerLauncher already retries SSH
                // internally; an OUTER retry loop with Future.cancel(true)
                // risks leaving the prior launcher thread running while a
                // new computer.connect() starts a second one in parallel.
                // Codex review R2 finding: cancel(true) does not stop the
                // launcher; the safe pattern is a single timed attempt and
                // fail clean on timeout.
                long remainingMs = waitStrategy.remainingMillis();
                if (remainingMs <= 0) {
                    throw new IllegalStateException(String.format(
                            "Boot deadline expired before connect attempt for '%s' "
                            + "(server id=%s, cloud=%s, template=%s, dc=%s)",
                            computer.getName(), serverInfo.getServerDetail().getId(),
                            cloud.name, template.getName(), template.getLocation()));
                }
                java.util.concurrent.Future<?> connectFuture = computer.connect(false);
                try {
                    connectFuture.get(remainingMs, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    connectFuture.cancel(true);
                    throw new IllegalStateException(String.format(
                            "Connect to '%s' timed out after %dms "
                            + "(server id=%s, cloud=%s, template=%s, dc=%s); "
                            + "launcher cancelled, may briefly continue in background",
                            computer.getName(), remainingMs,
                            serverInfo.getServerDetail().getId(),
                            cloud.name, template.getName(), template.getLocation()), te);
                } catch (InterruptedException ie) {
                    connectFuture.cancel(true);
                    Thread.currentThread().interrupt();
                    throw ie;
                } catch (ExecutionException ee) {
                    // Unwrap IOException causes so the outer catch(IOException)
                    // records a soft DC bootstrap failure for breaker tracking.
                    // Codex review R3 finding: wrapping launcher IOException as
                    // IllegalStateException routed every SSH/launcher failure
                    // through catch(Exception other), which does NOT call
                    // DcHealthTracker.recordFailure - so chronic DC-scoped
                    // launcher rot could never trip the breaker.
                    // AbortException extends IOException, so it is covered.
                    Throwable cause = ee.getCause();
                    if (cause instanceof java.io.IOException) {
                        throw (java.io.IOException) cause;
                    }
                    throw new IllegalStateException(String.format(
                            "Connect to '%s' failed "
                            + "(server id=%s, cloud=%s, template=%s, dc=%s)",
                            computer.getName(),
                            serverInfo.getServerDetail().getId(),
                            cloud.name, template.getName(), template.getLocation()),
                            cause != null ? cause : ee);
                }
            } else {
                throw new IllegalStateException(
                        "No computer object in agent '" + agent.getDisplayName()
                        + "' (server id=" + serverInfo.getServerDetail().getId() + ")");
            }
            // Option C: Post-boot architecture validation via uname -m.
            validateArchitectureFromHardware(computer, template.getServerType(), serverName);

            return agent;
        } catch (Exception e) {
            // WARN, not ERROR: a single bootstrap failure is a recoverable
            // event (we destroy the VM, the autoscaler will replace). ERROR
            // is reserved for cases where cleanup also failed and an operator
            // needs to act. Codex review R2 logging finding. The full
            // exception (including stack) is passed as the trailing SLF4J
            // argument so the launcher's underlying cause is preserved in
            // logs even when this is the last catch on the path. Codex
            // post-merge review CV4 / L3.
            final String agentNodeName = agent.getNodeName();
            log.warn("Failed to bootstrap server -- attempting cleanup "
                    + "(cloud={}, template={}, dc={}, node={}, server_id={})",
                    cloud.name, template.getName(), template.getLocation(),
                    agentNodeName, serverInfo.getServerDetail().getId(), e);
            // destroyServer returns boolean: true on confirmed delete, false if
            // the underlying API call swallowed an exception. Branch metrics on
            // the actual outcome so PROVISION_LEAKED_SERVERS no longer over-
            // reports successful cleanups when Hetzner rejected the delete.
            boolean destroyed = cloud.getResourceManager().destroyServer(serverInfo.getServerDetail());
            if (destroyed) {
                log.warn("Destroyed leaked server "
                        + "(cloud={}, template={}, dc={}, node={}, server_id={})",
                        cloud.name, template.getName(), template.getLocation(),
                        agentNodeName, serverInfo.getServerDetail().getId());
                HetznerMetricProvider.PROVISION_LEAKED_SERVERS
                        .labels(cloud.name, template.getName()).inc();
            } else {
                log.error("Failed to destroy leaked server -- manual cleanup required "
                        + "(OrphanedNodesCleaner will retry) "
                        + "(cloud={}, template={}, dc={}, node={}, server_id={})",
                        cloud.name, template.getName(), template.getLocation(),
                        agentNodeName, serverInfo.getServerDetail().getId());
                HetznerMetricProvider.PROVISION_LEAK_DESTROY_FAILURES
                        .labels(cloud.name, template.getName()).inc();
            }
            // Ghost-node prevention: addNode() may have succeeded before connect/
            // hardware-validation failed. If so, remove the Jenkins Node so it
            // doesn't sit around offline blocking queue routing for its labels.
            // Also belt-and-suspenders: lookup by node name even when our flag
            // says it wasn't added, because addNode() can mutate Jenkins state
            // and then throw partway through save (Opus review M4).
            boolean shouldTryRemove = nodeAddedToJenkins
                    || (agentNodeName != null && Jenkins.get().getNode(agentNodeName) != null);
            if (shouldTryRemove) {
                try {
                    // Re-resolve via Jenkins.getNode in case our `agent` reference
                    // has been swapped or invalidated; tolerates already-removed.
                    // Identity check: only remove if it's still OUR agent (Opus
                    // post-merge M4: a heavily concurrent provisioning burst on a
                    // JCasC-managed instance could in principle race a same-name
                    // agent into existence; we should not delete someone else's
                    // node, even if collisions are astronomically unlikely given
                    // the random suffix).
                    hudson.model.Node existing = Jenkins.get().getNode(agentNodeName);
                    if (existing != null && existing == agent) {
                        Jenkins.get().removeNode(existing);
                        log.warn("Removed Jenkins node after bootstrap failure "
                                + "(cloud={}, template={}, dc={}, node={})",
                                cloud.name, template.getName(), template.getLocation(),
                                agentNodeName);
                    } else if (existing != null) {
                        log.warn("Skipping ghost-node removal: node with our name "
                                + "exists but is a different agent instance "
                                + "(cloud={}, template={}, node={})",
                                cloud.name, template.getName(), agentNodeName);
                    }
                } catch (Exception removeEx) {
                    // WARN rather than ERROR: the most common cause is a race
                    // where another path already removed the node. ERROR is
                    // reserved for confirmed cleanup failure where an operator
                    // must act.
                    log.warn("Could not remove Jenkins node after bootstrap failure "
                            + "(cloud={}, template={}, node={}): {}",
                            cloud.name, template.getName(), agentNodeName,
                            removeEx.getMessage(), removeEx);
                }
            }
            throw e;
        }
    }

    /**
     * Infer expected CPU architecture from Hetzner server type name.
     * Known ARM prefixes: cax (Ampere Altra). Known x86_64 prefixes: cx, cpx, ccx.
     * Anything else logs a WARNING and defaults to x86_64 so the post-boot uname
     * check (Option C) can still catch real mismatches without forcing a hard
     * failure here. Hetzner introduces new server-type families regularly; the
     * warning surfaces unknown prefixes in logs for an operator to add to the
     * known-set explicitly.
     *
     * @param serverType Hetzner server type name (e.g., "cax41", "cpx62")
     * @return "arm64" or "x86_64"
     */
    private static final java.util.Set<String> KNOWN_ARM_PREFIXES = java.util.Set.of("cax");
    private static final java.util.Set<String> KNOWN_X86_PREFIXES = java.util.Set.of("cx", "cpx", "ccx");
    /** Dedup the "unknown family" warning to log at most once per family-per-JVM. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> WARNED_FAMILIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static String inferArchFromServerType(String serverType) {
        if (serverType == null || serverType.isEmpty()) {
            return "x86_64";
        }
        // Extract the alphabetic family name up to the first digit. Earlier
        // versions used startsWith() which incorrectly classified "cxx11" as
        // "cx" (x86_64) - any future ARM family with a name like "cxg11"
        // would silently default to x86_64 instead of warning.
        String family = extractFamilyPrefix(serverType.toLowerCase(Locale.ROOT));
        if (family.isEmpty()) {
            // Dedup even the empty-family path; an all-digit / leading-hyphen
            // server type from misconfiguration should not flood logs.
            if (WARNED_FAMILIES.putIfAbsent("<empty>", Boolean.TRUE) == null) {
                log.warn("Server type '{}' has no alphabetic prefix; defaulting to x86_64 "
                        + "(this warning is logged once per JVM)", serverType);
            }
            return "x86_64";
        }
        if (KNOWN_ARM_PREFIXES.contains(family)) {
            return "arm64";
        }
        if (KNOWN_X86_PREFIXES.contains(family)) {
            return "x86_64";
        }
        // Bound the dedup map to prevent unbounded growth from adversarial
        // configs. 1024 distinct unknown families is far above any plausible
        // legitimate set; beyond that we silently default without logging.
        // Insert-then-check (rather than check-then-insert) so a burst of
        // concurrent unknown families cannot all race past size() < 1024 and
        // collectively push the map past the cap (Codex review R3 finding).
        if (WARNED_FAMILIES.putIfAbsent(family, Boolean.TRUE) == null) {
            if (WARNED_FAMILIES.size() <= 1024) {
                log.warn("Unknown Hetzner server type family '{}' (from '{}') - defaulting to x86_64. "
                        + "If this is an ARM family, update KNOWN_ARM_PREFIXES; the post-boot "
                        + "uname check is the safety net for now.", family, serverType);
            } else {
                // Insertion pushed us past the cap. Remove our entry so the
                // map stays bounded and a slot remains for a real future
                // unknown. The same family hitting this path again will
                // simply be silently defaulted.
                WARNED_FAMILIES.remove(family);
            }
        }
        return "x86_64";
    }

    /** Extracts the alphabetic prefix up to (but not including) the first digit. */
    private static String extractFamilyPrefix(String s) {
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i);
    }

    /**
     * Option A: Validate architecture from the Hetzner API response.
     * After server creation, the API returns the actual server_type which may
     * differ from what was requested during availability incidents.
     */
    private static void validateArchitectureFromApi(String requestedType,
                                                    ServerType actualType,
                                                    String serverName) {
        if (actualType == null || actualType.getName() == null) {
            log.warn("Cannot validate architecture for '{}': server_type not in API response", serverName);
            return;
        }
        String expectedArch = inferArchFromServerType(requestedType);
        String actualArch = inferArchFromServerType(actualType.getName());
        if (!expectedArch.equals(actualArch)) {
            // Use the parsed arch ({arm64, x86_64}) as the "actual" label
            // value. The raw server type name (e.g., cax41/cpx62) is bounded
            // but the parsed arch is more useful for alerting and matches
            // the hardware-phase label.
            HetznerMetricProvider.ARCH_VALIDATION_FAILURES
                    .labels("api", requestedType != null ? requestedType : "", actualArch).inc();
            throw new IllegalStateException(String.format(
                    "Architecture mismatch for server '%s': requested type '%s' (%s) "
                    + "but Hetzner provisioned type '%s' (%s). "
                    + "This may indicate a Hetzner availability incident.",
                    serverName, requestedType, expectedArch,
                    actualType.getName(), actualArch));
        }
        log.debug("Architecture validated for '{}': requested='{}' actual='{}' arch={}",
                serverName, requestedType, actualType.getName(), actualArch);
    }

    /**
     * Option C: Validate architecture from actual hardware via uname -m.
     * Runs after SSH connection is established. This is the ground-truth check
     * that catches mismatches the API might not report.
     */
    private static void validateArchitectureFromHardware(Computer computer,
                                                         String requestedType,
                                                         String serverName) {
        String expectedArch = inferArchFromServerType(requestedType);
        try {
            VirtualChannel channel = computer.getChannel();
            if (channel == null) {
                log.warn("Cannot validate hardware architecture for '{}': no remoting channel", serverName);
                return;
            }
            // uname -m returns: x86_64, aarch64, armv7l, etc.
            // Use exact normalized match instead of substring "arm" - the substring
            // form matches armv7l (32-bit ARM) and would also false-match any kernel
            // build string or path that happens to contain "arm".
            String uname = channel.call(new UnameCallable()).trim();
            String unameLower = uname.toLowerCase(Locale.ROOT);
            String hardwareArch;
            if ("aarch64".equals(unameLower) || "arm64".equals(unameLower)) {
                hardwareArch = "arm64";
            } else if ("x86_64".equals(unameLower) || "amd64".equals(unameLower)) {
                hardwareArch = "x86_64";
            } else {
                log.warn("Unrecognized 'uname -m' output '{}' on server '{}'; treating as x86_64 "
                        + "for compatibility. Verify the hardware architecture manually.", uname, serverName);
                hardwareArch = "x86_64";
            }
            if (!expectedArch.equals(hardwareArch)) {
                // Use the parsed arch ({arm64, x86_64}) as the "actual" label
                // value, NOT the raw `uname` output (which can include kernel
                // build strings / hostnames -> unbounded cardinality).
                HetznerMetricProvider.ARCH_VALIDATION_FAILURES
                        .labels("hardware", requestedType != null ? requestedType : "",
                                hardwareArch).inc();
                throw new IllegalStateException(String.format(
                        "Hardware architecture mismatch for server '%s': "
                        + "requested type '%s' (expected %s) but hardware reports '%s' (%s). "
                        + "Hetzner provisioned wrong architecture.",
                        serverName, requestedType, expectedArch, uname, hardwareArch));
            }
            log.info("Hardware architecture validated for '{}': uname={} expected={}", serverName, uname, expectedArch);
        } catch (IllegalStateException e) {
            throw e; // re-throw arch mismatch
        } catch (Exception e) {
            log.warn("Could not validate hardware architecture for '{}': {}", serverName, e.getMessage());
            // Don't fail the build for validation errors, only for confirmed mismatches
        }
    }

    /**
     * Remoting callable that executes uname -m on the agent.
     */
    private static final class UnameCallable extends jenkins.security.MasterToSlaveCallable<String, Exception> {
        private static final long serialVersionUID = 1L;

        @Override
        public String call() throws Exception {
            try {
                ProcessBuilder pb = new ProcessBuilder("uname", "-m");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                int exit = proc.waitFor();
                if (exit == 0 && !output.isEmpty()) {
                    return output;
                }
            } catch (IOException | InterruptedException e) {
                // Fall back to JVM property if uname is unavailable (e.g., Windows agent)
            }
            return System.getProperty("os.arch", "unknown");
        }
    }

    private static final class WaitStrategy {
        private final int firstInterval;
        private final int subsequentIntervals;
        private final long deadlineNanos;
        private boolean first = true;

        private WaitStrategy(int deadlineMinutes, int firstInterval, int subsequentIntervals) {
            deadlineNanos = System.nanoTime() + deadlineMinutes * 60L * 1_000_000_000L;
            this.firstInterval = firstInterval;
            this.subsequentIntervals = subsequentIntervals;
        }

        boolean isDeadLineOver() {
            return System.nanoTime() > deadlineNanos;
        }

        /** Milliseconds left before the deadline, or 0 if already past. */
        long remainingMillis() {
            long remainingNanos = deadlineNanos - System.nanoTime();
            return remainingNanos <= 0 ? 0L : remainingNanos / 1_000_000L;
        }

        void waitNext() {
            final int waitSeconds;
            if (first) {
                first = false;
                waitSeconds = firstInterval;
            } else {
                waitSeconds = subsequentIntervals;
            }
            Uninterruptibles.sleepUninterruptibly(waitSeconds, TimeUnit.SECONDS);
        }
    }
}
