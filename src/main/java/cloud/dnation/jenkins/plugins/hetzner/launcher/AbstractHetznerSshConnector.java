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
package cloud.dnation.jenkins.plugins.hetzner.launcher;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nullable;
import java.util.Collections;

import static cloud.dnation.jenkins.plugins.hetzner.ConfigurationValidator.doCheckNonEmpty;

@Slf4j
public abstract class AbstractHetznerSshConnector extends AbstractDescribableImpl<AbstractHetznerSshConnector> {
    /**
     * SSH connection will be authenticated using different user then specified in credentials if this field is non-null.
     */
    @Nullable
    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    protected String usernameOverride;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    protected String sshCredentialsId;

    @Getter
    @Setter(onMethod = @__({@DataBoundSetter}))
    protected AbstractConnectionMethod connectionMethod = DefaultConnectionMethod.SINGLETON;

    public HetznerServerComputerLauncher createLauncher() {
        return new HetznerServerComputerLauncher(this);
    }

    public static abstract class DescriptorImpl extends Descriptor<AbstractHetznerSshConnector> {

        @Restricted(NoExternalUse.class)
        public FormValidation doCheckSshCredentialsId(@QueryParameter String sshCredentialsId) {
            return doCheckNonEmpty(sshCredentialsId, "SSH credentials");
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public ListBoxModel doFillSshCredentialsIdItems(@AncestorInPath Item owner) {
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
                    .includeMatchingAs(ACL.SYSTEM, owner, BasicSSHUserPrivateKey.class,
                            Collections.emptyList(), CredentialsMatchers.always());
        }
    }
}
