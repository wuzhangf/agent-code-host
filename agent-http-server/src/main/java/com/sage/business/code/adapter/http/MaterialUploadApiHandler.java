package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.material.MaterialUploadService;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Ordinary material profile routes (design 2026-11-01 §11.2) — the
 * project-scoped chunked+sealed upload contract PAC already calls upstream:
 * <pre>
 *   POST   /api/v2/projects/{p}/material-uploads
 *   GET    /api/v2/projects/{p}/material-uploads
 *   GET    /api/v2/projects/{p}/material-uploads/{key}
 *   PUT    /api/v2/projects/{p}/material-uploads/{key}/chunks/{offset}?digest=&lt;hex sha256&gt;
 *   POST   /api/v2/projects/{p}/material-uploads/{key}/seal
 *   DELETE /api/v2/projects/{p}/material-uploads/{key}
 * </pre>
 * The chunk body is raw bytes (never JSON/base64 — that is the PAC relay
 * shape, not this contract); {@code digest} is the per-chunk sha256 query
 * parameter. Every body field set is whitelisted before any service call.
 */
public final class MaterialUploadApiHandler extends V2HttpSupport implements V2RouteGroup {

    private static final String PREFIX = "/api/v2/projects/";
    private static final String SEGMENT = "material-uploads";
    private static final Set<String> CREATE_FIELDS = new HashSet<String>(
            Arrays.asList("schemaVersion", "requestKey", "target", "displayName",
                    "mediaType", "sizeBytes", "sha256"));
    private static final Set<String> TARGET_FIELDS = new HashSet<String>(
            Arrays.asList("sessionId", "taskId", "createKey"));
    private static final Set<String> SEAL_FIELDS = new HashSet<String>(
            Arrays.asList("schemaVersion", "requestKey", "sha256"));

    private final MaterialUploadService materials;

    public MaterialUploadApiHandler(ObjectMapper mapper, String token,
            MaterialUploadService materials) {
        super(mapper, token);
        if (materials == null) throw new IllegalArgumentException("materials is required");
        this.materials = materials;
    }

    @Override public boolean handleGroup(HttpExchange x, String path, String method)
            throws IOException {
        if (!path.startsWith(PREFIX)) return false;
        String rest = path.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return false;
        String projectId = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        if (!SEGMENT.equals(tail) && !tail.startsWith(SEGMENT + "/")) return false;
        requireAccess(x);
        safeEntityId(projectId, "project");
        if (SEGMENT.equals(tail)) {
            if ("GET".equals(method)) { sendJson(x, 200, materials.list(projectId)); return true; }
            if (!"POST".equals(method)) throw methodNotAllowed();
            JsonNode body = readBody(x);
            whitelist(body, CREATE_FIELDS);
            JsonNode target = body.get("target");
            if (target != null) whitelist(target, TARGET_FIELDS);
            sendJson(x, 200, materials.create(projectId, body));
            return true;
        }
        String keyAndTail = tail.substring(SEGMENT.length() + 1);
        String[] parts = keyAndTail.split("/");
        String uploadKey = parts[0];
        safeEntityId(uploadKey, "material upload");
        if (parts.length == 1) {
            if ("GET".equals(method)) {
                sendJson(x, 200, materials.get(projectId, uploadKey)); return true;
            }
            if ("DELETE".equals(method)) {
                sendJson(x, 200, materials.remove(projectId, uploadKey)); return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length == 2 && "seal".equals(parts[1])) {
            if (!"POST".equals(method)) throw methodNotAllowed();
            JsonNode body = readBody(x);
            whitelist(body, SEAL_FIELDS);
            JsonNode requestKey = body.get("requestKey");
            JsonNode sha256 = body.get("sha256");
            sendJson(x, 200, materials.seal(projectId, uploadKey,
                    requestKey != null && requestKey.isTextual() ? requestKey.asText() : null,
                    sha256 != null && sha256.isTextual() ? sha256.asText() : null));
            return true;
        }
        if (parts.length == 3 && "chunks".equals(parts[1])) {
            if (!"PUT".equals(method)) throw methodNotAllowed();
            final long offset;
            try { offset = Long.parseLong(parts[2]); }
            catch (NumberFormatException invalid) {
                throw new BusinessException("MATERIAL_CHUNK_OFFSET", 409,
                        "Chunk offset must be a non-negative integer");
            }
            byte[] bytes = readLimited(x.getRequestBody());
            sendJson(x, 200, materials.putChunk(projectId, uploadKey, offset,
                    bytes, queryParam(x, "digest")));
            return true;
        }
        throw new BusinessException("INVALID_REQUEST", 404,
                "Unknown material-upload resource");
    }

    private JsonNode readBody(HttpExchange x) throws IOException {
        JsonNode body;
        try { body = mapper.readTree(readLimited(x.getRequestBody())); }
        catch (BusinessException failure) { throw failure; }
        catch (Exception invalid) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Invalid material-upload request");
        }
        if (body == null || !body.isObject()) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Invalid material-upload request");
        }
        return body;
    }

    private static void whitelist(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Invalid material-upload request");
        }
        for (Iterator<String> names = body.fieldNames(); names.hasNext();) {
            if (!allowed.contains(names.next())) {
                throw new BusinessException("INVALID_REQUEST", 400, "Unexpected field");
            }
        }
    }
}
