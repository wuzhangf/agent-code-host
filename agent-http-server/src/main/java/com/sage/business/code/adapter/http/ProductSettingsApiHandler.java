package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.knowledge.KnowledgeService;
import com.sage.business.code.application.policy.PolicyLayerService;
import com.sage.business.code.application.settings.ProductSettingsService;
import com.sage.business.code.contract.v2.product.ProductSettingsUpdateRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EW9 product plane route group: policy precedence, product settings and
 * the knowledge suggestion/confirm flow. Read projections are public-safe;
 * mutations require an Idempotency-Key and bump revisions.
 */
public final class ProductSettingsApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final PolicyLayerService policyLayers;
    private final ProductSettingsService settings;
    private final KnowledgeService knowledge;

    public ProductSettingsApiHandler(ObjectMapper mapper, String token,
            PolicyLayerService policyLayers, ProductSettingsService settings,
            KnowledgeService knowledge) {
        super(mapper, token);
        if (policyLayers == null || settings == null || knowledge == null) {
            throw new IllegalArgumentException("EW9 services are required");
        }
        this.policyLayers = policyLayers;
        this.settings = settings;
        this.knowledge = knowledge;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (path.equals("/api/v2/policies/layers")) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, policyLayers.layers());
            return true;
        }
        if (path.startsWith("/api/v2/policies/") && path.indexOf('/', "/api/v2/policies/".length()) < 0) {
            requireAccess(exchange);
            if (!"PUT".equals(method)) throw methodNotAllowed();
            String layer = path.substring("/api/v2/policies/".length());
            if (!layer.matches("[A-Za-z0-9._-]{1,128}")) {
                throw new BusinessException("INVALID_REQUEST", 400, "policy layer is invalid");
            }
            String idempotencyKey = mutationKey(exchange);
            JsonNode body = readBody(exchange);
            com.sage.business.code.contract.v2.product.PolicyLayerUpdateRequest request =
                    mapper.convertValue(body,
                            com.sage.business.code.contract.v2.product.PolicyLayerUpdateRequest.class);
            if (request.getExpectedRevision() == null || request.getExpectedRevision() < 0L) {
                throw new BusinessException("MISSING_CONCURRENCY_PRECONDITION", 428,
                        "expectedRevision is required for policy changes");
            }
            sendJson(exchange, 200, policyLayers.update(layer, request, idempotencyKey));
            return true;
        }
        if (path.equals("/api/v2/settings")) {
            requireAccess(exchange);
            if ("GET".equals(method)) {
                sendJson(exchange, 200, settings.get());
                return true;
            }
            if ("PUT".equals(method)) {
                String idempotencyKey = mutationKey(exchange);
                ProductSettingsUpdateRequest request = body(exchange, ProductSettingsUpdateRequest.class);
                if (request.getExpectedRevision() == null || request.getExpectedRevision() < 1L) {
                    throw new BusinessException("MISSING_CONCURRENCY_PRECONDITION", 428,
                            "expectedRevision is required for settings changes");
                }
                sendJson(exchange, 200, settings.update(request, idempotencyKey));
                return true;
            }
            throw methodNotAllowed();
        }
        if (path.equals("/api/v2/memory/knowledge")) {
            requireAccess(exchange);
            if ("GET".equals(method)) {
                sendJson(exchange, 200, knowledge.list());
                return true;
            }
            if ("POST".equals(method)) {
                String key = mutationKey(exchange);
                JsonNode body = readBody(exchange);
                sendJson(exchange, 201, knowledge.suggest(
                        body == null ? null : body.path("content").asText(null),
                        body == null || !body.hasNonNull("sourceRef") ? null : body.path("sourceRef").asText(),
                        key));
                return true;
            }
            throw methodNotAllowed();
        }
        if (path.startsWith("/api/v2/memory/knowledge/")
                && path.endsWith("/confirm")) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            String knowledgeId = path.substring("/api/v2/memory/knowledge/".length(),
                    path.length() - "/confirm".length());
            safeEntityId(knowledgeId, "knowledge");
            String idempotencyKey = mutationKey(exchange);
            sendJson(exchange, 200, knowledge.confirm(knowledgeId, idempotencyKey));
            return true;
        }
        return false;
    }

    private JsonNode readBody(HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) throw new BusinessException("INVALID_REQUEST", 400, "Request body is required");
        try {
            return mapper.readTree(raw);
        } catch (Exception failure) {
            throw new BusinessException("INVALID_REQUEST", 400, "Request body cannot be parsed");
        }
    }
}
