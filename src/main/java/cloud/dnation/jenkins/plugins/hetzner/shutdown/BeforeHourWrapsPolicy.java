/*
 *     Copyright 2022 https://dnation.cloud
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
package cloud.dnation.jenkins.plugins.hetzner.shutdown;

import cloud.dnation.jenkins.plugins.hetzner.Helper;
import cloud.dnation.jenkins.plugins.hetzner.HetznerServerAgent;
import cloud.dnation.jenkins.plugins.hetzner.Messages;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.RetentionStrategy;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.time.LocalDateTime;

@Slf4j
public class BeforeHourWrapsPolicy extends AbstractShutdownPolicy {
    @SuppressWarnings("rawtypes")
    private static final RetentionStrategy<AbstractCloudComputer> STRATEGY_SINGLETON = new RetentionStrategyImpl();

    @DataBoundConstructor
    public BeforeHourWrapsPolicy() {
        super(STRATEGY_SINGLETON);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public RetentionStrategy<AbstractCloudComputer> getRetentionStrategy() {
        return STRATEGY_SINGLETON;
    }

    @SuppressWarnings("rawtypes")
    private static class RetentionStrategyImpl extends RetentionStrategy<AbstractCloudComputer> {
        @Override
        public void start(AbstractCloudComputer c) {
            c.connect(false);
        }

        @Override
        @GuardedBy("hudson.model.Queue.lock")
        public long check(final AbstractCloudComputer c) {
            final HetznerServerAgent agent = (HetznerServerAgent) c.getNode();
            if (c.isIdle() && agent != null && agent.getServerInstance() != null) {
                if (Helper.canShutdownServer(agent.getServerInstance().getServerDetail().getCreated(),
                        LocalDateTime.now())) {
                    log.info("Disconnecting {}", c.getName());
                    try {
                        agent.terminate();
                    } catch (InterruptedException | IOException | IllegalStateException e) {
                        log.warn("Failed to terminate {}", c.getName(), e);
                    }
                }
            }
            return 1;
        }
    }

    @Extension
    @Symbol("hour-wrap")
    public static final class DescriptorImpl extends Descriptor<AbstractShutdownPolicy> {
        @NonNull
        @Override
        public String getDisplayName() {
            return Messages.policy_shutdown_beforeHourWrap();
        }
    }
}
