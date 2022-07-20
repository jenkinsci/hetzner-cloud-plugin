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
package cloud.dnation.jenkins.plugins.hetzner.launcher;

import cloud.dnation.jenkins.plugins.hetzner.Messages;
import cloud.dnation.jenkins.plugins.hetzner.client.ServerDetail;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import lombok.NoArgsConstructor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

@NoArgsConstructor(onConstructor = @__({@DataBoundConstructor}))
public class PublicAddressOnly extends AbstractConnectionMethod {
    @Override
    public String getAddress(ServerDetail server) {
        return server.getPublicNet().getIpv4().getIp();
    }

    @Extension
    @Symbol("public")
    public static final class DescriptorImpl extends Descriptor<AbstractConnectionMethod> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.connection_method_public();
        }
    }
}
