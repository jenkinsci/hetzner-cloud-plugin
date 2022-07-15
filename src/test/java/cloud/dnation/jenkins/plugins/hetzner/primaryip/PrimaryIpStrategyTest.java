package cloud.dnation.jenkins.plugins.hetzner.primaryip;

import cloud.dnation.jenkins.plugins.hetzner.client.CreateServerRequest;
import cloud.dnation.jenkins.plugins.hetzner.client.DatacenterDetail;
import cloud.dnation.jenkins.plugins.hetzner.client.LocationDetail;
import cloud.dnation.jenkins.plugins.hetzner.client.PrimaryIpDetail;
import org.junit.Test;

import static cloud.dnation.jenkins.plugins.hetzner.primaryip.AbstractByLabelSelector.isIpUsable;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrimaryIpStrategyTest {
    private static final DatacenterDetail FSN1DC14;
    private static final DatacenterDetail NBG1DC4;
    private static final LocationDetail FSN1;
    private static final LocationDetail NBG1;
    static {
        FSN1 = new LocationDetail();
        FSN1.setName("fsn1");
        FSN1DC14 = new DatacenterDetail();
        FSN1DC14.setName("fsn1-dc14");
        FSN1DC14.setLocation(FSN1);
        NBG1 = new LocationDetail();
        NBG1.setName("nbg1");
        NBG1DC4 = new DatacenterDetail();
        NBG1DC4.setName("nbg1-dc3");
        NBG1DC4.setLocation(NBG1);
    }
    @Test
    public void testIpIsUsable() {
        final CreateServerRequest server = new CreateServerRequest();
        final PrimaryIpDetail ip = new PrimaryIpDetail();
        //Same datacenter
        server.setDatacenter(FSN1DC14.getName());
        ip.setDatacenter(FSN1DC14);
        assertTrue(isIpUsable(ip, server));

        //Same location
        server.setDatacenter(null);
        server.setLocation("fsn1");
        assertTrue(isIpUsable(ip, server));

        //Different datacenter
        ip.setDatacenter(NBG1DC4);
        server.setDatacenter(FSN1DC14.getName());
        server.setLocation(null);
        assertFalse(isIpUsable(ip, server));

        //Already allocated
        ip.setAssigneeId(0);
        assertFalse(isIpUsable(ip, server));
    }
}
