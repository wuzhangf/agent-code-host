package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * GET /api/v2/capabilities (desktop design §9.2). The response body is
 * normally precomputed at composition time; the composition root may provide
 * a bounded supplier when one operation depends on an immutable cached
 * environment snapshot that changes after startup.
 */
public final class CapabilityApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final Supplier<CapabilityResponse> capabilitySupplier;

    public CapabilityApiHandler(ObjectMapper mapper, String token,
            CapabilityResponse capabilities) {
        super(mapper, token);
        if (capabilities == null) throw new IllegalArgumentException("capabilities is required");
        this.capabilitySupplier = new Supplier<CapabilityResponse>() {
            @Override public CapabilityResponse get() { return capabilities; }
        };
    }

    /** Dynamic projection constructor for bounded runtime snapshots. */
    public CapabilityApiHandler(ObjectMapper mapper, String token,
            Supplier<CapabilityResponse> capabilitySupplier) {
        super(mapper, token);
        if (capabilitySupplier == null) {
            throw new IllegalArgumentException("capabilitySupplier is required");
        }
        this.capabilitySupplier = capabilitySupplier;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!"/api/v2/capabilities".equals(path)) return false;
        if (!"GET".equals(method)) throw methodNotAllowed();
        requireAccess(exchange);
        CapabilityResponse capabilities = capabilitySupplier.get();
        if (capabilities == null) {
            throw new BusinessException("CAPABILITY_PROJECTION_UNAVAILABLE", 503,
                    "Capability projection is temporarily unavailable");
        }
        // Registered Workers retain the readiness vocabulary used by released
        // Worker providers. Ordinary clients consume the published handshake enum.
        boolean worker = "WORKER".equals(exchange.getAttribute(V2ApiDispatcher.CLIENT_KIND_ATTRIBUTE))
                && exchange.getRequestHeaders().getFirst(com.sage.business.code.spi.LocalAccessPrincipals.CLIENT_TOKEN_HEADER) != null;
        sendJson(exchange, 200, projectForClient(capabilities, worker));
        return true;
    }
    /** Shared live authority for the thin Worker host binding. */
    public CapabilityResponse snapshot() { return capabilitySupplier.get(); }
    CapabilityResponse projectForClient(CapabilityResponse source, boolean worker) {
        if (worker) return source;
        CapabilityResponse projection = mapper.convertValue(source, CapabilityResponse.class);
        for (CapabilityResponse.OperationCapability operation : projection.getOperations()) {
            // Environment readiness uses BLOCKED internally. Ordinary clients
            // validate the published availability enum before opening any UI;
            // an unavailable optional environment must not reject the host.
            if ("BLOCKED".equals(operation.getAvailability())) {
                operation.setAvailability("UNAVAILABLE");
                continue;
            }
            String id = operation.getId();
            if (!java.util.Arrays.asList("session.sharedObserveV1", "run.commandReceiptV1",
                    "context.observationSnapshotV1", "context.packageV1", "session.auxiliaryReadOnlyV1",
                    "run.boundaryInputV1", "run.assignmentTransitionV1", "worker.executionWindowV1").contains(id)) continue;
            if ("READY".equals(operation.getAvailability())) operation.setAvailability("AVAILABLE");
            else if ("DISABLED".equals(operation.getAvailability())) operation.setAvailability("UNAVAILABLE");
        }
        return projection;
    }
}
