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
import com.google.common.base.Strings;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class HetznerServerAgent extends AbstractCloudSlave implements EphemeralNode, TrackedItem {
    private static final long serialVersionUID = 1;
    private final ProvisioningActivity.Id provisioningId;
    @Getter
    private final transient HetznerCloud cloud;
    @Getter
    @NonNull
    private final transient HetznerServerTemplate template;
    @Getter(AccessLevel.PUBLIC)
    @Setter(AccessLevel.PACKAGE)
    private transient HetznerServerInfo serverInstance;

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
        setMode(Mode.EXCLUSIVE);
        if (Strings.isNullOrEmpty(template.getKeepAroundMinutes())) {
            setRetentionStrategy(HetznerConstants.DEFAULT_RETENTION_STRATEGY);
        } else {
            setRetentionStrategy(new CloudRetentionStrategy(Integer.parseInt(template.getKeepAroundMinutes())));
        }
        readResolve();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public AbstractCloudComputer createComputer() {
        return new HetznerServerComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        ((HetznerServerComputerLauncher) getLauncher()).signalTermination();
        cloud.getResourceManager().destroyServer(serverInstance.getServerDetail());
        Optional.ofNullable(CloudStatistics.get().getActivityFor(this))
                .ifPresent(a -> a.enterIfNotAlready(ProvisioningActivity.Phase.COMPLETED));
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Nonnull
    @Override
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    /**
     * Check if server associated with this agent is running.
     *
     * @return <code>true</code> if status of server is "running", <code>false</code> otherwise
     */
    public boolean isAlive() {
        serverInstance = cloud.getResourceManager().refreshServerInfo(serverInstance);
        return serverInstance.getServerDetail().getStatus().equals("running");
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
