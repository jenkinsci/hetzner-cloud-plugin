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
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * {@link ComputerListener} that is responsible to perform cleanup tasks when Jenkins' controller node
 * comes online or offline.
 */
@Slf4j
@Extension
public class ControllerListener extends ComputerListener {
    /**
     * Feature flag: when true, the controller-online orphan cleanup is
     * deferred by {@link #REHYDRATE_GRACE_PROP} minutes so the rehydrate path
     * ({@code HetznerWorkerRehydrator}) has time to re-adopt VMs before the
     * cleanup pass runs. Default false (immediate cleanup, upstream behavior).
     * PS-11173 Phase 4b.
     */
    static final String REHYDRATE_ENABLED_PROP = "hetzner.rehydrate.enabled";

    /**
     * Configurable grace, minutes. Only consulted when
     * {@link #REHYDRATE_ENABLED_PROP} is true. Default 5 minutes.
     */
    static final String REHYDRATE_GRACE_PROP = "hetzner.rehydrate.grace-period-minutes";

    private static final long DEFAULT_GRACE_MINUTES = 5L;

    @Override
    public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
        //on controller startup, check for any orphan VMs in cloud
        if (c.getName().isEmpty()) {
            if (Boolean.getBoolean(REHYDRATE_ENABLED_PROP)) {
                final long graceMinutes = Long.getLong(REHYDRATE_GRACE_PROP, DEFAULT_GRACE_MINUTES);
                log.info("Hetzner rehydrate enabled; deferring OrphanedNodesCleaner by {} minute(s) "
                        + "so HetznerWorkerRehydrator can re-adopt VMs first", graceMinutes);
                Timer.get().schedule(OrphanedNodesCleaner::doCleanup, graceMinutes, TimeUnit.MINUTES);
            } else {
                OrphanedNodesCleaner.doCleanup();
            }
        }
        super.onOnline(c, listener);
    }

    @Override
    public void onOffline(@NonNull Computer c, OfflineCause cause) {
        //on controller shutdown, terminate any existing Hetzner agent and computer
        if (c.getName().isEmpty()) {
            Helper.getHetznerAgents().forEach(this::terminateAgent);
            Arrays.stream(Jenkins.get().getComputers())
                    .filter(HetznerServerComputer.class::isInstance)
                    .forEach(this::deleteComputer);
        }
        super.onOffline(c, cause);
    }

    private void deleteComputer(Computer computer) {
        try {
            log.info("Deleting computer {}", computer);
            computer.doDoDelete();
        } catch (IOException e) {
            log.error("Failed to delete computer '{}'", computer.getName(), e);
        }
    }

    private void terminateAgent(HetznerServerAgent agent) {
        try {
            log.info("Terminating Hetzner agent {}", agent.getDisplayName());
            agent.terminate();
        } catch (Exception e) {
            log.error("Failed to terminate agent '{}'", agent.getDisplayName(), e);
        }
    }
}
