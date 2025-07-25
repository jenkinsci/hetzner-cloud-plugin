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

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.function.Supplier;

public class JenkinsSecretTokenProvider implements Supplier<String> {
    private final String credentialsId;

    private JenkinsSecretTokenProvider(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    public static JenkinsSecretTokenProvider forCredentialsId(String credentialsId) {
        return new JenkinsSecretTokenProvider(credentialsId);
    }

    @Override
    public String get() {
        final StringCredentials secret = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(StringCredentials.class, Jenkins.get(), ACL.SYSTEM2),
                CredentialsMatchers.withId(credentialsId));
        if (secret == null) {
            throw new IllegalStateException("Can't find credentials with ID '" + credentialsId + "'");
        }
        return secret.getSecret().getPlainText();
    }
}
