package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.evidence.EvidenceQueryService;
import com.sage.business.code.application.run.RunQueryService;
import com.sage.business.code.application.run.RunChangeArtifactService;
import com.sage.business.code.application.review.RunReviewService;
import com.sage.business.code.application.workspace.ConflictResolutionService;
import com.sage.business.code.contract.v2.run.RunInterruptRequest;
import com.sage.business.code.contract.v2.run.RunControlRequest;
import com.sage.business.code.contract.v2.workspace.WorkspaceConflictResolveBatchRequest;
import com.sage.business.code.contract.v2.workspace.WorkspaceConflictResolveRequest;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * /api/v2/runs route group (desktop design §9.3). Terminal runs are served
 * from the persisted change artifact; review is an honest durable
 * NOT_REQUESTED projection until a review worker is enabled.
 */
public final class RunApiHandler extends V2HttpSupport implements V2RouteGroup {
    private final RunQueryService runs;
    private final EvidenceQueryService evidence;
    private final RunChangeArtifactService changes;
    private final RunReviewService reviews;
    private final com.sage.business.code.application.run.RunActivityQueryService activities;
    private final com.sage.business.code.application.run.RunReasoningQueryService reasoning;
    private final com.sage.business.code.application.run.RunOutputQueryService output;
    private final ConflictResolutionService conflicts;

    public RunApiHandler(ObjectMapper mapper, String token, RunQueryService runs,
            EvidenceQueryService evidence) {
        this(mapper, token, runs, evidence, null, null);
    }

    public RunApiHandler(ObjectMapper mapper, String token, RunQueryService runs,
            EvidenceQueryService evidence, RunChangeArtifactService changes,
            RunReviewService reviews) {
        this(mapper, token, runs, evidence, changes, reviews, null);
    }

    public RunApiHandler(ObjectMapper mapper, String token, RunQueryService runs,
            EvidenceQueryService evidence, RunChangeArtifactService changes,
            RunReviewService reviews,
            com.sage.business.code.application.run.RunActivityQueryService activities) {
        this(mapper, token, runs, evidence, changes, reviews, activities, null);
    }

    public RunApiHandler(ObjectMapper mapper, String token, RunQueryService runs,
            EvidenceQueryService evidence, RunChangeArtifactService changes,
            RunReviewService reviews,
            com.sage.business.code.application.run.RunActivityQueryService activities,
            ConflictResolutionService conflicts) {
        this(mapper, token, runs, evidence, changes, reviews, activities,
                conflicts, null);
    }

    public RunApiHandler(ObjectMapper mapper, String token, RunQueryService runs,
            EvidenceQueryService evidence, RunChangeArtifactService changes,
            RunReviewService reviews,
            com.sage.business.code.application.run.RunActivityQueryService activities,
            ConflictResolutionService conflicts,
            com.sage.business.code.application.run.RunReasoningQueryService reasoning) {
        this(mapper, token, runs, evidence, changes, reviews, activities,
                conflicts, reasoning, null);
    }

    public RunApiHandler(ObjectMapper mapper, String token, RunQueryService runs,
            EvidenceQueryService evidence, RunChangeArtifactService changes,
            RunReviewService reviews,
            com.sage.business.code.application.run.RunActivityQueryService activities,
            ConflictResolutionService conflicts,
            com.sage.business.code.application.run.RunReasoningQueryService reasoning,
            com.sage.business.code.application.run.RunOutputQueryService output) {
        super(mapper, token);
        if (runs == null || evidence == null) {
            throw new IllegalArgumentException("run services are required");
        }
        this.runs = runs;
        this.evidence = evidence;
        this.changes = changes;
        this.reviews = reviews;
        this.activities = activities;
        this.conflicts = conflicts;
        this.reasoning = reasoning;
        this.output = output;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if ("/api/v2/workspace-contents".equals(path)) {
            requireAccess(exchange);
            if (conflicts == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "workspace contents is not enabled on this service");
            if (!"PUT".equals(method)) throw methodNotAllowed();
            byte[] body = readLimited(exchange.getRequestBody());
            sendJson(exchange, 200, conflicts.putContent(body));
            return true;
        }
        if (path.startsWith("/api/v2/workspace-contents/")) {
            requireAccess(exchange);
            if (conflicts == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "workspace contents is not enabled on this service");
            if (!"GET".equals(method)) throw methodNotAllowed();
            String ref = path.substring("/api/v2/workspace-contents/".length());
            if (ref.isEmpty()) throw new com.sage.business.code.domain.BusinessException(
                    "INVALID_REQUEST", 400, "content ref is required");
            long offset = longParam(exchange, "offset", 0L);
            int limit = (int) longParam(exchange, "limit",
                    (long) Integer.MAX_VALUE);
            byte[] slice = conflicts.readContent(ref, offset,
                    limit == Integer.MAX_VALUE ? -1 : limit);
            Long total = conflicts.contentLength(ref);
            exchange.getResponseHeaders().set("Content-Type",
                    "application/octet-stream");
            if (total != null) {
                exchange.getResponseHeaders().set("X-Content-Total-Bytes",
                        String.valueOf(total));
                boolean truncated = offset + slice.length < total.longValue();
                exchange.getResponseHeaders().set("X-Content-Truncated",
                        String.valueOf(truncated));
            }
            sendBytes(exchange, 200, slice);
            return true;
        }
        if ("/api/v2/workspace-conflicts/resolve-batch".equals(path)) {
            requireAccess(exchange);
            if (conflicts == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "workspace conflict resolution is not enabled on this service");
            if (!"POST".equals(method)) throw methodNotAllowed();
            String key = mutationKey(exchange);
            WorkspaceConflictResolveBatchRequest request =
                    readConflictResolveBatchRequest(exchange);
            sendJson(exchange, 200, conflicts.resolveBatch(request, key));
            return true;
        }
        if ("/api/v2/workspace-conflicts".equals(path)) {
            requireAccess(exchange);
            if (conflicts == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "workspace conflict resolution is not enabled on this service");
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, conflicts.listFiltered(
                    queryParam(exchange, "projectId"),
                    queryParam(exchange, "origin"),
                    queryParam(exchange, "runId"),
                    queryParam(exchange, "state"),
                    queryParam(exchange, "cursor"),
                    (int) longParam(exchange, "limit", 50L)));
            return true;
        }
        if (path.startsWith("/api/v2/workspace-conflicts/")) {
            String[] conflictParts = path.split("/");
            if (conflictParts.length < 5 || conflictParts.length > 6) return false;
            requireAccess(exchange);
            if (conflicts == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "workspace conflict resolution is not enabled on this service");
            // Proposal jobs are addressed as /workspace-conflicts/proposals/{jobId}.
            if ("proposals".equals(conflictParts[4]) && conflictParts.length == 6) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                sendJson(exchange, 200, conflicts.proposal(conflictParts[5]));
                return true;
            }
            String conflictId = conflictParts[4];
            safeEntityId(conflictId, "conflict");
            if (conflictParts.length == 5) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                sendJson(exchange, 200, conflicts.get(conflictId));
                return true;
            }
            String action = conflictParts[5];
            if (!"POST".equals(method)) throw methodNotAllowed();
            if ("resolve".equals(action)) {
                String key = mutationKey(exchange);
                WorkspaceConflictResolveRequest request = readConflictResolveRequest(exchange);
                sendJson(exchange, 200, conflicts.resolve(conflictId, request, key));
                return true;
            }
            if ("auto-merge".equals(action)) {
                mutationKey(exchange);
                sendJson(exchange, 200, conflicts.autoMerge(conflictId));
                return true;
            }
            if ("proposals".equals(action)) {
                mutationKey(exchange);
                sendJson(exchange, 202, conflicts.submitProposal(conflictId));
                return true;
            }
            if ("dismiss".equals(action)) {
                String key = mutationKey(exchange);
                sendJson(exchange, 200, conflicts.dismiss(conflictId, key));
                return true;
            }
            return false;
        }
        if (path.startsWith("/api/v2/findings/")) {
            String[] findingParts = path.split("/");
            if (findingParts.length != 6 || !"fix-task".equals(findingParts[5])) return false;
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            safeEntityId(findingParts[4], "finding");
            mutationKey(exchange);
            if (reviews == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "review service is not enabled on this service");
            // No review worker is wired in the default composition. Keeping
            // this route explicit prevents a missing finding from becoming a
            // generic 404 and avoids creating an untraceable task.
            reviews.requireFinding(findingParts[4]);
            return true;
        }
        if (!path.equals("/api/v2/runs") && !path.startsWith("/api/v2/runs/")) return false;
        String[] parts = path.split("/");
        if ("/api/v2/runs".equals(path)) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, runs.list(queryParam(exchange, "sessionId"),
                    queryParam(exchange, "workspaceAlias"),
                    (int) longParam(exchange, "limit", 100L)));
            return true;
        }
        if (parts.length < 5 || !"runs".equals(parts[3])) return false;
        String runId = parts[4];
        safeEntityId(runId, "run");
        if (parts.length >= 6 && "artifacts".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (changes == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED",503,"Run result artifacts are not enabled");
            com.sage.business.code.domain.RunRecord record = runs.raw(runId);
            if (parts.length == 6) {
                java.util.Map<String,Object> result = changes.resultFiles(runId,
                        (int)longParam(exchange,"page",0L),(int)longParam(exchange,"pageSize",100L));
                result.put("sessionId",record.sessionId);
                sendJson(exchange,200,result); return true;
            }
            if (parts.length == 8 && "bytes".equals(parts[7])) {
                byte[] bytes = changes.resultFileBytes(runId,parts[6]);
                exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
                exchange.getResponseHeaders().set("Cache-Control","private, no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                sendBytes(exchange,200,bytes); return true;
            }
            return false;
        }
        if (parts.length == 5) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, runs.view(runId));
            return true;
        }
        if (parts.length == 6 && "scheduling".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, runs.view(runId).getScheduling());
            return true;
        }
        if (parts.length == 6 && "interrupt".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            String key = mutationKey(exchange);
            sendJson(exchange, 200, runs.interrupt(runId, key, readExpectedVersion(exchange), (String) exchange.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE)));
            return true;
        }
        if (parts.length == 6 && "controls".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            String key = mutationKey(exchange);
            RunControlRequest request = readControlRequest(exchange);
            sendJson(exchange, 200, runs.control(runId, key, request.getIntent(),
                    request.getReasonCode(), request.getExpectedStateVersion(),
                    (String) exchange.getAttribute(V2ApiDispatcher.CLIENT_SESSION_ID_ATTRIBUTE)));
            return true;
        }
        if (parts.length == 6 && "continue".equals(parts[5])) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            String key = mutationKey(exchange);
            com.sage.business.code.contract.v2.run.RunContinueRequest request =
                    readContinueRequest(exchange);
            sendJson(exchange, 201, runs.continueRun(runId, key,
                    request.getMessage(), request.getExpectedStateVersion()));
            return true;
        }
        if (parts.length == 7 && "controls".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            safeEntityId(parts[6], "control");
            if (!runId.equals(runs.raw(runId).runId)
                    || !parts[6].equals(runs.raw(runId).controlCommandId)) {
                throw new com.sage.business.code.domain.BusinessException("CONTROL_NOT_FOUND", 404,
                        "Control command was not found for this run");
            }
            sendJson(exchange, 200, runs.view(runId));
            return true;
        }
        if (parts.length == 6 && "evidence".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 200, evidence.bundle(runId));
            return true;
        }
        if (parts.length == 6 && "review".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (reviews == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "review service is not enabled on this service");
            sendJson(exchange, 200, reviews.get(runId, runs.raw(runId)));
            return true;
        }
        if (parts.length == 6 && "activities".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (activities == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "当前服务未提供执行明细 (run.activity.read)");
            long afterCursor = longParam(exchange, "afterCursor", 0L);
            int limit = (int) longParam(exchange, "limit", 0L);
            sendJson(exchange, 200, activities.snapshot(runId, afterCursor,
                    limit > 0 ? Integer.valueOf(limit) : null));
            return true;
        }
        if (parts.length == 6 && "reasoning".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (reasoning == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "run reasoning snapshot is not enabled on this service");
            sendJson(exchange, 200, reasoning.snapshot(runId));
            return true;
        }
        if (parts.length == 6 && "output".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (output == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "run output snapshot is not enabled on this service");
            sendJson(exchange, 200, output.snapshot(runId));
            return true;
        }
        if (parts.length == 6 && "diff".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (changes == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "run diff is not enabled on this service");
            int page = (int) longParam(exchange, "page", 0L);
            int pageSize = (int) longParam(exchange, "pageSize", 20L);
            sendJson(exchange, 200, changes.page(runId, page, pageSize));
            return true;
        }
        if (parts.length == 6 && "workspace-conflicts".equals(parts[5])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (conflicts == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "workspace conflict resolution is not enabled on this service");
            sendJson(exchange, 200, conflicts.list(runId));
            return true;
        }
        return false;
    }

    /** Body is optional; absent body means no optimistic-concurrency guard. */
    private Long readExpectedVersion(HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) return null;
        final RunInterruptRequest request;
        try {
            request = mapper.readValue(raw, RunInterruptRequest.class);
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Request body cannot be parsed");
        }
        return request == null ? null : request.getExpectedStateVersion();
    }

    private RunControlRequest readControlRequest(HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Control intent is required");
        }
        try {
            RunControlRequest request = mapper.readValue(raw, RunControlRequest.class);
            if (request == null || request.getIntent() == null
                    || request.getIntent().trim().isEmpty()) {
                throw new IllegalArgumentException("intent is required");
            }
            return request;
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Control request body cannot be parsed");
        }
    }

    private com.sage.business.code.contract.v2.run.RunContinueRequest readContinueRequest(
            HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) {
            return new com.sage.business.code.contract.v2.run.RunContinueRequest();
        }
        try {
            return mapper.readValue(raw,
                    com.sage.business.code.contract.v2.run.RunContinueRequest.class);
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Continue request body cannot be parsed");
        }
    }

    private WorkspaceConflictResolveRequest readConflictResolveRequest(HttpExchange exchange)
            throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Conflict resolution body is required");
        }
        try {
            WorkspaceConflictResolveRequest request =
                    mapper.readValue(raw, WorkspaceConflictResolveRequest.class);
            if (request == null || request.getChoice() == null
                    || request.getChoice().trim().isEmpty()) {
                throw new IllegalArgumentException("choice is required");
            }
            return request;
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Conflict resolution request body cannot be parsed");
        }
    }

    private WorkspaceConflictResolveBatchRequest readConflictResolveBatchRequest(
            HttpExchange exchange) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Resolve-batch body is required");
        }
        try {
            return mapper.readValue(raw, WorkspaceConflictResolveBatchRequest.class);
        } catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Resolve-batch request body cannot be parsed");
        }
    }
}
