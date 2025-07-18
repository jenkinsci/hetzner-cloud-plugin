/*
 *     Copyright 2024 https://dnation.cloud
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

import cloud.dnation.hetznerclient.CreateServerRequest;
import cloud.dnation.jenkins.plugins.hetzner.connect.ConnectivityType;
import org.junit.jupiter.api.Test;

import static cloud.dnation.jenkins.plugins.hetzner.HetznerCloudResourceManager.customizeNetworking;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HetznerCloudResourceManagerTest {

    @Test
    void testCustomizeNetworking() throws Exception {
        CreateServerRequest req;

        req = new CreateServerRequest();
        customizeNetworking(ConnectivityType.BOTH, req, "", (s1, s2) -> {
        });
        assertEquals(true, req.getPublicNet().getEnableIpv4());
        assertEquals(true, req.getPublicNet().getEnableIpv6());

        req = new CreateServerRequest();
        customizeNetworking(ConnectivityType.BOTH_V6, req, "", (s1, s2) -> {
        });
        assertEquals(false, req.getPublicNet().getEnableIpv4());
        assertEquals(true, req.getPublicNet().getEnableIpv6());

        req = new CreateServerRequest();
        customizeNetworking(ConnectivityType.PUBLIC, req, "", (s1, s2) -> {
        });
        assertEquals(true, req.getPublicNet().getEnableIpv4());
        assertEquals(true, req.getPublicNet().getEnableIpv6());

        req = new CreateServerRequest();
        customizeNetworking(ConnectivityType.PUBLIC_V6, req, "", (s1, s2) -> {
        });
        assertEquals(false, req.getPublicNet().getEnableIpv4());
        assertEquals(true, req.getPublicNet().getEnableIpv6());

        req = new CreateServerRequest();
        customizeNetworking(ConnectivityType.PRIVATE, req, "", (s1, s2) -> {
        });
        assertEquals(false, req.getPublicNet().getEnableIpv4());
        assertEquals(false, req.getPublicNet().getEnableIpv6());
    }
}
