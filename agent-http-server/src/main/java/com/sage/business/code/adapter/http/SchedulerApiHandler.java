package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.scheduler.SchedulerProjectionService;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Public scheduler projection; clients observe it but never schedule locally. */
public final class SchedulerApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final SchedulerProjectionService scheduler;
    public SchedulerApiHandler(ObjectMapper mapper, String token, SchedulerProjectionService scheduler) {
        super(mapper, token);
        if (scheduler == null) throw new IllegalArgumentException("scheduler is required");
        this.scheduler = scheduler;
    }
    @Override public boolean handleGroup(HttpExchange exchange, String path, String method) throws IOException {
        if (!"/api/v2/scheduler/overview".equals(path)) return false;
        requireAccess(exchange);
        if (!"GET".equals(method)) throw methodNotAllowed();
        sendJson(exchange, 200, scheduler.snapshot());
        return true;
    }
}
