package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.environment.EnvironmentFeatureService;
import com.sage.business.code.application.model.ManagedModelCatalogService;
import com.sage.business.code.application.websearch.ManagedWebSearchService;
import com.sage.business.code.contract.v2.environment.EnvironmentFeatureUpdateRequest;
import com.sage.business.code.contract.v2.model.ManagedModelProviderRequest;
import com.sage.business.code.contract.v2.model.ManagedModelRequest;
import com.sage.business.code.contract.v2.model.RemoteModelsProbeRequest;
import com.sage.business.code.contract.v2.websearch.WebSearchProviderRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Settings environment surface (settings design): capability cards with
 * desired/effective state plus preflight actions under
 * {@code /api/v2/environment/features}, and the two-level managed model
 * catalog under {@code /api/v2/model-providers}. All writes carry an
 * Idempotency-Key; MODEL catalog writes hot-apply the effective context
 * (non-model features still apply at the next service restart) —
 * responses keep the persisted revision for optimistic concurrency.
 */
public final class EnvironmentApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    private final EnvironmentFeatureService features;
    private final ManagedModelCatalogService models;
    private final ManagedWebSearchService webSearch;

    public EnvironmentApiHandler(ObjectMapper mapper, String token,
                                 EnvironmentFeatureService features,
                                 ManagedModelCatalogService models,
                                 ManagedWebSearchService webSearch) {
        super(mapper, token);
        if (features == null || models == null || webSearch == null) {
            throw new IllegalArgumentException(
                    "environment services are required");
        }
        this.features = features;
        this.models = models;
        this.webSearch = webSearch;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path,
                                         String method) throws IOException {
        if (path.startsWith("/api/v2/environment/features")) {
            return features(exchange, path, method);
        }
        if (path.startsWith("/api/v2/model-providers")) {
            return models(exchange, path, method);
        }
        if (path.startsWith("/api/v2/web-search-providers")) {
            return webSearch(exchange, path, method);
        }
        return false;
    }

    // ---- /api/v2/environment/features[/{id}[/preflight]] ----

    private boolean features(HttpExchange exchange, String path,
                             String method) throws IOException {
        String[] parts = path.split("/");
        // /api/v2/environment/features
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, features.list());
            return true;
        }
        // /api/v2/environment/features/{featureId}
        if (parts.length == 6) {
            requireAccess(exchange);
            String featureId = parts[5];
            safeEntityId(featureId, "feature");
            if ("GET".equals(method)) {
                sendJson(exchange, 200, features.card(featureId));
                return true;
            }
            if ("PUT".equals(method)) {
                mutationKey(exchange);
                EnvironmentFeatureUpdateRequest request =
                        body(exchange, EnvironmentFeatureUpdateRequest.class);
                sendJson(exchange, 200, features.update(featureId, request));
                return true;
            }
            throw methodNotAllowed();
        }
        // /api/v2/environment/features/{featureId}/preflight
        if (parts.length == 7 && "preflight".equals(parts[6])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            String featureId = parts[5];
            safeEntityId(featureId, "feature");
            sendJson(exchange, 200, features.preflight(featureId));
            return true;
        }
        return false;
    }

    // ---- /api/v2/model-providers[/{pid}[/models/{mid}]] ----

    private boolean models(HttpExchange exchange, String path,
                           String method) throws IOException {
        String[] parts = path.split("/");
        // /api/v2/model-providers
        if (parts.length == 4) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, models.list());
            return true;
        }
        // /api/v2/model-providers/remote-models — upstream supply-list
        // probe. Must be routed before the {providerId} branch so the
        // literal segment is not swallowed as an id.
        if (parts.length == 5 && "remote-models".equals(parts[4])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            RemoteModelsProbeRequest request =
                    body(exchange, RemoteModelsProbeRequest.class);
            sendJson(exchange, 200, models.probeRemoteModels(request));
            return true;
        }
        // /api/v2/model-providers/{providerId}
        if (parts.length == 5) {
            requireAccess(exchange);
            String providerId = parts[4];
            safeEntityId(providerId, "provider");
            if ("PUT".equals(method)) {
                mutationKey(exchange);
                ManagedModelProviderRequest request =
                        body(exchange, ManagedModelProviderRequest.class);
                sendJson(exchange, 200,
                        models.upsertProvider(providerId, request));
                return true;
            }
            if ("DELETE".equals(method)) {
                mutationKey(exchange);
                long expected = longParam(exchange, "expectedRevision", 0L);
                String replace = queryParam(exchange, "replaceDefault");
                models.deleteProvider(providerId, expected, replace);
                sendJson(exchange, 200, java.util.Collections
                        .singletonMap("deleted", Boolean.TRUE));
                return true;
            }
            throw methodNotAllowed();
        }
        // /api/v2/model-providers/{providerId}/models/{modelId} — the
        // model id may itself contain '/' (e.g. xiaomi/mimo-v2.5); both a
        // literal slash and %2F arrive decoded, so the id is the greedy
        // remainder of the path.
        if (parts.length >= 7 && "models".equals(parts[5])) {
            requireAccess(exchange);
            String providerId = parts[4];
            StringBuilder joined = new StringBuilder(parts[6]);
            for (int i = 7; i < parts.length; i++) {
                joined.append('/').append(parts[i]);
            }
            String modelId = joined.toString();
            safeEntityId(providerId, "provider");
            if ("PUT".equals(method)) {
                mutationKey(exchange);
                ManagedModelRequest request =
                        body(exchange, ManagedModelRequest.class);
                sendJson(exchange, 200,
                        models.upsertModel(providerId, modelId, request));
                return true;
            }
            if ("DELETE".equals(method)) {
                mutationKey(exchange);
                long expected = longParam(exchange, "expectedRevision", 0L);
                String replace = queryParam(exchange, "replaceDefault");
                models.deleteModel(providerId, modelId, expected, replace);
                sendJson(exchange, 200, java.util.Collections
                        .singletonMap("deleted", Boolean.TRUE));
                return true;
            }
            throw methodNotAllowed();
        }
        return false;
    }

    // ---- /api/v2/web-search-providers[/{recordId}] ----

    private boolean webSearch(HttpExchange exchange, String path,
                              String method) throws IOException {
        String[] parts = path.split("/");
        // /api/v2/web-search-providers
        if (parts.length == 4) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, webSearch.list());
            return true;
        }
        // /api/v2/web-search-providers/{providerId}
        if (parts.length == 5) {
            requireAccess(exchange);
            String providerId = parts[4];
            safeEntityId(providerId, "provider");
            if ("PUT".equals(method)) {
                mutationKey(exchange);
                WebSearchProviderRequest request =
                        body(exchange, WebSearchProviderRequest.class);
                sendJson(exchange, 200,
                        webSearch.upsertProvider(providerId, request));
                return true;
            }
            if ("DELETE".equals(method)) {
                mutationKey(exchange);
                long expected = longParam(exchange, "expectedRevision", 0L);
                webSearch.deleteProvider(providerId, expected);
                sendJson(exchange, 200, java.util.Collections
                        .singletonMap("deleted", Boolean.TRUE));
                return true;
            }
            throw methodNotAllowed();
        }
        return false;
    }
}
