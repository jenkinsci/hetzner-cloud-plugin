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

import cloud.dnation.hetznerclient.ClientFactory;
import cloud.dnation.hetznerclient.GetDatacentersResponse;
import cloud.dnation.hetznerclient.GetFirewallByIdResponse;
import cloud.dnation.hetznerclient.GetFirewallsBySelectorResponse;
import cloud.dnation.hetznerclient.GetImageByIdResponse;
import cloud.dnation.hetznerclient.GetImagesBySelectorResponse;
import cloud.dnation.hetznerclient.GetLocationsResponse;
import cloud.dnation.hetznerclient.GetNetworkByIdResponse;
import cloud.dnation.hetznerclient.GetNetworksBySelectorResponse;
import cloud.dnation.hetznerclient.GetPlacementGroupByIdResponse;
import cloud.dnation.hetznerclient.GetPlacementGroupsResponse;
import cloud.dnation.hetznerclient.GetServerTypesResponse;
import cloud.dnation.hetznerclient.GetVolumeByIdResponse;
import cloud.dnation.hetznerclient.HetznerApi;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.primitives.Ints;
import hudson.util.FormValidation;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

@Slf4j
public class ConfigurationValidator {
    /**
     * Simple validation of credentialsId. This is implemented by listing all datacenters.
     *
     * @param credentialsId credentialsId used for client connection
     * @return ValidationResult
     */
    static ValidationResult validateCloudConfig(String credentialsId) {
        return validateWithClient(api -> {
            final GetDatacentersResponse result = api.getAllDatacenters().execute().body();
            Preconditions.checkArgument(!result.getDatacenters().isEmpty(), "Expected some data");
            return ValidationResult.OK;
        }, credentialsId);
    }

    /**
     * Perform given {@link ValidationAction} using {@link HetznerApi} created from <code>credentialsId</code>.
     *
     * @param action        action to perform
     * @param credentialsId credentials for API client
     * @return ValidationResult
     */
    private static ValidationResult validateWithClient(ValidationAction action, String credentialsId) {
        final HetznerApi client = ClientFactory.create(JenkinsSecretTokenProvider.forCredentialsId(credentialsId));
        try {
            return action.validate(client);
        } catch (Exception e) {
            return ValidationResult.fromException(e);
        }
    }

    /**
     * Attempt to validate provided string as valid name of datacenter.
     *
     * @param datacenter    name of datacenter to validate.
     * @param credentialsId credentialsId used for client connection
     * @return ValidationResult representing result
     */
    static ValidationResult validateDatacenter(String datacenter, String credentialsId) {
        if (Strings.isNullOrEmpty(datacenter)) {
            return new ValidationResult(false, "Datacenter is empty");
        }
        return validateWithClient(api -> {
            final GetDatacentersResponse result = api.getAllDatacentersWithName(datacenter)
                    .execute().body();
            Preconditions.checkArgument(result.getDatacenters().size() == 1,
                    "Expected exactly one result, got %s", result.getDatacenters().size());
            return new ValidationResult(true, "Found: " +
                    result.getDatacenters().get(0).getDescription());

        }, credentialsId);
    }

    /**
     * Attempt to validate provided label expression as valid filter for image.
     *
     * @param image         label expression that should resolve to single image.
     * @param credentialsId credentialsId used for client connection
     * @return ValidationResult representing result
     */
    static ValidationResult verifyImage(String image, String credentialsId) {
        if (Strings.isNullOrEmpty(image)) {
            return new ValidationResult(false, "Image label expression is empty");
        }
        return validateWithClient(api -> {
            if (Helper.isLabelExpression(image)) {
                GetImagesBySelectorResponse result = api.getImagesBySelector(image).execute().body();
                Preconditions.checkArgument(result.getImages().size() == 1,
                        "Expected exactly one result, got %s", result.getImages().size());
                return new ValidationResult(true, "Found: " +
                        result.getImages().get(0).getDescription());
            } else if (Helper.isPossiblyLong(image)) {
                final GetImageByIdResponse result = api.getImageById(Long.parseLong(image)).execute().body();
                return new ValidationResult(true, "Found: " +
                        result.getImage().getDescription());
            } else {
                return new ValidationResult(false, "Image expression unsupported : " + image);
            }
        }, credentialsId);
    }

    static ValidationResult verifyNetwork(String network, String credentialsId) {
        if (Strings.isNullOrEmpty(network)) {
            return new ValidationResult(false, "Network label expression is empty");
        }
        return validateWithClient(api -> {
            if (Helper.isLabelExpression(network)) {
                final GetNetworksBySelectorResponse result = api.getNetworkBySelector(network).execute().body();
                Preconditions.checkArgument(result.getNetworks().size() == 1,
                        "Expected exactly one result, got %s", result.getNetworks().size());
                return new ValidationResult(true, "Found: " +
                        result.getNetworks().get(0).getName() + " " +
                        result.getNetworks().get(0).getIpRange());
            } else if (Helper.isPossiblyLong(network)) {
                final GetNetworkByIdResponse result = api.getNetworkById(Integer.parseInt(network)).execute().body();
                return new ValidationResult(true, "Found: " +
                        result.getNetwork().getName() + " " + result.getNetwork().getIpRange());
            } else {
                return new ValidationResult(false, "Network expression unsupported : " + network);
            }
        }, credentialsId);
    }

    static ValidationResult verifyFirewall(String firewall, String credentialsId) {
        if (Strings.isNullOrEmpty(firewall)) {
            return new ValidationResult(false, "Firewall label expression is empty");
        }
        return validateWithClient(api -> {
            if (Helper.isLabelExpression(firewall)) {
                final GetFirewallsBySelectorResponse result = api.getFirewallsBySelector(firewall).execute().body();
                Preconditions.checkArgument(result.getFirewalls().size() == 1,
                        "Expected exactly one result, got %s", result.getFirewalls().size());
                return new ValidationResult(true, "Found: " + result.getFirewalls().get(0).getName());
            } else if (Helper.isPossiblyLong(firewall)) {
                final GetFirewallByIdResponse result = api.getFirewallById(Long.parseLong(firewall)).execute().body();
                return new ValidationResult(true, "Found: " + result.getFirewall().getName());
            } else {
                return new ValidationResult(false, "Firewall expression unsupported : " + firewall);
            }
        }, credentialsId);
    }

    static ValidationResult verifyPlacementGroup(String placementGroup, String credentialsId) {
        if (Strings.isNullOrEmpty(placementGroup)) {
            return new ValidationResult(false, "Placement group expression is empty");
        }
        return validateWithClient(api -> {
            if (Helper.isLabelExpression(placementGroup)) {
                final GetPlacementGroupsResponse result = api.getPlacementGroups(placementGroup).execute().body();
                Preconditions.checkArgument(result.getPlacementGroups().size() == 1,
                        "Expected exactly one result, got %s", result.getPlacementGroups().size());
                return new ValidationResult(true, "Found: " +
                        result.getPlacementGroups().get(0).getName() + " " +
                        result.getPlacementGroups().get(0).getId());
            } else if (Helper.isPossiblyLong(placementGroup)) {
                final GetPlacementGroupByIdResponse result = api.getPlacementGroupById(Integer.parseInt(placementGroup)).execute().body();
                return new ValidationResult(true, "Found: " +
                        result.getPlacementGroup().getName() + " " + result.getPlacementGroup().getId());
            } else {
                return new ValidationResult(false, "Placement group expression unsupported : " + placementGroup);
            }
        }, credentialsId);
    }

    /**
     * Attempt to validate given server type name.
     *
     * @param serverType    server type name
     * @param credentialsId credentialsId used for client connection
     * @return ValidationResult representing result
     */
    static ValidationResult verifyServerType(String serverType, String credentialsId) {
        if (Strings.isNullOrEmpty(serverType)) {
            return new ValidationResult(false, "Server type is empty");
        }
        return validateWithClient(api -> {
            final GetServerTypesResponse result = api.getAllServerTypesWithName(serverType).execute().body();
            Preconditions.checkArgument(result.getServerTypes().size() == 1,
                    "Expected exactly one result, got {}", result.getServerTypes().size());
            return new ValidationResult(true, "Found: " +
                    result.getServerTypes().get(0).getDescription());

        }, credentialsId);
    }

    /**
     * Attempt to validate location name.
     *
     * @param location      name of location to validate
     * @param credentialsId credentialsId used for client connection
     * @return ValidationResult representing result
     */
    static ValidationResult verifyLocation(String location, String credentialsId) {
        if (Strings.isNullOrEmpty(location)) {
            return new ValidationResult(false, "Location is empty");
        }
        if (location.contains("-")) {
            return validateDatacenter(location, credentialsId);
        }
        return validateWithClient(api -> {
            final GetLocationsResponse result = api.getAllLocationsWithName(location).execute().body();
            Preconditions.checkArgument(result.getLocations().size() == 1,
                    "Expected exactly one result, got {}", result.getLocations().size());
            return new ValidationResult(true, "Found: " +
                    result.getLocations().get(0).getDescription());

        }, credentialsId);
    }

    static ValidationResult verifyVolume(String volume, String credentialsId) {
        return validateWithClient(api -> {
            if (!Helper.isPossiblyLong(volume)) {
                return new ValidationResult(false, String.format("not a valid volume ID: %s", volume));
            }
            final GetVolumeByIdResponse result = api.getVolumeById(Long.parseLong(volume)).execute().body();
            if (result == null) {
                return new ValidationResult(false, String.format("Volume %s not found", volume));
            }else {
                return new ValidationResult(true, String.format("%s: %s",
                        result.getVolume().getName(), result.getVolume().getFormat()));
            }
        }, credentialsId);
    }

    static ValidationResult verifyVolumes(String volumeIds, String credentialId) {
        log.info("volumeIds: {}", volumeIds);
        return Arrays.stream(volumeIds.split(",")).map(volId -> verifyVolume(volId, credentialId))
                .filter(res -> !res.isSuccess()).findFirst().orElse(ValidationResult.OK);
    }

    public static FormValidation doCheckPositiveInt(String value, String name) {
        if (Ints.tryParse(value) == null) {
            return FormValidation.error(name + " must be positive integer : " + value);
        }
        return FormValidation.ok();
    }

    public static FormValidation doCheckNonEmpty(String value, String name) {
        if (Strings.isNullOrEmpty(value)) {
            return FormValidation.error(name + " must be specified");
        }
        return FormValidation.ok();
    }

    // can't use java.util.Function due to declared checked exception
    private interface ValidationAction {
        ValidationResult validate(HetznerApi client) throws Exception;
    }

    @Data
    static class ValidationResult {
        static final ValidationResult OK = new ValidationResult(true, "OK");
        private final boolean success;
        private final String message;

        static ValidationResult fromException(Throwable e) {
            log.warn("API invocation failed", e);
            return new ValidationResult(false, Throwables.getRootCause(e).getMessage());
        }

        /**
         * Convert this instance to {@link FormValidation}.
         *
         * @return FormValidation
         */
        FormValidation toFormValidation() {
            if (success) {
                return FormValidation.ok(message);
            } else {
                return FormValidation.error(message);
            }
        }
    }
}
