package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.stagehost.StageHostService;
import com.sage.business.code.application.stagehost.StageHostService.ArtifactBody;
import com.sage.business.code.contract.v2.stagehost.HostDriveRequest;
import com.sage.business.code.contract.v2.stagehost.HostEffectRequest;
import com.sage.business.code.contract.v2.stagehost.HostSealRequest;
import com.sage.business.code.contract.v2.stagehost.HostStageContract;
import com.sage.business.code.contract.v2.stagehost.HostStartRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Worker-facing stage-host routes (worker-design 05 §5.1):
 *
 * <pre>
 * POST   /api/v2/implementation-operations
 * GET    /api/v2/implementation-operations/by-request/{requestId}
 * GET    /api/v2/implementation-operations/{id}
 * POST   /api/v2/implementation-operations/{id}/inputs
 * POST   /api/v2/implementation-operations/{id}/controls
 * POST   /api/v2/implementation-operations/{id}/review
 * GET    /api/v2/implementation-operations/{id}/review
 * POST   /api/v2/implementation-operations/{id}/review-decisions
 * GET    /api/v2/implementation-operations/{id}/requests/{requestId}
 * POST   /api/v2/implementation-operations/{id}/drive
 * GET    /api/v2/implementation-operations/{id}/events
 * GET    /api/v2/implementation-operations/{id}/quiescence
 * POST   /api/v2/implementation-operations/{id}/seal
 * GET    /api/v2/implementation-operations/{id}/currentness
 * GET    /api/v2/implementation-operations/{id}/artifacts/{artifactId}
 * </pre>
 *
 * The same surface mounts at {@code /api/v2/design-stage-operations}
 * (stage kind resolves from the START body identity). Every mutation
 * requires Idempotency-Key; GETs are pure reads. Error vocabulary is
 * the 05 §5.3 problem table — product codes pass through as
 * {@code productFailureRef} detail.
 */
public final class StageHostApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    private static final String IMPL_PREFIX =
            "/api/v2/implementation-operations";
    private static final String DESIGN_PREFIX =
            "/api/v2/design-stage-operations";
    /** release/03 §4: RELEASE stage operations family — same
     *  sub-routes; the stage kind resolves from the START identity. */
    private static final String RELEASE_PREFIX =
            "/api/v2/" + HostStageContract.RELEASE_ROUTE;

    private final StageHostService service;

    public StageHostApiHandler(ObjectMapper mapper, String token,
            StageHostService service) {
        super(mapper, token);
        this.service = service;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
            String method) throws IOException {
        String prefix = null;
        if (path.equals(IMPL_PREFIX)
                || path.startsWith(IMPL_PREFIX + "/")) {
            prefix = IMPL_PREFIX;
        } else if (path.equals(DESIGN_PREFIX)
                || path.startsWith(DESIGN_PREFIX + "/")) {
            prefix = DESIGN_PREFIX;
        } else if (path.equals(RELEASE_PREFIX)
                || path.startsWith(RELEASE_PREFIX + "/")) {
            prefix = RELEASE_PREFIX;
        }
        if (prefix == null) {
            return false;
        }
        requireAccess(exchange);
        String rest = path.substring(prefix.length());
        if (rest.isEmpty()) {
            if (!"POST".equals(method)) {
                throw methodNotAllowed();
            }
            return start(exchange);
        }
        String[] parts = rest.substring(1).split("/");

        if (RELEASE_PREFIX.equals(prefix) && "input-transfers".equals(parts[0])) {
            com.sage.business.code.application.stagehost.InputTransferSessions transfers=service.inputTransfers();
            if(parts.length==1&&"POST".equals(method)) {
                sendJson(exchange,201,transfers.create(body(exchange,Map.class)));return true;
            }
            if(parts.length==2&&"GET".equals(method)){sendJson(exchange,200,transfers.query(parts[1]));return true;}
            if(parts.length==3&&"complete".equals(parts[2])&&"POST".equals(method)){sendJson(exchange,200,transfers.complete(parts[1]));return true;}
            if(parts.length==4&&"blobs".equals(parts[2])&&"PUT".equals(method)) {
                long size;
                try{size=Long.parseLong(exchange.getRequestHeaders().getFirst("X-Declared-Size"));}
                catch(Exception bad){throw new BusinessException("INVALID_REQUEST",400,"X-Declared-Size required");}
                try(java.io.InputStream input=exchange.getRequestBody()) {
                    sendJson(exchange,200,transfers.upload(parts[1],parts[3],size,exchange.getRequestHeaders().getFirst("If-Content-SHA256"),input));
                }return true;
            }
            throw methodNotAllowed();
        }
        // Static segment before {id} — never shadowed.
        if (RELEASE_PREFIX.equals(prefix) && "input-assets".equals(parts[0]) && parts.length==1) {
            if(!"POST".equals(method))throw methodNotAllowed();
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream input=exchange.getRequestBody()){
                byte[] chunk=new byte[8192];int count;
                while((count=input.read(chunk))>=0){
                    if(bytes.size()+count>1024*1024)throw new BusinessException("INVALID_REQUEST",413,"input asset exceeds 1048576 bytes");
                    bytes.write(chunk,0,count);
                }
            }
            sendJson(exchange,201,service.publishInputAsset(bytes.toByteArray(),
                    exchange.getRequestHeaders().getFirst("If-Content-SHA256"),
                    exchange.getRequestHeaders().getFirst("X-Origin-Artifact-Ref")));return true;
        }
        if ("by-request".equals(parts[0]) && parts.length == 2) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return byRequest(exchange, parts[1]);
        }
        if ("capabilities".equals(parts[0]) && parts.length == 2) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return capabilities(exchange, parts[1]);
        }
        String opId = parts[0];
        safeEntityId(opId, "operation");
        if (parts.length == 1) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return view(exchange, opId);
        }
        String sub = parts[1];
        if ("bodies".equals(sub) && parts.length == 2) {
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            sendJson(exchange,201,service.publishBody(opId,readLimited(exchange.getRequestBody()),
                    exchange.getRequestHeaders().getFirst("If-Content-SHA256")));
            return true;
        }
        if ("bodies".equals(sub) && parts.length == 3) {
            if (!"GET".equals(method)) throw methodNotAllowed();
            ArtifactBody body=service.body(opId,parts[2]);
            exchange.getResponseHeaders().set("Content-Type",body.mediaType);sendBytes(exchange,200,body.bytes);return true;
        }
        if ("inputs".equals(sub) && parts.length == 2) {
            if (!"POST".equals(method)) {
                throw methodNotAllowed();
            }
            return effect(exchange, opId, "INPUT");
        }
        if ("controls".equals(sub) && parts.length == 2) {
            if (!"POST".equals(method)) {
                throw methodNotAllowed();
            }
            return effect(exchange, opId, "CONTROL");
        }
        if ("review".equals(sub) && parts.length == 2) {
            if ("POST".equals(method)) {
                return reviewAlias(exchange, opId);
            }
            if ("GET".equals(method)) {
                return reviewView(exchange, opId);
            }
            throw methodNotAllowed();
        }
        // DECISION route name is prefix-scoped (05 §5.1/§5.2):
        // impl uses /review-decisions, design/release use /decisions.
        boolean decisionRoute = IMPL_PREFIX.equals(prefix)
                ? "review-decisions".equals(sub)
                : "decisions".equals(sub) || "review-decisions".equals(sub);
        if (decisionRoute && parts.length == 2) {
            if (!"POST".equals(method)) {
                throw methodNotAllowed();
            }
            return effect(exchange, opId, "DECISION");
        }
        if ("requests".equals(sub) && parts.length == 3) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return requestLookup(exchange, opId, parts[2]);
        }
        if ("drive".equals(sub) && parts.length == 2) {
            if (!"POST".equals(method)) {
                throw methodNotAllowed();
            }
            return drive(exchange, opId);
        }
        if ("events".equals(sub) && parts.length == 2) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return events(exchange, opId);
        }
        if ("quiescence".equals(sub) && parts.length == 2) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return quiescence(exchange, opId);
        }
        if ("seal".equals(sub) && parts.length == 2) {
            if (!"POST".equals(method)) {
                throw methodNotAllowed();
            }
            return seal(exchange, opId);
        }
        if ("currentness".equals(sub) && parts.length == 2) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return currentness(exchange, opId);
        }
        if ("artifacts".equals(sub) && parts.length >= 3) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            // artifactId is the whole tail — entry ids legitimately
            // contain '/' (e.g. document:design/x.md), which the wire
            // encodes as %2F but HttpExchange decodes before routing.
            return artifact(exchange, opId, String.join("/",
                    java.util.Arrays.copyOfRange(parts, 2,
                            parts.length)));
        }
        return false;
    }

    // ------------------------------------------------------------ verbs

    private boolean start(HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        HostStartRequest request;
        try {
            request = mapper.readValue(raw, HostStartRequest.class);
        } catch (Exception e) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "start body cannot be parsed");
        }
        sendJson(exchange, 202,
                service.start(request, mutationKey(exchange), raw));
        return true;
    }

    private boolean capabilities(HttpExchange exchange, String stageKind)
            throws IOException {
        safeEntityId(stageKind, "stageKind");
        sendJson(exchange, 200, service.capabilities(stageKind));
        return true;
    }

    private boolean byRequest(HttpExchange exchange, String requestId)
            throws IOException {
        sendJson(exchange, 200, service.lookupByRequest(requestId,
                queryParam(exchange, "requestDigest")));
        return true;
    }

    private boolean view(HttpExchange exchange, String opId)
            throws IOException {
        sendJson(exchange, 200, service.operation(opId));
        return true;
    }

    private boolean requestLookup(HttpExchange exchange, String opId,
            String requestId) throws IOException {
        sendJson(exchange, 200, service.lookupRequestInOperation(opId,
                requestId, queryParam(exchange, "requestDigest")));
        return true;
    }

    private boolean effect(HttpExchange exchange, String opId,
            String kind) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        HostEffectRequest request;
        try {
            request = mapper.readValue(raw, HostEffectRequest.class);
        } catch (Exception e) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "effect body cannot be parsed");
        }
        Object receipt;
        if ("INPUT".equals(kind)) {
            receipt = service.input(opId, request,
                    mutationKey(exchange), raw);
        } else if ("DECISION".equals(kind)) {
            receipt = service.decide(opId, request,
                    mutationKey(exchange), raw);
        } else {
            receipt = service.control(opId, request,
                    mutationKey(exchange), raw);
        }
        sendJson(exchange, 202, receipt);
        return true;
    }

    /**
     * POST /{id}/review — mint an INDEPENDENT REVIEW-stage binding
     * pinned to this IMPLEMENT operation's sealed output (05 §5.1:
     * the alias never mutates the IMPLEMENT identity).
     */
    private boolean reviewAlias(HttpExchange exchange, String opId)
            throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        HostStartRequest request;
        try {
            request = raw == null || raw.length == 0 ? null
                    : mapper.readValue(raw, HostStartRequest.class);
        } catch (Exception e) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "review start body cannot be parsed");
        }
        if (request == null || request.getIdentity() == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "review start requires a REVIEW-stage identity "
                            + "with requestId/effectId/requestDigest");
        }
        if (!"REVIEW".equals(request.getIdentity().getStageKind())) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "review alias requires stageKind REVIEW");
        }
        sendJson(exchange, 202,
                service.start(request, mutationKey(exchange), raw));
        return true;
    }

    private boolean reviewView(HttpExchange exchange, String opId)
            throws IOException {
        sendJson(exchange, 200, service.operation(opId));
        return true;
    }

    private boolean drive(HttpExchange exchange, String opId)
            throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        HostDriveRequest request;
        try {
            request = mapper.readValue(raw, HostDriveRequest.class);
        } catch (Exception e) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "drive body cannot be parsed");
        }
        sendJson(exchange, 202,
                service.drive(opId, request, mutationKey(exchange)));
        return true;
    }

    private boolean events(HttpExchange exchange, String opId)
            throws IOException {
        sendJson(exchange, 200, service.events(opId,
                queryParam(exchange, "cursor"),
                (int) longParam(exchange, "limit", 100)));
        return true;
    }

    private boolean quiescence(HttpExchange exchange, String opId)
            throws IOException {
        sendJson(exchange, 200, service.quiescence(opId));
        return true;
    }

    private boolean seal(HttpExchange exchange, String opId)
            throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        HostSealRequest request;
        try {
            request = mapper.readValue(raw, HostSealRequest.class);
        } catch (Exception e) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "seal body cannot be parsed");
        }
        sendJson(exchange, 202,
                service.seal(opId, request, mutationKey(exchange)));
        return true;
    }

    private boolean currentness(HttpExchange exchange, String opId)
            throws IOException {
        sendJson(exchange, 200, service.currentness(opId,
                queryParam(exchange, "purpose"),
                queryParam(exchange, "objectRef"),
                queryParam(exchange, "objectVersion"),
                queryParam(exchange, "objectDigest"),
                queryParam(exchange, "observationRequestId")));
        return true;
    }

    private boolean artifact(HttpExchange exchange, String opId,
            String artifactId) throws IOException {
        // Fixed artifact ids / cas refs only — caller paths rejected by
        // the service (05 §5.1 artifact read rule).
        try(StageHostService.ArtifactStream body=service.artifactStream(opId,artifactId)) {
            exchange.getResponseHeaders().set("Content-Type",body.mediaType==null?"application/octet-stream":body.mediaType);
            if(body.sizeBytes==0){exchange.getResponseHeaders().set("Content-Length","0");exchange.sendResponseHeaders(200,-1);}
            else {exchange.sendResponseHeaders(200,body.sizeBytes);byte[] chunk=new byte[65536];int count;try(java.io.OutputStream output=exchange.getResponseBody()){while((count=body.input.read(chunk))!=-1)output.write(chunk,0,count);}}
        }
        return true;
    }
}
