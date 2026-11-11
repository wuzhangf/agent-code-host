package com.sage.business.code.adapter.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agent.product.design.api.ActorRef;
import com.agent.product.design.api.AnswerBatch;
import com.agent.product.design.api.ClarificationDraft;
import com.agent.product.design.api.CommandContext;
import com.agent.product.design.api.DesignErrorCode;
import com.agent.product.design.api.DesignException;
import com.agent.product.design.api.DesignInteractionKind;
import com.agent.product.design.api.DesignMaterialRequirement;
import com.agent.product.design.api.DesignQuestionOption;
import com.agent.product.design.api.DesignRequest;
import com.agent.product.design.api.DesignReviewDecision;
import com.agent.product.design.api.DesignRevisionRequest;
import com.agent.product.design.api.DesignTaskSnapshot;
import com.agent.product.design.api.MaterialSubmission;
import com.agent.product.design.api.RefDigest;
import com.agent.product.design.model.DesignTask;
import com.agent.product.design.service.DesignTaskService;
import com.agent.product.design.spi.DesignTaskStore;
import com.agent.product.design.store.FileDesignTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.design.DesignEventBridge;
import com.sage.business.code.application.design.DesignInteractionBoard;
import com.sage.business.code.contract.v2.design.DesignAnswersRequest;
import com.sage.business.code.contract.v2.design.DesignExpectedVersionRequest;
import com.sage.business.code.contract.v2.design.DesignInteractionRaiseRequest;
import com.sage.business.code.contract.v2.design.DesignMaterialsRequest;
import com.sage.business.code.contract.v2.design.DesignRefValue;
import com.sage.business.code.contract.v2.design.DesignReviewRequest;
import com.sage.business.code.contract.v2.design.DesignReviseRequest;
import com.sage.business.code.contract.v2.design.DesignTaskCreateRequest;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;

/**
 * /api/v2/design route group (design-agent 03 runtime plane). Thin adapter:
 * every mutation goes through the product {@link DesignTaskService} verbs
 * with the Idempotency-Key as commandId and expectedTaskVersion as the CAS
 * token; durable event publication and attention projection are drained
 * after the product commit via {@link DesignEventBridge}. All failures are
 * typed — {@link DesignException} maps to its stable code, nothing internal
 * leaks to the wire.
 */
public final class DesignApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    /** Desktop local-owner identity; matches ClientSessionApiHandler. */
    private static final String LOCAL_OWNER = "local-owner";
    /** Canonical route base (spec 03 §4). */
    private static final String BASE = "/api/v2/design-tasks";
    /** Legacy alias kept for one release — same handler, same contract. */
    private static final String LEGACY_BASE = "/api/v2/design/tasks";
    /** Content-root listing route (10 §G5): sibling of the task subtree. */
    private static final String CONTENT_BASE = "/api/v2/design/content";

    private final DesignTaskService service;
    private final DesignTaskStore store;
    private final DesignInteractionBoard interactions;
    private final DesignEventBridge bridge;
    private final com.sage.business.code.application.design
            .FileDesignContentPort content;

    public DesignApiHandler(ObjectMapper mapper, String token,
            DesignTaskService service, DesignTaskStore store,
            DesignInteractionBoard interactions, DesignEventBridge bridge,
            com.sage.business.code.application.design
                    .FileDesignContentPort content) {
        super(mapper, token);
        if (service == null || store == null || interactions == null
                || bridge == null) {
            throw new IllegalArgumentException(
                    "design service, store, board and bridge are required");
        }
        this.service = service;
        this.store = store;
        this.interactions = interactions;
        this.bridge = bridge;
        this.content = content;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
                               String method) throws IOException {
        if (LEGACY_BASE.equals(path)
                || path.startsWith(LEGACY_BASE + "/")) {
            // alias: normalize to the canonical base before routing
            path = BASE + path.substring(LEGACY_BASE.length());
        } else if (CONTENT_BASE.equals(path)) {
            // /api/v2/design/content sits outside the task subtree — the
            // gate is widened deliberately for this one sibling route.
            try {
                if ("POST".equals(method)) {
                    return contentPublish(exchange);
                }
                return contentList(exchange, method);
            } catch (DesignException failure) {
                sendProblem(exchange, failure.getCode().name(),
                        statusFor(failure.getCode()), failure.getMessage(),
                        Collections.<String, Object>singletonMap("retryable",
                                failure.getCode().isDefaultRetryable()));
                return true;
            } catch (BusinessException failure) {
                sendProblem(exchange, failure.getCode(),
                        failure.getHttpStatus(), failure.getMessage(),
                        failure.getDetails());
                return true;
            }
        } else if (!BASE.equals(path) && !path.startsWith(BASE + "/")) {
            return false;
        }
        // ND-13 (redesign-20261006): the fixed S0–S7 design-task write
        // surface is retired — mutations answer 410 GONE with a stable
        // pointer at the v2 session plane; reads stay live so
        // historical artifacts remain reachable.
        if (!"GET".equals(method)) {
            sendProblem(exchange, "DESIGN_V1_RETIRED", 410,
                    "the fixed design-task write surface is retired; "
                            + "use /api/v2/design/sessions",
                    null);
            return true;
        }
        try {
            return route(exchange, path, method);
        } catch (DesignException failure) {
            sendProblem(exchange, failure.getCode().name(),
                    statusFor(failure.getCode()), failure.getMessage(),
                    Collections.<String, Object>singletonMap("retryable",
                            failure.getCode().isDefaultRetryable()));
            return true;
        } catch (IllegalArgumentException failure) {
            sendProblem(exchange, "INVALID_REQUEST", 400,
                    failure.getMessage(), null);
            return true;
        } catch (BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(),
                    failure.getMessage(), failure.getDetails());
            return true;
        }
    }

    private boolean route(HttpExchange exchange, String path, String method)
            throws IOException {
        if (BASE.equals(path)) {
            requireAccess(exchange);
            if ("POST".equals(method)) {
                return create(exchange);
            }
            if ("GET".equals(method)) {
                return list(exchange);
            }
            throw methodNotAllowed();
        }
        String[] parts = path.substring(BASE.length() + 1).split("/");
        String taskId = parts[0];
        safeEntityId(taskId, "design task");
        requireAccess(exchange);
        if (parts.length == 1) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return getTask(exchange, taskId);
        }
        String sub = parts[1];
        if ("advance".equals(sub) && parts.length == 2) {
            requirePost(method);
            DesignExpectedVersionRequest body =
                    body(exchange, DesignExpectedVersionRequest.class);
            return snapshot(exchange, service.advance(taskId,
                    command(exchange, body.getExpectedTaskVersion())),
                    taskId);
        }
        if ("cancel".equals(sub) && parts.length == 2) {
            requirePost(method);
            DesignExpectedVersionRequest body =
                    body(exchange, DesignExpectedVersionRequest.class);
            return snapshot(exchange, service.cancel(taskId,
                    command(exchange, body.getExpectedTaskVersion())),
                    taskId);
        }
        if ("answers".equals(sub) && parts.length == 2) {
            requirePost(method);
            return answers(exchange, taskId);
        }
        if ("materials".equals(sub) && parts.length == 2) {
            requirePost(method);
            return materials(exchange, taskId);
        }
        if (("revise".equals(sub) || "revisions".equals(sub))
                && parts.length == 2 && "POST".equals(method)) {
            return revise(exchange, taskId);
        }
        if ("review".equals(sub) && parts.length == 2) {
            requirePost(method);
            return review(exchange, taskId);
        }
        if ("interactions".equals(sub)) {
            return interactions(exchange, path, method, taskId, parts);
        }
        if ("revisions".equals(sub) && parts.length == 2) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return revisions(exchange, taskId);
        }
        if ("bundles".equals(sub) && parts.length == 3) {
            if (!"GET".equals(method)) {
                throw methodNotAllowed();
            }
            return bundleDownload(exchange, taskId, parts[2]);
        }
        return false;
    }

    // ---- verbs ----

    private boolean create(HttpExchange exchange) throws IOException {
        DesignTaskCreateRequest request =
                body(exchange, DesignTaskCreateRequest.class);
        DesignTaskSnapshot snapshot =
                service.create(toProductRequest(request),
                        command(exchange, null));
        bridge.drain(snapshot.getTaskId());
        sendJson(exchange, 201, snapshot);
        return true;
    }

    private boolean answers(HttpExchange exchange, String taskId)
            throws IOException {
        DesignAnswersRequest request =
                body(exchange, DesignAnswersRequest.class);
        String commandId = mutationKey(exchange);
        DesignTaskSnapshot snapshot = service.answer(taskId,
                toAnswerBatch(request),
                new CommandContext(commandId, actor(),
                        request.getExpectedTaskVersion()));
        bridge.drain(taskId);
        // §4: answers return a 202 receipt envelope — the per-item ledger
        // outcomes ride on the receipt, resolution lands asynchronously.
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("receiptId", commandId);
        response.put("taskVersion", snapshot.getVersion());
        response.put("pendingActions", snapshot.getPendingActions());
        response.put("perAnswerOutcomes",
                receiptOutcomes(taskId, "design.answer", commandId));
        response.put("task", snapshot);
        sendJson(exchange, 202, response);
        return true;
    }

    private boolean materials(HttpExchange exchange, String taskId)
            throws IOException {
        DesignMaterialsRequest request =
                body(exchange, DesignMaterialsRequest.class);
        String commandId = mutationKey(exchange);
        DesignTaskSnapshot snapshot = service.attach(taskId,
                toMaterialSubmission(request),
                new CommandContext(commandId, actor(),
                        request.getExpectedTaskVersion()));
        bridge.drain(taskId);
        // §4: material submissions also return the 202 receipt envelope —
        // admission (ACCEPTED/REJECTED) is per-item on the ledger.
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("receiptId", commandId);
        response.put("taskVersion", snapshot.getVersion());
        response.put("pendingActions", snapshot.getPendingActions());
        response.put("perMaterialOutcomes",
                receiptOutcomes(taskId, "design.attach", commandId));
        response.put("task", snapshot);
        sendJson(exchange, 202, response);
        return true;
    }

    private boolean revise(HttpExchange exchange, String taskId)
            throws IOException {
        DesignReviseRequest request =
                body(exchange, DesignReviseRequest.class);
        DesignRevisionRequest revision = new DesignRevisionRequest(
                enumOf(DesignRevisionRequest.Kind.class, request.getKind()),
                request.getTargetRefs(), request.getRationale(),
                request.getSource(), request.getDesignGapRef());
        return snapshot(exchange, service.revise(taskId, revision,
                command(exchange, request.getExpectedTaskVersion())), taskId);
    }

    private boolean review(HttpExchange exchange, String taskId)
            throws IOException {
        DesignReviewRequest request =
                body(exchange, DesignReviewRequest.class);
        DesignReviewDecision decision = new DesignReviewDecision(
                request.getDesignRevisionId(), request.getSubjectDigest(),
                enumOf(DesignReviewDecision.Decision.class,
                        request.getDecision()),
                request.getRationale(), request.getConditions(),
                request.getEvidenceRefs(), request.getResolvedQuestionIds(),
                request.getAcceptedAssumptionIds());
        return snapshot(exchange, service.review(taskId, decision,
                command(exchange, request.getExpectedTaskVersion())), taskId);
    }

    // ---- interaction plane ----

    private boolean interactions(HttpExchange exchange, String path,
            String method, String taskId, String[] parts) throws IOException {
        if (parts.length == 2) {
            if ("GET".equals(method)) {
                // unknown task → 404, not an empty collection (§4 matrix)
                if (store.load(taskId) == null) {
                    throw new BusinessException("NOT_FOUND", 404,
                            "design task not found: " + taskId);
                }
                String bucket = queryParam(exchange, "bucket");
                if (bucket == null) {
                    bucket = DesignInteractionBoard.BUCKET_REQUESTS;
                }
                safeBucket(bucket);
                String after = queryParam(exchange, "after");
                int limit = (int) longParam(exchange, "limit", 100L);
                List<Map<String, Object>> items =
                        interactions.list(taskId, bucket);
                // Stable order + id cursor — a refetch never skips or
                // repeats rows.
                java.util.Collections.sort(items,
                        new java.util.Comparator<Map<String, Object>>() {
                            public int compare(Map<String, Object> x,
                                               Map<String, Object> y) {
                                return String.valueOf(idOf(x))
                                        .compareTo(String.valueOf(idOf(y)));
                            }
                        });
                List<Map<String, Object>> page =
                        new ArrayList<Map<String, Object>>();
                String nextCursor = null;
                for (Map<String, Object> item : items) {
                    String id = String.valueOf(idOf(item));
                    if (after != null && id.compareTo(after) <= 0) {
                        continue;
                    }
                    if (page.size() >= limit) {
                        nextCursor = id;
                        break;
                    }
                    page.add(item);
                }
                Map<String, Object> response =
                        new LinkedHashMap<String, Object>();
                response.put("taskId", taskId);
                response.put("bucket", bucket);
                response.put("items", page);
                response.put("nextCursor", nextCursor);
                sendJson(exchange, 200, response);
                return true;
            }
            if ("POST".equals(method)) {
                return raise(exchange, taskId);
            }
            throw methodNotAllowed();
        }
        if (parts.length == 3 && "GET".equals(method)) {
            String interactionId = parts[2];
            safeEntityId(interactionId, "interaction");
            Map<String, Object> card = interactions.read(taskId,
                    DesignInteractionBoard.BUCKET_REQUESTS, interactionId);
            if (card == null) {
                throw new BusinessException("NOT_FOUND", 404,
                        "interaction not found: " + interactionId);
            }
            List<Map<String, Object>> answers =
                    new ArrayList<Map<String, Object>>();
            for (Map<String, Object> answer : interactions.list(taskId,
                    DesignInteractionBoard.BUCKET_ANSWERS)) {
                if (interactionId.equals(answer.get("requestId"))) {
                    answers.add(answer);
                }
            }
            Map<String, Object> response =
                    new LinkedHashMap<String, Object>();
            response.put("card", card);
            response.put("answers", answers);
            sendJson(exchange, 200, response);
            return true;
        }
        return false;
    }

    private boolean raise(HttpExchange exchange, String taskId)
            throws IOException {
        DesignInteractionRaiseRequest request =
                body(exchange, DesignInteractionRaiseRequest.class);
        ClarificationDraft draft = new ClarificationDraft();
        draft.kind = enumOf(DesignInteractionKind.class, request.getKind());
        draft.summary = request.getSummary();
        draft.knownFacts = listOrEmpty(request.getKnownFacts());
        draft.evidenceRefs = listOrEmpty(request.getEvidenceRefs());
        draft.affectedRequirementIds =
                listOrEmpty(request.getAffectedRequirementIds());
        draft.blockingStepIds = listOrEmpty(request.getBlockingStepIds());
        draft.blockingDesignIds = listOrEmpty(request.getBlockingDesignIds());
        for (DesignInteractionRaiseRequest.Option o
                : listOrEmpty(request.getOptions())) {
            draft.options.add(
                    new DesignQuestionOption(o.getId(), o.getLabel(),
                            o.getConsequence()));
        }
        draft.recommendation = request.getRecommendation();
        draft.requestedRole = request.getRequestedRole();
        for (DesignInteractionRaiseRequest.MaterialRequirement m
                : listOrEmpty(request.getMaterialRequirements())) {
            draft.materialRequirements.add(
                    new DesignMaterialRequirement(m.getName(),
                            m.getDescription()));
        }
        Map<String, Object> card = interactions.raise(taskId, draft,
                command(exchange, request.getExpectedTaskVersion()));
        bridge.drain(taskId);
        sendJson(exchange, Boolean.TRUE.equals(card.get("replayed"))
                ? 200 : 201, card);
        return true;
    }

    // ---- reads ----

    private boolean list(HttpExchange exchange) throws IOException {
        List<DesignTaskSnapshot> items =
                new ArrayList<DesignTaskSnapshot>();
        int limit = (int) longParam(exchange, "limit", 100L);
        for (String taskId : store.listTaskIds()) {
            if (items.size() >= limit) {
                break;
            }
            DesignTask task = store.load(taskId);
            if (task != null) {
                items.add(DesignTaskSnapshot.of(task));
            }
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("items", items);
        sendJson(exchange, 200, response);
        return true;
    }

    /**
     * {@code GET /api/v2/design/content} — lists referenceable documents
     * under the content root so task-creation callers can build
     * {@code ref@sha256:…} references without hashing bytes themselves
     * (10 §G5). Bounded listing comes from the port (depth 2, 200 rows).
     */
    private boolean contentList(HttpExchange exchange, String method)
            throws IOException {
        requireAccess(exchange);
        if (!"GET".equals(method)) {
            throw methodNotAllowed();
        }
        if (content == null) {
            throw new BusinessException("CAPABILITY_UNAVAILABLE", 503,
                    "design content root is not configured");
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("items", content.list());
        sendJson(exchange, 200, response);
        return true;
    }

    /**
     * {@code POST /api/v2/design/content} — worker-design 05 seam S6:
     * publishes a pinned material document under {@code ref} so the
     * product's content port can resolve it. Body
     * {@code {ref, digest, contentBase64}}; the port verifies the digest
     * over the decoded bytes before writing, identical re-publication is
     * a no-op, and a differing write under the same ref is VERSION_
     * CONFLICT — the content root is append-only evidence, never
     * mutated in place.
     */
    private boolean contentPublish(HttpExchange exchange)
            throws IOException {
        requireAccess(exchange);
        if (content == null) {
            throw new BusinessException("CAPABILITY_UNAVAILABLE", 503,
                    "design content root is not configured");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> request = body(exchange, Map.class);
        String ref = string(request.get("ref"));
        String digest = string(request.get("digest"));
        String encoded = string(request.get("contentBase64"));
        if (ref == null || digest == null || encoded == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "content publish requires ref, digest and "
                            + "contentBase64");
        }
        final byte[] bytes;
        try {
            bytes = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException bad) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "contentBase64 is not valid base64");
        }
        if (bytes.length > 8L * 1024 * 1024) {
            throw new BusinessException("INVALID_REQUEST", 413,
                    "content exceeds the 8 MiB publish bound");
        }
        String stored = content.publish(ref, digest, bytes);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("ref", ref);
        response.put("digest", stored);
        response.put("bytes", bytes.length);
        sendJson(exchange, 200, response);
        return true;
    }

    private static String string(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = (String) value;
        return s.isEmpty() || s.trim().isEmpty() ? null : s;
    }

    private boolean getTask(HttpExchange exchange, String taskId)
            throws IOException {
        DesignTaskSnapshot snapshot = service.get(taskId);
        if (snapshot == null) {
            throw new BusinessException("NOT_FOUND", 404,
                    "design task not found: " + taskId);
        }
        sendJson(exchange, 200, snapshot);
        return true;
    }

    private boolean revisions(HttpExchange exchange, String taskId)
            throws IOException {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String id : store.listInputRevisionIds(taskId)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("inputRevisionId", id);
            items.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("items", items);
        sendJson(exchange, 200, response);
        return true;
    }

    /**
     * {@code GET /{id}/bundles/{revision}} — authorized read of a published
     * bundle's manifest. The revision id resolves through the ledger (never
     * a caller-supplied path), the bundle must still verify, and the bytes
     * come back through the artifact port's containment-checked read —
     * no cross-task path guessing (§4/R08).
     */
    private boolean bundleDownload(HttpExchange exchange, String taskId,
                                   String revisionId) throws IOException {
        safeEntityId(revisionId, "design revision");
        if (store.load(taskId) == null) {
            throw new BusinessException("NOT_FOUND", 404,
                    "design task not found: " + taskId);
        }
        com.agent.product.design.model.DesignRevision rev =
                store.loadDesignRevision(taskId, revisionId);
        if (rev == null || rev.getBundleRef() == null) {
            throw new BusinessException("NOT_FOUND", 404,
                    "design revision not found: " + revisionId);
        }
        if (!(store instanceof FileDesignTaskStore)) {
            throw new BusinessException("CAPABILITY_UNAVAILABLE", 503,
                    "bundle download requires the file-backed store");
        }
        java.nio.file.Path dir = ((FileDesignTaskStore) store)
                .bundleDirForRef(taskId, rev.getBundleRef());
        if (dir == null) {
            throw new BusinessException("NOT_FOUND", 404,
                    "bundle ref does not resolve under this task");
        }
        com.agent.product.design.artifact.FileDesignArtifactPort port =
                new com.agent.product.design.artifact
                        .FileDesignArtifactPort();
        if (!port.verifyPublished(dir.toString()).ok) {
            throw new BusinessException("NOT_FOUND", 404,
                    "bundle missing or failed re-verification");
        }
        String name = queryParam(exchange, "file");
        if (name == null) {
            name = "result-manifest.json";
        }
        byte[] bytes = port.readAuthorized(dir.toString(), name);
        if (bytes == null) {
            throw new BusinessException("NOT_FOUND", 404,
                    "no such file in bundle: " + name);
        }
        exchange.getResponseHeaders().set("Content-Type",
                "application/octet-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
        return true;
    }

    private static String idOf(Map<String, Object> row) {
        Object id = row.get("requestId");
        if (id == null) { id = row.get("answerId"); }
        if (id == null) { id = row.get("materialId"); }
        if (id == null) { id = row.get("resolutionId"); }
        return id == null ? "" : String.valueOf(id);
    }

    // ---- mapping ----

    private DesignRequest toProductRequest(DesignTaskCreateRequest r) {
        DesignRequest.Builder builder = DesignRequest.builder()
                .projectId(requireText(r.getProjectId(), "projectId"))
                .requirementRef(ref(r.getRequirementRef(), "requirementRef"))
                .owners(toOwners(r.getOwners()))
                .interactionPolicy(toPolicy(r.getInteractionPolicy()));
        if (r.getSchemaVersion() != null) {
            builder.schemaVersion(r.getSchemaVersion());
        }
        if (r.getBaselineRef() != null) {
            builder.baselineRef(ref(r.getBaselineRef(), "baselineRef"));
        }
        if (r.getScopePolicyRef() != null) {
            builder.scopePolicyRef(ref(r.getScopePolicyRef(), "scopePolicyRef"));
        }
        if (r.getModelPolicyRef() != null) {
            builder.modelPolicyRef(ref(r.getModelPolicyRef(), "modelPolicyRef"));
        }
        if (r.getConstraintRefs() != null) {
            builder.constraintRefs(refs(r.getConstraintRefs()));
        }
        if (r.getRuntimeFactRefs() != null) {
            builder.runtimeFactRefs(refs(r.getRuntimeFactRefs()));
        }
        if (r.getPreviousBundleRef() != null) {
            builder.previousBundleRef(
                    ref(r.getPreviousBundleRef(), "previousBundleRef"));
        }
        if (r.getRevisionRequestRef() != null) {
            builder.revisionRequestRef(
                    ref(r.getRevisionRequestRef(), "revisionRequestRef"));
        }
        if (r.getExternalBindings() != null) {
            List<DesignRequest.ExternalBinding> bindings =
                    new ArrayList<DesignRequest.ExternalBinding>();
            for (DesignTaskCreateRequest.Binding b : r.getExternalBindings()) {
                bindings.add(new DesignRequest.ExternalBinding(b.getSystem(),
                        b.getExternalId()));
            }
            builder.externalBindings(bindings);
        }
        builder.clientRequestNote(r.getClientRequestNote());
        return builder.build();
    }

    private static DesignRequest.Owners toOwners(
            DesignTaskCreateRequest.Owners owners) {
        if (owners == null) {
            return new DesignRequest.Owners(DesignRequest.OwnerRef.unassigned(),
                    DesignRequest.OwnerRef.unassigned(),
                    DesignRequest.OwnerRef.unassigned(),
                    DesignRequest.OwnerRef.unassigned());
        }
        return new DesignRequest.Owners(owner(owners.getRequester()),
                owner(owners.getBusinessResponder()),
                owner(owners.getTechnicalResponder()),
                owner(owners.getDesignReviewer()));
    }

    private static DesignRequest.OwnerRef owner(
            DesignTaskCreateRequest.Owner o) {
        if (o == null || o.getIdentityRef() == null
                || o.getIdentityRef().trim().isEmpty()) {
            return DesignRequest.OwnerRef.unassigned();
        }
        return DesignRequest.OwnerRef.of(o.getIdentityRef(),
                o.getDisplayName());
    }

    private static DesignRequest.InteractionPolicy toPolicy(
            DesignTaskCreateRequest.Policy p) {
        if (p == null) {
            return new DesignRequest.InteractionPolicy(
                    DesignRequest.InteractionPolicy.AskMode.PROACTIVE, 8,
                    "zh-CN");
        }
        return new DesignRequest.InteractionPolicy(
                enumOf(DesignRequest.InteractionPolicy.AskMode.class,
                        p.getAskMode()),
                p.getMaxBatchCards() < 1 ? 8 : p.getMaxBatchCards(),
                p.getLocale() == null ? "zh-CN" : p.getLocale());
    }

    private static AnswerBatch toAnswerBatch(DesignAnswersRequest request) {
        List<AnswerBatch.AnswerItem> items =
                new ArrayList<AnswerBatch.AnswerItem>();
        if (request.getItems() != null) {
            for (DesignAnswersRequest.Item i : request.getItems()) {
                items.add(new AnswerBatch.AnswerItem(i.getRequestId(),
                        i.getQuestionRevision(), i.getInputRevisionId(),
                        i.getAnswerKind(), i.getPayload(),
                        i.getSupersedesAnswerId()));
            }
        }
        return new AnswerBatch(items);
    }

    private static MaterialSubmission toMaterialSubmission(
            DesignMaterialsRequest request) {
        List<MaterialSubmission.MaterialItem> items =
                new ArrayList<MaterialSubmission.MaterialItem>();
        if (request.getItems() != null) {
            for (DesignMaterialsRequest.Item i : request.getItems()) {
                DesignMaterialsRequest.Provenance p = i.getProvenance();
                items.add(new MaterialSubmission.MaterialItem(
                        i.getMaterialKind(), i.getContentRef(),
                        i.getInlineContent(), i.getSha256(), i.getSizeBytes(),
                        p == null ? null
                                : new MaterialSubmission.Provenance(
                                        p.getSource(), p.getObservedAt(),
                                        p.getVersion()),
                        i.getRequestId()));
            }
        }
        return new MaterialSubmission(items);
    }

    // ---- plumbing ----

    private boolean snapshot(HttpExchange exchange,
                             DesignTaskSnapshot snapshot, String taskId)
            throws IOException {
        bridge.drain(taskId);
        sendJson(exchange, 200, snapshot);
        return true;
    }

    private CommandContext command(HttpExchange exchange, Long expected) {
        return new CommandContext(mutationKey(exchange), actor(), expected);
    }

    private static ActorRef actor() {
        return new ActorRef(LOCAL_OWNER,
                Collections.singletonList(LOCAL_OWNER));
    }

    /** Per-item verb outcomes are recorded on the command receipt. */
    private List<String> receiptOutcomes(String taskId, String verb,
                                         String commandId) {
        DesignTaskStore.StoredReceipt receipt =
                store.findReceipt(taskId, verb, commandId);
        if (receipt == null || !verb.equals(receipt.verb)
                || receipt.receiptJson == null
                || receipt.receiptJson.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> outcomes = new ArrayList<String>();
        for (String part : receipt.receiptJson.split(";")) {
            if (!part.isEmpty()) {
                outcomes.add(part);
            }
        }
        return outcomes;
    }

    private static void requirePost(String method) {
        if (!"POST".equals(method)) {
            throw methodNotAllowed();
        }
    }

    private static void safeBucket(String bucket) {
        if (!bucket.matches("[a-z-]{1,32}")) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "bucket is invalid");
        }
    }

    private static RefDigest ref(DesignRefValue value, String field) {
        if (value == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    field + " is required");
        }
        return new RefDigest(value.getRef(), value.getDigest());
    }

    private static List<RefDigest> refs(List<DesignRefValue> values) {
        List<RefDigest> out = new ArrayList<RefDigest>();
        for (DesignRefValue v : values) {
            out.add(ref(v, "ref"));
        }
        return out;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    field + " is required");
        }
        return value;
    }

    private static <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? new ArrayList<T>() : values;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    type.getSimpleName() + " value is required");
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException bad) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "unknown " + type.getSimpleName() + ": " + name);
        }
    }

    private static int statusFor(DesignErrorCode code) {
        switch (code) {
            case INPUT_INVALID:
            case INVALID_REQUEST:
            case NEEDS_METADATA:
                return 400;
            case FORBIDDEN:
                return 403;
            case NOT_FOUND:
                return 404;
            case VERSION_CONFLICT:
            case COMMAND_CONFLICT:
            case INPUT_CHANGED:
            case SCOPE_CHANGE_REQUIRED:
            case ANSWER_STALE:
            case REVIEW_MISMATCH:
            case REVISION_SUPERSEDED:
            case CONCURRENT_MODIFICATION:
            case CANCEL_NOT_CONFIRMED:
            case DESIGN_NOT_READY:
                return 409;
            case MATERIAL_REJECTED:
                return 422;
            case RESEARCH_OUTPUT_INVALID:
            case MODEL_OUTPUT_INVALID:
                return 502;
            case BASELINE_UNAVAILABLE:
            case CAPABILITY_UNAVAILABLE:
            case DISPATCH_REJECTED:
                return 503;
            case OUTCOME_UNKNOWN:
            default:
                return 500;
        }
    }
}
