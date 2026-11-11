package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.run.FailureDescriptorAdapter;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared plumbing for v2 route groups (desktop design §11.3). Semantics are
 * identical to the legacy V2ApiHandler helpers: same status codes, same
 * messages, same 1 MiB body limit, same Idempotency-Key enforcement.
 */
public abstract class V2HttpSupport {
    protected final ObjectMapper mapper;
    protected final String token;

    protected V2HttpSupport(ObjectMapper mapper, String token) {
        if (mapper == null || token == null) {
            throw new IllegalArgumentException("mapper and token are required");
        }
        this.mapper = mapper;
        this.token = token;
    }

    // ---- access ----

    protected void requireAccess(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + token).equals(authorization)) {
            throw new BusinessException("UNAUTHORIZED", 401, "Bearer token is required");
        }
    }

    // ---- request plumbing ----

    protected static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            if (name.equals(pair.substring(0, equals))) {
                try {
                    return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException impossible) {
                    throw new IllegalStateException("UTF-8 is unavailable", impossible);
                }
            }
        }
        return null;
    }

    protected static long longParam(HttpExchange exchange, String name, long fallback) {
        String raw = queryParam(exchange, name);
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try { return Long.parseLong(raw.trim()); }
        catch (NumberFormatException invalid) {
            throw new BusinessException("INVALID_REQUEST", 400, name + " must be an integer");
        }
    }

    protected <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) throw new BusinessException("INVALID_REQUEST", 400,
                "Request body is required");
        try { return mapper.readValue(raw, type); }
        catch (Exception failure) {
            throw new BusinessException("INVALID_REQUEST", 400, "Request body cannot be parsed");
        }
    }

    protected String mutationKey(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (value == null || value.trim().isEmpty() || value.length() > 128) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Idempotency-Key is required and limited to 128 characters");
        }
        return value;
    }

    protected static void safeEntityId(String value, String kind) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new BusinessException("INVALID_REQUEST", 400, kind + " id is invalid");
        }
    }

    protected static byte[] readLimited(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read; long total = 0L;
        try {
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                // 多模态设计 §7.4（WU-10）：与 V2ApiHandler/服务层共用同一
                // InputLimits body 上限（Base64 膨胀 + 正文 + 64 KiB 预留）；
                // 超额在完整解码前停止。
                if (total > com.sage.business.code.contract.input.InputLimits.CURRENT
                        .maxRequestBodyBytes()) {
                    throw new BusinessException("INVALID_REQUEST", 413, "Request body exceeds "
                            + com.sage.business.code.contract.input.InputLimits.CURRENT
                                    .maxRequestBodyBytes()
                            + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally { input.close(); }
    }

    protected static BusinessException methodNotAllowed() {
        return new BusinessException("INVALID_REQUEST", 405, "Method not allowed");
    }

    // ---- response plumbing ----

    protected void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    protected void sendBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body == null ? 0 : body.length);
        OutputStream out = exchange.getResponseBody();
        if (body != null) out.write(body);
        out.close();
    }

    protected void sendProblem(HttpExchange exchange, String code, int status, String message,
            Map<String, Object> details) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<String, Object>();
        problem.put("code", code);
        problem.put("status", status);
        problem.put("message", message);
        if (details != null) problem.putAll(details);
        if (!problem.containsKey("failure")) {
            problem.put("failure", FailureDescriptorAdapter.from(null, mapper, code, message));
        }
        byte[] body = mapper.writeValueAsBytes(problem);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json; charset=utf-8");
        try {
            exchange.sendResponseHeaders(status, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        } catch (IOException ignored) {
            // The stream may already be committed; nothing else can be sent.
        }
    }
}
