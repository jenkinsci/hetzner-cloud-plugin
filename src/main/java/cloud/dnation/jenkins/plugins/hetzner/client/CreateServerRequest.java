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

import java.util.List;
import java.util.Map;

@Data
public class CreateServerRequest {
    private String name;
    private String location;
    private String datacenter;
    @SerializedName("server_type")
    private String serverType;
    @SerializedName("start_after_create")
    private boolean startAfterCreate = true;
    private String image;
    @SerializedName("user_data")
    private String userData;
    private Map<String, String> labels;
    @SerializedName("ssh_keys")
    private List<String> sshKeys;
    private List<Integer> networks;
    @SerializedName("public_net")
    private PublicNetRequest publicNet;
}
