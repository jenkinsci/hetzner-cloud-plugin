/*
 *     Copyright 2022 https://dnation.cloud
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

@Data
public class PublicNetRequest {
    /**
     * Attach an IPv4 on the public NIC. If false, no IPv4 address will be attached. Defaults to true.
     */
    @SerializedName("enable_ipv4")
    private boolean enableIpv4;

    /**
     * Attach an IPv6 on the public NIC. If false, no IPv6 address will be attached. Defaults to true.
     */
    @SerializedName("enable_ipv6")
    private boolean enableIpv6;

    /**
     * ID of the ipv4 Primary IP to use. If omitted and enable_ipv4 is true, a new ipv4 Primary IP will automatically be created.
     */
    private Integer ipv4;

    /**
     * ID of the ipv6 Primary IP to use. If omitted and enable_ipv6 is true, a new ipv6 Primary IP will automatically be created.
     */
    private Integer ipv6;
}
