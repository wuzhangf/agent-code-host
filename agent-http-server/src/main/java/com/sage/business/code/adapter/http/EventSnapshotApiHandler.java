package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.event.EventSnapshotService;
import com.sage.business.code.contract.v2.event.EventQueryResponse;
import com.sage.business.code.contract.v2.event.EventSnapshotResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** HTTP adapter for the fixed-high-water-mark, scope-bound event snapshot. */
public final class EventSnapshotApiHandler extends V2HttpSupport
        implements V2RouteGroup, com.sun.net.httpserver.HttpHandler {
    public static final String LOCAL_SERVICE_PRINCIPAL = "local-owner";
    private final EventSnapshotService snapshots;

    public EventSnapshotApiHandler(ObjectMapper mapper, String token,
            EventSnapshotService snapshots) {
        super(mapper, token);
        if (snapshots == null) throw new IllegalArgumentException("snapshot service is required");
        this.snapshots = snapshots;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!"/api/v2/event-snapshots".equals(path)) return false;
        try {
            if (!"GET".equals(method)) throw methodNotAllowed();
            requireAccess(exchange);
            EventSnapshotService.Scope scope = scope(exchange);
            String token = queryParam(exchange, "snapshotToken");
            if (token == null) token = queryParam(exchange, "nextPageToken");
            String rawPageSize = queryParam(exchange, "pageSize");
            EventSnapshotService.SnapshotPage page;
            if (token == null || token.trim().isEmpty()) {
                page = snapshots.firstPage(scope, LOCAL_SERVICE_PRINCIPAL,
                        rawPageSize == null ? 100 : pageSize(rawPageSize));
            } else if (rawPageSize == null || rawPageSize.trim().isEmpty()) {
                page = snapshots.nextPage(token, scope, LOCAL_SERVICE_PRINCIPAL);
            } else {
                page = snapshots.nextPage(token, scope, LOCAL_SERVICE_PRINCIPAL,
                        pageSize(rawPageSize));
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            sendJson(exchange, 200, response(page));
            return true;
        } catch (BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(),
                    failure.getMessage(), failure.getDetails());
            return true;
        }
    }

    private EventSnapshotService.Scope scope(HttpExchange exchange) {
        String raw = queryParam(exchange, "scopeType");
        if (raw == null || raw.trim().isEmpty()) {
            throw new BusinessException("INVALID_REQUEST", 400, "scopeType is required");
        }
        EventSnapshotService.ScopeType type;
        try { type = EventSnapshotService.ScopeType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException invalid) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "scopeType must be GLOBAL, SESSION or MISSION");
        }
        String id = queryParam(exchange, "scopeId");
        if (type == EventSnapshotService.ScopeType.GLOBAL) {
            if (id != null && !id.trim().isEmpty()) {
                throw new BusinessException("INVALID_REQUEST", 400,
                        "scopeId is not allowed for GLOBAL scope");
            }
            return EventSnapshotService.Scope.global();
        }
        safeEntityId(id, "scope");
        return type == EventSnapshotService.ScopeType.SESSION
                ? EventSnapshotService.Scope.session(id)
                : EventSnapshotService.Scope.mission(id);
    }

    private static int pageSize(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) {
            throw new BusinessException("INVALID_REQUEST", 400, "pageSize must be an integer");
        }
    }

    private static EventSnapshotResponse response(EventSnapshotService.SnapshotPage page) {
        EventQueryResponse source = page.getResponse();
        EventSnapshotResponse out = new EventSnapshotResponse();
        out.setEvents(source.getEvents());
        out.setNextCursor(source.getNextCursor());
        out.setScannedThroughCursor(page.getScannedThroughCursor());
        out.setHighWaterMark(source.getHighWaterMark());
        out.setRetentionFloor(source.getRetentionFloor());
        out.setSnapshotCursor(page.getSnapshotCursor());
        out.setNextPageToken(page.getNextPageToken());
        out.setExpiresAt(page.getExpiresAtMillis());
        out.setComplete(page.isComplete());
        out.setTruncated(page.isTruncated());
        out.setResponseBytes(page.getResponseBytes());
        return out;
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
