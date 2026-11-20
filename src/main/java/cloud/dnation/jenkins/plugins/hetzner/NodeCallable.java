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

import cloud.dnation.hetznerclient.ActionDetail;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import hudson.model.Computer;
import hudson.model.Node;
import jenkins.model.Jenkins;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
class NodeCallable implements Callable<Node> {
    private final HetznerServerAgent agent;
    private final HetznerCloud cloud;
    private final Runnable creationFinished;

    @Override
    public Node call() throws Exception {
        Computer computer = agent.getComputer();
        final HetznerServerInfo serverInfo;
        try {
            if (computer != null && computer.isOnline()) {
                return agent;
            }
            serverInfo = cloud.getResourceManager().createServer(agent);
        } finally {
            creationFinished.run();
        }
        agent.setServerInstance(serverInfo);
        final String serverName = serverInfo.getServerDetail().getName();
        final int bootDeadlineMinutes = agent.getTemplate().getBootDeadline();
        final ActionDetail createAction = serverInfo.getCreateAction();
        Preconditions.checkState(createAction != null,
                "Server '%s' was created but no action was returned by the API", serverName);
        final long deadline = System.currentTimeMillis() + bootDeadlineMinutes * 60L * 1000L;
        final List<ActionDetail> actions = new ArrayList<>();
        actions.add(createAction);
        actions.addAll(serverInfo.getNextActions());
        log.info("Server '{}' created, waiting for actions {} to complete", serverName,
                actions.stream().map(a -> a.getCommand() + "#" + a.getId()).collect(Collectors.toList()));
        // create_server succeeds as soon as the server exists; start_server (and network
        // attachments) are separate follow-up actions returned in next_actions.
        for (ActionDetail action : actions) {
            cloud.getResourceManager().waitForAction(action.getId(), remaining(deadline), 5_000);
        }
        cloud.getResourceManager().refreshServerInfo(serverInfo);
        final String status = serverInfo.getServerDetail() != null ? serverInfo.getServerDetail().getStatus() : null;
        Preconditions.checkState("running".equals(status),
                "Server '%s' actions completed but server is not running (status: %s)", serverName, status);
        log.info("Server '{}' is now running, waiting 10 seconds before proceeding", serverName);
        Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS);
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

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.currentTimeMillis());
    }
}
