package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.source.ReadOnlySourceService;
import com.sage.business.code.contract.v2.source.SourceFetchRequest;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * POST /api/v2/sources/fetch (EW10.2, design §10.9). Read-only Web/issue/docs
 * source retrieval through the Java Host adapter. The Idempotency-Key is
 * required by the v2 mutation convention even though a fetch is a pure read:
 * the request creates no state and appends no journal events.
 */
public final class SourceApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final ReadOnlySourceService sources;

    public SourceApiHandler(ObjectMapper mapper, String token, ReadOnlySourceService sources) {
        super(mapper, token);
        if (sources == null) throw new IllegalArgumentException("sources is required");
        this.sources = sources;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!"/api/v2/sources/fetch".equals(path)) return false;
        if (!"POST".equals(method)) throw methodNotAllowed();
        requireAccess(exchange);
        mutationKey(exchange);
        SourceFetchRequest request = body(exchange, SourceFetchRequest.class);
        sendJson(exchange, 200, sources.fetch(request));
        return true;
    }
}
