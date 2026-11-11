package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.run.RunExecutionExportService;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * GET /api/v2/runs/{runId}/execution-export?after={cursor}&limit={n}
 *
 * U03 export endpoint: serves the lease-volume facts (baseline receipt,
 * mutation journal page, terminal projection) to the authenticated service
 * puller. Read-only — it never mutates run state and never replays work.
 */
public final class RunExecutionExportApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    private static final String PREFIX = "/api/v2/runs/";
    private static final String SUFFIX = "/execution-export";

    private final RunExecutionExportService exports;

    public RunExecutionExportApiHandler(ObjectMapper mapper, String token,
            RunExecutionExportService exports) {
        super(mapper, token);
        if (exports == null) {
            throw new IllegalArgumentException("export service is required");
        }
        this.exports = exports;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path,
            String method) throws IOException {
        if (!path.startsWith(PREFIX) || !path.endsWith(SUFFIX)
                || path.length() <= PREFIX.length() + SUFFIX.length()) {
            return false;
        }
        try {
            if (!"GET".equals(method)) throw methodNotAllowed();
            requireAccess(exchange);
            String runId = path.substring(PREFIX.length(),
                    path.length() - SUFFIX.length());
            long after = longParam(exchange, "after", 0L);
            int limit = (int) longParam(exchange, "limit",
                    RunExecutionExportService.DEFAULT_LIMIT);
            sendJson(exchange, 200, exports.export(runId, after, limit));
            return true;
        } catch (BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(),
                    failure.getMessage(), failure.getDetails());
            return true;
        }
    }
}
