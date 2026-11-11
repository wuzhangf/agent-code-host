package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.client.ClientRegistrationResult;
import com.sage.business.code.application.client.ClientSessionRecord;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.contract.v2.client.ClientHeartbeatResponse;
import com.sage.business.code.contract.v2.client.ClientRegistrationRequest;
import com.sage.business.code.contract.v2.client.ClientSessionResponse;
import com.sage.business.code.contract.v2.client.ClientSessionSummaryResponse;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP boundary for the temporary multi-client session registry. */
public final class ClientSessionApiHandler extends V2HttpSupport implements V2RouteGroup {
    public static final String CLIENT_TOKEN_HEADER = "X-CodeAgent-Client-Token";
    /** Existing bearer authentication maps to the same principal used by receipts and snapshots. */
    public static final String LOCAL_OWNER_PRINCIPAL = "local-owner";

    private final ClientSessionRegistry registry;

    public ClientSessionApiHandler(ObjectMapper mapper, String token,
            ClientSessionRegistry registry) {
        super(mapper, token);
        if (registry == null) throw new IllegalArgumentException("client session registry is required");
        this.registry = registry;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if ("/api/v2/client-sessions".equals(path)) {
            if ("POST".equals(method)) return register(exchange);
            if ("GET".equals(method)) return list(exchange);
            throw methodNotAllowed();
        }
        if (!path.startsWith("/api/v2/client-sessions/")) return false;
        String[] parts = path.split("/");
        if (parts.length < 5) return false;
        String sessionId = parts[4];
        safeEntityId(sessionId, "client session");
        if (parts.length == 5 && "DELETE".equals(method)) {
            requireAccess(exchange);
            registry.revoke(LOCAL_OWNER_PRINCIPAL, sessionId, clientToken(exchange));
            sendNoContent(exchange);
            return true;
        }
        if (parts.length == 6 && "heartbeat".equals(parts[5])
                && "POST".equals(method)) {
            requireAccess(exchange);
            mutationKey(exchange);
            ClientSessionRegistry.HeartbeatResult result = registry.heartbeat(
                    LOCAL_OWNER_PRINCIPAL, sessionId, clientToken(exchange));
            ClientHeartbeatResponse response = new ClientHeartbeatResponse();
            response.setClientSessionId(result.getSession().getClientSessionId());
            response.setServerTimeMillis(result.getServerTimeMillis());
            response.setRemainingTtlMillis(result.getRemainingTtlMillis());
            sendJson(exchange, 200, response);
            return true;
        }
        return false;
    }

    private boolean register(HttpExchange exchange) throws IOException {
        requireAccess(exchange);
        ClientRegistrationRequest request = body(exchange, ClientRegistrationRequest.class);
        if (request == null) {
            throw new com.sage.business.code.domain.BusinessException(
                    "INVALID_REQUEST", 400, "registration body is required");
        }
        String idempotencyKey = mutationKey(exchange);
        if (!idempotencyKey.equals(request.getRegistrationKey())) {
            throw new com.sage.business.code.domain.BusinessException(
                    "IDEMPOTENCY_PAYLOAD_MISMATCH", 409,
                    "Idempotency-Key must match registrationKey");
        }
        ClientSessionRegistry.RegistrationRequest internal =
                new ClientSessionRegistry.RegistrationRequest(
                        request.getRegistrationKey(), requestHash(request),
                        request.getClientId(), request.getProcessInstanceId(),
                        request.getClientKind(), request.getProtocols(),
                        request.getDisplayLabel());
        ClientRegistrationResult result = registry.register(LOCAL_OWNER_PRINCIPAL, internal);
        ClientSessionResponse response = new ClientSessionResponse();
        response.setClientSessionId(result.getClientSessionId());
        response.setClientSessionToken(result.getClientSessionToken());
        response.setStoreId(result.getStoreId());
        response.setBootId(result.getBootId());
        response.setTtlMillis(result.getTtlMillis());
        response.setRemainingTtlMillis(result.getRemainingTtlMillis());
        response.setReplay(result.isReplay());
        response.setAcceptedProtocols(result.getAcceptedProtocols());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        sendJson(exchange, 201, response);
        return true;
    }

    private boolean list(HttpExchange exchange) throws IOException {
        requireAccess(exchange);
        List<ClientSessionSummaryResponse> response = new ArrayList<ClientSessionSummaryResponse>();
        for (ClientSessionRecord record : registry.list(LOCAL_OWNER_PRINCIPAL)) {
            ClientSessionSummaryResponse item = new ClientSessionSummaryResponse();
            item.setClientSessionId(record.getClientSessionId());
            item.setPrincipalId(record.getPrincipalId());
            item.setClientId(record.getClientId());
            item.setClientKind(record.getClientKind());
            item.setAcceptedProtocols(record.getAcceptedProtocols());
            item.setDisplayLabel(record.getDisplayLabel());
            item.setCreatedAtMillis(record.getCreatedAtMillis());
            item.setLastHeartbeatAtMillis(record.getLastHeartbeatAtMillis());
            item.setExpiresAtMillis(record.getExpiresAtMillis());
            item.setState(record.getState());
            response.add(item);
        }
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("items", response);
        sendJson(exchange, 200, envelope);
        return true;
    }

    private String clientToken(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst(CLIENT_TOKEN_HEADER);
        if (token == null || token.trim().isEmpty() || token.length() > 256) {
            throw new com.sage.business.code.domain.BusinessException(
                    "CLIENT_SESSION_UNAUTHORIZED", 401,
                    "client session token is invalid");
        }
        return token;
    }

    /** Hashes only the normalized registration body; transport credentials never enter it. */
    private String requestHash(ClientRegistrationRequest request) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        normalized.put("clientId", request.getClientId());
        normalized.put("processInstanceId", request.getProcessInstanceId());
        normalized.put("clientKind", request.getClientKind());
        normalized.put("protocols", request.getProtocols());
        normalized.put("displayLabel", request.getDisplayLabel());
        try {
            byte[] bytes = mapper.writeValueAsBytes(normalized);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("unable to hash registration request", failure);
        }
    }

    private void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(204, -1L);
    }
}
