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
import hudson.model.AbstractDescribableImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractPrimaryIpStrategy extends AbstractDescribableImpl<AbstractPrimaryIpStrategy> {
    protected final boolean failIfError;

    public void apply(HetznerApi api, CreateServerRequest server) {
        try {
            applyInternal(api, server);
        } catch (Exception e) {
            if (failIfError) {
                throw new RuntimeException(e);
            } else {
                log.error("Fail to apply primary IP to server", e);
            }
        }
    }

    protected abstract void applyInternal(HetznerApi api, CreateServerRequest server) throws IOException;
}