package com.sage.business.code.bootstrap;

import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Focused contract coverage for the Business capability operation projection. */
public class BusinessServiceFactoryCapabilityTest {

    @Test
    public void projectsWorkspaceDiscoveryOperationsWithProductLimits() throws Exception {
        Method factory = BusinessServiceFactory.class.getDeclaredMethod(
                "capabilityResponse", String.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, String.class,
                boolean.class, String.class, String.class,
                com.sage.business.code.application.terminal.TerminalApplicationService.class,
                com.sage.business.code.bootstrap.TerminalSettings.class);
        factory.setAccessible(true);

        CapabilityResponse response = (CapabilityResponse) factory.invoke(
                null, "svc-test", false, false, false, false, false, "NONE", false, "NOT_CONFIGURED", "DISABLED", null, null);
        Map<String, CapabilityResponse.OperationCapability> operations = byId(
                response.getOperations());

        CapabilityResponse.OperationCapability glob =
                operations.get("agent.tool.workspace.glob");
        Assert.assertNotNull(glob);
        Assert.assertEquals("AVAILABLE", glob.getAvailability());
        Assert.assertNull(glob.getReasonCode());
        Assert.assertEquals("2.2", glob.getContractVersion());
        Assert.assertEquals(Integer.valueOf(100), glob.getLimits().get("maxResultsPerPage"));
        Assert.assertEquals(Boolean.TRUE, glob.getLimits().get("cursor"));

        CapabilityResponse.OperationCapability batchRead =
                operations.get("agent.tool.workspace.batchRead");
        Assert.assertNotNull(batchRead);
        Assert.assertEquals("AVAILABLE", batchRead.getAvailability());
        Assert.assertNull(batchRead.getReasonCode());
        Assert.assertEquals("2.2", batchRead.getContractVersion());
        Assert.assertEquals(Integer.valueOf(10), batchRead.getLimits().get("maxFiles"));
        Assert.assertEquals(Boolean.TRUE, batchRead.getLimits().get("aggregateBudget"));
        Assert.assertEquals(Boolean.TRUE, batchRead.getLimits().get("perItemContinuation"));

        Assert.assertTrue("legacy operations remain projected",
                operations.containsKey("session.create.inspect"));
        Assert.assertEquals(new HashMap<String, Object>(),
                operations.get("session.create.inspect").getLimits());

        CapabilityResponse.OperationCapability permission = operations.get(
                "permission.mode.update");
        Assert.assertNotNull(permission);
        Assert.assertEquals("AVAILABLE", permission.getAvailability());
        Assert.assertNull(permission.getReasonCode());
    }

    private static Map<String, CapabilityResponse.OperationCapability> byId(
            List<CapabilityResponse.OperationCapability> operations) {
        Map<String, CapabilityResponse.OperationCapability> result =
                new HashMap<String, CapabilityResponse.OperationCapability>();
        for (CapabilityResponse.OperationCapability operation : operations) {
            result.put(operation.getId(), operation);
        }
        return result;
    }
}
