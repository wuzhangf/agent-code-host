package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.adapter.http.stream.LongRequestAdmission;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.application.sync.BridgeCommandRecord;
import com.sage.business.code.application.sync.BridgeCommandService;
import com.sage.business.code.application.sync.WorkspaceBlobService;
import com.sage.business.code.application.sync.WorkspaceEndpointRecord;
import com.sage.business.code.application.sync.WorkspaceEndpointService;
import com.sage.business.code.contract.v2.event.BusinessEventEnvelope;
import com.sage.business.code.contract.v2.event.EventQueryResponse;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace endpoint + bridge command + blob API (design §7.3.1–§7.3.2).
 *
 * Routes:
 *   POST   /api/v2/workspace-endpoints                          register
 *   GET    /api/v2/workspace-endpoints/{id}                     read
 *   POST   /api/v2/workspace-endpoints/{id}/heartbeat           heartbeat
 *   POST   /api/v2/workspace-endpoints/{id}/primary             claim PRIMARY
 *   POST   /api/v2/workspace-endpoints/{id}/revoke              revoke
 *   GET    /api/v2/workspace-endpoints/{id}/commands/stream     SSE outbox
 *   POST   /api/v2/workspace-endpoints/{id}/commands/{id}/ack   device ack
 *   POST   /api/v2/workspace-endpoints/{id}/commands/{id}/result device result
 *   PUT    /api/v2/workspace-blobs/{sha256}                     blob upload
 *   GET    /api/v2/workspace-blobs/{sha256}                     blob download
 *
 * The SSE stream rides the shared durable journal scoped to the endpoint —
 * cursor replay gives at-least-once delivery; results are idempotent on
 * commandId.
 */
public final class WorkspaceEndpointApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    private static final Logger LOG =
            LoggerFactory.getLogger(WorkspaceEndpointApiHandler.class);
    private static final String ENDPOINT_PREFIX = "/api/v2/workspace-endpoints";
    private static final String BLOB_PREFIX = "/api/v2/workspace-blobs";
    private static final long HEARTBEAT_IDLE_MILLIS = 15_000L;
    private static final int BACKLOG_PAGE = 200;

    private final WorkspaceEndpointService endpoints;
    private final BridgeCommandService commands;
    private final WorkspaceBlobService blobs;
    private final SqliteEventJournal journal;
    private final LiveEventHub hub;
    private final LongRequestAdmission longRequests;
    private volatile com.sage.business.code.application.sync
            .DeliveryBundleService bundleService;
    private volatile com.sage.business.code.application.sync.SyncMetrics
            syncMetrics;

    /** WU-12 §7.6: metrics snapshot for the acceptance read. */
    public void bindMetrics(
            com.sage.business.code.application.sync.SyncMetrics metrics) {
        this.syncMetrics = metrics;
    }

    public WorkspaceEndpointApiHandler(ObjectMapper mapper, String token,
            WorkspaceEndpointService endpoints, BridgeCommandService commands,
            WorkspaceBlobService blobs, SqliteEventJournal journal,
            LiveEventHub hub, LongRequestAdmission longRequests) {
        super(mapper, token);
        this.endpoints = endpoints;
        this.commands = commands;
        this.blobs = blobs;
        this.journal = journal;
        this.hub = hub;
        this.longRequests = longRequests;
    }

    /** WU-10 §7.4: bundle import rides through apply-bundle. */
    public void bindBundleService(
            com.sage.business.code.application.sync.DeliveryBundleService
                    service) {
        this.bundleService = service;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
            String method) throws IOException {
        if (path.startsWith(BLOB_PREFIX)) {
            return handleBlob(exchange, path, method);
        }
        // WU-12 §7.6: acceptance read — sync-plane counters + UNKNOWN
        // sightings, durable across restarts.
        if ("/api/v2/workspace-metrics".equals(path)) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, syncMetrics == null
                    ? java.util.Collections.<String, Object>emptyMap()
                    : syncMetrics.snapshot());
            return true;
        }
        if (!path.equals(ENDPOINT_PREFIX)
                && !path.startsWith(ENDPOINT_PREFIX + "/")) return false;
        String[] parts = path.split("/");
        if (parts.length == 4 && "POST".equals(method)) {
            requireAccess(exchange);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = body(exchange, Map.class);
            WorkspaceEndpointRecord record = new WorkspaceEndpointRecord();
            record.projectId = stringValue(body.get("projectId"));
            record.deviceId = stringValue(body.get("deviceId"));
            record.localProjectRef = stringValue(body.get("localProjectRef"));
            record.principalId = stringValue(body.get("principalId"));
            record.kind = stringValue(body.get("kind"));
            record.platform = mapValue(body.get("platform"));
            record.capabilities = mapValue(body.get("capabilities"));
            record.vcs = mapValue(body.get("vcs"));
            WorkspaceEndpointRecord created = endpoints.register(record);
            Map<String, Object> out = endpointView(created);
            out.put("channel", endpoints.channelDescriptor(
                    created.endpointId));
            sendJson(exchange, 201, out);
            return true;
        }
        if (parts.length < 5) return false;
        String endpointId = parts[4];
        safeEntityId(endpointId, "workspace endpoint");
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, endpointView(endpoints.get(endpointId)));
            return true;
        }
        requireAccess(exchange);
        String command = parts[5];
        if (parts.length == 6 && "heartbeat".equals(command)
                && "POST".equals(method)) {
            sendJson(exchange, 200,
                    endpointView(endpoints.heartbeat(endpointId)));
            return true;
        }
        if (parts.length == 6 && "primary".equals(command)
                && "POST".equals(method)) {
            sendJson(exchange, 200,
                    endpointView(endpoints.setPrimary(endpointId)));
            return true;
        }
        if (parts.length == 6 && "revoke".equals(command)
                && "POST".equals(method)) {
            sendJson(exchange, 200,
                    endpointView(endpoints.revoke(endpointId)));
            return true;
        }
        if (parts.length == 6 && "apply-bundle".equals(command)
                && "POST".equals(method)) {
            // WU-10 §7.4: verify signature + checksums, then apply through
            // the endpoint bound to this id.  Conflicts enter the unified
            // editor with origin=BUNDLE_IMPORT.
            if (bundleService == null) {
                throw new com.sage.business.code.domain.BusinessException(
                        "DELIVERY_BUNDLE_UNAVAILABLE", 503,
                        "bundle import is not wired");
            }
            byte[] zip = readRequestBody(exchange);
            String projectId = queryParam(exchange, "projectId");
            if (projectId == null) {
                WorkspaceEndpointRecord bound = endpoints.get(endpointId);
                projectId = bound == null ? null : bound.projectId;
            }
            String spiEndpointId = "local".equals(endpointId)
                    ? null : "bridge:" + endpointId;
            sendJson(exchange, 200, bundleService.applyBundle(projectId,
                    spiEndpointId, zip, mutationKey(exchange)));
            return true;
        }
        if (parts.length == 7 && "commands".equals(command)
                && "stream".equals(parts[6]) && "GET".equals(method)) {
            streamCommands(exchange, endpointId);
            return true;
        }
        if (parts.length == 8 && "commands".equals(command)) {
            String commandId = parts[6];
            safeEntityId(commandId, "bridge command");
            String action = parts[7];
            if ("ack".equals(action) && "POST".equals(method)) {
                sendJson(exchange, 200, commands.acknowledge(endpointId,
                        commandId));
                return true;
            }
            if ("result".equals(action) && "POST".equals(method)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = body(exchange, Map.class);
                Map<String, Object> result = mapValue(body.get("result"));
                if (result == null) result = body;
                sendJson(exchange, 200, commands.result(endpointId,
                        commandId, result, mutationKey(exchange)));
                return true;
            }
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Blob API (§7.3.2)
    // ------------------------------------------------------------------

    private boolean handleBlob(HttpExchange exchange, String path,
            String method) throws IOException {
        String[] parts = path.split("/");
        if (parts.length != 5) return false;
        String sha256 = parts[4];
        safeEntityId(sha256, "workspace blob");
        if ("PUT".equals(method)) {
            requireAccess(exchange);
            byte[] bytes = readRequestBody(exchange);
            String declared = sha256.toLowerCase();
            blobs.put(declared, bytes);
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ref", "blob:sha256:" + declared);
            out.put("byteLength", bytes.length);
            sendJson(exchange, 201, out);
            return true;
        }
        if ("GET".equals(method)) {
            requireAccess(exchange);
            byte[] bytes = blobs.get(sha256);
            exchange.getResponseHeaders().set("Content-Type",
                    "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Length",
                    String.valueOf(bytes.length));
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Command stream (SSE over the durable journal, endpoint-scoped)
    // ------------------------------------------------------------------

    private void streamCommands(HttpExchange exchange, String endpointId)
            throws IOException {
        requireAccess(exchange);
        WorkspaceEndpointRecord record = endpoints.get(endpointId);
        if (record == null) {
            throw new com.sage.business.code.domain.BusinessException(
                    "WORKSPACE_ENDPOINT_NOT_FOUND", 404,
                    "workspace endpoint " + endpointId + " does not exist");
        }
        long afterCursor = parseCursor(exchange);
        LiveEventHub.Scope scope = LiveEventHub.Scope.ofSession(
                "wse-" + endpointId);
        LiveEventHub.Subscription subscription = null;
        LongRequestAdmission.Handle reservation = null;
        OutputStream out = null;
        long lastEmitted = afterCursor;
        try {
            reservation = longRequests.reserve(
                    LongRequestAdmission.Kind.CONTROL_STREAM,
                    "local-owner", endpointId);
            subscription = hub.subscribe(scope);
            exchange.getResponseHeaders().set("Content-Type",
                    "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            out = exchange.getResponseBody();
            // Durable backlog: replay everything after the cursor before the
            // live tail — the journal is the transport, the hub the hint.
            long backlogHighWaterMark =
                    journal.readAfter(0L, 1).getHighWaterMark();
            long pageFrom = afterCursor;
            while (pageFrom < backlogHighWaterMark) {
                EventQueryResponse page = journal.readAfter(pageFrom,
                        BACKLOG_PAGE);
                if (page == null || page.getEvents() == null) break;
                for (BusinessEventEnvelope event : page.getEvents()) {
                    if (event.getCursor() > backlogHighWaterMark) continue;
                    if (scope.matches(event)
                            && event.getCursor() > lastEmitted) {
                        writeFrame(event, out);
                        lastEmitted = event.getCursor();
                    }
                }
                if (page.getNextCursor() <= pageFrom) break;
                pageFrom = page.getNextCursor();
            }
            for (BusinessEventEnvelope buffered : subscription.drain()) {
                if (buffered.getCursor() > lastEmitted) {
                    writeFrame(buffered, out);
                    lastEmitted = buffered.getCursor();
                }
            }
            out.flush();
            while (true) {
                if (subscription.isOverflowed()) {
                    out.write(": overflow; reconnect with Last-Event-ID\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    break;
                }
                BusinessEventEnvelope event =
                        subscription.poll(HEARTBEAT_IDLE_MILLIS);
                if (event == null) {
                    out.write(String.format(": heartbeat %d%n",
                            System.currentTimeMillis())
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                if (event.getCursor() <= lastEmitted
                        || !scope.matches(event)) continue;
                writeFrame(event, out);
                out.flush();
                lastEmitted = event.getCursor();
            }
        } catch (IOException clientGone) {
            LOG.info("sse.bridge_disconnected event=sse.bridge_disconnected endpoint={} cursor={}",
                    endpointId, lastEmitted);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (subscription != null) subscription.close();
            if (reservation != null) reservation.close();
            if (out != null) {
                try { out.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void writeFrame(BusinessEventEnvelope event, OutputStream out)
            throws IOException {
        StringBuilder frame = new StringBuilder();
        frame.append("id: ").append(event.getCursor()).append('\n');
        frame.append("event: ").append(event.getType()).append('\n');
        frame.append("data: ")
                .append(mapper.writeValueAsString(event)).append("\n\n");
        out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> endpointView(WorkspaceEndpointRecord record) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("endpointId", record.endpointId);
        out.put("schemaVersion", record.schemaVersion);
        out.put("kind", record.kind);
        out.put("projectId", record.projectId);
        out.put("deviceId", record.deviceId);
        out.put("localProjectRef", record.localProjectRef);
        out.put("state", record.state);
        out.put("primary", record.primary);
        out.put("endpointVersion", record.endpointVersion);
        out.put("lastHeartbeatAt", record.lastHeartbeatAt);
        out.put("createdAt", record.createdAt);
        out.put("updatedAt", record.updatedAt);
        out.put("platform", record.platform);
        out.put("capabilities", record.capabilities);
        out.put("vcs", record.vcs);
        return out;
    }

    private byte[] readRequestBody(HttpExchange exchange) throws IOException {
        java.io.InputStream in = exchange.getRequestBody();
        java.io.ByteArrayOutputStream buffer =
                new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) >= 0) buffer.write(chunk, 0, n);
        return buffer.toByteArray();
    }

    private static long parseCursor(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders()
                .getFirst("Last-Event-ID");
        if (raw == null) raw = queryParam(exchange, "cursor");
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException bad) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
