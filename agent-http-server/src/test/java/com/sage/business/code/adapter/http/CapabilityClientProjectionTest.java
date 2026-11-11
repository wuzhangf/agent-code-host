package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import org.junit.Assert;
import org.junit.Test;

public class CapabilityClientProjectionTest {
    @Test public void blockedEnvironmentDoesNotInvalidateOrdinaryHandshake() {
        CapabilityResponse source = new CapabilityResponse();
        CapabilityResponse.OperationCapability op = new CapabilityResponse.OperationCapability();
        op.setId("environment.prepare"); op.setAvailability("BLOCKED");
        op.setContractVersion("2.2"); op.setReasonCode("ENVIRONMENT_NOT_READY");
        source.getOperations().add(op);
        CapabilityApiHandler handler = new CapabilityApiHandler(new ObjectMapper(), "unused", source);
        CapabilityResponse desktop = handler.projectForClient(source, false);
        Assert.assertEquals("UNAVAILABLE", desktop.getOperations().get(0).getAvailability());
        Assert.assertEquals("ENVIRONMENT_NOT_READY", desktop.getOperations().get(0).getReasonCode());
        Assert.assertEquals("BLOCKED", source.getOperations().get(0).getAvailability());
        Assert.assertSame(source, handler.projectForClient(source, true));
    }
    @Test public void ordinaryHandshakeKeepsPublishedEnumWithoutMutatingWorkerReadiness() {
        CapabilityResponse source = new CapabilityResponse();
        for (String state : new String[]{"READY", "DISABLED"}) {
            CapabilityResponse.OperationCapability op = new CapabilityResponse.OperationCapability();
            op.setId("READY".equals(state) ? "context.packageV1" : "run.assignmentTransitionV1");
            op.setAvailability(state); source.getOperations().add(op);
        }
        CapabilityApiHandler handler = new CapabilityApiHandler(new ObjectMapper(), "unused", source);
        CapabilityResponse desktop = handler.projectForClient(source, false);
        Assert.assertEquals("AVAILABLE", desktop.getOperations().get(0).getAvailability());
        Assert.assertEquals("UNAVAILABLE", desktop.getOperations().get(1).getAvailability());
        Assert.assertEquals("READY", source.getOperations().get(0).getAvailability());
        Assert.assertEquals("DISABLED", source.getOperations().get(1).getAvailability());
        Assert.assertSame(source, handler.projectForClient(source, true));
    }
}
