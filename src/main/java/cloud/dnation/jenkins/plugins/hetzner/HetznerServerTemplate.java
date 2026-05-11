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

import cloud.dnation.jenkins.plugins.hetzner.connect.AbstractConnectivity;
import cloud.dnation.jenkins.plugins.hetzner.launcher.AbstractHetznerSshConnector;
import cloud.dnation.jenkins.plugins.hetzner.primaryip.AbstractPrimaryIpStrategy;
import cloud.dnation.jenkins.plugins.hetzner.shutdown.AbstractShutdownPolicy;
import com.google.common.base.Strings;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node.Mode;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.RandomStringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.doCheckNonEmpty;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.doCheckPositiveInt;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyFirewall;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyImage;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyLocation;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyNetwork;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyPlacementGroup;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyPrefix;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyServerType;
import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.verifyVolumes;
import static cloud.dnation.jenkins.plugins.hetzner.Helper.getStringOrDefault;
import static cloud.dnation.jenkins.plugins.hetzner.HetznerConstants.DEFAULT_REMOTE_FS;

@ToString
@Slf4j
public class HetznerServerTemplate extends AbstractDescribableImpl<HetznerServerTemplate> {
    private static final Pattern PREFIX_RE = Pattern.compile("^[a-z][\\w_-]+$");
    @Getter
    private final String name;

    @Getter
    private final String labelStr;

    @Getter
    private final String image;

    @Getter
    private final String location;

    @Getter
    private final String serverType;

    @Getter
    private transient Set<LabelAtom> labels;

    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PACKAGE)
    @NonNull
    private transient HetznerCloud cloud;

    @Setter(onMethod = @__({@DataBoundSetter}))
    @Getter
    private AbstractHetznerSshConnector connector;

    @Setter(onMethod = @__({@DataBoundSetter}))
    @Getter
    private String remoteFs;

    @Setter(onMethod = @__({@DataBoundSetter}))
    @Getter
    private String placementGroup;

    @Setter(onMethod = @__({@DataBoundSetter}))
    @Getter
    private String userData;

    @Setter(onMethod = @__({@DataBoundSetter}))
    @Getter
    private String jvmOpts;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private int numExecutors;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private int bootDeadline;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private String network;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private String firewall;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private String prefix;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private Mode mode = Mode.EXCLUSIVE;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private AbstractShutdownPolicy shutdownPolicy;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private AbstractPrimaryIpStrategy primaryIp;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private AbstractConnectivity connectivity;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private boolean automountVolumes;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    private String volumeIds;

    @DataBoundConstructor
    @SuppressFBWarnings("NP_NONNULL_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR")
    public HetznerServerTemplate(String name, String labelStr, String image,
                                 String location, String serverType) {
        this.name = name;
        this.labelStr = Util.fixNull(labelStr);
        this.image = image;
        this.location = location;
        this.serverType = serverType;
        readResolve();
    }

    protected Object readResolve() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        labels = Label.parse(labelStr);
        if (Strings.isNullOrEmpty(location)) {
            throw new IllegalArgumentException("Location must be specified");
        }
        if (numExecutors == 0) {
            setNumExecutors(HetznerConstants.DEFAULT_NUM_EXECUTORS);
        }
        if (bootDeadline == 0) {
            setBootDeadline(HetznerConstants.DEFAULT_BOOT_DEADLINE);
        }
        if (shutdownPolicy == null) {
            shutdownPolicy = HetznerConstants.DEFAULT_SHUTDOWN_POLICY;
        }
        if (primaryIp == null) {
            primaryIp = HetznerConstants.DEFAULT_PRIMARY_IP_STRATEGY;
        }
        if (connectivity == null ) {
            connectivity = HetznerConstants.DEFAULT_CONNECTIVITY;
        }
        if (placementGroup == null) {
            placementGroup = "";
        }
        if (userData == null) {
            userData = "";
        }
        if (volumeIds == null) {
            volumeIds = "";
        }
        if (prefix == null) {
            prefix = "";
        }
        prefix = prefix.toLowerCase(Locale.ROOT);
        return this;
    }

    boolean isPrefixValid() {
        return checkPrefixValue(prefix);
    }

    static boolean checkPrefixValue(String prefixStr) {
        return PREFIX_RE.matcher(prefixStr).matches();
    }

    String generateNodeName() {
        final String prefixStr = isPrefixValid() ? prefix : "hcloud";
        return  prefixStr + "-" + RandomStringUtils.randomAlphanumeric(16)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Create new {@link HetznerServerAgent}.
     *
     * @param provisioningId ID to track activity of provisioning
     * @param nodeName       name of server
     * @return new agent instance
     */
    HetznerServerAgent createAgent(ProvisioningActivity.Id provisioningId, String nodeName)
            throws IOException, Descriptor.FormException {
        return new HetznerServerAgent(
                provisioningId,
                nodeName,
                getStringOrDefault(remoteFs, DEFAULT_REMOTE_FS),
                connector.createLauncher(),
                cloud,
                this
        );
    }

    /**
     * Whether this template is safe to swap into an in-flight provision attempt
     * whose agent was constructed from {@code other}. The agent embeds {@code
     * other}'s connector / labels / executors / remoteFs / mode; if the
     * substitute differs on any of those, the resulting Jenkins node would have
     * mismatched metadata (wrong SSH credentials, wrong labels for the queue
     * matcher, wrong workspace, etc.) and bootstrap would silently fail.
     *
     * Returns {@code true} only when the substitute differs ONLY in location /
     * server-type / image / network / firewall / userData / placement / volumes
     * (the fields the API request is built from and that are NOT baked into
     * the Jenkins agent). DC failover should refuse to attempt the next
     * ranked template when this returns false.
     */
    boolean isFailoverCompatibleWith(HetznerServerTemplate other) {
        if (other == null) {
            return false;
        }
        if (other == this) {
            return true;
        }
        if (this.numExecutors != other.numExecutors) {
            return false;
        }
        if (!java.util.Objects.equals(this.labelStr, other.labelStr)) {
            return false;
        }
        // Normalize remoteFs: null and empty string both fall through to the
        // launcher default at agent-construction time, so they're equivalent
        // for failover purposes.
        if (!java.util.Objects.equals(
                Strings.nullToEmpty(this.remoteFs),
                Strings.nullToEmpty(other.remoteFs))) {
            return false;
        }
        if (this.mode != other.mode) {
            return false;
        }
        // Connector identity: same class + same credentials/port/user override.
        // Two templates with different SSH credential IDs cannot share an agent
        // because the launcher caches the credential ID at construction.
        if (this.connector == null || other.connector == null) {
            return this.connector == other.connector;
        }
        if (!this.connector.getClass().equals(other.connector.getClass())) {
            return false;
        }
        if (!java.util.Objects.equals(this.connector.getSshCredentialsId(),
                other.connector.getSshCredentialsId())) {
            return false;
        }
        // Normalize sshPort: 0 is the unset sentinel and falls through to the
        // default port (22) at runtime (see AbstractHetznerSshConnector.readResolve).
        int thisPort = this.connector.getSshPort() == 0 ? 22 : this.connector.getSshPort();
        int otherPort = other.connector.getSshPort() == 0 ? 22 : other.connector.getSshPort();
        if (thisPort != otherPort) {
            return false;
        }
        if (!java.util.Objects.equals(this.connector.getUsernameOverride(),
                other.connector.getUsernameOverride())) {
            return false;
        }
        // Connection method (IPv4 / IPv6 / private network) determines which
        // address the launcher SSHes to. The agent's launcher is built from
        // the original connector at construction; if the failover target
        // switches connection method, the launcher would try the wrong
        // address family for the freshly-created VM.
        Class<?> thisCm = this.connector.getConnectionMethod() != null
                ? this.connector.getConnectionMethod().getClass() : null;
        Class<?> otherCm = other.connector.getConnectionMethod() != null
                ? other.connector.getConnectionMethod().getClass() : null;
        if (!java.util.Objects.equals(thisCm, otherCm)) {
            return false;
        }
        return true;
    }

    @SuppressWarnings("unused")
    @Extension
    public static final class DescriptorImpl extends Descriptor<HetznerServerTemplate> {
        @Override
        @NonNull
        public String getDisplayName() {
            return Messages.serverTemplate_displayName();
        }


        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyPrefix(@QueryParameter String prefix) {
            return verifyPrefix(prefix);
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyLocation(@QueryParameter String location,
                                               @QueryParameter String credentialsId) {
            return verifyLocation(location, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyImage(@QueryParameter String image,
                                            @QueryParameter String credentialsId) {
            return verifyImage(image, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyNetwork(@QueryParameter String network,
                                              @QueryParameter String credentialsId) {
            return verifyNetwork(network, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyFirewall(@QueryParameter String firewall,
                                              @QueryParameter String credentialsId) {
            return verifyFirewall(firewall, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyPlacementGroup(@QueryParameter String placementGroup,
                                              @QueryParameter String credentialsId) {
            return verifyPlacementGroup(placementGroup, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyServerType(@QueryParameter String serverType,
                                                 @QueryParameter String credentialsId) {
            return verifyServerType(serverType, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyVolumes(@QueryParameter String volumeIds,
                                                 @QueryParameter String credentialsId) {
            return verifyVolumes(volumeIds, credentialsId).toFormValidation();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckImage(@QueryParameter String image) {
            return doCheckNonEmpty(image, "Image");
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckLabelStr(@QueryParameter String labelStr, @QueryParameter Mode mode) {
            if (Strings.isNullOrEmpty(labelStr) && Mode.EXCLUSIVE == mode) {
                return FormValidation.warning("You may want to assign labels to this node;"
                        + " it's marked to only run jobs that are exclusively tied to itself or a label.");
            }
            return FormValidation.ok();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckServerType(@QueryParameter String serverType) {
            return doCheckNonEmpty(serverType, "Server type");
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckLocation(@QueryParameter String location) {
            return doCheckNonEmpty(location, "Location");
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckName(@QueryParameter String name) {
            return doCheckNonEmpty(name, "Name");
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckNumExecutors(@QueryParameter String numExecutors) {
            return doCheckPositiveInt(numExecutors, "Number of executors");
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckBootDeadline(@QueryParameter String bootDeadline) {
            return doCheckPositiveInt(bootDeadline, "Boot deadline");
        }
    }
}
