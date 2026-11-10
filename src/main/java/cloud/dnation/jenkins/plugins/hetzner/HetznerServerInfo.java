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

import cloud.dnation.hetznerclient.ActionDetail;
import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.hetznerclient.SshKeyDetail;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class HetznerServerInfo {
    private final SshKeyDetail sshKeyDetail;
    private ServerDetail serverDetail;
    private ActionDetail createAction;
    /**
     * Follow-up actions returned by the create call (e.g. start_server, attach_to_network).
     * The server is only running once these have completed as well.
     */
    private List<ActionDetail> nextActions = new ArrayList<>();
}
