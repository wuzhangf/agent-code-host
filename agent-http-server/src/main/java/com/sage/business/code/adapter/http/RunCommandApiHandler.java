package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.run.RunControlCoordinator;
import com.sage.business.code.contract.v2.run.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Versioned receipt routes. Authentication precedes every command lookup. */
public final class RunCommandApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final RunControlCoordinator commands;
    private final boolean enabled;
    public RunCommandApiHandler(ObjectMapper mapper, String token, RunControlCoordinator commands, boolean enabled) {
        super(mapper, token); this.commands = commands; this.enabled = enabled;
    }
    public boolean handleGroup(HttpExchange exchange, String path, String method) throws IOException {
        if (!path.matches("/api/v2/runs/[^/]+/commands(?:/[^/]+)?")) return false;
        requireAccess(exchange);
        // Queries remain available when rollout is disabled, for accepted commands from earlier runs.
        String actor = (String) exchange.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
        if (actor == null) throw new BusinessException("CLIENT_REGISTRATION_REQUIRED", 428, "Registered client identity is required");
        String[] parts = path.split("/"); String runId = parts[4]; safeEntityId(runId, "run");
        if (parts.length == 7) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, commands.get(runId, parts[6])); return true;
        }
        if ("GET".equals(method)) {
            long limit = longParam(exchange, "limit", 30L);
            if (limit < 1 || limit > 100) throw new BusinessException("INVALID_REQUEST", 400, "limit must be 1..100");
            sendJson(exchange, 200, commands.list(runId, (int) limit, queryParam(exchange, "before"))); return true;
        }
        if (!"POST".equals(method)) throw methodNotAllowed();
        String key = mutationKey(exchange); RunCommandRequest request;
        try {
            byte[] raw = readLimited(exchange.getRequestBody());
            JsonNode tree = mapper.readTree(raw);
            if (tree == null || !tree.isObject()) throw new IllegalArgumentException("object is required");
            for (String field : new String[]{"schemaVersion", "commandId", "intent", "reasonCode"}) {
                JsonNode value = tree.get(field);
                if (value != null && !value.isNull() && !value.isTextual())
                    throw new IllegalArgumentException(field+" must be a string");
            }
            JsonNode version = tree == null ? null : tree.get("expectedStateVersion");
            if (version != null && !version.isNull() && (!version.isIntegralNumber() || !version.canConvertToLong()))
                throw new IllegalArgumentException("expectedStateVersion must be an integer");
            request = mapper.readerFor(RunCommandRequest.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        } catch (Exception e) { throw new BusinessException("INVALID_REQUEST", 400, "Invalid run-command/1 body"); }
        RunCommandReceipt receipt = commands.submit(runId, key, request, actor, enabled);
        sendJson(exchange, receipt.getTerminal() ? 200 : 202, receipt); return true;
    }
}
