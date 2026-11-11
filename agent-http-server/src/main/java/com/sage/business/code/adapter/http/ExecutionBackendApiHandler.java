package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.ExecutionEnvironmentService;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.contract.ExecutionBackendStatusResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * v2 projection for the cached execution backend state.
 *
 * <p>The handler never probes Docker from a GET and never accepts a backend,
 * image or executable from the client.  Refresh is the only side-effecting
 * operation and delegates to the existing single-flight coordinator.</p>
 */
public final class ExecutionBackendApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    public static final String STATUS_PATH = "/api/v2/execution-backends";
    public static final String REFRESH_PATH = STATUS_PATH + "/refresh";

    private final ExecutionEnvironmentService service;

    public ExecutionBackendApiHandler(ObjectMapper mapper, String token,
            ExecutionEnvironmentService service) {
        super(mapper, token);
        this.service = service;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!STATUS_PATH.equals(path) && !REFRESH_PATH.equals(path)) {
            return false;
        }
        requireAccess(exchange);
        requireEnabled();
        if (STATUS_PATH.equals(path) && "GET".equals(method)) {
            ExecutionBackendStatusResponse status = service.status();
            String fingerprint = status.getCatalogFingerprint();
            if (fingerprint != null) {
                exchange.getResponseHeaders().set("ETag", fingerprint);
            }
            if (fingerprint != null && fingerprint.equals(
                    exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1L);
                return true;
            }
            sendJson(exchange, 200, status);
            return true;
        }
        if (REFRESH_PATH.equals(path) && "POST".equals(method)) {
            mutationKey(exchange);
            sendJson(exchange, 202, service.refresh());
            return true;
        }
        throw methodNotAllowed();
    }

    private void requireEnabled() {
        if (service == null || !service.isSandboxEnabled()) {
            throw new BusinessException("SANDBOX_DISABLED", 404,
                    "Sandbox execution is not enabled");
        }
    }
}
