/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link HetznerProvisioningException} classification.
 *
 * Background: Hetzner returns HTTP 429 for several distinct conditions:
 * - real token rate limiting (`rate_limit_exceeded` code)
 * - per-project instance cap (`instance_cap_reached` code)
 * - other transient throttles
 *
 * The previous {@code isRateLimited()} returned true for either HTTP 429 or
 * the code, conflating capacity bookkeeping with token quota. That caused
 * NodeCallable to abort DC failover for {@code instance_cap_reached} errors,
 * even though another DC would have succeeded.
 *
 * The fix tightens {@code isRateLimited()} to require BOTH the status AND
 * the code, and adds {@code isPlausiblyDcAttributable()} so unknown
 * 422/5xx codes failover instead of giving up.
 */
class HetznerProvisioningExceptionTest {

    @Test
    void isRateLimited_strictRequiresStatusAndCode() {
        // Real token rate limit: both 429 and rate_limit_exceeded -> true
        HetznerProvisioningException real =
                new HetznerProvisioningException("Rate limited", 429, "rate_limit_exceeded", "fsn1");
        assertTrue(real.isRateLimited(), "429 + rate_limit_exceeded should be rate-limited");

        // Instance cap reached: 429 but different code -> not rate-limited
        HetznerProvisioningException capReached =
                new HetznerProvisioningException("Cap reached", 429, "instance_cap_reached", "fsn1");
        assertFalse(capReached.isRateLimited(),
                "instance_cap_reached must NOT be treated as token rate limit");

        // 429 without a code (Hetzner can return bare 429 too): not rate-limited
        // under strict check; failover should still be attempted via isPlausiblyDcAttributable.
        HetznerProvisioningException bare429 =
                new HetznerProvisioningException("Throttled", 429, null, "fsn1");
        assertFalse(bare429.isRateLimited(),
                "bare 429 with null code must not be classified as rate_limit_exceeded");

        // Code "rate_limit_exceeded" without HTTP 429 (shouldn't happen but defensive)
        HetznerProvisioningException onlyCode =
                new HetznerProvisioningException("Weird", 422, "rate_limit_exceeded", "fsn1");
        assertFalse(onlyCode.isRateLimited(),
                "code without 429 status must not be classified as rate_limit_exceeded");
    }

    @Test
    void isPlausiblyDcAttributable_failsOverForUnknown422And5xx() {
        // Known DC-attributable -> true
        assertTrue(new HetznerProvisioningException("Full", 422, "resource_unavailable", "fsn1")
                .isPlausiblyDcAttributable());
        assertTrue(new HetznerProvisioningException("Placement", 422, "placement_error", "fsn1")
                .isPlausiblyDcAttributable());

        // Unknown 422 code: still failover-eligible (Hetzner introduces codes
        // regularly; conservative default is to try another DC).
        assertTrue(new HetznerProvisioningException("Unknown 422", 422, "new_code_2026", "fsn1")
                .isPlausiblyDcAttributable());
        assertTrue(new HetznerProvisioningException("Unknown 422 null", 422, null, "fsn1")
                .isPlausiblyDcAttributable());

        // 5xx codes: backend issues, failover-eligible
        assertTrue(new HetznerProvisioningException("Backend", 500, null, "fsn1")
                .isPlausiblyDcAttributable());
        assertTrue(new HetznerProvisioningException("Bad gateway", 502, null, "fsn1")
                .isPlausiblyDcAttributable());
        assertTrue(new HetznerProvisioningException("Service unavailable", 503, null, "fsn1")
                .isPlausiblyDcAttributable());

        // Rate-limit excluded
        assertFalse(new HetznerProvisioningException("RL", 429, "rate_limit_exceeded", "fsn1")
                .isPlausiblyDcAttributable());

        // Config error excluded
        assertFalse(new HetznerProvisioningException("Invalid", 422, "invalid_input", "fsn1")
                .isPlausiblyDcAttributable());

        // Auth (401/403) excluded
        assertFalse(new HetznerProvisioningException("Unauthorized", 401, "unauthorized", "fsn1")
                .isPlausiblyDcAttributable());
        assertFalse(new HetznerProvisioningException("Forbidden", 403, null, "fsn1")
                .isPlausiblyDcAttributable());

        // 404 / other client errors excluded
        assertFalse(new HetznerProvisioningException("Not found", 404, null, "fsn1")
                .isPlausiblyDcAttributable());
    }
}
