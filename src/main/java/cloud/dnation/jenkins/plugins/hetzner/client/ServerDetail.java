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
package cloud.dnation.jenkins.plugins.hetzner.client;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class ServerDetail extends IdentifiableResource {
    private String name;
    /**
     * Status of the Server.
     * <p/>
     * Possible values:
     * <ul>
     *     <li>running</li>
     *     <li>initializing</li>
     *     <li>starting</li>
     *     <li>stopping</li>
     *     <li>off</li>
     *     <li>deleting</li>
     *     <li>migrating</li>
     *     <li>rebuilding</li>
     *     <li>unknown</li>
     * </ul>
     */
    private String status;

    private String created;

    @SerializedName("public_net")
    private PublicNetDetail publicNet;

    @SerializedName("private_net")
    private List<PrivateNetDetail> privateNet;

    @SerializedName("server_type")
    private ServerType serverType;

    private DatacenterDetail datacenter;

    private ImageDetail image;
}
