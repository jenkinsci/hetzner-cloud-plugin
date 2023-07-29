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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HelperTest {
    @Test
    public void testExtractPublicKey() throws IOException {
        final String pubKeyStr = TestHelper.resourceAsString("id_rsa.pub");
        final String privKeyStr = TestHelper.resourceAsString("id_rsa");
        assertEquals(pubKeyStr, Helper.getSSHPublicKeyFromPrivate(privKeyStr, null));
    }

    private static LocalDateTime time(String str) {
        return LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(str + "+02:00"));
    }

    @Test
    public void testCanShutdownServer() {
        //server started at 10:41 UTC, so it can be shutdown in minutes 36-40
        String str = "2022-05-21T10:41:19+00:00";
        assertFalse(Helper.canShutdownServer(str, time("2022-05-21T10:50:11")));
        assertTrue(Helper.canShutdownServer(str, time("2022-05-21T11:36:19")));
        assertTrue(Helper.canShutdownServer(str, time("2022-05-21T11:40:13")));
        assertFalse(Helper.canShutdownServer(str, time("2022-05-21T10:41:14")));
        //server started at 10:01, so it can be shutdown in minutes 56-00
        str = "2022-05-21T10:01:19+00:00";
        assertFalse(Helper.canShutdownServer(str, time("2022-05-21T10:55:15")));
        assertTrue(Helper.canShutdownServer(str, time("2022-05-21T10:56:19")));
        assertTrue(Helper.canShutdownServer(str, time("2022-05-21T10:59:17")));
        assertFalse(Helper.canShutdownServer(str, time("2022-05-21T10:00:18")));
        assertTrue(Helper.canShutdownServer(str, time("2022-05-21T11:00:18")));
        assertFalse(Helper.canShutdownServer(str, time("2022-05-21T10:01:19")));
        assertFalse(Helper.canShutdownServer(str, time("2022-05-21T10:32:20")));
        str = "2022-08-08T11:03:55+00:00";
        assertFalse(Helper.canShutdownServer(str, time("2022-08-08T11:03:02")));
        assertTrue(Helper.canShutdownServer(str, time("2022-08-08T11:59:02")));
    }

    @Test
    public void testIsPossiblyLong() {
        assertTrue(Helper.isPossiblyLong("1"));
        assertFalse(Helper.isPossiblyLong("0"));
        assertFalse(Helper.isPossiblyLong("not-a-number"));
    }
}
