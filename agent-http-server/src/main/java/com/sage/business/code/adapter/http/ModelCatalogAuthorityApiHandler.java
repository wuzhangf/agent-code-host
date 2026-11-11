package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.model.ModelCatalogAuthorityService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * GET /api/v2/model-catalog/authority (MA-001 slice MA04): one coherent
 * snapshot of the catalog authority posture — source kind, applied
 * release identity, sync/freshness state, receipt state and the last
 * typed error. Bearer-gated like its siblings; never includes secret
 * material and never performs a model call.
 */
public final class ModelCatalogAuthorityApiHandler
        extends V2HttpSupport implements V2RouteGroup {

    private final ModelCatalogAuthorityService authority;

    public ModelCatalogAuthorityApiHandler(ObjectMapper mapper,
            String token, ModelCatalogAuthorityService authority) {
        super(mapper, token);
        if (authority == null) {
            throw new IllegalArgumentException("authority is required");
        }
        this.authority = authority;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
            String method) throws IOException {
        if (!"/api/v2/model-catalog/authority".equals(path)) {
            return false;
        }
        if (!"GET".equals(method)) {
            throw methodNotAllowed();
        }
        requireAccess(exchange);
        sendJson(exchange, 200, authority.status());
        return true;
    }
}
