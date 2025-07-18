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
package cloud.dnation.jenkins.plugins.hetzner.launcher;

import cloud.dnation.hetznerclient.Ipv4Detail;
import cloud.dnation.hetznerclient.Ipv6Detail;
import cloud.dnation.hetznerclient.PublicNetDetail;
import cloud.dnation.hetznerclient.ServerDetail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestPublicV6AddressOnly {

    @Test
    void testMissingV6Address() {
        final PublicV6AddressOnly addr = new PublicV6AddressOnly();
        assertThrows(IllegalArgumentException.class, () ->
            addr.getAddress(new ServerDetail().publicNet(new PublicNetDetail().ipv4(new Ipv4Detail()))));
    }

    @Test
    void testValid() {
        final PublicV6AddressOnly addr = new PublicV6AddressOnly();
        final String res = addr.getAddress(new ServerDetail().publicNet(
                new PublicNetDetail().ipv6(new Ipv6Detail().ip("2a01:4e3:a0a:9b7b::/64"))));
        assertEquals("2a01:4e3:a0a:9b7b::1", res);
    }
}
