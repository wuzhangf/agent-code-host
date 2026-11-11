package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.workspace.WorkspaceLaneService;
import com.sage.business.code.contract.v2.workspace.WorkspaceLaneResponse;
import com.sage.business.code.contract.v2.workspace.WorkspaceMode;
import com.sage.business.code.contract.v2.workspace.WorkspaceStartPolicy;
import com.sage.business.code.infrastructure.Hashing;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Session-owned workspace lane API.  The handler exposes only safe aliases
 * and state; lease ids, fencing tokens and physical paths stay in Service.
 */
public final class WorkspaceLaneApiHandler extends V2HttpSupport implements V2RouteGroup {
    private static final String PREFIX = "/api/v2/workspace-lanes";
    private final WorkspaceLaneService lanes;

    public WorkspaceLaneApiHandler(ObjectMapper mapper, String token,
            WorkspaceLaneService lanes) {
        super(mapper, token);
        if (lanes == null) throw new IllegalArgumentException("workspace lane service is required");
        this.lanes = lanes;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (!path.equals(PREFIX) && !path.startsWith(PREFIX + "/")) return false;
        if (PREFIX.equals(path)) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            WorkspaceLaneCreateBody body = body(exchange, WorkspaceLaneCreateBody.class);
            WorkspaceLaneService.CreateRequest request =
                    new WorkspaceLaneService.CreateRequest(body.sessionId, body.projectId,
                            body.baselineRevision);
            request.startPolicy = parseStartPolicy(body.startPolicy);
            request.snapshotId = body.wipSnapshotId;
            request.baseSnapshotFingerprint = body.baseSnapshotFingerprint;
            request.allowPrefixes = body.allowPrefixes;
            returnJson(exchange, 201, lanes.create(request, mutationKey(exchange)));
            return true;
        }
        String[] parts = path.split("/");
        if (parts.length < 5) return false;
        String laneId = parts[4];
        safeEntityId(laneId, "workspace lane");
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            returnJson(exchange, 200, lanes.get(laneId));
            return true;
        }
        if (parts.length != 6) return false;
        requireAccess(exchange);
        String command = parts[5];
        if (!("bind".equals(command) || "reopen".equals(command)
                || "release".equals(command)) || !"POST".equals(method)) {
            throw methodNotAllowed();
        }
        if ("bind".equals(command)) {
            WorkspaceLaneBindBody body = body(exchange, WorkspaceLaneBindBody.class);
            WorkspaceLaneService.BindRequest request = new WorkspaceLaneService.BindRequest();
            request.workspaceLaneId = laneId;
            request.sessionId = body.sessionId;
            request.runId = body.runId;
            request.idempotencyKey = mutationKey(exchange);
            request.expectedStateVersion = body.expectedStateVersion;
            returnJson(exchange, 200, lanes.bind(request));
            return true;
        }
        if ("reopen".equals(command)) {
            WorkspaceLaneReopenBody body = body(exchange, WorkspaceLaneReopenBody.class);
            returnJson(exchange, 200, lanes.reopen(laneId, body.sessionId, body.runId,
                    body.expectedStateVersion));
            return true;
        }
        WorkspaceLaneReleaseBody body = body(exchange, WorkspaceLaneReleaseBody.class);
        WorkspaceLaneService.ReleaseRequest request = new WorkspaceLaneService.ReleaseRequest();
        request.workspaceLaneId = laneId;
        request.sessionId = body.sessionId;
        request.runId = body.runId;
        request.leaseId = body.leaseId;
        request.fencingToken = body.fencingToken;
        request.expectedStateVersion = body.expectedStateVersion;
        returnJson(exchange, 200, lanes.release(request));
        return true;
    }

    private void returnJson(HttpExchange exchange, int status,
            WorkspaceLaneService.Response value) throws IOException {
        sendJson(exchange, status, toSafeResponse(value));
    }

    private static WorkspaceLaneResponse toSafeResponse(WorkspaceLaneService.Response value) {
        WorkspaceLaneResponse out = new WorkspaceLaneResponse();
        out.setWorkspaceLaneId(value.workspaceLaneId);
        out.setProjectId(value.projectId);
        out.setOwnerType("SESSION");
        out.setOwnerId(value.sessionId);
        out.setMode(WorkspaceMode.ISOLATED_WORKTREE);
        out.setBaseSource(value.startPolicy == null ? "CLEAN_HEAD" : value.startPolicy);
        out.setBaseRevision(value.baselineRevision);
        out.setBaseSnapshotFingerprint(value.baseSnapshotFingerprint);
        out.setWorkspaceExecutionId(value.workspaceExecutionId);
        out.setWorkspaceRef(value.workspaceRef);
        out.setState(value.state);
        out.setIntegrationState("NOT_SUBMITTED");
        out.setEnvironmentNamespace("env-" + Hashing.sha256(
                value.projectId + "\n" + value.workspaceLaneId));
        out.setStateVersion(Long.valueOf(value.stateVersion));
        return out;
    }

    private static WorkspaceStartPolicy parseStartPolicy(String value) {
        if (value == null || value.trim().isEmpty()) return WorkspaceStartPolicy.CLEAN_HEAD;
        try { return WorkspaceStartPolicy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "startPolicy is invalid");
        }
    }

    public static final class WorkspaceLaneCreateBody {
        public String sessionId;
        public String projectId;
        public String baselineRevision;
        public String startPolicy;
        public String wipSnapshotId;
        public String baseSnapshotFingerprint;
        public java.util.List<String> allowPrefixes;
    }

    public static final class WorkspaceLaneBindBody {
        public String sessionId;
        public String runId;
        public Long expectedStateVersion;
    }

    public static final class WorkspaceLaneReopenBody {
        public String sessionId;
        public String runId;
        public Long expectedStateVersion;
    }

    public static final class WorkspaceLaneReleaseBody {
        public String sessionId;
        public String runId;
        public String leaseId;
        public long fencingToken;
        public Long expectedStateVersion;
    }
}
