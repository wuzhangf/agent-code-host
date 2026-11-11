package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.model.PublicModelCatalogService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** GET /api/v2/model-catalog route group (desktop design §9.8). */
public final class ModelCatalogApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final PublicModelCatalogService catalog;

    public ModelCatalogApiHandler(ObjectMapper mapper, String token,
            PublicModelCatalogService catalog) {
        super(mapper, token);
        if (catalog == null) throw new IllegalArgumentException("catalog is required");
        this.catalog = catalog;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!"/api/v2/model-catalog".equals(path)) return false;
        if (!"GET".equals(method)) throw methodNotAllowed();
        requireAccess(exchange);
        sendJson(exchange, 200, catalog.list());
        return true;
    }
}
