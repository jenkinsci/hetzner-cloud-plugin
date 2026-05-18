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

import cloud.dnation.hetznerclient.AbstractSearchResponse;
import cloud.dnation.hetznerclient.CreateServerFirewallsRequest;
import cloud.dnation.hetznerclient.CreateServerRequest;
import cloud.dnation.hetznerclient.CreateServerResponse;
import cloud.dnation.hetznerclient.CreateSshKeyRequest;
import cloud.dnation.hetznerclient.CreateSshKeyResponse;
import cloud.dnation.hetznerclient.GetFirewallsBySelectorResponse;
import cloud.dnation.hetznerclient.GetImagesBySelectorResponse;
import cloud.dnation.hetznerclient.GetNetworksBySelectorResponse;
import cloud.dnation.hetznerclient.GetPlacementGroupsResponse;
import cloud.dnation.hetznerclient.GetServerByIdResponse;
import cloud.dnation.hetznerclient.GetSshKeysBySelectorResponse;
import cloud.dnation.hetznerclient.HetznerApi;
import cloud.dnation.hetznerclient.IdentifiableResource;
import cloud.dnation.hetznerclient.PagedResourceHelper;
import cloud.dnation.hetznerclient.PublicNetRequest;
import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.hetznerclient.SshKeyDetail;
import cloud.dnation.jenkins.plugins.hetzner.connect.ConnectivityType;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cloud.dnation.jenkins.plugins.hetzner.Helper.assertSshKey;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.assertValidResponse;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.getPayload;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.getSSHPublicKeyFromPrivate;
import static cloud.dnation.jenkins.plugins.hetzner.HetznerConstants.LABEL_VALUE_PLUGIN;

@RequiredArgsConstructor
@Slf4j
public class HetznerCloudResourceManager {
    @NonNull
    private final String credentialsId;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static HetznerCloudResourceManager create(String credentialsId) {
        return new HetznerCloudResourceManager(credentialsId);
    }

    private static Map<String, String> createLabelsForServer(String cloudName) {
        final LinkedHashMap<String, String> ret = new LinkedHashMap<>();
        ret.put(HetznerConstants.LABEL_MANAGED_BY, LABEL_VALUE_PLUGIN);
        ret.put(HetznerConstants.LABEL_CLOUD_NAME, cloudName);
        return ret;
    }

    private static Map<String, String> createLabelsForSshKey(String credentialsId) {
        return ImmutableMap.<String, String>builder()
                .put(HetznerConstants.LABEL_MANAGED_BY, LABEL_VALUE_PLUGIN)
                .put(HetznerConstants.LABEL_CREDENTIALS_ID, credentialsId)
                .build();
    }

    /**
     * Build label expression to query SSH key for server in particular cloud.
     *
     * @param credentialsId ID of credentials for SSH access to server
     * @return label expression
     */
    @VisibleForTesting
    static String buildLabelExpressionForSshKey(String credentialsId) {
        return createLabelsForSshKey(credentialsId)
                .entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    // --- Caches (static, shared across all HetznerCloudResourceManager instances) ---
    // Keys are prefixed with credentialsId (Hetzner API token) to prevent cross-project collisions.

    // SSH key: "htzCredId:sshCredId" -> SshKeyDetail. Keys are immutable after creation.
    private static final Cache<String, SshKeyDetail> SSH_KEY_CACHE =
            CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).maximumSize(50)
                    .recordStats().build();

    // Label expression: "htzCredId:labelExpr" -> resource ID. Images/networks/firewalls/placement groups rarely change.
    private static final Cache<String, Long> LABEL_ID_CACHE =
            CacheBuilder.newBuilder().expireAfterWrite(15, TimeUnit.MINUTES).maximumSize(200)
                    .recordStats().build();

    // Server list: cloudName -> List<ServerDetail>. Short TTL; invalidated on create/destroy.
    // cloudName is unique per Jenkins cloud config, so no prefix needed.
    private static final Cache<String, List<ServerDetail>> SERVER_LIST_CACHE =
            CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).maximumSize(20)
                    .recordStats().build();

    /**
     * Return cache statistics for operational observability.
     * Callable from Jenkins Script Console via:
     *   println cloud.dnation.jenkins.plugins.hetzner.HetznerCloudResourceManager.getCacheStats()
     */
    public static String getCacheStats() {
        return String.format(
                "SSH_KEY_CACHE:  size=%d hits=%d misses=%d hitRate=%.1f%%%n"
              + "LABEL_ID_CACHE: size=%d hits=%d misses=%d hitRate=%.1f%%%n"
              + "SERVER_LIST:    size=%d hits=%d misses=%d hitRate=%.1f%%",
                SSH_KEY_CACHE.size(), SSH_KEY_CACHE.stats().hitCount(),
                SSH_KEY_CACHE.stats().missCount(), SSH_KEY_CACHE.stats().hitRate() * 100,
                LABEL_ID_CACHE.size(), LABEL_ID_CACHE.stats().hitCount(),
                LABEL_ID_CACHE.stats().missCount(), LABEL_ID_CACHE.stats().hitRate() * 100,
                SERVER_LIST_CACHE.size(), SERVER_LIST_CACHE.stats().hitCount(),
                SERVER_LIST_CACHE.stats().missCount(), SERVER_LIST_CACHE.stats().hitRate() * 100);
    }

    private HetznerApiClient apiClient() {
        return HetznerApiClient.forCredentials(credentialsId);
    }

    private HetznerApi proxy() {
        return apiClient().proxy();
    }

    /**
     * Throw if the API token is currently rate-limited. Prevents burning
     * API budget on calls that will certainly fail with HTTP 429.
     */
    private void checkRateLimit(String context) {
        HetznerApiClient client = apiClient();
        if (client.isRateLimited()) {
            long resetSeconds = client.timeUntilReset().toSeconds();
            log.info("Rate-limit guard blocked {} (remaining={}, resets in {}s)",
                    context, client.getRemaining(), resetSeconds);
            throw new HetznerProvisioningException(
                    String.format("Token rate-limited, skipping %s (resets in %ds)",
                            context, resetSeconds),
                    429, "rate_limit_exceeded", "token-global");
        }
    }

    /**
     * Attempt to obtain image ID based on label expression.
     * It's expected that provided label expression resolves to single image.
     *
     * @param labelExpression label expression used to filter image
     * @return image ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single image
     */
    private long getImageIdForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getImagesBySelector,
                GetImagesBySelectorResponse::getImages);
    }

    /**
     * Attempt to obtain network ID based on label expression.
     * It's expected that provided label expression resolves to single network.
     *
     * @param labelExpression label expression used to filter network
     * @return network ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single network
     */
    private long getNetworkIdForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getNetworkBySelector,
                GetNetworksBySelectorResponse::getNetworks);
    }

    /**
     * Attempt to obtain firewall ID based on label expression.
     * It's expected that provided label expression resolves to single firewall.
     *
     * @param labelExpression label expression used to filter firewall
     * @return firewall ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single firewall
     */
    private long getFirewallIdForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getFirewallsBySelector,
                GetFirewallsBySelectorResponse::getFirewalls);
    }

    /**
     * Attempt to obtain placement group based on label expression.
     * It's expected that provided label expression resolves to single placement group.
     *
     * @param labelExpression label expression used to filter placement group
     * @return placement group ID
     * @throws IOException              if fails to make API call
     * @throws IllegalStateException    if there was invalid response from API server
     * @throws IllegalArgumentException if label expression didn't yield single placement group
     */
    private long getPlacementGroupForLabelExpression(String labelExpression) throws IOException {
        return searchResourceByLabelExpression(labelExpression, proxy()::getPlacementGroups,
                GetPlacementGroupsResponse::getPlacementGroups);
    }

    private <R extends AbstractSearchResponse, I extends IdentifiableResource> long searchResourceByLabelExpression(
            String labelExpression,
            Function<String, Call<R>> searchFunction,
            Function<R, List<I>> getItemsFunction) throws IOException {
        // Cache key includes Hetzner API credential to prevent cross-project collisions
        final String cacheKey = credentialsId + ":" + labelExpression;

        Long cachedId = LABEL_ID_CACHE.getIfPresent(cacheKey);
        if (cachedId != null) {
            log.debug("Label expression cache hit: '{}' -> {}", labelExpression, cachedId);
            return cachedId;
        }

        log.debug("Label expression cache miss, querying API for '{}'", labelExpression);
        final Response<R> response = searchFunction.apply(labelExpression).execute();
        assertValidResponse(response);
        List<I> items = getPayload(response, getItemsFunction);
        Preconditions.checkArgument(items.size() == 1,
                "No exact match found for expression '%s', results %d",
                labelExpression, items.size());
        long id = Iterables.getOnlyElement(items).getId();
        LABEL_ID_CACHE.put(cacheKey, id);
        log.debug("Label expression cached: '{}' -> {}", labelExpression, id);
        return id;
    }

    /**
     * Destroy server.
     *
     * @param server server instance to remove from cloud
     * @return {@code true} if the server was successfully deleted, {@code false} otherwise.
     *         Callers should branch on the return value to distinguish real cleanup from
     *         silently-swallowed failures; the internal try/catch is required to protect
     *         periodic-timer callers, so exceptions never propagate up.
     */
    public boolean destroyServer(ServerDetail server) {
        final long serverId = server.getId();
        final HetznerApi client = proxy();
        try {
            // Initiate server power off
            assertValidResponse(client.powerOffServer(serverId).execute());
            log.info("Power off initiated for server with ID = {}, waiting for shutdown...", serverId);

            // Wait for server to be fully powered off
            boolean isShutdown = false;
            int attempts = 0;
            final int maxAttempts = 60; // Maximum wait time: 60 * 5s = 300s (5 minutes)

            while (!isShutdown && attempts < maxAttempts) {
                attempts++;
                try {
                    Thread.sleep(5000); // Wait 5 seconds between checks

                    if (apiClient().isRateLimited()) {
                        log.warn("Token rate-limited during shutdown wait for server {}, skipping poll", serverId);
                        break;
                    }

                    Response<GetServerByIdResponse> response = client.getServer(serverId).execute();
                    if (response.isSuccessful() && response.body() != null
                            && response.body().getServer() != null) {
                        ServerDetail currentState = response.body().getServer();
                        if ("off".equals(currentState.getStatus())) {
                            isShutdown = true;
                            log.info("Server with ID = {} is now powered off, proceeding with deletion", serverId);
                        } else {
                            log.debug("Server with ID = {} is still in '{}' status, waiting...",
                                    serverId, currentState.getStatus());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for server shutdown", e);
                    break;
                }
            }

            if (!isShutdown) {
                log.warn("Server with ID = {} did not power off within expected time, proceeding with deletion anyway", serverId);
            }

            // Delete the server
            assertValidResponse(client.deleteServer(serverId).execute());
            log.info("Server with ID = {} successfully deleted", serverId);
            SERVER_LIST_CACHE.invalidateAll();
            log.debug("Server list cache invalidated after destroyServer (id={})", serverId);
            return true;
        } catch (Exception e) {
            // Catch ALL exceptions (IOException, IllegalStateException from
            // assertValidResponse on HTTP 429/412, and any other RuntimeException).
            // This method is called from _terminate() and OrphanedNodesCleaner,
            // both running inside periodic timers. An unchecked exception here
            // kills the timer thread permanently, disabling idle cleanup for
            // ALL nodes on this Jenkins instance.
            // The OrphanedNodesCleaner will retry deletion on its next hourly run.
            log.error("Unable to destroy server with ID = {} (name={}). "
                    + "Server may become orphaned and will be retried by OrphanedNodesCleaner.",
                    serverId, server.getName(), e);
            return false;
        }
    }

    /**
     * Get or create SSH key in cloud for given credentialsId derived from server template.
     *
     * @param template instance of {@link HetznerServerTemplate} to get credentialsId from
     * @return SshKeyDetail
     */
    private SshKeyDetail getOrCreateSshKey(HetznerServerTemplate template) throws IOException {
        final String sshCredId = template.getConnector().getSshCredentialsId();
        final String cacheKey = credentialsId + ":" + sshCredId;

        // Check cache first (SSH keys are immutable after creation)
        SshKeyDetail cached = SSH_KEY_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("SSH key cache hit for {}", cacheKey);
            return cached;
        }

        checkRateLimit("getOrCreateSshKey");
        final HetznerApi client = proxy();

        final BasicSSHUserPrivateKey privateKey = assertSshKey(sshCredId);
        final Response<GetSshKeysBySelectorResponse> searchResponse = client.getSshKeysBySelector(
                        buildLabelExpressionForSshKey(sshCredId))
                .execute();
        assertValidResponse(searchResponse);
        final List<SshKeyDetail> sshKeys = getPayload(searchResponse, GetSshKeysBySelectorResponse::getSshKeys);

        SshKeyDetail result;
        if (!sshKeys.isEmpty()) {
            result = Iterables.getOnlyElement(sshKeys);
        } else {
            final String publicKey = getSSHPublicKeyFromPrivate(privateKey.getPrivateKey(),
                    Secret.toString(privateKey.getPassphrase()));
            final Response<CreateSshKeyResponse> createResponse = proxy().createSshKey(new CreateSshKeyRequest()
                            .labels(createLabelsForSshKey(sshCredId))
                            .name(sshCredId)
                            .publicKey(publicKey))
                    .execute();
            result = assertValidResponse(createResponse, CreateSshKeyResponse::getSshKey);
        }

        SSH_KEY_CACHE.put(cacheKey, result);
        log.debug("SSH key cached for {} (id={})", cacheKey, result.getId());
        return result;
    }

    /**
     * Update {@link HetznerServerInfo} with fresh data from cloud.
     *
     * @param info server info
     * @return ServerDetail
     * @throws IllegalArgumentException if server didn't respond with code HTTP200
     * @throws IllegalStateException    if API call fails
     */
    public HetznerServerInfo refreshServerInfo(HetznerServerInfo info) throws IOException {
        final Response<GetServerByIdResponse> response = proxy().getServer(info.getServerDetail().getId())
                .execute();
        info.setServerDetail(assertValidResponse(response, GetServerByIdResponse::getServer));
        return info;
    }

    @SneakyThrows
    private void configurePrivateNetwork(CreateServerRequest req, String network) {
        if (!Strings.isNullOrEmpty(network)) {
            final long networkId;
            if (Helper.isPossiblyLong(network)) {
                networkId = Long.parseLong(network);
            } else {
                networkId = getNetworkIdForLabelExpression(network);
            }
            req.setNetworks(Lists.newArrayList(networkId));
        }
    }

    @VisibleForTesting
    static void customizeNetworking(ConnectivityType ct, CreateServerRequest req, String network,
                             BiConsumer<CreateServerRequest, String> privateNetConfig) throws IOException {
        req.setPublicNet(new PublicNetRequest());
        req.getPublicNet().setEnableIpv4(false);
        req.getPublicNet().setEnableIpv6(false);
        switch (ct) {
            case PRIVATE:
                privateNetConfig.accept(req, network);
                break;

            case BOTH:
                req.getPublicNet().setEnableIpv4(true);
                req.getPublicNet().setEnableIpv6(true);
                privateNetConfig.accept(req, network);
                break;

            case BOTH_V6:
                req.getPublicNet().setEnableIpv6(true);
                privateNetConfig.accept(req, network);
                break;

            case PUBLIC:
                req.getPublicNet().setEnableIpv4(true);
                req.getPublicNet().setEnableIpv6(true);
                break;

            case PUBLIC_V6:
                req.getPublicNet().setEnableIpv6(true);
                break;

            default:
                throw new IllegalArgumentException("Unknown connectivity type: " + ct);
        }
    }

    /**
     * Create new server instance.
     *
     * @param agent agent instance
     * @return instance of {@link CreateServerResponse}
     */
    public HetznerServerInfo createServer(HetznerServerAgent agent) {
        // Backward-compatible delegate: use the agent's own template. NodeCallable's
        // DC failover loop must call the (agent, template) overload to actually
        // try a different template/DC per iteration; otherwise the iteration is
        // cosmetic because the agent's template is final.
        return createServer(agent, agent.getTemplate());
    }

    /**
     * Create a server using the supplied {@code template} for image/location/
     * server-type/network/etc., while {@code agent} provides node identity
     * (node name, cloud reference). Required by the DC failover loop in
     * NodeCallable, where the agent is built once with the first ranked
     * template but subsequent iterations must target different templates.
     */
    public HetznerServerInfo createServer(HetznerServerAgent agent, HetznerServerTemplate template) {
        checkRateLimit("createServer");
        try {
            lock.writeLock().lock();

            // Recheck instance cap under write lock to prevent over-provisioning.
            // The initial cap check in HetznerCloud.provision() is stale by the
            // time this lock is acquired; concurrent NodeCallables may have created
            // servers since then.
            String cloudName = template.getCloud().name;
            int instanceCap = template.getCloud().getInstanceCap();
            if (instanceCap > 0) {
                SERVER_LIST_CACHE.invalidate(cloudName);
                long running = fetchAllServers(cloudName).stream()
                        .filter(sd -> HetznerConstants.RUNNABLE_STATE_SET.contains(sd.getStatus()))
                        .count();
                if (running >= instanceCap) {
                    throw new HetznerProvisioningException(
                            String.format("Instance cap reached under lock: %d running >= %d cap "
                                    + "(cloud=%s)", running, instanceCap, cloudName),
                            429, "instance_cap_reached", template.getLocation());
                }
            }

            final SshKeyDetail sshKey = getOrCreateSshKey(template);
            final String imageId;
            //check if image is label expression
            if (template.getImage().contains("=")) {
                //if so, query image ID
                imageId = String.valueOf(getImageIdForLabelExpression(template.getImage()));
            } else {
                imageId = template.getImage();
            }

            final CreateServerRequest createServerRequest = new CreateServerRequest();
            if (template.isAutomountVolumes()) {
                createServerRequest.setAutomount(true);
            }
            if (!Strings.isNullOrEmpty(template.getVolumeIds())) {
                createServerRequest.setVolumes(Helper.idList(template.getVolumeIds()));
            }
            final ConnectivityType ct = template.getConnectivity().getType();
            customizeNetworking(ct, createServerRequest, template.getNetwork(),
                    this::configurePrivateNetwork);
            final String placementGroup = template.getPlacementGroup();
            if (!Strings.isNullOrEmpty(placementGroup)) {
                if(Helper.isPossiblyLong(placementGroup)) {
                    createServerRequest.setPlacementGroup(Long.parseLong(placementGroup));
                } else {
                    createServerRequest.setPlacementGroup(getPlacementGroupForLabelExpression(placementGroup));
                }
            }
            final String firewall = template.getFirewall();
            if (!Strings.isNullOrEmpty(template.getFirewall())) {
                if(Helper.isPossiblyLong(firewall)) {
                    createServerRequest.setFirewalls(List.of(new CreateServerFirewallsRequest().
                            firewall(Long.parseLong(firewall))));
                } else {
                    createServerRequest.setFirewalls(List.of(new CreateServerFirewallsRequest().
                            firewall(getFirewallIdForLabelExpression(firewall))));
                }
            }
            if (!Strings.isNullOrEmpty(template.getUserData())) {
                createServerRequest.setUserData(template.getUserData());
            }
            createServerRequest.setServerType(template.getServerType());
            createServerRequest.setImage(imageId);
            createServerRequest.setName(agent.getNodeName());
            createServerRequest.setSshKeys(Collections.singletonList(sshKey.getName()));
            if (template.getLocation().contains("-")) {
                createServerRequest.setDatacenter(template.getLocation());
            } else {
                createServerRequest.setLocation(template.getLocation());
            }
            createServerRequest.setLabels(createLabelsForServer(template.getCloud().name));
            if (ct == ConnectivityType.BOTH || ct == ConnectivityType.PUBLIC_V6 || ct == ConnectivityType.PUBLIC) {
                Optional.of(template.getPrimaryIp()).ifPresent(ip -> ip.apply(proxy(), createServerRequest));
            }
            log.debug("Calling API to create server resource : {}", createServerRequest);
            final Response<CreateServerResponse> createServerResponse = proxy().createServer(createServerRequest)
                    .execute();
            if (!createServerResponse.isSuccessful()) {
                String errorBody = null;
                try {
                    if (createServerResponse.errorBody() != null) {
                        errorBody = createServerResponse.errorBody().string();
                    }
                } catch (IOException ignored) {
                    // best effort
                }
                String errorCode = Helper.parseHetznerErrorCode(errorBody);
                String location = template.getLocation();
                throw new HetznerProvisioningException(
                        String.format("Hetzner API error creating server in %s: HTTP %d, code=%s, body=%s",
                                location, createServerResponse.code(), errorCode, errorBody),
                        createServerResponse.code(),
                        errorCode,
                        location);
            }
            final HetznerServerInfo info = new HetznerServerInfo(sshKey);
            info.setServerDetail(assertValidResponse(createServerResponse, CreateServerResponse::getServer));
            log.info("Server created: name={}, id={}, dc={}, type={} (remaining={})",
                    info.getServerDetail().getName(), info.getServerDetail().getId(),
                    template.getLocation(), template.getServerType(),
                    apiClient().getRemaining());
            // Invalidate server list cache so runningNodeCount() sees the new server
            SERVER_LIST_CACHE.invalidate(cloudName);
            log.debug("Server list cache invalidated for cloud={} after createServer", cloudName);
            return info;
        } catch (HetznerProvisioningException e) {
            throw e; // propagate typed exception without wrapping
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ServerDetail> fetchAllServers(String cloudName) throws IOException {
        List<ServerDetail> cached = SERVER_LIST_CACHE.getIfPresent(cloudName);
        if (cached != null) {
            log.debug("Server list cache hit for cloud={} ({} servers)", cloudName, cached.size());
            return cached;
        }

        checkRateLimit("fetchAllServers");
        final String selector = createLabelsForServer(cloudName).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        final List<ServerDetail> raw = PagedResourceHelper.getAllServers(proxy(), selector);
        final List<ServerDetail> servers = Collections.unmodifiableList(dedupServersByName(raw));
        SERVER_LIST_CACHE.put(cloudName, servers);
        if (raw.size() != servers.size()) {
            log.debug("Server list fetched and cached for cloud={} ({} raw, {} unique after dedup, remaining={})",
                    cloudName, raw.size(), servers.size(), apiClient().getRemaining());
        } else {
            log.debug("Server list fetched and cached for cloud={} ({} servers, remaining={})",
                    cloudName, servers.size(), apiClient().getRemaining());
        }
        return servers;
    }

    /**
     * Deduplicate a server list by {@code name}, preserving first occurrence.
     *
     * <p>Workaround for the upstream
     * <a href="https://github.com/dNationCloud/hetzner-cloud-client-java/blob/release/1.12.0/src/main/java/cloud/dnation/hetznerclient/PagedResourceHelper.java">PagedResourceHelper</a>
     * pagination bug (present in our pinned 1.10.0 AND in latest 1.12.0):
     * the loop starts page index at 0, but Hetzner's API uses
     * 1-based pagination, so {@code page=0} returns page 1 and the next
     * iteration {@code page=1} returns page 1 again, duplicating every record
     * on the first page. Observed in production on cloud.cd as 35 unique
     * servers returned 60 raw entries, inflating
     * {@code hetzner_running_servers} by ~1.7x and silently rejecting
     * legitimate provisions when the inflated count crossed
     * {@code instance_cap} (false {@code cap_reached_under_lock} outcomes).
     *
     * <p>Dedup here is the single source of truth for every downstream
     * consumer (runningNodeCount, OrphanedNodesCleaner, the under-lock
     * cap recheck at {@link #createServer}, the metrics refresher) so one
     * fix closes every path. {@code LinkedHashMap.putIfAbsent} preserves
     * insertion order so the first occurrence wins.
     *
     * <p>Hetzner enforces server-name uniqueness per project, so name is a
     * safe dedup key. Null entries and null names are filtered defensively;
     * a malformed entry in the API response should not blow up the metrics
     * tick or the orphan-cleanup scan.
     *
     * <p>Package-private for direct unit-test access (no need to mock the
     * upstream {@code PagedResourceHelper} or the {@code SERVER_LIST_CACHE}).
     */
    static List<ServerDetail> dedupServersByName(List<ServerDetail> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        final Map<String, ServerDetail> byName = new LinkedHashMap<>();
        for (ServerDetail s : raw) {
            if (s != null && s.getName() != null) {
                byName.putIfAbsent(s.getName(), s);
            }
        }
        return new ArrayList<>(byName.values());
    }
}
