package com.sage.business.code.bootstrap;

import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/** Focused WP5 contract test for the Business capability projection. */
public class BusinessServiceFactoryCapabilityProjectionTest {

    @Test
    public void projectsWorkspaceToolOperationsWithTheirBoundedLimits() throws Exception {
        Method factory = BusinessServiceFactory.class.getDeclaredMethod(
                "capabilityResponse", String.class, boolean.class, boolean.class,
                boolean.class, boolean.class, boolean.class, String.class,
                boolean.class, String.class, String.class,
                com.sage.business.code.application.terminal.TerminalApplicationService.class,
                com.sage.business.code.bootstrap.TerminalSettings.class);
        factory.setAccessible(true);

        CapabilityResponse response = (CapabilityResponse) factory.invoke(
                null, "svc-test", false, false, false, false, false, null, false, "NOT_CONFIGURED", "DISABLED", null, null);
        Map<String, CapabilityResponse.OperationCapability> operations = new HashMap<String,
                CapabilityResponse.OperationCapability>();
        for (CapabilityResponse.OperationCapability operation : response.getOperations()) {
            operations.put(operation.getId(), operation);
        }

        CapabilityResponse.OperationCapability glob = operations.get(
                "agent.tool.workspace.glob");
        Assert.assertNotNull(glob);
        Assert.assertEquals("AVAILABLE", glob.getAvailability());
        Assert.assertNull(glob.getReasonCode());
        Assert.assertEquals("2.2", glob.getContractVersion());
        Assert.assertEquals(Integer.valueOf(100), glob.getLimits().get("maxResultsPerPage"));
        Assert.assertEquals(Boolean.TRUE, glob.getLimits().get("cursor"));

        CapabilityResponse.OperationCapability batchRead = operations.get(
                "agent.tool.workspace.batchRead");
        Assert.assertNotNull(batchRead);
        Assert.assertEquals("AVAILABLE", batchRead.getAvailability());
        Assert.assertNull(batchRead.getReasonCode());
        Assert.assertEquals("2.2", batchRead.getContractVersion());
        Assert.assertEquals(Integer.valueOf(10), batchRead.getLimits().get("maxFiles"));
        Assert.assertEquals(Boolean.TRUE, batchRead.getLimits().get("aggregateBudget"));
        Assert.assertEquals(Boolean.TRUE, batchRead.getLimits().get("perItemContinuation"));

        CapabilityResponse.OperationCapability policy = operations.get(
                "execution.policy.read");
        Assert.assertNotNull(policy);
        Assert.assertEquals(Boolean.FALSE,
                policy.getLimits().get("modelSuppliedRiskFacts"));
        Assert.assertEquals(java.util.Arrays.asList(
                        "PRODUCT_CODE.command", "PRODUCT_CODE.write",
                        "PRODUCT_CODE.sandbox", "PRODUCT_CODE.budget"),
                policy.getLimits().get("wiredExecutionEntries"));

        // The service must not claim capabilities whose actual execution
        // authority is still in Product/Framework.  In particular, a ready
        // USER terminal and read-only MCP source do not imply agent grants.
        CapabilityResponse.OperationCapability agentTerminal = operations.get(
                "terminal.agent.open");
        Assert.assertNotNull(agentTerminal);
        Assert.assertEquals("UNAVAILABLE", agentTerminal.getAvailability());
        Assert.assertEquals("TERMINAL_AGENT_GRANT_NOT_WIRED",
                agentTerminal.getReasonCode());

        CapabilityResponse.OperationCapability git = operations.get(
                "git.delivery.mutate");
        Assert.assertNotNull(git);
        Assert.assertEquals("UNAVAILABLE", git.getAvailability());
        Assert.assertEquals("GIT_DELIVERY_ADAPTER_NOT_WIRED", git.getReasonCode());

        // WU01 (design 2026-09-29 §10.2): without assembly facts the MCP
        // surface must fail closed — no hardcoded AVAILABLE, and the
        // profile projection explains the unaccepted modern profile.
        CapabilityResponse.OperationCapability mcp = operations.get(
                "mcp.tool.invoke");
        Assert.assertNotNull(mcp);
        Assert.assertEquals("UNAVAILABLE", mcp.getAvailability());
        Assert.assertEquals("EXT_HOST_NOT_WIRED", mcp.getReasonCode());

        CapabilityResponse.OperationCapability manage = operations.get(
                "mcp.server.manage");
        Assert.assertNotNull(manage);
        Assert.assertEquals("UNAVAILABLE", manage.getAvailability());
        Assert.assertEquals("EXT_HOST_NOT_WIRED", manage.getReasonCode());
        Assert.assertEquals("UNAVAILABLE",
                manage.getLimits().get("durableOperations"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> profiles =
                (java.util.Map<String, Object>) manage.getLimits()
                        .get("protocolProfiles");
        Assert.assertNotNull(profiles);
        Assert.assertEquals("AVAILABLE", profiles.get("2024-11-05"));
        Assert.assertEquals("AVAILABLE", profiles.get("2025-11-25"));
        Assert.assertEquals("UNAVAILABLE", profiles.get("2026-07-28"));

        CapabilityResponse.OperationCapability stdio = operations.get(
                "mcp.transport.stdio");
        Assert.assertNotNull(stdio);
        Assert.assertEquals("UNAVAILABLE", stdio.getAvailability());
        Assert.assertEquals("EXT_HOST_NOT_WIRED", stdio.getReasonCode());
        Assert.assertFalse("mcpHost is only true with a wired host",
                response.getFeatures().isMcpHost());

        CapabilityResponse.OperationCapability hook = operations.get(
                "hook.pretool.gate");
        Assert.assertNotNull(hook);
        Assert.assertEquals("UNAVAILABLE", hook.getAvailability());
        Assert.assertEquals("GOVERNANCE_HOOK_NOT_WIRED", hook.getReasonCode());

        CapabilityResponse.OperationCapability plan = operations.get(
                "mission.planrun.execute");
        Assert.assertNotNull(plan);
        Assert.assertEquals("UNAVAILABLE", plan.getAvailability());
        Assert.assertEquals("PLAN_PERMISSION_CONTEXT_NOT_WIRED", plan.getReasonCode());

        CapabilityResponse.OperationCapability backendRead = operations.get(
                "execution.backends.read");
        Assert.assertNotNull(backendRead);
        Assert.assertEquals("UNAVAILABLE", backendRead.getAvailability());
        Assert.assertEquals("SANDBOX_DISABLED", backendRead.getReasonCode());

        CapabilityResponse.OperationCapability sandbox = operations.get(
                "agent.tool.sandbox.shell");
        Assert.assertNotNull(sandbox);
        Assert.assertEquals("UNAVAILABLE", sandbox.getAvailability());
        Assert.assertEquals("SANDBOX_DISABLED", sandbox.getReasonCode());
        Assert.assertEquals(Boolean.FALSE, sandbox.getLimits().get("inspect"));
        Assert.assertEquals("SOURCE_ONLY",
                sandbox.getLimits().get("materializationProfile"));
    }
}
