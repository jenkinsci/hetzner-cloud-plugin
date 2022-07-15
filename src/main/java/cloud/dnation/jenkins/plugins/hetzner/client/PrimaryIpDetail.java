package cloud.dnation.jenkins.plugins.hetzner.client;


import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class PrimaryIpDetail extends IdentifiableResource{
    /**
     * ID of the resource the Primary IP is assigned to, null if it is not assigned at all.
     */
    @SerializedName("assignee_id")
    private Integer assigneeId;

    /**
     * Resource type the Primary IP can be assigned to.
     */
    @SerializedName("assignee_type")
    private String assigneeType;

    /**
     * Delete this Primary IP when the resource it is assigned to is deleted.
     */
    @SerializedName("auto_delete")
    private boolean autoDelete;

    /**
     * Whether the IP is blocked.
     */
    private boolean blocked;

    /**
     * Point in time when the Resource was created (in ISO-8601 format).
     */
    private String created;

    /**
     * Datacenter this Primary IP is located at.
     */
    private DatacenterDetail datacenter;

    /**
     * Array of reverse DNS entries.
     */
    @SerializedName("dns_ptr")
    private List<Ipv4Detail> dnsPtr;

    /**
     * IP address.
     */
    private String ip;

    /**
     * User-defined labels (key-value pairs)
     */
    private Map<String, String> labels;

    /**
     * Name of the Resource. Must be unique per Project.
     */
    private String name;

    /**
     * Type of the Primary IP.
     */
    private String type;
}
