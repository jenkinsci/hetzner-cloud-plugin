/*
 *     Copyright 2026 Percona, LLC.
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

import hudson.Extension;
import hudson.model.PeriodicWork;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.jenkinsci.Symbol;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Periodically refresh per-cloud gauges (running servers, pending provisions)
 * regardless of provisioning activity. Without this, the gauges only update
 * when {@code HetznerCloud.provision()} fires, and an idle cloud is pinned
 * to its last-known value indefinitely.
 *
 * <p>Observed before this fix: {@code hetzner_running_servers{master="psmdb.cd"}}
 * showed {@code 25} in Mimir while the Hetzner API reported {@code 2} running
 * servers. The cloud had drained from a recent peak but no new provisions
 * triggered a gauge refresh, so the metric never caught up.
 *
 * <p>Rate-limit hygiene: each call to {@code refreshMetrics()} hits the
 * Hetzner API once per cloud. The plugin's API budget is 3600 requests per
 * hour per token; refreshing every minute costs 60/hour per cloud, which
 * is well within budget even at 15 templates across all 10 Percona Jenkins
 * masters.
 *
 * @since v103.percona.16
 */
@Extension
@Symbol("HetznerMetricsRefresher")
@Slf4j
public class HetznerMetricsRefresher extends PeriodicWork {
    @Override
    public long getRecurrencePeriod() {
        return MIN;
    }

    private static Set<HetznerCloud> getHetznerClouds() {
        return Jenkins.get().clouds.stream()
                .filter(HetznerCloud.class::isInstance)
                .map(HetznerCloud.class::cast)
                .collect(Collectors.toSet());
    }

    @Override
    protected void doRun() {
        try {
            doRefresh();
        } catch (Exception e) {
            // Catch-all to prevent killing this PeriodicWork timer.
            log.error("Hetzner metrics refresh failed unexpectedly", e);
        }
    }

    static void doRefresh() {
        getHetznerClouds().forEach(HetznerMetricsRefresher::refreshCloud);
    }

    private static void refreshCloud(HetznerCloud cloud) {
        if (HetznerApiClient.forCredentials(cloud.getCredentialsId()).isRateLimited()) {
            log.debug("Token rate-limited for cloud '{}', skipping metrics refresh this cycle",
                    cloud.name);
            return;
        }
        cloud.refreshMetrics();
    }
}
