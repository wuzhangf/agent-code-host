package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.hook.HookService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * EW10.6 typed hook routes: read-only health projections (design §10.6).
 * Hooks themselves are operator-allowlisted configuration — the API can
 * never register or modify one.
 */
public final class HookApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final HookService hooks;

    public HookApiHandler(ObjectMapper mapper, String token, HookService hooks) {
        super(mapper, token);
        if (hooks == null) throw new IllegalArgumentException("hooks is required");
        this.hooks = hooks;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!path.startsWith("/api/v2/hooks")) return false;
        requireAccess(exchange);
        if ("/api/v2/hooks".equals(path)) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, hooks.list());
            return true;
        }
        if ("/api/v2/hooks/executions".equals(path)) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, hooks.executions());
            return true;
        }
        return false;
    }
}
