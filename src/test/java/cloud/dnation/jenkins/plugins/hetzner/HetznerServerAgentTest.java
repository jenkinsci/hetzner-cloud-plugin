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
package cloud.dnation.jenkins.plugins.hetzner;

import hudson.model.TaskListener;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link HetznerServerAgent} exception safety.
 * <p>
 * Verify that _terminate() and isAlive() handle null transient fields gracefully,
 * which occurs after Jenkins restart/deserialization. An uncaught exception from
 * _terminate() kills the ComputerRetentionWork timer permanently (see issue #89).
 */
@WithJenkins
class HetznerServerAgentTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private HetznerServerAgent createTestAgent() throws Exception {
        HetznerServerTemplate template = new HetznerServerTemplate(
                "test-template", "test-label", "test-image", "fsn1", "cx31");
        HetznerCloud cloud = new HetznerCloud(
                "test-cloud", "mock-credentials", "10",
                Collections.singletonList(template));
        return new HetznerServerAgent(
                new ProvisioningActivity.Id("test-cloud", "test-template", "test-node"),
                "test-node",
                "/tmp/jenkins",
                new hudson.slaves.JNLPLauncher(),
                cloud,
                template
        );
    }

    private static void setFieldNull(Object obj, String fieldName) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, null);
    }

    // -- _terminate: must never throw (kills CRW timer) --

    @Test
    void terminateWithNullCloudDoesNotThrow() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        assertDoesNotThrow(() -> agent._terminate(TaskListener.NULL));
    }

    @Test
    void terminateWithNullServerInstanceDoesNotThrow() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        agent.setServerInstance(null);
        assertDoesNotThrow(() -> agent._terminate(TaskListener.NULL));
    }

    @Test
    void terminateWithAllNullTransientsDoesNotThrow() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        agent.setServerInstance(null);
        assertDoesNotThrow(() -> agent._terminate(TaskListener.NULL));
    }

    // -- isAlive: must return false on error, never throw --

    @Test
    void isAliveReturnsFalseWhenCloudIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        setFieldNull(agent, "cloud");
        assertFalse(agent.isAlive());
    }

    @Test
    void isAliveReturnsFalseWhenServerInstanceIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        agent.setServerInstance(null);
        assertFalse(agent.isAlive());
    }

    // -- getDisplayName: must not throw --

    @Test
    void getDisplayNameDoesNotThrowWhenServerInstanceIsNull() throws Exception {
        HetznerServerAgent agent = createTestAgent();
        agent.setServerInstance(null);
        assertDoesNotThrow(agent::getDisplayName);
    }
}
