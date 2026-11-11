package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Native-fleet binding over the existing ACS authority, never a second run store. */
public final class WorkerHostApiHandler extends V2HttpSupport implements V2RouteGroup {
    public static final String ROOT = "/worker-host/v1";
    public static final String V2_ROOT = "/api/v2/worker-host/v1";
    private final ClientSessionRegistry identity;
    private final Supplier<CapabilityResponse> capabilities;
    private final List<V2RouteGroup> delegates;
    private static final List<String> PATHS = Collections.unmodifiableList(Arrays.asList(
            "/manifest", "/capabilities", "/sessions", "/sessions/{id}",
            "/sessions/{id}/messages", "/sessions/{id}/forks", "/runs/{id}",
            "/runs/{id}/commands", "/runs/{id}/commands/{key}", "/operations/lookup"));

    public WorkerHostApiHandler(ObjectMapper mapper, String token, ClientSessionRegistry identity,
            Supplier<CapabilityResponse> capabilities, List<V2RouteGroup> delegates) {
        super(mapper, token);
        if (identity == null || capabilities == null || delegates == null)
            throw new IllegalArgumentException("existing identity, capabilities and route groups required");
        this.identity = identity;
        this.capabilities = capabilities;
        this.delegates = new ArrayList<V2RouteGroup>(delegates);
    }

    /** Also used before dispatcher authorization, so aliases cannot weaken source guards. */
    public static String sourcePath(String path) {
        String tail = tail(path);
        if (tail == null) return path;
        if (tail.matches("/sessions(?:/[A-Za-z0-9._-]{1,128}(?:/(?:messages|forks))?)?")
                || tail.matches("/runs/[A-Za-z0-9._-]{1,128}(?:/commands(?:/[A-Za-z0-9._-]{1,128})?)?")
                || "/operations/lookup".equals(tail)) return "/api/v2" + tail;
        return path;
    }

    private static String tail(String path) {
        if (path.startsWith(V2_ROOT + "/")) return path.substring(V2_ROOT.length());
        if (path.startsWith(ROOT + "/")) return path.substring(ROOT.length());
        return null;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method) throws IOException {
        String tail = tail(path);
        if (tail == null) return false;
        requireAccess(exchange);
        String store = exchange.getRequestHeaders().getFirst("X-Worker-Local-Store");
        if (store != null && !identity.getStoreId().equals(store))
            throw new BusinessException("STORE_IDENTITY_CHANGED", 409, "Worker store identity changed");
        if ("/manifest".equals(tail) || "/capabilities".equals(tail)) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            List<Map<String,Object>> items = capabilityItems();
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            if ("/capabilities".equals(tail)) {
                result.put("schemaVersion", "worker-capabilities/1"); result.put("items", items);
            } else {
                result.put("schemaVersion", "worker-host/1");
                result.put("authorityId", "acs:" + identity.getStoreId());
                result.put("workerId", "acs:" + identity.getStoreId());
                result.put("storeId", identity.getStoreId()); result.put("bootId", identity.getBootId());
                Map<String,Object> engine = new LinkedHashMap<String,Object>();
                engine.put("id", "acs"); engine.put("version", com.sage.business.code.domain.ServiceVersion.SERVICE_VERSION);
                result.put("engine", engine); result.put("contracts", Arrays.asList("worker-host/1", "business-codeagent-v2"));
                result.put("supportedPaths", PATHS); result.put("capabilities", items);
                // Existing ACS DTOs/receipts are intentionally returned unchanged.
                result.put("payloadContract", "business-codeagent-v2");
                result.put("apiRoot", ROOT); result.put("sourceApiRoot", "/api/v2");
            }
            sendJson(exchange, 200, result); return true;
        }
        String source = sourcePath(path);
        if (!source.equals(path)) for (V2RouteGroup delegate : delegates)
            if (delegate.handleGroup(exchange, source, method)) return true;
        throw new BusinessException("INVALID_REQUEST", 404, "Worker host route is not published");
    }

    private List<Map<String,Object>> capabilityItems() {
        CapabilityResponse current = capabilities.get();
        if (current == null) throw new BusinessException("CAPABILITY_PROJECTION_UNAVAILABLE", 503, "Capabilities unavailable");
        List<Map<String,Object>> rows = new ArrayList<Map<String,Object>>();
        for (CapabilityResponse.OperationCapability operation : current.getOperations()) {
            Map<String,Object> row = new LinkedHashMap<String,Object>();
            row.put("schemaVersion", "worker-capability/1");
            row.put("id", operation.getId()); row.put("implementation", "IMPLEMENTED");
            row.put("qualification", "NOT_RUN");
            row.put("availability", Arrays.asList("AVAILABLE", "READY").contains(operation.getAvailability()) ? "AVAILABLE" : "UNAVAILABLE");
            row.put("semantics", "ACS_SOURCE_CONTRACT"); row.put("sourceAvailability", operation.getAvailability());
            row.put("sourceContract", operation.getContractVersion()); row.put("limits", operation.getLimits());
            row.put("evidenceRef", null); row.put("reasonCode", operation.getReasonCode()); rows.add(row);
        }
        return rows;
    }
}
