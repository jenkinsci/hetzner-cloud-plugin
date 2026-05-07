/*
 * OkHttp interceptor that reads Hetzner rate-limit headers from every API
 * response and feeds them back to the owning HetznerApiClient.
 *
 * Runs transparently on ALL API calls, including paginated fetches inside
 * PagedResourceHelper that are unreachable from plugin code.
 *
 * Headers parsed (per https://docs.hetzner.cloud and hcloud-go reference):
 *   RateLimit-Limit     – total requests allowed per window (e.g. 3600)
 *   RateLimit-Remaining – requests remaining in current window
 *   RateLimit-Reset     – unix epoch (seconds) when the window resets
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
class RateLimitInterceptor implements Interceptor {

    private final HetznerApiClient apiClient;

    @Override
    public Response intercept(Chain chain) throws IOException {
        // Defensive: under mockito the apiClient may return null. simpleclient
        // rejects null label values; coerce to "unknown" so the metric still
        // records something useful and the request path is unaffected.
        // (OkHttp's Request.method() is @NonNull -- no guard needed there.)
        String credIdRaw = apiClient.getCredentialsId();
        final String credId = credIdRaw != null ? credIdRaw : "unknown";
        final String method = chain.request().method();
        final long startNanos = System.nanoTime();
        Response response;
        try {
            response = chain.proceed(chain.request());
        } catch (IOException networkErr) {
            // Network-layer failure (DNS, connect, read) before any HTTP
            // response. Count as a distinct status class so dashboards can
            // separate "API said no" from "API didn't respond".
            HetznerMetricProvider.API_REQUESTS
                    .labels(credId, method, "network_error").inc();
            double secs = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            HetznerMetricProvider.API_REQUEST_DURATION
                    .labels(credId, method).observe(secs);
            throw networkErr;
        }

        double secs = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        HetznerMetricProvider.API_REQUEST_DURATION.labels(credId, method).observe(secs);
        String statusClass = (response.code() / 100) + "xx";
        HetznerMetricProvider.API_REQUESTS.labels(credId, method, statusClass).inc();

        int limit = parseIntHeader(response, "RateLimit-Limit", -1);
        int remaining = parseIntHeader(response, "RateLimit-Remaining", -1);

        apiClient.updateRateLimitState(limit, remaining);

        if (response.code() == 429) {
            long retryAfter = parseLongHeader(response, "Retry-After", 0);
            log.warn("HTTP 429 on {} {} (remaining={}, retryAfter={}s)",
                    method, chain.request().url().encodedPath(),
                    remaining, retryAfter > 0 ? retryAfter : "default-60");
            apiClient.recordRateLimit(retryAfter > 0 ? retryAfter : 60);
        } else if (response.code() == 401) {
            log.warn("HTTP 401 on {} {} -- token may have been rotated, invalidating client",
                    method, chain.request().url().encodedPath());
            HetznerMetricProvider.API_TOKEN_INVALIDATED.labels(credId).inc();
            apiClient.invalidate();
        }

        return response;
    }

    private static int parseIntHeader(Response response, String name, int defaultValue) {
        String value = response.header(name);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                log.debug("Failed to parse header {}={}", name, value);
            }
        }
        return defaultValue;
    }

    private static long parseLongHeader(Response response, String name, long defaultValue) {
        String value = response.header(name);
        if (value != null) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                log.debug("Failed to parse header {}={}", name, value);
            }
        }
        return defaultValue;
    }
}
