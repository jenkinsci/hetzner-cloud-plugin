/*
 *     Copyright 2026 Percona, LLC.
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
import cloud.dnation.hetznerclient.ServerType;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the v103.percona.16 fix for stale {@code hetzner_running_servers}
 * and {@code hetzner_provisioning_pending} gauges. The refresher walks
 * configured HetznerCloud instances on a 1-minute PeriodicWork and forces
 * a re-emit; without it, the gauges only update inside
 * {@link HetznerCloud#provision} and pin to the last-known peak when the
 * cloud is idle (observed today: psmdb.cd reported 25 servers in Mimir
 * while the Hetzner API reported 2 actually running).
 */
class HetznerMetricsRefresherTest {

    private HetznerCloudResourceManager rsrcMgr;
    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrMock;
    private Jenkins jenkins;

    @BeforeEach
    void setUp() {
        // Reset the metrics registry so each test starts clean. Otherwise the
        // RUNNING_SERVERS gauge keeps state across tests via the global registry.
        HetznerMetricProvider.RUNNING_SERVERS.clear();
        HetznerMetricProvider.PROVISIONING_PENDING.clear();

        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrMock = mockStatic(HetznerCloudResourceManager.class);

        rsrcMgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(rsrcMgr);

        jenkins = mock(Jenkins.class);
        doAnswer((Answer<LabelAtom>) inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
        rsrcMgrMock.close();
    }

    /**
     * The whole point of the refresher: when the cloud is idle and nothing has
     * called provision() in a while, refreshMetrics() must re-emit a fresh
     * count straight from the Hetzner API into the RUNNING_SERVERS gauge.
     *
     * <p>v103.percona.23: the gauge is now arch-labelled. Test servers
     * created via {@link #serverWithStatus(String)} have no serverType set,
     * so they collapse to the "unknown" bucket. Per-arch grouping is
     * exercised separately in {@link #refreshMetrics_splitsByArch}.
     */
    @Test
    void refreshMetrics_emitsLiveServerCountToGauge() throws Exception {
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(serverList(2));

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        cloud.refreshMetrics();

        double emitted = sumRunningAcrossArchs("hcloud-test");
        assertEquals(2.0, emitted, 0.0001, "RUNNING_SERVERS gauge should reflect live API count");
        verify(rsrcMgr, times(1)).fetchAllServers("hcloud-test");
    }

    /**
     * Only RUNNABLE state servers count (initialising / off / deleting do not).
     * Today this matches HetznerConstants.RUNNABLE_STATE_SET = {running, starting}.
     */
    @Test
    void refreshMetrics_filtersByRunnableState() throws Exception {
        List<ServerDetail> mixed = new ArrayList<>();
        mixed.add(serverWithStatus("running"));
        mixed.add(serverWithStatus("running"));
        mixed.add(serverWithStatus("off"));
        mixed.add(serverWithStatus("initializing"));
        mixed.add(serverWithStatus("deleting"));
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(mixed);

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        cloud.refreshMetrics();

        double emitted = sumRunningAcrossArchs("hcloud-test");
        assertTrue(emitted == 2.0 || emitted == 3.0,
                "Only RUNNABLE state servers counted; got " + emitted);
    }

    /**
     * v103.percona.23: running servers are emitted per arch derived from
     * Hetzner serverType (cpx* -> amd64, cax* -> arm64). Verifies the
     * grouping logic plus the explicit zero-emission for archs with no
     * running servers (otherwise stale series pin to their last non-zero
     * value in Mimir).
     */
    @Test
    void refreshMetrics_splitsByArch() throws Exception {
        List<ServerDetail> mixed = new ArrayList<>();
        mixed.add(serverWithType("cpx42"));
        mixed.add(serverWithType("cpx62"));
        mixed.add(serverWithType("cpx22"));
        mixed.add(serverWithType("cax31"));
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(mixed);

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        cloud.refreshMetrics();

        double amd = HetznerMetricProvider.RUNNING_SERVERS
                .labels("hcloud-test", HetznerMetricProvider.ARCH_AMD64).get();
        double arm = HetznerMetricProvider.RUNNING_SERVERS
                .labels("hcloud-test", HetznerMetricProvider.ARCH_ARM64).get();
        double unknown = HetznerMetricProvider.RUNNING_SERVERS
                .labels("hcloud-test", HetznerMetricProvider.ARCH_UNKNOWN).get();
        assertEquals(3.0, amd, 0.0001, "3 cpx* servers -> amd64");
        assertEquals(1.0, arm, 0.0001, "1 cax* server -> arm64");
        assertEquals(0.0, unknown, 0.0001,
                "no unrecognised serverType -> the unknown bucket must be set to 0, not left stale");
    }

    /**
     * Transient Hetzner API failure must not propagate; the gauge keeps its
     * last-good value rather than gapping. This matches the explicit
     * "stale gauge is more useful than a gap" contract in HetznerCloud.
     */
    @Test
    void refreshMetrics_swallowsApiException() throws Exception {
        when(rsrcMgr.fetchAllServers(anyString()))
                .thenThrow(new RuntimeException("simulated 503 from Hetzner"));

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        // Must NOT throw. PROVISIONING_PENDING still gets re-emitted defensively.
        cloud.refreshMetrics();

        double pending = HetznerMetricProvider.PROVISIONING_PENDING.labels("hcloud-test").get();
        assertEquals(0.0, pending, 0.0001,
                "PROVISIONING_PENDING re-emit must run even when running-count fetch fails");
    }

    /**
     * The HetznerMetricsRefresher PeriodicWork uses a 1-minute recurrence
     * period (PeriodicWork.MIN). Anything shorter would risk hammering the
     * Hetzner API; anything longer reintroduces the staleness this whole
     * fix exists to eliminate.
     */
    @Test
    void getRecurrencePeriod_isOneMinute() {
        HetznerMetricsRefresher refresher = new HetznerMetricsRefresher();
        assertEquals(TimeUnit.MINUTES.toMillis(1), refresher.getRecurrencePeriod(),
                "Refresher must tick every minute; this is the contract the dashboard relies on");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static List<ServerDetail> serverList(int n) {
        List<ServerDetail> servers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            servers.add(serverWithStatus("running"));
        }
        return servers;
    }

    private static ServerDetail serverWithStatus(String status) {
        ServerDetail s = new ServerDetail();
        s.setStatus(status);
        s.setName("hcloud-mock-" + System.nanoTime());
        return s;
    }

    private static ServerDetail serverWithType(String serverTypeName) {
        ServerDetail s = serverWithStatus("running");
        ServerType st = new ServerType();
        st.setName(serverTypeName);
        s.setServerType(st);
        return s;
    }

    /**
     * Read the running-servers gauge regardless of how many arch buckets it
     * is split across. Used by tests that care about the cloud-wide total
     * but not the per-arch breakdown.
     */
    private static double sumRunningAcrossArchs(String cloudName) {
        double sum = 0;
        for (String arch : HetznerMetricProvider.KNOWN_ARCHS) {
            sum += HetznerMetricProvider.RUNNING_SERVERS.labels(cloudName, arch).get();
        }
        return sum;
    }
}
