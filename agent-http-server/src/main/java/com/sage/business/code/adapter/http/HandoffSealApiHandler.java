package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.session.HandoffSealService;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * C5 work-handoff source-seal route group — registered BEFORE every other
 * group so it owns two responsibilities on the same pass:
 * <ol>
 *   <li>{@code /api/v2/sessions/{sid}/source-seals} POST/GET — the durable
 *   seal primitive the PAC calls for ACS sources (D §9.4/§9.5).</li>
 *   <li>The mutation fence: once a session (or its parent, for auxiliary
 *   children) is sealed, every non-GET under {@code /api/v2/sessions/{sid}/…}
 *   and {@code /api/v2/runs/{rid}/…} is refused {@code SOURCE_SEALED} 409.
 *   Reads stay open — a seal freezes writes, not observation.</li>
 * </ol>
 */
public final class HandoffSealApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final HandoffSealService seals;
    public HandoffSealApiHandler(ObjectMapper mapper, String token, HandoffSealService seals) {
        super(mapper, token); this.seals = seals;
    }

    public boolean handleGroup(HttpExchange x, String path, String method) throws IOException {
        if (path.matches("/api/v2/sessions/[^/]+/source-seals")) {
            requireAccess(x);
            String sid = path.split("/")[4]; safeEntityId(sid, "session");
            if ("GET".equals(method)) { sendJson(x, 200, seals.get(sid)); return true; }
            if (!"POST".equals(method)) throw methodNotAllowed();
            String actor = (String) x.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE);
            JsonNode body;
            try { body = mapper.readTree(readLimited(x.getRequestBody())); }
            catch (Exception invalid) { throw new BusinessException("INVALID_REQUEST", 400, "Invalid source-seal request"); }
            if (body == null || !body.isObject()) throw new BusinessException("INVALID_REQUEST", 400, "Invalid source-seal request");
            Set<String> allowed = new HashSet<String>(Arrays.asList("schemaVersion", "requestKey", "handoffId", "targetRef"));
            for (Iterator<String> names = body.fieldNames(); names.hasNext();)
                if (!allowed.contains(names.next())) throw new BusinessException("INVALID_REQUEST", 400, "Unexpected field");
            for (String field : new String[]{"requestKey", "handoffId", "targetRef"}) {
                JsonNode value = body.get(field);
                if (value == null || !value.isTextual()) throw new BusinessException("INVALID_REQUEST", 400, field + " is required");
            }
            sendJson(x, 200, seals.seal(sid, body.path("requestKey").asText(),
                    body.path("handoffId").asText(), body.path("targetRef").asText(), actor));
            return true;
        }
        if (!"GET".equals(method)) {
            if (path.startsWith("/api/v2/sessions/") && path.split("/").length >= 5) {
                String sid = path.split("/")[4];
                if (sid.matches("[A-Za-z0-9._-]+") && seals.sealed(sid))
                    throw new BusinessException("SOURCE_SEALED", 409,
                            "Session is sealed for work handoff; new writes are refused");
            }
            if (path.startsWith("/api/v2/runs/") && path.split("/").length >= 5) {
                String rid = path.split("/")[4];
                if (rid.matches("[A-Za-z0-9._-]+") && seals.runSealed(rid))
                    throw new BusinessException("SOURCE_SEALED", 409,
                            "Owning session is sealed for work handoff; new writes are refused");
            }
        }
        return false;
    }
}
