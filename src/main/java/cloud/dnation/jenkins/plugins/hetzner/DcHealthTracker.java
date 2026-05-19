/*
 * Static registry of per-DC circuit breakers.
 * Provides sorted template lists that prefer healthy DCs while maintaining
 * backward-compatible random selection when all DCs are healthy.
 *
 * State persistence (PS-11173, v103.percona.21):
 * Breaker state is persisted to $JENKINS_HOME/hetzner-dc-health.xml via
 * XmlFile + Saveable so OPEN circuit breakers survive a JVM restart and
 * the master does not stampede a still-sick DC on first boot. Stale
 * OPEN entries (lastFailureAt older than STALE_OPEN_TTL_MS) load as
 * CLOSED so a transient outage does not pin a DC out of rotation.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import hudson.BulkChange;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Saveable;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class DcHealthTracker {

    private static final ConcurrentHashMap<String, DcCircuitBreaker> BREAKERS = new ConcurrentHashMap<>();
    private static final long STALE_OPEN_TTL_MS = 30 * 60 * 1000L;
    private static final AtomicBoolean SAVE_SCHEDULED = new AtomicBoolean(false);

    private DcHealthTracker() {
    }

    /**
     * Load persisted breaker state from $JENKINS_HOME/hetzner-dc-health.xml.
     * Runs after PLUGINS_STARTED so {@link Jenkins#XSTREAM2} is available and
     * before any provisioning event would create breakers from scratch.
     * Missing file is a silent no-op (clean install / first-ever load).
     */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static void load() {
        XmlFile xml = getXmlFile();
        if (!xml.exists()) {
            return;
        }
        try {
            Store store = (Store) xml.read();
            if (store != null && store.breakers != null) {
                long now = System.currentTimeMillis();
                store.breakers.forEach((location, breaker) -> {
                    if (location != null && breaker != null) {
                        breaker.afterLoad(location, now, STALE_OPEN_TTL_MS);
                        BREAKERS.put(location, breaker);
                    }
                });
                log.info("Hetzner DC health state loaded from {}: {} breakers",
                        xml.getFile(), BREAKERS.size());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to load Hetzner DC health state from {}", xml.getFile(), e);
        }
    }

    /**
     * Get or create the circuit breaker for a given DC location.
     */
    static DcCircuitBreaker getBreaker(String location) {
        return BREAKERS.computeIfAbsent(location, DcCircuitBreaker::new);
    }

    /**
     * Record a provisioning failure for the given DC.
     */
    static void recordFailure(String location) {
        getBreaker(location).recordFailure();
        save();
    }

    /**
     * Record a provisioning success for the given DC.
     */
    static void recordSuccess(String location) {
        getBreaker(location).recordSuccess();
        save();
    }

    /**
     * Check if a DC is currently considered healthy.
     */
    static boolean isHealthy(String location) {
        return getBreaker(location).isHealthy();
    }

    /**
     * Sort templates by DC health: healthy DCs first, unhealthy last.
     * Within each partition, templates are shuffled randomly.
     * When all DCs are healthy (normal case), this is equivalent to a random shuffle.
     *
     * @param templates list of matching templates
     * @return new list sorted by DC health (never modifies input)
     */
    static List<HetznerServerTemplate> sortByHealth(List<HetznerServerTemplate> templates) {
        if (templates == null || templates.size() <= 1) {
            return templates == null ? Collections.emptyList() : new ArrayList<>(templates);
        }

        List<HetznerServerTemplate> healthy = templates.stream()
                .filter(t -> isHealthy(t.getLocation()))
                .collect(Collectors.toCollection(ArrayList::new));

        List<HetznerServerTemplate> unhealthy = templates.stream()
                .filter(t -> !isHealthy(t.getLocation()))
                .collect(Collectors.toCollection(ArrayList::new));

        Collections.shuffle(healthy);
        Collections.shuffle(unhealthy);

        if (!unhealthy.isEmpty()) {
            log.info("DC health ranking: {} healthy, {} unhealthy DCs for {} templates",
                    healthy.size(), unhealthy.size(), templates.size());
        }

        List<HetznerServerTemplate> ranked = new ArrayList<>(templates.size());
        ranked.addAll(healthy);
        ranked.addAll(unhealthy);
        return ranked;
    }

    /**
     * Get a snapshot of all tracked breakers. For observability/testing.
     */
    static ConcurrentHashMap<String, DcCircuitBreaker> getAllBreakers() {
        return BREAKERS;
    }

    /**
     * Reset all circuit breakers. For testing only.
     */
    static void resetAll() {
        BREAKERS.clear();
    }

    /**
     * Schedule a persistence write. Coalesces concurrent triggers via
     * an AtomicBoolean so a burst of recordFailure/recordSuccess calls
     * produces a single write instead of N. Deferring to Timer keeps
     * disk I/O off the synchronized breaker lock.
     *
     * Defensive against missing Jenkins context: in unit tests that mock
     * Jenkins.get() without stubbing getRootDir(), this would NPE; we
     * swallow the exception so test code paths that exercise the breaker
     * directly do not need to mock the persistence layer.
     */
    static void save() {
        // Skip persistence if Jenkins is not fully up (unit tests that mock
        // Jenkins.get() without stubbing getRootDir() would NPE in the
        // deferred Timer task and leave SAVE_SCHEDULED stuck).
        Jenkins j;
        try {
            j = Jenkins.getInstanceOrNull();
        } catch (RuntimeException e) {
            return;
        }
        if (j == null || j.getRootDir() == null) {
            return;
        }
        if (SAVE_SCHEDULED.compareAndSet(false, true)) {
            Timer.get().submit(() -> {
                try {
                    new Store(BREAKERS).save();
                } catch (IOException | RuntimeException e) {
                    log.warn("Failed to save Hetzner DC health state", e);
                } finally {
                    SAVE_SCHEDULED.set(false);
                }
            });
        }
    }

    private static XmlFile getXmlFile() {
        File rootDir = Jenkins.get().getRootDir();
        if (rootDir == null) {
            throw new IllegalStateException("Jenkins root directory not available");
        }
        return new XmlFile(Jenkins.XSTREAM2, new File(rootDir, "hetzner-dc-health.xml"));
    }

    /**
     * Saveable wrapper for the in-memory breaker map. Kept package-private
     * with a no-arg constructor so XStream can deserialize without
     * reflection magic.
     */
    static final class Store implements Saveable {
        ConcurrentHashMap<String, DcCircuitBreaker> breakers = new ConcurrentHashMap<>();

        Store() {
        }

        Store(ConcurrentHashMap<String, DcCircuitBreaker> source) {
            this.breakers = new ConcurrentHashMap<>(source);
        }

        @Override
        public void save() throws IOException {
            if (BulkChange.contains(this)) {
                return;
            }
            getXmlFile().write(this);
        }
    }
}
