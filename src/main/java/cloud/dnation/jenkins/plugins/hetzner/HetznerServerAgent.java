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

import cloud.dnation.jenkins.plugins.hetzner.launcher.HetznerServerComputerLauncher;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import java.io.Serial;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class HetznerServerAgent extends AbstractCloudSlave implements EphemeralNode, TrackedItem {
    @Serial
    private static final long serialVersionUID = 1;
    private final ProvisioningActivity.Id provisioningId;
    @Getter
    private final transient HetznerCloud cloud;
    @Getter
    @NonNull
    private final transient HetznerServerTemplate template;
    @Getter(AccessLevel.PUBLIC)
    @Setter(AccessLevel.PACKAGE)
    private transient volatile HetznerServerInfo serverInstance;

    public HetznerServerAgent(@NonNull ProvisioningActivity.Id provisioningId,
                              @NonNull String name, String remoteFS, ComputerLauncher launcher,
                              @NonNull HetznerCloud cloud, @NonNull HetznerServerTemplate template)
            throws IOException, Descriptor.FormException {
        super(name, remoteFS, launcher);
        this.cloud = Objects.requireNonNull(cloud);
        this.template = Objects.requireNonNull(template);
        this.provisioningId = Objects.requireNonNull(provisioningId);
        setLabelString(template.getLabelStr());
        setNumExecutors(template.getNumExecutors());
        setMode(template.getMode() == null ? Mode.EXCLUSIVE : template.getMode());
        setRetentionStrategy(template.getShutdownPolicy().getRetentionStrategy());
        readResolve();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public AbstractCloudComputer createComputer() {
        return new HetznerServerComputer(this);
    }

    @Override
    public String getDisplayName() {
        try {
            if (serverInstance != null && serverInstance.getServerDetail() != null
                    && serverInstance.getServerDetail().getDatacenter() != null
                    && serverInstance.getServerDetail().getDatacenter().getLocation() != null) {
                return getNodeName() + " in " + serverInstance.getServerDetail()
                        .getDatacenter().getLocation().getDescription();
            }
        } catch (Exception e) {
            log.debug("Could not resolve display name for {}: {}", getNodeName(), e.getMessage());
        }
        return super.getDisplayName();
    }

    @Override
    protected void _terminate(TaskListener listener) {
        // Signal termination to the launcher. Guard against ClassCastException
        // after deserialization (launcher type may change).
        try {
            if (getLauncher() instanceof HetznerServerComputerLauncher hLauncher) {
                hLauncher.signalTermination();
            }
        } catch (Exception e) {
            log.warn("Failed to signal termination for node {}: {}", getNodeName(), e.getMessage());
        }

        // Destroy the Hetzner VM. Guard against null transient fields (cloud,
        // serverInstance) which are null after Jenkins restart/deserialization,
        // and against API failures that throw IllegalStateException.
        try {
            if (cloud == null) {
                log.error("Cannot destroy server for node {}: cloud reference is null "
                        + "(transient field lost after Jenkins restart). "
                        + "Server will be cleaned up by OrphanedNodesCleaner.", getNodeName());
            } else if (serverInstance == null || serverInstance.getServerDetail() == null) {
                log.error("Cannot destroy server for node {}: serverInstance is null "
                        + "(transient field lost after Jenkins restart). "
                        + "Server will be cleaned up by OrphanedNodesCleaner.", getNodeName());
            } else {
                cloud.getResourceManager().destroyServer(serverInstance.getServerDetail());
            }
        } catch (Exception e) {
            // Log but do NOT propagate. An unchecked exception here kills
            // ComputerRetentionWork's periodic timer, permanently stopping
            // idle cleanup for ALL nodes on this Jenkins instance.
            log.error("Failed to destroy server for node {} (id={}). "
                    + "Server will be cleaned up by OrphanedNodesCleaner.",
                    getNodeName(),
                    serverInstance != null && serverInstance.getServerDetail() != null
                            ? serverInstance.getServerDetail().getId() : "unknown",
                    e);
        }

        try {
            Optional.ofNullable(CloudStatistics.get().getActivityFor(this))
                    .ifPresent(a -> a.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED));
        } catch (Exception e) {
            log.debug("Failed to update CloudStatistics for {}: {}", getNodeName(), e.getMessage());
        }
    }

    @Override
    public Node asNode() {
        return this;
    }

    @NonNull
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    /**
     * Check if server associated with this agent is running.
     *
     * @return {@code true} if status of server is "running", {@code false} otherwise
     */
    public boolean isAlive() {
        try {
            if (cloud == null || cloud.getResourceManager() == null) {
                log.warn("Cannot check liveness for node {}: cloud reference is null "
                        + "(transient field lost after Jenkins restart)", getNodeName());
                return false;
            }
            if (serverInstance == null) {
                log.warn("Cannot check liveness for node {}: serverInstance is null", getNodeName());
                return false;
            }
            serverInstance = cloud.getResourceManager().refreshServerInfo(serverInstance);
            return serverInstance != null
                    && serverInstance.getServerDetail() != null
                    && "running".equals(serverInstance.getServerDetail().getStatus());
        } catch (Exception e) {
            log.error("Failed to check liveness for node {}: {}", getNodeName(), e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("unused")
    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.plugin_displayName();
        }

        public boolean isInstantiable() {
            return false;
        }
    }
}
