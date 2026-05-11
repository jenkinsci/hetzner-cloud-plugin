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

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelperTest {

    @Test
    void testExtractPublicKeyRSA() throws Exception {
        final String pubKeyStr = TestHelper.resourceAsString("id_rsa.pub");
        final String privKeyStr = TestHelper.resourceAsString("id_rsa");
        assertEquals(pubKeyStr, Helper.getSSHPublicKeyFromPrivate(privKeyStr, null));
    }

    @Test
    void testExtractPublicKeyED25519() throws Exception {
        final String pubKeyStr = TestHelper.resourceAsString("id_ed25519.pub");
        final String privKeyStr = TestHelper.resourceAsString("id_ed25519");
        assertEquals(pubKeyStr, Helper.getSSHPublicKeyFromPrivate(privKeyStr, null));
    }

    private static LocalDateTime time(String str) {
        return LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(str + "+02:00"));
    }

    @Test
    void testCanShutdownServer() {
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
    void testIsPossiblyLong() {
        assertTrue(Helper.isPossiblyLong("1"));
        assertFalse(Helper.isPossiblyLong("0"));
        assertFalse(Helper.isPossiblyLong("not-a-number"));
    }

    @Test
    void testAssertValidResponseRejectsNullBody() {
        // Simulate a successful HTTP response with null body
        Response<String> response = Response.success(null);
        assertThrows(IllegalStateException.class,
                () -> Helper.assertValidResponse(response, s -> s));
    }

    @Test
    void testAssertValidResponseRejectsFailedResponse() {
        Response<String> response = Response.error(500,
                ResponseBody.create("error", MediaType.get("text/plain")));
        assertThrows(IllegalStateException.class,
                () -> Helper.assertValidResponse(response, s -> s));
    }

    @Test
    void testAssertValidResponsePassesOnSuccess() {
        Response<String> response = Response.success("ok");
        assertEquals("ok", Helper.assertValidResponse(response, s -> s));
    }

    @Test
    void testParseHetznerErrorCode_valid() {
        String body = "{\"error\":{\"code\":\"resource_unavailable\",\"message\":\"no resources\"}}";
        assertEquals("resource_unavailable", Helper.parseHetznerErrorCode(body));
    }

    @Test
    void testParseHetznerErrorCode_null() {
        assertNull(Helper.parseHetznerErrorCode(null));
        assertNull(Helper.parseHetznerErrorCode(""));
    }

    @Test
    void testParseHetznerErrorCode_malformed() {
        assertNull(Helper.parseHetznerErrorCode("not json at all"));
        assertNull(Helper.parseHetznerErrorCode("{\"other\":\"field\"}"));
    }

    @Test
    void testHetznerProvisioningException_isResourceUnavailable() {
        // 422 without a recognized error code is NOT resource unavailable
        HetznerProvisioningException ex422NoCode = new HetznerProvisioningException(
                "test", 422, null, "fsn1");
        assertFalse(ex422NoCode.isResourceUnavailable());

        // 422 with invalid_input is a config error, not resource unavailable
        HetznerProvisioningException ex422Config = new HetznerProvisioningException(
                "test", 422, "invalid_input", "fsn1");
        assertFalse(ex422Config.isResourceUnavailable());
        assertTrue(ex422Config.isConfigError());

        // resource_unavailable error code
        HetznerProvisioningException exCode = new HetznerProvisioningException(
                "test", 409, "resource_unavailable", "fsn1");
        assertTrue(exCode.isResourceUnavailable());

        // placement_error
        HetznerProvisioningException exPlacement = new HetznerProvisioningException(
                "test", 409, "placement_error", "fsn1");
        assertTrue(exPlacement.isResourceUnavailable());

        // server_limit_exceeded
        HetznerProvisioningException exLimit = new HetznerProvisioningException(
                "test", 403, "server_limit_exceeded", "fsn1");
        assertTrue(exLimit.isResourceUnavailable());

        // Normal error (auth) should NOT be resource unavailable
        HetznerProvisioningException exAuth = new HetznerProvisioningException(
                "test", 401, "unauthorized", "fsn1");
        assertFalse(exAuth.isResourceUnavailable());
    }

    @Test
    void testHetznerProvisioningException_isRateLimited() {
        // Strict contract (post-fix): requires BOTH HTTP 429 AND code
        // "rate_limit_exceeded". Hetzner returns 429 for several conditions
        // (real token rate limit, instance_cap_reached, others); only the
        // first is a real token-scoped quota burn worth aborting failover for.
        // Detailed semantics covered in HetznerProvisioningExceptionTest.

        HetznerProvisioningException exReal = new HetznerProvisioningException(
                "test", 429, "rate_limit_exceeded", "fsn1");
        assertTrue(exReal.isRateLimited(), "429 + rate_limit_exceeded is the real token rate limit");

        // Bare 429 with null code: no longer treated as rate limit
        HetznerProvisioningException ex429NullCode = new HetznerProvisioningException(
                "test", 429, null, "fsn1");
        assertFalse(ex429NullCode.isRateLimited(),
                "bare 429 with null code must NOT be rate-limited (could be instance_cap_reached etc.)");

        // Code-only without 429: not rate-limited
        HetznerProvisioningException exCodeOnly = new HetznerProvisioningException(
                "test", 200, "rate_limit_exceeded", "fsn1");
        assertFalse(exCodeOnly.isRateLimited(),
                "code without 429 status must NOT be rate-limited");

        // Normal error should NOT be rate-limited
        HetznerProvisioningException ex500 = new HetznerProvisioningException(
                "test", 500, "server_error", "fsn1");
        assertFalse(ex500.isRateLimited());
    }

    @Test
    void testHetznerProvisioningException_constructorWithCause() {
        RuntimeException cause = new RuntimeException("root cause");
        HetznerProvisioningException ex = new HetznerProvisioningException(
                "msg", 422, "invalid_input", "fsn1", cause);
        assertEquals("msg", ex.getMessage());
        assertEquals(422, ex.getHttpStatus());
        assertEquals("invalid_input", ex.getHetznerErrorCode());
        assertEquals("fsn1", ex.getLocation());
        assertSame(cause, ex.getCause());
    }

    @Test
    void testIsValidLabelValue() {
        assertFalse(Helper.isValidLabelValue(""));
        assertFalse(Helper.isValidLabelValue(null));
        assertTrue(Helper.isValidLabelValue("cloud-01"));
        assertTrue(Helper.isValidLabelValue("cloud_01"));
        assertFalse(Helper.isValidLabelValue("cloud 01"));
    }
}
