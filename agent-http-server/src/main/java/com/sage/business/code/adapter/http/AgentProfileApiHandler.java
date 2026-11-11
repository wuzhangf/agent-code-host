package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.session.AgentProfileCatalog;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** GET /api/v2/agent-profiles; profile instructions never cross this boundary. */
public final class AgentProfileApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final AgentProfileCatalog catalog;

    public AgentProfileApiHandler(ObjectMapper mapper, String token,
            AgentProfileCatalog catalog) {
        super(mapper, token);
        if (catalog == null) throw new IllegalArgumentException("catalog is required");
        this.catalog = catalog;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!"/api/v2/agent-profiles".equals(path)) return false;
        if (!"GET".equals(method)) throw methodNotAllowed();
        requireAccess(exchange);
        sendJson(exchange, 200, catalog.response());
        return true;
    }
}
