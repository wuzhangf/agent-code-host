package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.extension.ExtensionPlane;
import com.sage.business.code.application.mcp.McpConnectionManager;
import com.sage.business.code.application.mcp.McpProtocolProfile;
import com.sage.business.code.application.mcp.McpServerConfig;
import com.sage.business.code.application.mcp.McpServerRegistryService;
import com.sage.business.code.application.mcp.McpSourceService;
import com.sage.business.code.application.mcp.McpToolCatalogService;
import com.sage.business.code.application.mcp.McpToolDescriptor;
import com.sage.business.code.application.mcp.McpTransport;
import com.sage.business.code.contract.v2.mcp.McpAuthActionRequest;
import com.sage.business.code.contract.v2.mcp.McpConnectionActionRequest;
import com.sage.business.code.contract.v2.mcp.McpOperationResponse;
import com.sage.business.code.contract.v2.mcp.McpPromptResolveRequest;
import com.sage.business.code.contract.v2.mcp.McpResourceReadRequest;
import com.sage.business.code.application.mcp.McpRuntimeOperationService;
import com.sage.business.code.contract.v2.mcp.McpServerConfigRequest;
import com.sage.business.code.contract.v2.mcp.McpServerDetailResponse;
import com.sage.business.code.contract.v2.mcp.McpServerStatusRequest;
import com.sage.business.code.contract.v2.mcp.McpServerUpdateRequest;
import com.sage.business.code.contract.v2.mcp.McpToolDescriptorView;
import com.sage.business.code.contract.v2.mcp.McpToolListResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP routes (design §9.1). The legacy read-only source surface stays
 * intact; the governed surface below delegates to the {@link ExtensionPlane}
 * and fails closed with EXT_HOST_NOT_WIRED when the host is not assembled.
 * Every mutation requires an Idempotency-Key per the v2 convention.
 */
public final class McpApiHandler extends V2HttpSupport implements V2RouteGroup {

    /** principal identity for the single-user desktop host. */
    private static final String PRINCIPAL = "local-owner";

    private final McpSourceService mcp;
    private final ExtensionPlane extension;

    public McpApiHandler(ObjectMapper mapper, String token, McpSourceService mcp,
                       ExtensionPlane extension) {
        super(mapper, token);
        if (mcp == null) throw new IllegalArgumentException("mcp is required");
        this.mcp = mcp;
        this.extension = extension;
    }

    /** Legacy constructor: read-only source surface only. */
    public McpApiHandler(ObjectMapper mapper, String token, McpSourceService mcp) {
        this(mapper, token, mcp, null);
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (path.startsWith("/api/v2/mcp/operations/")) {
            requireAccess(exchange);
            requirePlane();
            if (!"GET".equals(method)) throw methodNotAllowed();
            String operationId = path.substring("/api/v2/mcp/operations/".length());
            if (extension.operations != null) {
                McpOperationResponse durable =
                        extension.operations.operation(PRINCIPAL, operationId);
                if (durable != null) {
                    sendJson(exchange, 200, durable);
                    return true;
                }
            }
            if (extension.oauth != null) {
                McpOperationResponse op = extension.oauth.operation(operationId);
                if (op != null) {
                    sendJson(exchange, 200, op);
                    return true;
                }
            }
            throw new BusinessException("MCP_OPERATION_NOT_FOUND", 404,
                    "unknown operationId " + operationId);
        }
        if (!path.startsWith("/api/v2/mcp/servers")) return false;
        String[] parts = path.split("/");
        if (parts.length >= 5 && "servers".equals(parts[4])) {
            if (parts.length == 5) {
                requireAccess(exchange);
                if ("GET".equals(method)) {
                    // ?view=governed → public-safe detail rows (digests,
                    // counts, slot flags); default keeps the legacy summary.
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query != null && query.contains("view=governed")) {
                        requirePlane();
                        sendJson(exchange, 200, extension.registry.governedList(PRINCIPAL));
                        return true;
                    }
                    sendJson(exchange, 200, mcp.servers());
                    return true;
                }
                if ("POST".equals(method)) {
                    // Governed registration (§9.1 registerMcpServer).
                    String key = mutationKey(exchange);
                    requirePlane();
                    final McpServerConfigRequest request =
                            body(exchange, McpServerConfigRequest.class);
                    sendJson(exchange, 200, mutate(null, "SERVER_REGISTER", key,
                            request, McpServerDetailResponse.class,
                            new McpRuntimeOperationService.OperationAction<McpServerDetailResponse>() {
                                @Override public McpServerDetailResponse run(String operationId) {
                                    return extension.registry.register(PRINCIPAL, request);
                                }
                            }));
                    return true;
                }
                throw methodNotAllowed();
            }
            String serverId = parts[5];
            if (parts.length == 6) {
                requireAccess(exchange);
                if ("GET".equals(method)) {
                    requirePlane();
                    sendJson(exchange, 200,
                            extension.registry.detail(PRINCIPAL, serverId));
                    return true;
                }
                if ("PATCH".equals(method)) {
                    String key = mutationKey(exchange);
                    requirePlane();
                    final McpServerUpdateRequest request =
                            body(exchange, McpServerUpdateRequest.class);
                    sendJson(exchange, 200, mutate(serverId, "SERVER_UPDATE", key,
                            request, McpServerDetailResponse.class,
                            new McpRuntimeOperationService.OperationAction<McpServerDetailResponse>() {
                                @Override public McpServerDetailResponse run(String operationId) {
                                    return extension.registry.update(PRINCIPAL, serverId, request);
                                }
                            }));
                    return true;
                }
                throw methodNotAllowed();
            }
            if (parts.length == 7 && "status".equals(parts[6])) {
                String key = mutationKey(exchange);
                requireAccess(exchange);
                requirePlane();
                if (!"POST".equals(method)) throw methodNotAllowed();
                final McpServerStatusRequest request = body(exchange, McpServerStatusRequest.class);
                sendJson(exchange, 200, mutate(serverId, "SERVER_STATUS", key,
                        request, McpServerDetailResponse.class,
                        new McpRuntimeOperationService.OperationAction<McpServerDetailResponse>() {
                            @Override public McpServerDetailResponse run(String operationId) {
                                return extension.registry.setStatus(PRINCIPAL, serverId, request);
                            }
                        }));
                return true;
            }
            if (parts.length == 7 && ("connection-actions".equals(parts[6])
                    || "actions".equals(parts[6]))) {
                String key = mutationKey(exchange);
                requireAccess(exchange);
                requirePlane();
                if (!"POST".equals(method)) throw methodNotAllowed();
                final McpConnectionActionRequest request =
                        body(exchange, McpConnectionActionRequest.class);
                sendJson(exchange, 202, mutate(serverId, "CONNECTION_ACTION", key,
                        request, McpOperationResponse.class,
                        new McpRuntimeOperationService.OperationAction<McpOperationResponse>() {
                            @Override public McpOperationResponse run(String operationId) {
                                return connectionAction(serverId, request, operationId);
                            }
                        }));
                return true;
            }
            if (parts.length == 7 && "auth-actions".equals(parts[6])) {
                mutationKey(exchange);
                requireAccess(exchange);
                requirePlane();
                if (!"POST".equals(method)) throw methodNotAllowed();
                McpAuthActionRequest request = body(exchange, McpAuthActionRequest.class);
                if (extension.oauth == null) {
                    throw new BusinessException("EXT_HOST_NOT_WIRED", 503,
                            "oauth flow is not assembled on this host");
                }
                McpServerRegistryService.Resolved resolved =
                        extension.registry.resolve(PRINCIPAL, serverId);
                sendJson(exchange, 202, extension.oauth.action(resolved.config, request.getAction()));
                return true;
            }
            if (parts.length == 7 && "tools".equals(parts[6])) {
                requireAccess(exchange);
                requirePlane();
                if (!"GET".equals(method)) throw methodNotAllowed();
                sendJson(exchange, 200, listTools(serverId));
                return true;
            }
            if (parts.length == 7 && ("catalog-refresh".equals(parts[6])
                    || "refresh".equals(parts[6]))) {
                String key = mutationKey(exchange);
                requireAccess(exchange);
                requirePlane();
                if (!"POST".equals(method)) throw methodNotAllowed();
                sendJson(exchange, 202, mutate(serverId, "CATALOG_REFRESH", key,
                        "CATALOG_REFRESH:" + serverId, McpOperationResponse.class,
                        new McpRuntimeOperationService.OperationAction<McpOperationResponse>() {
                            @Override public McpOperationResponse run(String operationId) {
                                return refreshCatalog(serverId, operationId);
                            }
                        }));
                return true;
            }
            if (parts.length == 7 && "resources".equals(parts[6])) {
                requireAccess(exchange);
                if (!"GET".equals(method)) throw methodNotAllowed();
                sendJson(exchange, 200, mcp.resources(serverId));
                return true;
            }
            if (parts.length == 8 && "resources".equals(parts[6]) && "read".equals(parts[7])) {
                requireAccess(exchange);
                if (!"POST".equals(method)) throw methodNotAllowed();
                mutationKey(exchange);
                McpResourceReadRequest request = body(exchange, McpResourceReadRequest.class);
                sendJson(exchange, 200, mcp.read(serverId, request.getUri(),
                        request.getInputResponses(), request.getRequestState()));
                return true;
            }
            // WU12 capability-gated advanced discovery.
            if (parts.length == 7 && "resource-templates".equals(parts[6])) {
                requireAccess(exchange);
                requirePlane();
                if (!"GET".equals(method)) throw methodNotAllowed();
                String cursor = queryParam(exchange, "cursor");
                sendJson(exchange, 200,
                        extension.discovery.listTemplates(PRINCIPAL, serverId, cursor));
                return true;
            }
            if (parts.length == 7 && "prompts".equals(parts[6])) {
                requireAccess(exchange);
                requirePlane();
                if (!"GET".equals(method)) throw methodNotAllowed();
                String cursor = queryParam(exchange, "cursor");
                sendJson(exchange, 200,
                        extension.discovery.listPrompts(PRINCIPAL, serverId, cursor));
                return true;
            }
            if (parts.length == 8 && "prompts".equals(parts[6])
                    && "resolve".equals(parts[7])) {
                requireAccess(exchange);
                requirePlane();
                if (!"POST".equals(method)) throw methodNotAllowed();
                mutationKey(exchange);
                McpPromptResolveRequest request =
                        body(exchange, McpPromptResolveRequest.class);
                if (request == null || request.getName() == null
                        || request.getName().trim().isEmpty()) {
                    throw new BusinessException("INVALID_REQUEST", 400,
                            "prompt name is required");
                }
                sendJson(exchange, 200, extension.discovery.resolvePrompt(
                        PRINCIPAL, serverId, request.getName(), request.getArguments(),
                        request.getInputResponses(), request.getRequestState()));
                return true;
            }
        }
        return false;
    }

    // ---- governed surface ----

    private void requirePlane() {
        if (extension == null) {
            throw new BusinessException("EXT_HOST_NOT_WIRED", 503,
                    "the extension host is not assembled on this service");
        }
    }

    /**
     * Run one management mutation with real idempotency when the durable
     * operation service is assembled: replays of the same key and request
     * return the stored outcome; the same key with a different request
     * answers 409. Hosts without the service keep the inline path.
     */
    private <T> T mutate(String serverId, String kind, String idempotencyKey,
            Object request, Class<T> resultType,
            McpRuntimeOperationService.OperationAction<T> action) {
        if (extension.operations == null) {
            try {
                return action.run("op-" + java.util.UUID.randomUUID()
                        .toString().replace("-", "").substring(0, 12));
            } catch (BusinessException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new BusinessException("MCP_OPERATION_FAILED", 500,
                        String.valueOf(failure.getMessage()));
            }
        }
        return extension.operations.mutate(PRINCIPAL, serverId, null, kind,
                idempotencyKey, request, resultType, action);
    }

    /**
     * CONNECT / DISCONNECT / TEST, executed inside a durable operation when
     * the runtime operation service is assembled: the terminal outcome is
     * persisted with the request digest so a replayed Idempotency-Key
     * returns the stored outcome instead of re-executing.
     */
    private McpOperationResponse connectionAction(String serverId,
                                                  McpConnectionActionRequest request,
                                                  String operationId) {
        if (request == null || request.getAction() == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "action must be CONNECT, DISCONNECT or TEST");
        }
        McpServerRegistryService.Resolved resolved =
                extension.registry.resolve(PRINCIPAL, serverId);
        McpOperationResponse response = new McpOperationResponse();
        response.setOperationId(operationId);
        response.setServerId(serverId);
        String action = request.getAction();
        try {
            if ("DIAGNOSE".equals(action)) {
                // WU08 dependency diagnostics: the doctor's findings are
                // public-safe (transport validity, process-tree isolation,
                // slot-name presence — never slot material). detail carries
                // the JSON array so the durable operation replays it verbatim.
                java.util.List<com.sage.business.code.application.mcp
                        .McpDependencyDoctor.Finding> findings =
                        new com.sage.business.code.application.mcp
                                .McpDependencyDoctor(extension.secrets)
                                .diagnose(resolved.config);
                response.setStatus("SUCCEEDED");
                try {
                    response.setDetail(mapper.writeValueAsString(findings));
                } catch (com.fasterxml.jackson.core.JsonProcessingException serial) {
                    response.setDetail("MCP_DIAGNOSE_SERIALIZE: findings could not be encoded");
                }
            } else if ("DISCONNECT".equals(action)) {
                int closed = extension.connections.disconnect(serverId);
                extension.registry.markConnectionState(serverId, "STOPPED", null);
                response.setStatus("SUCCEEDED");
                response.setDetail("closed " + closed + " pooled connection(s)");
            } else {
                // CONNECT and TEST both establish a live transport; TEST is
                // read-side (initialize handshake only, no tool calls).
                if (!resolved.record.enabled) {
                    throw new BusinessException("MCP_SERVER_DISABLED", 409,
                            "server is disabled: " + serverId);
                }
                extension.registry.markConnectionState(serverId, "CONNECTING", null);
                McpTransport transport = extension.connections.acquire(
                        PRINCIPAL, resolved.record.projectId, resolved.config,
                        resolved.record.revision);
                McpProtocolProfile profile =
                        transport instanceof com.sage.business.code.application.mcp.McpStdioConnection
                        ? ((com.sage.business.code.application.mcp.McpStdioConnection) transport)
                                .profile() : null;
                extension.registry.markConnectionState(serverId, "READY", null);
                response.setStatus("SUCCEEDED");
                response.setDetail("READY" + (profile == null ? "" : " via " + profile.name()));
            }
        } catch (BusinessException failure) {
            extension.registry.markConnectionState(serverId, "FAILED", failure.getCode());
            throw failure;
        } catch (Exception failure) {
            extension.registry.markConnectionState(serverId, "FAILED", "MCP_CONNECTION_FAILED");
            response.setStatus("FAILED");
            response.setDetail("MCP_CONNECTION_FAILED: connection failed");
        }
        return response;
    }

    private McpToolListResponse listTools(String serverId) {
        McpServerRegistryService.Resolved resolved =
                extension.registry.resolve(PRINCIPAL, serverId);
        McpProtocolProfile profile = "PIN".equalsIgnoreCase(resolved.config.protocolPolicy)
                ? McpProtocolProfile.resolve(resolved.config.protocolProfile)
                : McpProtocolProfile.resolve("AUTO");
        McpToolCatalogService.Catalog snap = extension.catalog.cached(
                PRINCIPAL, serverId, resolved.record.revision, profile.name());
        McpToolListResponse out = new McpToolListResponse();
        List<McpToolDescriptorView> views = new ArrayList<McpToolDescriptorView>();
        if (snap != null) {
            for (McpToolDescriptor tool : snap.tools) {
                McpToolDescriptorView view = new McpToolDescriptorView();
                view.setToolId(tool.toolId);
                view.setRawName(tool.rawName);
                view.setTitle(tool.title);
                view.setDescription(tool.description);
                view.setInputSchema(tool.inputSchema == null ? null
                        : mapper.convertValue(tool.inputSchema,
                                java.util.Map.class));
                view.setOutputSchema(tool.outputSchema == null ? null
                        : mapper.convertValue(tool.outputSchema,
                                java.util.Map.class));
                view.setSchemaDigest(tool.schemaDigest);
                view.setEffectiveRisk(tool.effectiveRisk);
                view.setAvailability(tool.availability);
                view.setAvailabilityReason(tool.unavailableReason);
                view.setSchemaDialect(tool.schemaDialect);
                view.setValidatorVersion(tool.validatorVersion);
                views.add(view);
            }
            out.setCatalogRevision(snap.catalogRevision);
            out.setCached(Boolean.TRUE);
        }
        out.setItems(views);
        return out;
    }

    private McpOperationResponse refreshCatalog(String serverId, String operationId) {
        McpServerRegistryService.Resolved resolved =
                extension.registry.resolve(PRINCIPAL, serverId);
        if (!resolved.record.enabled) {
            throw new BusinessException("MCP_SERVER_DISABLED", 409,
                    "server is disabled: " + serverId);
        }
        McpOperationResponse response = new McpOperationResponse();
        response.setOperationId(operationId);
        response.setServerId(serverId);
        try {
            McpTransport transport = extension.connections.acquire(
                    PRINCIPAL, resolved.record.projectId, resolved.config,
                    resolved.record.revision);
            McpProtocolProfile profile =
                    transport instanceof com.sage.business.code.application.mcp.McpStdioConnection
                    ? ((com.sage.business.code.application.mcp.McpStdioConnection) transport)
                            .profile() : McpProtocolProfile.resolve("AUTO");
            McpToolCatalogService.Catalog snap = extension.catalog.refresh(
                    PRINCIPAL, "local", resolved.config, resolved.record.revision,
                    transport, profile);
            response.setStatus("SUCCEEDED");
            response.setDetail("catalog " + snap.snapshotId
                    + " (" + snap.tools.size() + " tools)");
        } catch (IOException failure) {
            response.setStatus("FAILED");
            response.setDetail("MCP_CONNECTION_FAILED: catalog refresh failed");
        }
        return response;
    }
}
