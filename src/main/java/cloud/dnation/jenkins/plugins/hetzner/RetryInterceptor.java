/*
 * OkHttp interceptor implementing retry with exponential backoff and jitter
 * for transient Hetzner API errors.
 *
 * Retry policy:
 *   Retried:     502, 504, network timeouts
 *   Not retried: 401, 403, 404, 409, 422, 429, 500, 503, and all other codes
 *   Note: 429 is handled by RateLimitInterceptor (token-scoped block), not retried here.
 *
 * Backoff formula (AWS full jitter):
 *   raw   = base * multiplier^attempt
 *   cap   = min(maxDelay, raw)
 *   delay = base + random * (cap - base)
 *
 * For 429 responses, the delay is max(calculated_backoff, Retry-After header).
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
class RetryInterceptor implements Interceptor {

    private static final int MAX_RETRIES = 3;
    private static final double MULTIPLIER = 2.0;
    // 429 is NOT retried here; RateLimitInterceptor handles it by blocking
    // further API calls until the rate-limit window resets.
    private static final Set<Integer> RETRYABLE_CODES = Set.of(502, 504);

    private final String credentialsId;
    private final long baseMs;
    private final long capMs;

    RetryInterceptor(String credentialsId) {
        this(credentialsId, 1_000, 30_000);
    }

    // Package-visible for testing with fast retries
    RetryInterceptor(String credentialsId, long baseMs, long capMs) {
        this.credentialsId = credentialsId;
        this.baseMs = baseMs;
        this.capMs = capMs;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            // Close previous response body before retry to avoid connection leak
            if (response != null) {
                response.close();
            }

            try {
                response = chain.proceed(attempt == 0 ? request : request.newBuilder().build());

                if (!isRetryable(response.code()) || attempt == MAX_RETRIES) {
                    if (isRetryable(response.code()) && attempt == MAX_RETRIES) {
                        // Retryable code on the LAST attempt -> exhausted
                        HetznerMetricProvider.API_RETRIES_EXHAUSTED
                                .labels(credentialsId, "http_" + response.code()).inc();
                    }
                    return response;
                }

                // Retryable status code -- calculate backoff
                HetznerMetricProvider.API_RETRIES
                        .labels(credentialsId, "http_" + response.code()).inc();
                long delay = calculateDelay(attempt);
                log.warn("HTTP {} on {} {} [{}], retrying in {}ms (attempt {}/{})",
                        response.code(),
                        request.method(), request.url().encodedPath(),
                        credentialsId, delay, attempt + 1, MAX_RETRIES);
                sleep(delay);

            } catch (SocketTimeoutException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) {
                    break;
                }
                HetznerMetricProvider.API_RETRIES.labels(credentialsId, "timeout").inc();
                long delay = calculateDelay(attempt);
                log.warn("Timeout on {} {} [{}], retrying in {}ms (attempt {}/{}): {}",
                        request.method(), request.url().encodedPath(),
                        credentialsId, delay, attempt + 1, MAX_RETRIES, e.getMessage());
                sleep(delay);
            } catch (InterruptedIOException e) {
                // Thread interrupted (not a timeout) -- do not retry
                throw e;
            }
        }

        // Exhausted retries on network timeout
        if (lastException != null) {
            log.error("Exhausted {} retries on {} {} [{}] due to timeouts",
                    MAX_RETRIES, request.method(), request.url().encodedPath(), credentialsId);
            HetznerMetricProvider.API_RETRIES_EXHAUSTED.labels(credentialsId, "timeout").inc();
            throw lastException;
        }

        // Should not reach here, but return last response as safety net
        return response;
    }

    private static boolean isRetryable(int code) {
        return RETRYABLE_CODES.contains(code);
    }

    /**
     * Exponential backoff with full jitter (AWS-style).
     */
    private long calculateDelay(int attempt) {
        double raw = baseMs * Math.pow(MULTIPLIER, attempt);
        long capped = Math.min(capMs, (long) raw);
        return baseMs + (long) (ThreadLocalRandom.current().nextDouble() * (capped - baseMs));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
