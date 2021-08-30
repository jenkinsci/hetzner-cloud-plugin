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

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import hudson.model.Computer;
import hudson.model.Node;
import jenkins.model.Jenkins;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
class NodeCallable implements Callable<Node> {
    private final HetznerServerAgent agent;
    private final HetznerCloud cloud;

    @Override
    public Node call() throws Exception {
        Computer computer = agent.getComputer();
        if (computer != null && computer.isOnline()) {
            return agent;
        }
        final HetznerServerInfo serverInfo = cloud.getResourceManager().createServer(agent);
        agent.setServerInstance(serverInfo);
        final String serverName = serverInfo.getServerDetail().getName();
        boolean running = false;
        final int bootDeadline = agent.getTemplate().getBootDeadline();
        //wait for status == "running", but at most 15 minutes
        final WaitStrategy waitStrategy = new WaitStrategy(bootDeadline, 45, 15);
        while (!waitStrategy.isDeadLineOver()) {
            waitStrategy.waitNext();
            if (agent.isAlive()) {
                log.info("Server '{}' is now running, waiting 10 seconds before proceeding", serverName);
                Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS);
                running = true;
                break;
            }
        }
        Preconditions.checkState(running, "Server '%s' didn't start after 15 minutes, giving up",
                serverName);
        Jenkins.get().addNode(agent);
        computer = agent.toComputer();
        int retry = 5;
        boolean connected = false;
        if (computer != null) {
            while (--retry > 0) {
                try {
                    computer.connect(false).get();
                    connected = true;
                    break;
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("Connection to '{}' has failed, remaining retries {}", computer.getDisplayName(),
                            retry, e);
                    TimeUnit.SECONDS.sleep(10);
                }
            }
            if (!connected) {
                throw new IllegalStateException("Computer is not connected : " + computer.getName());
            }
        } else {
            throw new IllegalStateException("No computer object in agent " + agent.getDisplayName());
        }

        return agent;
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
