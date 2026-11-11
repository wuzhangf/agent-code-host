package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.lifecycle.LocalLifecycleService;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;

/**
 * Local lifecycle route group (desktop consolidation design §5.3/MC-07).
 *
 * <p>{@code POST /api/v2/local-lifecycle/restart-prepare|restart-cancel|
 * shutdown}. Every request needs the bearer token AND the supervisor
 * startup nonce in {@code X-Service-Nonce}; the nonce is delivered to the
 * child only through its process environment, never persisted, never
 * logged. A service without a nonce reports UNSUPPORTED so shared/attach
 * deployments are unaffected.</p>
 */
public final class LocalLifecycleApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    /** Header carrying the supervisor's per-boot nonce. */
    public static final String NONCE_HEADER = "X-Service-Nonce";
    private static final String PREFIX = "/api/v2/local-lifecycle/";

    private final LocalLifecycleService lifecycle;

    public LocalLifecycleApiHandler(ObjectMapper mapper, String token,
                                    LocalLifecycleService lifecycle) {
        super(mapper, token);
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle service is required");
        }
        this.lifecycle = lifecycle;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
                               String method) throws IOException {
        if (path == null || !path.startsWith(PREFIX)) return false;
        requireAccess(exchange);
        if (!"POST".equals(method)) throw methodNotAllowed();
        String presented = exchange.getRequestHeaders().getFirst(NONCE_HEADER);
        if (!lifecycle.isManaged()) {
            // Never gate unmanaged services on a nonce they do not have;
            // the caller learns the surface is unsupported instead.
            Map<String, Object> unsupported =
                    new java.util.LinkedHashMap<String, Object>();
            unsupported.put("state", "UNSUPPORTED");
            sendJson(exchange, 200, unsupported);
            return true;
        }
        if (!lifecycle.nonceMatches(presented)) {
            throw new BusinessException("SERVICE_NONCE_INVALID", 403,
                    "service nonce does not match the owning supervisor");
        }
        String action = path.substring(PREFIX.length());
        Map<String, Object> body = requestBody(exchange);
        String token = body == null ? null : stringField(body, "restartToken");
        if ("restart-prepare".equals(action)) {
            sendJson(exchange, 200, lifecycle.prepare());
        } else if ("restart-cancel".equals(action)) {
            sendJson(exchange, 200, lifecycle.cancel(token));
        } else if ("shutdown".equals(action)) {
            sendJson(exchange, 200, lifecycle.shutdown(token));
        } else {
            throw new BusinessException("INVALID_REQUEST", 404,
                    "Endpoint not found");
        }
        return true;
    }

    /** The request body is optional for prepare; strict JSON object otherwise. */
    private Map<String, Object> requestBody(HttpExchange exchange)
            throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) return null;
        try {
            Object parsed = mapper.readValue(raw, Map.class);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) parsed;
                return body;
            }
            return null;
        } catch (Exception failure) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Request body cannot be parsed");
        }
    }

    private static String stringField(Map<String, Object> body, String name) {
        Object value = body.get(name);
        return value instanceof String ? (String) value : null;
    }
}
