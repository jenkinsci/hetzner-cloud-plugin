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
package cloud.dnation.jenkins.plugins.hetzner.primaryip;

import cloud.dnation.hetznerclient.CreateServerRequest;
import cloud.dnation.hetznerclient.HetznerApi;
import cloud.dnation.hetznerclient.PrimaryIpDetail;
import cloud.dnation.hetznerclient.PublicNetRequest;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public abstract class AbstractByLabelSelector extends AbstractPrimaryIpStrategy {
    @Getter
    private final String selector;

    public AbstractByLabelSelector(boolean failIfError, String selector) {
        super(failIfError);
        this.selector = selector;
    }

    @Override
    public void applyInternal(HetznerApi api, CreateServerRequest server) throws IOException {
        final PrimaryIpDetail pip = api.getAllPrimaryIps(selector).execute().body().getPrimaryIps().stream()
                .filter(ip -> isIpUsable(ip, server)).findFirst().get();
        final PublicNetRequest net = new PublicNetRequest();
        net.setIpv4(pip.getId());
        net.setEnableIpv6(false);
        net.setEnableIpv4(true);
        server.setPublicNet(net);
    }

    @VisibleForTesting
    static boolean isIpUsable(PrimaryIpDetail ip, CreateServerRequest server) {
        if (ip.getAssigneeId() != null) {
            return false;
        }
        if (!Strings.isNullOrEmpty(server.getLocation())) {
            if (server.getLocation().equals(ip.getDatacenter().getLocation().getName())) {
                return true;
            }
        }
        if (!Strings.isNullOrEmpty(server.getDatacenter())) {
            return server.getDatacenter().equals(ip.getDatacenter().getName());
        }
        return false;
    }
}
