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

import com.gargoylesoftware.htmlunit.html.HtmlButton;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;

public class HetznerCloudTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void test() throws Exception {
        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                new ArrayList<>());
        j.jenkins.clouds.add(cloud);
        j.jenkins.save();
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage p = wc.goTo("configureClouds/");
        HtmlForm f = p.getFormByName("config");
        HtmlButton buttonExtends = HtmlFormUtil.getButtonByCaption(f, "Server templates...");
        buttonExtends.click();
        HtmlButton buttonAddTemplate = HtmlFormUtil.getButtonByCaption(f, "Add");
        buttonAddTemplate.click();
        HtmlButton applyButton = HtmlFormUtil.getButtonByCaption(f, "Apply");
        applyButton.click();
        j.submit(f);
    }
}
