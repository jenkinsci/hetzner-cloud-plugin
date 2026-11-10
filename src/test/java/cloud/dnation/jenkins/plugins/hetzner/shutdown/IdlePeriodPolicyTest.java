/*
 *     Copyright 2026 https://dnation.cloud
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

import hudson.slaves.CloudRetentionStrategy;
import hudson.util.XStream2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class IdlePeriodPolicyTest {
    @Test
    public void testRetentionStrategySurvivesDeserialization() {
        final XStream2 xstream = new XStream2();
        final IdlePeriodPolicy policy = (IdlePeriodPolicy) xstream.fromXML(xstream.toXML(new IdlePeriodPolicy(42)));
        assertEquals(42, policy.getIdleMinutes());
        assertInstanceOf(CloudRetentionStrategy.class, policy.getRetentionStrategy());
    }
}
