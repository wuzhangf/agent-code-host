package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.workspace.WorkspaceExecutionService;
import com.sage.business.code.contract.v2.workspace.WorkspaceExecutionCreateRequest;
import com.sage.business.code.contract.v2.workspace.WorkspaceExecutionLeaseRenewRequest;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * /api/v2/workspace-executions route group (EW7, design §8.4/§10.3).
 * Provisions and drives service-owned workspaces with lease/fencing;
 * every mutation requires an Idempotency-Key and fails closed on conflict.
 */
public final class WorkspaceExecutionApiHandler extends V2HttpSupport implements V2RouteGroup {
    private static final String PREFIX = "/api/v2/workspace-executions";
    private final WorkspaceExecutionService workspaceExecutions;

    public WorkspaceExecutionApiHandler(ObjectMapper mapper, String token,
            WorkspaceExecutionService workspaceExecutions) {
        super(mapper, token);
        if (workspaceExecutions == null) {
            throw new IllegalArgumentException("workspace execution service is required");
        }
        this.workspaceExecutions = workspaceExecutions;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) return false;
        String[] parts = path.split("/");
        if (PREFIX.equals(path)) {
            requireAccess(exchange);
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                WorkspaceExecutionCreateRequest request =
                        body(exchange, WorkspaceExecutionCreateRequest.class);
                sendJson(exchange, 201, workspaceExecutions.create(request, key));
                return true;
            }
            if ("GET".equals(method)) {
                sendJson(exchange, 200,
                        workspaceExecutions.list(queryParam(exchange, "projectId")));
                return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length < 5) return false;
        String id = parts[4];
        safeEntityId(id, "workspace execution");
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, workspaceExecutions.view(id));
            return true;
        }
        if (parts.length == 6 && "lease".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            WorkspaceExecutionLeaseRenewRequest request =
                    body(exchange, WorkspaceExecutionLeaseRenewRequest.class);
            sendJson(exchange, 200, workspaceExecutions.renewLease(id, request));
            return true;
        }
        if (parts.length == 6 && "handoff".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            Long expectedVersion = readExpectedVersion(exchange);
            sendJson(exchange, 200, workspaceExecutions.handoff(id, expectedVersion));
            return true;
        }
        if (parts.length == 6 && "reclaim".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            Long expectedVersion = readExpectedVersion(exchange);
            sendJson(exchange, 200, workspaceExecutions.reclaim(id, expectedVersion));
            return true;
        }
        return false;
    }

    private Long readExpectedVersion(HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) return null;
        final com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Request body cannot be parsed");
        }
        if (node == null || !node.hasNonNull("expectedStateVersion")) return null;
        return Long.valueOf(node.path("expectedStateVersion").asLong());
    }
}
