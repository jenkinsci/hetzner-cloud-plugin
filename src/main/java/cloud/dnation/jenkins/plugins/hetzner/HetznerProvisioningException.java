/*
 * Typed exception for Hetzner API provisioning failures.
 * Carries HTTP status code, Hetzner error code, and DC location
 * to enable intelligent retry decisions in NodeCallable.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import lombok.Getter;

@Getter
class HetznerProvisioningException extends RuntimeException {

    private final int httpStatus;
    private final String hetznerErrorCode;
    private final String location;

    HetznerProvisioningException(String message, int httpStatus, String hetznerErrorCode, String location) {
        super(message);
        this.httpStatus = httpStatus;
        this.hetznerErrorCode = hetznerErrorCode;
        this.location = location;
    }

    HetznerProvisioningException(String message, int httpStatus, String hetznerErrorCode,
                                  String location, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.hetznerErrorCode = hetznerErrorCode;
        this.location = location;
    }

    /**
     * Whether this error is a Hetzner token rate-limit (HTTP 429) response.
     * Rate-limiting is token-scoped, not DC-scoped: all DCs share the same
     * API token, so retrying in another DC will not help.
     *
     * Strict check: requires BOTH HTTP 429 AND hetznerErrorCode "rate_limit_exceeded".
     * Hetzner returns HTTP 429 for several conditions including the per-project
     * "instance_cap_reached" which is plugin capacity bookkeeping, not real token
     * rate limiting. Conflating the two aborts DC failover for capacity errors
     * that would have succeeded in another DC.
     */
    boolean isRateLimited() {
        return httpStatus == 429 && "rate_limit_exceeded".equals(hetznerErrorCode);
    }

    /**
     * Whether this error indicates a persistent template configuration problem.
     * These errors won't resolve with DC failover or retries; they require
     * a config change (e.g., updating a deprecated image ID).
     */
    boolean isConfigError() {
        return "invalid_input".equals(hetznerErrorCode);
    }

    /**
     * Whether this error indicates DC resource unavailability (should retry in another DC).
     * Hetzner returns HTTP 422 with error code "resource_unavailable" when a DC cannot
     * fulfill the server type request. Also matches "placement_error" and "server_limit_exceeded".
     */
    boolean isResourceUnavailable() {
        if (hetznerErrorCode == null) {
            return false;
        }
        return "resource_unavailable".equals(hetznerErrorCode)
                || "placement_error".equals(hetznerErrorCode)
                || "server_limit_exceeded".equals(hetznerErrorCode);
    }

    /**
     * Whether this error is plausibly retryable in another DC even if the specific
     * Hetzner error code is unknown or missing. Conservative heuristic:
     * - HTTP 422 with unknown code (Hetzner introduces new error codes regularly)
     * - HTTP 5xx (transient backend / DC infrastructure issues)
     * Does NOT include 401/403 (auth) or 4xx-client-error variants that imply config issues.
     */
    boolean isPlausiblyDcAttributable() {
        if (isRateLimited() || isConfigError()) {
            return false;
        }
        if (isResourceUnavailable()) {
            return true;
        }
        return httpStatus == 422 || (httpStatus >= 500 && httpStatus <= 599);
    }
}
