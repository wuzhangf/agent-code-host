package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.extension.ExtensionPlane;
import com.sage.business.code.application.hub.HubConnectionService;
import com.sage.business.code.application.mcp.McpRuntimeOperationService;
import com.sage.business.code.contract.v2.hub.HubConnectionActionRequest;
import com.sage.business.code.contract.v2.hub.HubConnectionRequest;
import com.sage.business.code.contract.v2.hub.HubConnectionResponse;
import com.sage.business.code.contract.v2.hub.HubConnectionUpdateRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Hub association routes (uplift design §5/WU01):
 *
 *   GET    /api/v2/hub/connections                    list
 *   POST   /api/v2/hub/connections                    register
 *   GET    /api/v2/hub/connections/{id}               detail
 *   PATCH  /api/v2/hub/connections/{id}               update (CAS)
 *   POST   /api/v2/hub/connections/{id}/connection-actions
 *   POST   /api/v2/hub/connections/{id}/register-server
 *   GET    /api/v2/hub/connections/{id}/capabilities  live catalog probe
 *   GET    /api/v2/hub/connections/{id}/resources     resource occupancy
 *   GET    /api/v2/hub/connections/{id}/sessions      session list
 *   POST   /api/v2/hub/connections/{id}/sessions      acquire a lease
 *   GET    /api/v2/hub/connections/{id}/sessions/{sid}          detail
 *   POST   /api/v2/hub/connections/{id}/sessions/{sid}/control  lease action
 *
 * Mutations require the same Idempotency-Key convention as the MCP
 * surface. The Hub /mcp endpoint itself is governed through the normal
 * managed-server routes — this group only owns the association record.
 */
public final class HubApiHandler extends V2HttpSupport implements V2RouteGroup {

    private static final String PRINCIPAL =
            com.sage.business.code.spi.LocalAccessPrincipals.LOCAL_OWNER_PRINCIPAL;
    private static final String PREFIX = "/api/v2/hub/connections";

    private final ExtensionPlane extension;

    public HubApiHandler(ObjectMapper mapper, String token,
                       ExtensionPlane extension) {
        super(mapper, token);
        this.extension = extension;
    }

    private HubConnectionService service() {
        if (extension == null || extension.hub() == null) {
            throw new BusinessException("EXT_HOST_NOT_WIRED", 503,
                    "the extension host is not assembled on this service");
        }
        return extension.hub();
    }

    @Override public boolean handleGroup(HttpExchange exchange,
            String path, String method) throws IOException {
        if (!path.startsWith(PREFIX)) return false;
        requireAccess(exchange);
        String rest = path.substring(PREFIX.length());
        if (rest.isEmpty() || "/".equals(rest)) {
            if ("GET".equals(method)) {
                sendJson(exchange, 200, service().list(PRINCIPAL));
                return true;
            }
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                final HubConnectionRequest request =
                        body(exchange, HubConnectionRequest.class);
                sendJson(exchange, 200, mutate(null, "HUB_REGISTER", key,
                        request, HubConnectionResponse.class,
                        new McpRuntimeOperationService
                                .OperationAction<HubConnectionResponse>() {
                            @Override public HubConnectionResponse run(
                                    String operationId) {
                                return service().register(PRINCIPAL, request);
                            }
                        }));
                return true;
            }
            throw methodNotAllowed();
        }
        // /{id}[/{action}]
        String[] segments = rest.substring(1).split("/");
        String id = segments[0];
        safeEntityId(id, "hub connection");
        if (segments.length == 1) {
            if ("GET".equals(method)) {
                sendJson(exchange, 200, service().detail(PRINCIPAL, id));
                return true;
            }
            if ("PATCH".equals(method)) {
                String key = mutationKey(exchange);
                final HubConnectionUpdateRequest request =
                        body(exchange, HubConnectionUpdateRequest.class);
                sendJson(exchange, 200, mutate(id, "HUB_UPDATE", key,
                        request, HubConnectionResponse.class,
                        new McpRuntimeOperationService
                                .OperationAction<HubConnectionResponse>() {
                            @Override public HubConnectionResponse run(
                                    String operationId) {
                                return service().update(PRINCIPAL, id, request);
                            }
                        }));
                return true;
            }
            throw methodNotAllowed();
        }
        if (segments.length == 2
                && "connection-actions".equals(segments[1])) {
            String key = mutationKey(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            final HubConnectionActionRequest request =
                    body(exchange, HubConnectionActionRequest.class);
            sendJson(exchange, 200, mutate(id, "HUB_CONNECTION_ACTION", key,
                    request, HubConnectionResponse.class,
                    new McpRuntimeOperationService
                            .OperationAction<HubConnectionResponse>() {
                        @Override public HubConnectionResponse run(
                                String operationId) {
                            return service().connectionAction(PRINCIPAL, id,
                                    request.getAction());
                        }
                    }));
            return true;
        }
        if (segments.length == 2 && "register-server".equals(segments[1])) {
            String key = mutationKey(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, mutate(id, "HUB_REGISTER_SERVER", key,
                    id, HubConnectionResponse.class,
                    new McpRuntimeOperationService
                            .OperationAction<HubConnectionResponse>() {
                        @Override public HubConnectionResponse run(
                                String operationId) {
                            return service().ensureManagedServer(
                                    PRINCIPAL, id);
                        }
                    }));
            return true;
        }
        if (segments.length == 2 && "capabilities".equals(segments[1])) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            try {
                sendJson(exchange, 200,
                        service().port(PRINCIPAL, id).capabilities());
            } catch (BusinessException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new BusinessException("HUB_UNREACHABLE", 502,
                        "hub probe failed: " + id);
            }
            return true;
        }
        // ---- v2 resource-session passthrough (Hub W3 contract) ----
        if (segments.length == 2 && "resources".equals(segments[1])) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, service().listResources(PRINCIPAL, id));
            return true;
        }
        if (segments.length == 2 && "sessions".equals(segments[1])) {
            if ("GET".equals(method)) {
                sendJson(exchange, 200,
                        service().listSessions(PRINCIPAL, id));
                return true;
            }
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                final JsonNode request = body(exchange, JsonNode.class);
                sendJson(exchange, 200, mutate(id, "HUB_SESSION_ACQUIRE",
                        key, request, JsonNode.class,
                        new McpRuntimeOperationService
                                .OperationAction<JsonNode>() {
                            @Override public JsonNode run(
                                    String operationId) {
                                return service().acquireSession(
                                        PRINCIPAL, id, request);
                            }
                        }));
                return true;
            }
            throw methodNotAllowed();
        }
        if (segments.length == 3 && "sessions".equals(segments[1])) {
            String sid = segments[2];
            safeEntityId(sid, "hub session");
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, service().getSession(PRINCIPAL, id, sid));
            return true;
        }
        if (segments.length == 4 && "sessions".equals(segments[1])
                && "control".equals(segments[3])) {
            String sid = segments[2];
            safeEntityId(sid, "hub session");
            if (!"POST".equals(method)) throw methodNotAllowed();
            String key = mutationKey(exchange);
            final JsonNode request = body(exchange, JsonNode.class);
            sendJson(exchange, 200, mutate(id, "HUB_SESSION_CONTROL",
                    key, request, JsonNode.class,
                    new McpRuntimeOperationService
                            .OperationAction<JsonNode>() {
                        @Override public JsonNode run(String operationId) {
                            return service().sessionControl(
                                    PRINCIPAL, id, sid, request);
                        }
                    }));
            return true;
        }
        return false;
    }

    /**
     * Same durable-mutation seam as {@link McpApiHandler}: replays of an
     * Idempotency-Key return the stored outcome when the operation service
     * is assembled; otherwise the action runs inline.
     */
    private <T> T mutate(String entityId, String kind, String idempotencyKey,
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
                throw new BusinessException("HUB_OPERATION_FAILED", 500,
                        String.valueOf(failure.getMessage()));
            }
        }
        return extension.operations.mutate(PRINCIPAL, entityId, null, kind,
                idempotencyKey, request, resultType, action);
    }
}
