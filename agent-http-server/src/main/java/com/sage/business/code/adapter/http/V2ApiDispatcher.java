package com.sage.business.code.adapter.http;

import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Dispatcher for the /api/v2 plane (desktop design §11.3). Route groups are
 * tried in registration order; the first group that owns the path handles
 * the exchange, otherwise the dispatcher answers with the single canonical
 * 404 problem. Business exceptions are mapped to problems exactly once.
 */
public final class V2ApiDispatcher implements HttpHandler {
    /** Request attribute consumed by handlers that need the authenticated holder identity. */
    public static final String CLIENT_KIND_ATTRIBUTE = "com.sage.business.code.clientKind";
    public static final String CLIENT_SESSION_ID_ATTRIBUTE =
            "com.sage.business.code.clientSessionId";
    /** Monotonic-ish wall-clock projection used to cap a terminal lease. */
    public static final String CLIENT_SESSION_REMAINING_TTL_ATTRIBUTE =
            "com.sage.business.code.clientSessionRemainingTtlMillis";
    /** Registration-declared display label of the authenticated client. */
    public static final String CLIENT_DISPLAY_ATTRIBUTE =
            "com.sage.business.code.clientDisplayLabel";
    private final List<V2RouteGroup> groups;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final com.sage.business.code.application.client.ClientSessionRegistry clientSessions;
    private final boolean registrationRequiredForMutation;
    /** Legacy bearer/ticket streams are read-only and globally bounded. */
    private final Semaphore legacyStreamSlots = new Semaphore(2, true);

    public V2ApiDispatcher(com.fasterxml.jackson.databind.ObjectMapper mapper, List<V2RouteGroup> groups) {
        this(mapper, groups, null, false);
    }

    public V2ApiDispatcher(com.fasterxml.jackson.databind.ObjectMapper mapper,
            List<V2RouteGroup> groups,
            com.sage.business.code.application.client.ClientSessionRegistry clientSessions,
            boolean registrationRequiredForMutation) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("at least one route group is required");
        }
        if (mapper == null) throw new IllegalArgumentException("mapper is required");
        this.mapper = mapper;
        this.groups = new ArrayList<V2RouteGroup>(groups);
        this.clientSessions = clientSessions;
        this.registrationRequiredForMutation = registrationRequiredForMutation;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean legacyStream = clientSessions != null
                && isLegacyReadOnlyStream(path, method)
                && (exchange.getRequestHeaders().getFirst(
                        com.sage.business.code.spi.LocalAccessPrincipals.CLIENT_TOKEN_HEADER) == null
                    || exchange.getRequestHeaders().getFirst(
                        com.sage.business.code.spi.LocalAccessPrincipals.CLIENT_TOKEN_HEADER).trim().isEmpty());
        boolean legacySlotHeld = false;
        try {
            if (legacyStream) {
                legacySlotHeld = legacyStreamSlots.tryAcquire();
                if (!legacySlotHeld) {
                    throw new BusinessException("STREAM_LIMIT_REACHED", 429,
                            "Unregistered legacy stream capacity is exhausted");
                }
            }
            enforceClientSession(path, method, exchange);
            for (V2RouteGroup group : groups) {
                if (group.handleGroup(exchange, path, method)) return;
            }
            throw new BusinessException("INVALID_REQUEST", 404, "Endpoint not found");
        } catch (BusinessException failure) {
            sendProblemOnly(exchange, failure);
        } catch (RuntimeException failure) {
            // Unchecked faults (step crashes, provider bugs) must still
            // answer with a problem — a silent close leaves the worker
            // staring at an empty response forever.
            sendProblemOnly(exchange, new BusinessException(
                    "INTERNAL", 500, "internal failure: "
                            + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? ""
                                    : " " + failure.getMessage())));
        } finally {
            if (legacySlotHeld) legacyStreamSlots.release();
            exchange.close();
        }
    }

    private static boolean isLegacyReadOnlyStream(String path, String method) {
        if (!"GET".equals(method)) return false;
        return "/api/v2/events".equals(path)
                || path.matches("/api/v2/sessions/[^/]+/events")
                || path.matches("/api/v2/missions/[^/]+/events");
    }

    private void enforceClientSession(String path, String method,
            HttpExchange exchange) {
        path = WorkerHostApiHandler.sourcePath(path);
        if (clientSessions == null || isClientSessionRoute(path)) {
            return;
        }
        String token = exchange.getRequestHeaders().getFirst(
                com.sage.business.code.spi.LocalAccessPrincipals.CLIENT_TOKEN_HEADER);
        if (token != null && !token.trim().isEmpty()) {
            // The client never supplies a session id for ordinary requests;
            // the registry derives it from the secret and verifies its owner.
            com.sage.business.code.application.client.ClientSessionRegistry.ValidatedToken session =
                    clientSessions.validateTokenWithRemainingTtl(
                    com.sage.business.code.spi.LocalAccessPrincipals.LOCAL_OWNER_PRINCIPAL,
                    token);
            // Do not make downstream handlers hash or otherwise reconstruct a
            // secret-derived identity.  The registry's opaque session id is
            // the only holder identity that may enter a control lease.
            exchange.setAttribute(CLIENT_SESSION_ID_ATTRIBUTE,
                    session.getSession().getClientSessionId());
            exchange.setAttribute(CLIENT_KIND_ATTRIBUTE,session.getSession().getClientKind());
            exchange.setAttribute(CLIENT_DISPLAY_ATTRIBUTE,session.getSession().getDisplayLabel());
            exchange.setAttribute(CLIENT_SESSION_REMAINING_TTL_ATTRIBUTE,
                    Long.valueOf(session.getRemainingTtlMillis()));
            return;
        }
        if (requiresRegisteredSession(path, method)
                || (registrationRequiredForMutation && isMutation(method))) {
            throw new BusinessException("CLIENT_REGISTRATION_REQUIRED", 428,
                    "client session registration is required for this endpoint");
        }
    }

    private static boolean requiresRegisteredSession(String path, String method) {
        if (path.matches("/api/v2/runs/[^/]+/commands(?:/[^/]+)?")) return true;
        if (path.matches("/api/v2/sessions/[^/]+/(foundation|observation-snapshots(?:/[^/]+(?:/items)?)?)")) return true;
        if ("/api/v2/event-snapshots".equals(path)
                || "/api/v2/operations/lookup".equals(path)) return true;
        if ("GET".equals(method) && path.startsWith("/api/v2/operations/")) return true;
        return path.matches("/api/v2/terminal-sessions/[^/]+/control/(acquire|renew|release)")
                || path.matches("/api/v2/terminal-sessions/[^/]+/(input|resize|signals)")
                || ("DELETE".equals(method)
                    && path.matches("/api/v2/terminal-sessions/[^/]+"));
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static boolean isClientSessionRoute(String path) {
        return "/api/v2/client-sessions".equals(path)
                || path.startsWith("/api/v2/client-sessions/");
    }

    private void sendProblemOnly(HttpExchange exchange, BusinessException failure)
            throws IOException {
        java.util.Map<String, Object> problem = new java.util.LinkedHashMap<String, Object>();
        problem.put("code", failure.getCode());
        problem.put("status", failure.getHttpStatus());
        problem.put("message", failure.getMessage());
        if (failure.getDetails() != null) problem.putAll(failure.getDetails());
        if (!problem.containsKey("failure")) {
            problem.put("failure", com.sage.business.code.application.run.FailureDescriptorAdapter.from(
                    null, mapper, failure.getCode(), failure.getMessage()));
        }
        byte[] body = mapper.writeValueAsBytes(problem);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json; charset=utf-8");
        if (failure.getHttpStatus() == 429) {
            exchange.getResponseHeaders().set("Retry-After", "1");
        }
        try {
            exchange.sendResponseHeaders(failure.getHttpStatus(), body.length);
            java.io.OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        } catch (IOException ignored) {
            // The stream may already be committed; nothing else can be sent.
        }
    }
}
