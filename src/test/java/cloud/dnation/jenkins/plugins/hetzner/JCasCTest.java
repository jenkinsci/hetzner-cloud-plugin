/*
 *     Copyright 2021 https://dnation.cloud
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

import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.model.CNode;
import jenkins.model.Jenkins;
import org.junit.ClassRule;
import org.junit.Test;

import static io.jenkins.plugins.casc.misc.Util.getJenkinsRoot;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class JCasCTest {

    @ClassRule
    @ConfiguredWithCode("jcasc.yaml")
    public static JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Test
    public void testConfigure() {
        final HetznerCloud cloud = (HetznerCloud) Jenkins.get().clouds.getByName("hcloud-01");
        assertNotNull(cloud);
        assertEquals("hcloud-01", cloud.getDisplayName());
        assertEquals(10, cloud.getInstanceCap());
        assertEquals(1, cloud.getServerTemplates().size());
        assertEquals("name=jenkins", cloud.getServerTemplates().get(0).getImage());
        assertEquals("fsn1", cloud.getServerTemplates().get(0).getLocation());
    }

    @Test
    public void testExport() throws Exception {
        final ConfigurationContext ctx = new ConfigurationContext(ConfiguratorRegistry.get());
        final CNode cloud = getJenkinsRoot(ctx).get("clouds");
        assertNotNull(cloud);
    }
}

