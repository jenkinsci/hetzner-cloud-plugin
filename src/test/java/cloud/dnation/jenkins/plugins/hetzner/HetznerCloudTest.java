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

import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class HetznerCloudTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void test() throws Exception {
        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                new ArrayList<>());
        j.jenkins.clouds.add(cloud);
        j.jenkins.save();
        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            HtmlPage p = wc.goTo("manage/cloud/");
            DomElement domElement = p.getElementById("cloud_" + cloud.name);
            assertNotNull(domElement);
            p = wc.goTo("manage/cloud/hcloud-01/configure");
            assertTrue(p.getElementsByTagName("input").stream()
                    .filter(element -> element.hasAttribute("value"))
                    .anyMatch(element -> cloud.name.equals(element.getAttribute("value"))), "No input with value " + cloud.name);
        }
    }
}
