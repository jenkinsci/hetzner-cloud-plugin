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
import com.google.common.primitives.Ints;
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
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.RandomStringUtils;
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
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class HetznerCloud extends AbstractCloudImpl {
    @Getter
    private final String credentialsId;
    @Getter
    private List<HetznerServerTemplate> serverTemplates;
    @Getter
    private transient HetznerCloudResourceManager resourceManager;

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
        if (serverTemplates == null) {
            this.serverTemplates = Collections.emptyList();
        } else {
            this.serverTemplates = serverTemplates;
        }
        readResolve();
    }

    protected Object readResolve() {
        resourceManager = HetznerCloudResourceManager.create(credentialsId);
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

    @Override
    public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
        log.debug("provision(cloud={},label={},excessWorkload={})", name, state.getLabel(), excessWorkload);
        int available = getInstanceCap() - runningNodeCount();
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
                if (available <= 0) {
                    log.warn("Cloud capacity reached. Has {} but want {} more", getInstanceCap(), excessWorkload);
                    break;
                } else {
                    final HetznerServerTemplate template = pickTemplate(matchingTemplates);
                    final String serverName = "hcloud-" + RandomStringUtils.randomAlphanumeric(16)
                            .toLowerCase(Locale.ROOT);
                    final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(name, template.getName(),
                            serverName);
                    final HetznerServerAgent agent = template.createAgent(provisioningId, serverName);
                    agent.setMode(template.getMode());
                    plannedNodes.add(new TrackedPlannedNode(
                                    provisioningId,
                                    agent.getNumExecutors(),
                                    Computer.threadPoolForRemoting.submit(new NodeCallable(agent, this)
                                    )
                            )
                    );
                    excessWorkload -= agent.getNumExecutors();
                    available -= agent.getNumExecutors();
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
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            final StandardListBoxModel result = new StandardListBoxModel();
            if (owner == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result;
                }
            } else {
                if (!owner.hasPermission(owner.EXTENDED_READ)
                        && !owner.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result;
                }
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM, owner, StringCredentialsImpl.class,
                            Collections.emptyList(), CredentialsMatchers.always());
        }
    }
}
