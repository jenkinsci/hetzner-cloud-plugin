/*
 * DC-level circuit breaker for Hetzner Cloud provisioning.
 * Tracks consecutive failures per datacenter location and short-circuits
 * provisioning attempts to broken DCs, forcing failover to healthy ones.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DcCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = 2;
    private static final long RESET_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

    @Getter
    private final String location;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0;
    private long lastSuccessAt = System.currentTimeMillis();
    private long lastFailureAt = 0;

    DcCircuitBreaker(String location) {
        this.location = location;
        // Initialize gauge with starting state so panels render immediately
        // instead of "no data" until the first transition.
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location).set(State.CLOSED.ordinal());
    }

    /**
     * Record a state transition to Prometheus. Called from the four sites
     * where {@code state} is mutated (isHealthy reset, recordSuccess close,
     * recordFailure open, getState lazy reset).
     */
    private void recordTransition(State from, State to) {
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location).set(to.ordinal());
        HetznerMetricProvider.DC_BREAKER_TRANSITIONS.labels(location, from.name(), to.name()).inc();
    }

    /**
     * Check if this DC should be attempted for provisioning.
     * CLOSED: always yes.
     * OPEN: no, unless reset timeout has elapsed (transitions to HALF_OPEN).
     * HALF_OPEN: yes (one probe attempt allowed).
     */
    synchronized boolean isHealthy() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
                state = State.HALF_OPEN;
                log.info("DC {} circuit breaker: OPEN -> HALF_OPEN (reset timeout elapsed)", location);
                recordTransition(State.OPEN, State.HALF_OPEN);
                return true;
            }
            return false;
        }
        // HALF_OPEN: allow one probe
        return true;
    }

    /**
     * Record a successful provisioning in this DC.
     * Resets the circuit breaker to CLOSED regardless of current state.
     */
    synchronized void recordSuccess() {
        State previous = state;
        consecutiveFailures = 0;
        state = State.CLOSED;
        lastSuccessAt = System.currentTimeMillis();
        if (previous != State.CLOSED) {
            log.info("DC {} circuit breaker: {} -> CLOSED (provisioning succeeded)", location, previous);
            recordTransition(previous, State.CLOSED);
        }
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location).set(0);
    }

    /**
     * Record a failed provisioning in this DC.
     * After FAILURE_THRESHOLD consecutive failures, opens the circuit breaker.
     */
    synchronized void recordFailure() {
        consecutiveFailures++;
        lastFailureAt = System.currentTimeMillis();
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location).set(consecutiveFailures);
        if (state == State.HALF_OPEN) {
            // Probe failed, go back to OPEN
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} circuit breaker: HALF_OPEN -> OPEN (probe failed, {} consecutive failures)",
                    location, consecutiveFailures);
            recordTransition(State.HALF_OPEN, State.OPEN);
        } else if (consecutiveFailures >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} circuit breaker: CLOSED -> OPEN ({} consecutive failures)",
                    location, consecutiveFailures);
            recordTransition(State.CLOSED, State.OPEN);
        } else {
            log.info("DC {} provisioning failed ({}/{} before circuit opens)",
                    location, consecutiveFailures, FAILURE_THRESHOLD);
        }
    }

    synchronized State getState() {
        // Re-evaluate in case reset timeout elapsed.
        //
        // Intentionally does NOT emit DC_BREAKER_TRANSITIONS counter here.
        // getState() is called by getters (Script Console, tests, dashboards)
        // and would inflate the counter every time the timeout has elapsed
        // until isHealthy() actually transitions. We update only the gauge
        // so the state stays consistent with what callers observe; the
        // transition counter is bumped exactly once when isHealthy() drives
        // the state change.
        if (state == State.OPEN && System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
            state = State.HALF_OPEN;
            HetznerMetricProvider.DC_BREAKER_STATE.labels(location).set(State.HALF_OPEN.ordinal());
        }
        return state;
    }

    synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    synchronized long getLastSuccessAt() {
        return lastSuccessAt;
    }

    synchronized long getLastFailureAt() {
        return lastFailureAt;
    }

    /** Visible for testing. */
    static int failureThreshold() {
        return FAILURE_THRESHOLD;
    }

    /** Visible for testing. */
    static long resetTimeoutMs() {
        return RESET_TIMEOUT_MS;
    }
}
