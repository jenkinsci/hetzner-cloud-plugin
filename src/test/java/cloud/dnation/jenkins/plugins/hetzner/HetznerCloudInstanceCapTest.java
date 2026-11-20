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
import cloud.dnation.jenkins.plugins.hetzner.launcher.AbstractHetznerSshConnector;
import com.google.common.collect.Lists;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The instance cap has to hold against a burst of demand, and the whole of it has to stay usable.
 *
 * <p>The fake cloud reproduces the timing that makes that hard: {@code createServer} takes
 * {@code CREATE_LATENCY_MS} and only then is the server in what {@code fetchAllServers} returns, as
 * in production, where {@code POST /servers} answers with the server already in state
 * {@code initializing} - a member of {@code RUNNABLE_STATE_SET}. Between the order and the answer
 * the machine is in no count at all; after it, it must be counted once, not once as running and
 * again as in flight.
 *
 * <p>A real Jenkins is used rather than a static mock of it, because Mockito scopes static mocks to
 * the registering thread and {@link NodeCallable} runs on {@code threadPoolForRemoting}.
 *
 * <p>See <a href="https://github.com/jenkinsci/hetzner-cloud-plugin/issues/85">issue #85</a>.
 */
@WithJenkins
class HetznerCloudInstanceCapTest {
    private static final int CAP = 4;
    private static final int CONCURRENT_CALLERS = 12;
    private static final long FETCH_LATENCY_MS = 50;
    private static final long CREATE_LATENCY_MS = 100;

    private JenkinsRule j;
    private HetznerCloudResourceManager rsrcMgr;
    private final AtomicInteger createdServers = new AtomicInteger();

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        createdServers.set(0);
        rsrcMgr = mock(HetznerCloudResourceManager.class);
        when(rsrcMgr.fetchAllServers(anyString())).thenAnswer(in -> {
            Thread.sleep(FETCH_LATENCY_MS);
            final List<ServerDetail> servers = new ArrayList<>();
            for (int i = 0; i < createdServers.get(); i++) {
                final ServerDetail detail = new ServerDetail();
                detail.setId(1000L + i);
                detail.setName("server-" + i);
                detail.setStatus("initializing");
                servers.add(detail);
            }
            return servers;
        });
        when(rsrcMgr.createServer(any())).thenAnswer(in -> {
            Thread.sleep(CREATE_LATENCY_MS);
            createdServers.incrementAndGet();
            throw new IllegalStateException("no API in this test");
        });
    }

    private HetznerCloud cloudWithCap(int cap) {
        final HetznerServerTemplate template = new HetznerServerTemplate("template-1", "java",
                "name=img1", "nbg1", "cx21");
        template.setConnector(mock(AbstractHetznerSshConnector.class));
        try (MockedStatic<HetznerCloudResourceManager> scope =
                     mockStatic(HetznerCloudResourceManager.class)) {
            scope.when(() -> HetznerCloudResourceManager.create(anyString())).thenReturn(rsrcMgr);
            return new HetznerCloud("hcloud-01", "mock-credentials", String.valueOf(cap),
                    Lists.newArrayList(template));
        }
    }

    private Cloud.CloudState state(int workload) {
        return new Cloud.CloudState(Label.get("java"), workload);
    }

    @Test
    void testSingleProvisionCallPlansExactlyTheCap() {
        final HetznerCloud cloud = cloudWithCap(CAP);

        final int planned = cloud.provision(state(20), 20).size();

        assertEquals(CAP, planned, "one provision() call for 20 executors planned " + planned
                + " machines against a cap of " + CAP);
    }

    @Test
    void testConcurrentProvisionCallsNeverPlanMoreMachinesThanTheCap() throws Exception {
        final HetznerCloud cloud = cloudWithCap(CAP);
        final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLERS);
        final CountDownLatch startGate = new CountDownLatch(1);
        final AtomicInteger planned = new AtomicInteger();
        try {
            final List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CALLERS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    final Collection<NodeProvisioner.PlannedNode> nodes = cloud.provision(state(1), 1);
                    planned.addAndGet(nodes.size());
                    return nodes.size();
                }));
            }
            startGate.countDown();
            for (Future<Integer> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(planned.get() <= CAP,
                CONCURRENT_CALLERS + " concurrent provision() calls planned " + planned.get()
                        + " machines against a cap of " + CAP + "; overshoot "
                        + (planned.get() - CAP));
        assertTrue(planned.get() > 0, "the cap must still be reachable, planned " + planned.get());
    }

    @Test
    void testWholeCapStaysUsableWhileEarlierMachinesAreStillBooting() {
        final HetznerCloud cloud = cloudWithCap(CAP);

        int planned = 0;
        for (int i = 0; i < CAP; i++) {
            planned += cloud.provision(state(1), 1).size();
        }

        assertEquals(CAP, planned, "machines still booting must be counted once, not twice; "
                + CAP + " successive calls planned only " + planned);
    }

    @Test
    void testReservationsAreReleasedWhenCreationFails() {
        final HetznerCloud cloud = cloudWithCap(CAP);

        final Collection<NodeProvisioner.PlannedNode> first = cloud.provision(state(CAP), CAP);

        assertEquals(CAP, first.size(), "the first burst must be allowed to fill the cap");
        first.forEach(n -> await().atMost(60, TimeUnit.SECONDS).until(n.future::isDone));
        await().atMost(30, TimeUnit.SECONDS).until(() -> cloud.getPendingProvisions() == 0);
    }
}
