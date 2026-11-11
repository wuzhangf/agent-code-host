package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.operation.GovernanceCommandReceiptStore;
import com.sage.business.code.contract.v2.operation.OperationLookupRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Read-only cross-client receipt lookup; it never admits or replays work. */
public final class OperationReceiptApiHandler extends V2HttpSupport
        implements V2RouteGroup, com.sun.net.httpserver.HttpHandler {
    public static final String LOCAL_SERVICE_PRINCIPAL = "local-owner";
    private final GovernanceCommandReceiptStore receipts;

    public OperationReceiptApiHandler(ObjectMapper mapper, String token,
            GovernanceCommandReceiptStore receipts) {
        super(mapper, token);
        if (receipts == null) throw new IllegalArgumentException("receipt store is required");
        this.receipts = receipts;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        try {
            if ("/api/v2/operations/lookup-created-session".equals(path)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                String key = mutationKey(exchange);
                com.fasterxml.jackson.databind.JsonNode request = body(exchange,
                        com.fasterxml.jackson.databind.JsonNode.class);
                if (request == null || !request.isObject() || request.size() != 1
                        || !request.path("idempotencyKey").isTextual()
                        || !key.equals(request.path("idempotencyKey").asText())) {
                    throw new BusinessException("INVALID_REQUEST", 400,
                            "lookup requires the original matching idempotencyKey only");
                }
                sendJson(exchange, 200, receipts.lookupCreatedSession(LOCAL_SERVICE_PRINCIPAL, key));
                return true;
            }
            if ("/api/v2/operations/lookup".equals(path)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                String headerKey = mutationKey(exchange);
                OperationLookupRequest request = body(exchange, OperationLookupRequest.class);
                if (request == null) throw new BusinessException("INVALID_REQUEST", 400,
                        "lookup body is required");
                if (!headerKey.equals(request.getIdempotencyKey())) {
                    throw new BusinessException("INVALID_REQUEST", 400,
                            "Idempotency-Key must match lookup idempotencyKey");
                }
                sendJson(exchange, 200, receipts.lookup(LOCAL_SERVICE_PRINCIPAL,
                        request.getOperation(), request.getResourceId(), request.getIdempotencyKey()));
                return true;
            }
            if (path.startsWith("/api/v2/operations/") && path.length() > "/api/v2/operations/".length()) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                String receiptId = path.substring("/api/v2/operations/".length());
                sendJson(exchange, 200, receipts.byReceiptId(LOCAL_SERVICE_PRINCIPAL, receiptId));
                return true;
            }
            return false;
        } catch (BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(),
                    failure.getMessage(), failure.getDetails());
            return true;
        }
    }

    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!handleGroup(exchange, exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod())) {
                throw new BusinessException("INVALID_REQUEST", 404, "Endpoint not found");
            }
        } catch (BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(),
                    failure.getMessage(), failure.getDetails());
        } finally { exchange.close(); }
    }
}
