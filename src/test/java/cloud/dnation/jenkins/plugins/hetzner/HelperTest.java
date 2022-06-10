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

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class HelperTest {
    @Test
    public void testExtractPublicKey() throws IOException {
        final String pubKeyStr = TestHelper.resourceAsString("id_rsa.pub");
        final String privKeyStr = TestHelper.resourceAsString("id_rsa");
        assertEquals(pubKeyStr, Helper.getSSHPublicKeyFromPrivate(privKeyStr, null));
    }

    @Test
    public void testCanShutdownServer() {
        //server started at 10:41 UTC, so it can be shutdown in minutes 36-40
        String str = "2022-05-21T10:41:19+00:00";
        assertFalse(Helper.canShutdownServer(str, 50));
        assertTrue(Helper.canShutdownServer(str, 36));
        assertTrue(Helper.canShutdownServer(str, 40));
        assertFalse(Helper.canShutdownServer(str, 41));
        //server started at 10:01 so it can be shutdown in minutes 56-00
        str = "2022-05-21T10:01:19+00:00";
        assertFalse(Helper.canShutdownServer(str, 55));
        assertTrue(Helper.canShutdownServer(str, 56));
        assertTrue(Helper.canShutdownServer(str, 59));
        assertTrue(Helper.canShutdownServer(str, 0));
        assertFalse(Helper.canShutdownServer(str, 1));
        assertFalse(Helper.canShutdownServer(str, 32));
    }

}
