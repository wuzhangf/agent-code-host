package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.parallel.ParallelPlanApplicationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal operable HTTP entry for the Host parallel-plan state machine
 * (review HOST-P0-01): propose -> approve -> execute, plus status,
 * cancel and same-batch recovery. Disabled or misconfigured hosts fail
 * closed with a structured error; the bearer token check reuses the
 * server's shared secret contract.
 */
public final class ParallelPlanHttpHandler implements HttpHandler {

    private final ParallelPlanApplicationService service;
    private final ObjectMapper mapper;
    private final String token;

    public ParallelPlanHttpHandler(ParallelPlanApplicationService service,
                                   ObjectMapper mapper, String token) {
        this.service = service;
        this.mapper = mapper;
        this.token = token;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String auth = exchange.getRequestHeaders().getFirst(
                    "Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                send(exchange, 401, err("UNAUTHORIZED"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            Map<String, Object> body = readBody(exchange);
            switch (path) {
                case "/api/v1/parallel/propose":
                    requirePost(method);
                    send(exchange, 200, service.propose(
                            text(body.get("workspace")),
                            text(body.get("goal")),
                            strings(body.get("authorizedWritePaths")),
                            strings(body.get("forbiddenPaths")),
                            2, text(body.get("sessionId"))));
                    return;
                case "/api/v1/parallel/approve":
                    requirePost(method);
                    service.approve(text(body.get("proposalId")),
                            text(body.get("planFingerprint")),
                            text(body.get("graphFingerprint")),
                            text(body.get("proposalFingerprintV2")));
                    send(exchange, 200, ok("APPROVED"));
                    return;
                case "/api/v1/parallel/execute":
                    requirePost(method);
                    send(exchange, 200, service.execute(
                            text(body.get("proposalId")),
                            text(body.get("sessionId"))));
                    return;
                case "/api/v1/parallel/status":
                    send(exchange, 200, service.status(query(exchange,
                            "batchId")));
                    return;
                case "/api/v1/parallel/cancel":
                    requirePost(method);
                    send(exchange, 200, ok(service.cancel(
                            text(body.get("correlationId")))
                            ? "CANCEL_REQUESTED" : "NO_ACTIVE_RUN"));
                    return;
                case "/api/v1/parallel/recover":
                    requirePost(method);
                    send(exchange, 200,
                            service.recover(text(body.get("batchId"))));
                    return;
                default:
                    send(exchange, 404, err("UNKNOWN_PARALLEL_ROUTE"));
            }
        } catch (IllegalStateException e) {
            // Fail-closed configuration and boundary violations surface
            // as structured errors, never as a silent legacy fallback.
            send(exchange, 409, err(String.valueOf(e.getMessage())));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, err(String.valueOf(e.getMessage())));
        } catch (Exception e) {
            send(exchange, 500, err("PARALLEL_INTERNAL_ERROR"));
        }
    }

    private static void requirePost(String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            throw new IllegalArgumentException("POST required");
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private String query(HttpExchange exchange, String key) {
        String q = exchange.getRequestURI().getQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpExchange exchange)
            throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            return new LinkedHashMap<String, Object>();
        }
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<String, Object>();
        }
        return mapper.readValue(bytes, Map.class);
    }

    private void send(HttpExchange exchange, int status,
                      Map<String, Object> payload) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, Object> ok(String message) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", message);
        return map;
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("error", message);
        return map;
    }
}
