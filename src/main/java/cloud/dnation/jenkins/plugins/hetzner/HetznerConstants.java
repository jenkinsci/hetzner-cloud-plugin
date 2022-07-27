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

import cloud.dnation.jenkins.plugins.hetzner.launcher.AbstractConnectionMethod;
import cloud.dnation.jenkins.plugins.hetzner.launcher.DefaultConnectionMethod;
import cloud.dnation.jenkins.plugins.hetzner.shutdown.IdlePeriodPolicy;
import com.google.common.collect.ImmutableSet;
import lombok.experimental.UtilityClass;

import java.util.Set;

@UtilityClass
public class HetznerConstants {
    /**
     * System property name prefix for all properties that this plugin understands.
     */
    public static final String PROP_PREFIX = HetznerConstants.class.getPackage().getName();

    /**
     * Name of system property used to override Hetzner API endpoint, used by tests only.
     */
    public static final String PROP_API_ENDPOINT = PROP_PREFIX + ".api-endpoint";

    /**
     * Hetzner public cloud API endpoint.
     */
    public static final String DEFAULT_ENDPOINT = "https://api.hetzner.cloud/v1/";

    /**
     * Namespace for labels added to all objects managed by this plugin.
     */
    public static final String LABEL_NS = "jenkins.io/";

    /**
     * Name of label for credentials-id to apply to SSH key.
     */
    public static final String LABEL_CREDENTIALS_ID = LABEL_NS + "credentials-id";

    /**
     * Name of label for cloud instance associated with server.
     */
    public static final String LABEL_CLOUD_NAME = LABEL_NS + "cloud-name";

    /**
     * Name of label for all objects managed by this plugin.
     */
    public static final String LABEL_MANAGED_BY = LABEL_NS + "managed-by";

    /**
     * Internal identifier used to label cloud resources that this plugin manages.
     */
    public static final String LABEL_VALUE_PLUGIN = "hetzner-jenkins-plugin";

    /**
     * Default remote working directory.
     */
    public static final String DEFAULT_REMOTE_FS = "/home/jenkins";

    public static final int DEFAULT_NUM_EXECUTORS = 1;

    public static final int DEFAULT_BOOT_DEADLINE = 1;

    /**
     * Set of server states that are considered as runnable.
     */
    public static final Set<String> RUNNABLE_STATE_SET = ImmutableSet.<String>builder()
            .add("running")
            .add("initializing")
            .add("starting")
            .build();

    /**
     * Default shutdown policy to use.
     */
    static final IdlePeriodPolicy DEFAULT_SHUTDOWN_POLICY = new IdlePeriodPolicy(10);

    /*
     * Arbitrary value in minutes which gives us some time to shut down server before usage hour wraps.
     */
    public static final int SHUTDOWN_TIME_BUFFER = 5;

    public static final AbstractConnectionMethod DEFAULT_CONNECTION_METHOD = DefaultConnectionMethod.SINGLETON;

}
