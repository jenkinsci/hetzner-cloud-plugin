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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Objects;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class HetznerCloud extends AbstractCloudImpl {
    @Getter
    private final String credentialsId;
    @Getter
    private List<HetznerServerTemplate> serverTemplates;
    @Getter
    private transient HetznerCloudResourceManager resourceManager;
    /**
     * Servers ordered by this loop whose {@code createServer} call has not returned yet, and which
     * are therefore absent from {@link #runningNodeCount()}. Creation is submitted to a thread pool
     * (see {@link NodeCallable}), so without this the loop races its own orders. See issue #85.
     */
    private transient AtomicInteger pendingProvisions;

    @DataBoundConstructor
    public HetznerCloud(String name, String credentialsId, String instanceCapStr,
                        List<HetznerServerTemplate> serverTemplates) {
        super(name, instanceCapStr);
        this.credentialsId = credentialsId;
        this.serverTemplates = serverTemplates;
        readResolve();
    }

    /**
     * Pick random template from provided list.
     *
     * @param matchingTemplates List of all matching templates.
     * @return picked template
     */
    private static HetznerServerTemplate pickTemplate(List<HetznerServerTemplate> matchingTemplates) {
        if (matchingTemplates.size() == 1) {
            return matchingTemplates.get(0);
        }
        final List<HetznerServerTemplate> shuffled = new ArrayList<>(matchingTemplates);
        Collections.shuffle(shuffled);
        return shuffled.get(0);
    }

    @DataBoundSetter
    public void setServerTemplates(List<HetznerServerTemplate> serverTemplates) {
      this.serverTemplates = Objects.requireNonNullElse(serverTemplates, Collections.emptyList());
        readResolve();
    }

    private AtomicInteger pending() {
        return pendingProvisions;
    }

    protected Object readResolve() {
        resourceManager = HetznerCloudResourceManager.create(credentialsId);
        pendingProvisions = new AtomicInteger();
        if (serverTemplates == null) {
            setServerTemplates(Collections.emptyList());
        }
        for (HetznerServerTemplate template : serverTemplates) {
            template.setCloud(this);
            template.readResolve();
        }
        return this;
    }

    @SneakyThrows
    private int runningNodeCount() {
        return Ints.checkedCast(resourceManager.fetchAllServers(name)
                .stream()
                .filter(sd -> HetznerConstants.RUNNABLE_STATE_SET.contains(sd.getStatus()))
                .count());
    }

    /**
     * Compare-and-set rather than read-then-increment, because concurrent {@code provision()} calls
     * otherwise all observe the same free capacity and each take it.
     */
    private boolean reserveSlot(int instanceCap, int running) {
        while (true) {
            final int inFlight = pending().get();
            if (running + inFlight >= instanceCap) {
                return false;
            }
            if (pending().compareAndSet(inFlight, inFlight + 1)) {
                return true;
            }
        }
    }

    /**
     * The reservation lasts only until {@code createServer} returns, at which point the server is in
     * the list {@link #runningNodeCount()} reads. Holding it for the whole launch would count every
     * booting machine twice and halve the usable cap.
     */
    private Runnable slotRelease() {
        final AtomicBoolean released = new AtomicBoolean();
        final AtomicInteger counter = pending();
        return () -> {
            if (released.compareAndSet(false, true)) {
                counter.decrementAndGet();
            }
        };
    }

    @VisibleForTesting
    int getPendingProvisions() {
        return pending().get();
    }

    @Override
    public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
        log.debug("provision(cloud={},label={},excessWorkload={})", name, state.getLabel(), excessWorkload);
        final List<PlannedNode> plannedNodes = new ArrayList<>();
        final Label label = state.getLabel();
        final List<HetznerServerTemplate> matchingTemplates = getTemplates(label);
        final Jenkins jenkinsInstance = Jenkins.get();
        try {
            while (excessWorkload > 0) {
                if (jenkinsInstance.isQuietingDown() || jenkinsInstance.isTerminating()) {
                    log.warn("Jenkins is going down, no new nodes will be provisioned");
                    break;
                }
                int running = runningNodeCount();
                int instanceCap = getInstanceCap();
                final HetznerServerTemplate template = pickTemplate(matchingTemplates);
                if (!reserveSlot(instanceCap, running)) {
                    log.warn("Cloud capacity reached ({}). Has {} VMs running and {} being created, "
                                    + "but want {} more executors",
                            instanceCap, running, pending().get(), excessWorkload);
                    break;
                } else {
                    log.info("Creating new agent with {} executors, have {} running VMs and {} being created",
                            template.getNumExecutors(), running, pending().get());
                    final String serverName = template.generateNodeName();
                    final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(name, template.getName(),
                            serverName);
                    final Runnable release = slotRelease();
                    try {
                        final HetznerServerAgent agent = template.createAgent(provisioningId, serverName);
                        agent.setMode(template.getMode());
                        plannedNodes.add(new TrackedPlannedNode(
                                        provisioningId,
                                        agent.getNumExecutors(),
                                        Computer.threadPoolForRemoting.submit(
                                                new NodeCallable(agent, this, release))
                                )
                        );
                        excessWorkload -= agent.getNumExecutors();
                    } catch (RuntimeException | IOException | Descriptor.FormException e) {
                        release.run();
                        throw e;
                    }
                }
            }

        } catch (IOException | Descriptor.FormException e) {
            log.error("Unable to provision node", e);
        }
        return plannedNodes;
    }

    @Override
    public boolean canProvision(CloudState state) {
        return !getTemplates(state.getLabel()).isEmpty();
    }

    private List<HetznerServerTemplate> getTemplates(Label label) {
        return serverTemplates.stream().filter(t -> {
                    //no labels has been provided in template
                    if (t.getLabels().isEmpty()) {
                        return Node.Mode.NORMAL.equals(t.getMode());
                    } else {
                        if (Node.Mode.NORMAL.equals(t.getMode())) {
                            return label == null || label.matches(t.getLabels());
                        } else {
                            return label != null && label.matches(t.getLabels());
                        }
                    }
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        @NonNull
        public String getDisplayName() {
            return Messages.plugin_displayName();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyConfiguration(@QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            final ConfigurationValidator.ValidationResult result = ConfigurationValidator.validateCloudConfig(credentialsId);
            if (result.isSuccess()) {
                return FormValidation.ok(Messages.cloudConfigPassed());
            } else {
                return FormValidation.error(result.getMessage());
            }
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckCloudName(@QueryParameter String name) {
            if (Helper.isValidLabelValue(name)) {
                return FormValidation.ok();
            }
            return FormValidation.error("Cloud name is not a valid label value: %s", name);
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            final StandardListBoxModel result = new StandardListBoxModel();
            if (owner == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result;
                }
            } else {
                if (!owner.hasPermission(Item.EXTENDED_READ)
                        && !owner.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result;
                }
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, owner, StringCredentialsImpl.class,
                            Collections.emptyList(), CredentialsMatchers.always());
        }
    }
}
