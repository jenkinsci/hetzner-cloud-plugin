/*
 *     Copyright 2022 https://dnation.cloud
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * {@link ComputerListener} that is responsible to perform cleanup tasks when Jenkins' controller node
 * comes online or offline.
 */
@Slf4j
@Extension
public class ControllerListener extends ComputerListener {
    @Override
    public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
        //on controller startup, check for any orphan VMs in cloud
        if ("".equals(c.getName())) {
            OrphanedNodesCleaner.doCleanup();
        }
        super.onOnline(c, listener);
    }

    @Override
    public void onOffline(@NonNull Computer c, OfflineCause cause) {
        //on controller shutdown, terminate any existing Hetzner agent
        if ("".equals(c.getName())) {
            Helper.getHetznerAgents().forEach(this::terminateAgent);
        }
        super.onOffline(c, cause);
    }

    private void terminateAgent(HetznerServerAgent agent) {
        try {
            log.info("Terminating Hetzner agent {}", agent.getDisplayName());
            agent.terminate();
        } catch (Exception e) {
            log.error("Failed to terminate agent", e);
        }
    }
}
