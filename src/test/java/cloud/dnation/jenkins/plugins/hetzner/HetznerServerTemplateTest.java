/*
 *     Copyright 2025 https://dnation.cloud
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

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
@Slf4j
public class HetznerServerTemplateTest {
    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }
    @Test
    public void testValidPrefix() {
        var tmpl  = new HetznerServerTemplate("my-template-1", "lbl", "img", "loc", "cx12");
        tmpl.setPrefix("myprefix1");
        assertTrue(tmpl.isPrefixValid());
        assertTrue(tmpl.generateNodeName().startsWith("myprefix1-"));
        tmpl.setPrefix("0");
        assertFalse(tmpl.isPrefixValid());
        assertTrue(tmpl.generateNodeName().startsWith("hcloud-"));
        tmpl.setPrefix("");
        assertFalse(tmpl.isPrefixValid());
    }
}
