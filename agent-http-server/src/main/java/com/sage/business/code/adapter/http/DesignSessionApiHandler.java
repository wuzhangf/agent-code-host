package com.sage.business.code.adapter.http;

import com.agent.product.design.v2.api.ActionAvailability;
import com.agent.product.design.v2.api.CallerScope;
import com.agent.product.design.v2.api.CommandContext;
import com.agent.product.design.v2.api.DeliveryRef;
import com.agent.product.design.v2.api.DesignSessionErrorCode;
import com.agent.product.design.v2.api.DesignSessionException;
import com.agent.product.design.v2.api.DesignSessionRequest;
import com.agent.product.design.v2.api.DesignSessionService;
import com.agent.product.design.v2.api.DocumentView;
import com.agent.product.design.v2.api.MaterialRef;
import com.agent.product.design.v2.api.PendingInteraction;
import com.agent.product.design.v2.api.SessionConclusion;
import com.agent.product.design.v2.api.SessionControl;
import com.agent.product.design.v2.api.SessionControlKind;
import com.agent.product.design.v2.api.SessionInput;
import com.agent.product.design.v2.api.SessionSnapshot;
import com.agent.product.design.v2.api.SourceBaseline;
import com.agent.product.design.v2.api.WorkSpecChange;
import com.agent.product.design.v2.recipe.BudgetLimits;
import com.agent.product.design.v2.recipe.CollaborationMode;
import com.agent.product.design.v2.recipe.ControlMode;
import com.agent.product.design.v2.recipe.DeliveryOrganization;
import com.agent.product.design.v2.recipe.Depth;
import com.agent.product.design.v2.recipe.DocumentSlot;
import com.agent.product.design.v2.recipe.Milestone;
import com.agent.product.design.v2.recipe.RequirementSelection;
import com.agent.product.design.v2.recipe.WorkSpecDraft;
import com.agent.product.design.v2.skill.SkillSelection;
import com.agent.product.design.v2.spi.DocumentPort;
import com.agent.product.design.v2.spi.EventPage;
import com.agent.product.design.v2.spi.SessionEvent;
import com.agent.product.design.v2.spi.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.contract.v2.designsession.BaselineDto;
import com.sage.business.code.contract.v2.designsession.BudgetDto;
import com.sage.business.code.contract.v2.designsession.DesignDeliveryView;
import com.sage.business.code.contract.v2.designsession.DesignSessionControlRequest;
import com.sage.business.code.contract.v2.designsession.DesignSessionCreateRequest;
import com.sage.business.code.contract.v2.designsession.DesignSessionEventPage;
import com.sage.business.code.contract.v2.designsession.DesignSessionInputRequest;
import com.sage.business.code.contract.v2.designsession.DesignSessionView;
import com.sage.business.code.contract.v2.designsession.DocumentSlotDto;
import com.sage.business.code.contract.v2.designsession.MaterialRefDto;
import com.sage.business.code.contract.v2.designsession.MilestoneDto;
import com.sage.business.code.contract.v2.designsession.SkillPickDto;
import com.sage.business.code.contract.v2.designsession.WorkSpecDto;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/v2/design/sessions} route group (design 03 &sect;3.1).
 * Thin adapter — every mutation goes through {@link DesignSessionService}
 * verbs with the {@code Idempotency-Key} as the product commandId and an
 * optional {@code expectedSessionVersion} as the CAS token. Reads project
 * {@link SessionSnapshot} / {@link EventPage}; nothing internal escapes
 * as a wire shape.
 *
 * <p>Replaces the retired {@code /api/v2/design/tasks} write surface —
 * a POST to the old base answers {@code DESIGN_LEGACY_EXECUTION_RETIRED}
 * rather than silently routing to the v1 executor.</p>
 */
public final class DesignSessionApiHandler extends V2HttpSupport
        implements V2RouteGroup {

    /** Canonical route base (design 03 &sect;3.1). */
    public static final String BASE = "/api/v2/design/sessions";
    /** Retired v1 write base — rejects, never redirects silently. */
    private static final String RETIRED_WRITE_BASE =
            "/api/v2/design/tasks";

    private static final String LOCAL_OWNER = "local-owner";
    private static final int MAX_EVENTS_PER_PAGE = 200;

    private final DesignSessionService service;
    private final SessionStore store;
    private final DocumentPort documentsPort;
    private final com.agent.product.design.v2.spi.ArtifactPort artifacts;

    public DesignSessionApiHandler(ObjectMapper mapper, String token,
                                   DesignSessionService service,
                                   SessionStore store,
                                   DocumentPort documentsPort,
                                   com.agent.product.design.v2.spi.ArtifactPort
                                           artifacts) {
        super(mapper, token);
        if (service == null || store == null || documentsPort == null
                || artifacts == null) {
            throw new IllegalArgumentException(
                    "session service, store, document port and artifact "
                            + "port are required");
        }
        this.service = service;
        this.store = store;
        this.documentsPort = documentsPort;
        this.artifacts = artifacts;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path,
                               String method) throws IOException {
        if (!path.equals(BASE) && !path.startsWith(BASE + "/")
                && !path.startsWith(RETIRED_WRITE_BASE)) {
            return false;
        }
        // Retired v1 write surface — honest 410, not a silent redirect.
        // Reads on the legacy base fall through to DesignApiHandler so
        // historical artifacts stay reachable.
        if (path.startsWith(RETIRED_WRITE_BASE)
                && ("POST".equals(method) || "PUT".equals(method)
                    || "PATCH".equals(method))) {
            requireAccess(exchange);
            sendProblem(exchange, "DESIGN_LEGACY_EXECUTION_RETIRED", 410,
                    "the v1 design task write surface is retired; create "
                            + "a v2 session via POST " + BASE, null);
            return true;
        }
        if (!path.equals(BASE) && !path.startsWith(BASE + "/")) {
            return false;
        }
        requireAccess(exchange);
        String tail = path.length() > BASE.length()
                ? path.substring(BASE.length() + 1) : "";
        try {
            route(exchange, method, tail);
        } catch (DesignSessionException failure) {
            sendProblem(exchange, failure.getCode().name(),
                    httpStatus(failure.getCode()), failure.getMessage(),
                    null);
        }
        return true;
    }

    private void route(HttpExchange exchange, String method, String tail)
            throws IOException {
        // POST /api/v2/design/sessions — create.
        if (tail.isEmpty() && "POST".equals(method)) {
            create(exchange);
            return;
        }
        String[] segments = tail.split("/");
        if (segments.length == 0 || segments[0].isEmpty()) {
            throw new BusinessException("INVALID_REQUEST", 404,
                    "Endpoint not found");
        }
        String sessionId = segments[0];
        safeEntityId(sessionId, "session");

        if (segments.length == 1) {
            if ("GET".equals(method)) {
                get(exchange, sessionId);
                return;
            }
            throw methodNotAllowed();
        }
        String sub = segments[1];
        if ("start".equals(sub) && "POST".equals(method)) {
            start(exchange, sessionId);
        } else if ("inputs".equals(sub) && "POST".equals(method)) {
            sendInput(exchange, sessionId);
        } else if ("work-spec-revisions".equals(sub)
                && "POST".equals(method)) {
            changeWorkSpec(exchange, sessionId);
        } else if ("controls".equals(sub) && "POST".equals(method)) {
            control(exchange, sessionId);
        } else if ("events".equals(sub) && "GET".equals(method)) {
            events(exchange, sessionId);
        } else if ("documents".equals(sub)) {
            documents(exchange, method, sessionId, segments);
        } else if ("deliveries".equals(sub) && "GET".equals(method)
                && segments.length == 3) {
            getDelivery(exchange, sessionId, segments[2]);
        } else {
            throw new BusinessException("INVALID_REQUEST", 404,
                    "Endpoint not found");
        }
    }

    // ------------------------------------------------------------ verbs

    private void create(HttpExchange exchange) throws IOException {
        DesignSessionCreateRequest body = body(exchange,
                DesignSessionCreateRequest.class);
        // CAS digests prove byte identity, not a member's right to read them.
        // Scoped HTTP callers submit text or an authorized sourceDelivery;
        // trusted local-owner integrations keep their existing ref contract.
        if (scopedCaller(exchange) && (body.getRequirementRef() != null
                || (body.getMaterials() != null && !body.getMaterials().isEmpty()))) {
            throw new DesignSessionException(DesignSessionErrorCode.SCOPE_DENIED,
                    "Scoped Design requests require requirementText and authorized sourceDelivery; raw CAS refs have no scope grant");
        }
        DesignSessionRequest.Builder builder = DesignSessionRequest
                .builder(body.getProjectRef());
        if (body.getRequirementRef() != null) {
            builder.requirement(body.getRequirementRef(),
                    body.getRequirementDigest());
        } else if (body.getRequirementText() != null) {
            // Raw requirement text rides the wire once; persist it into
            // the artifact CAS so material_read (and any future session)
            // resolves the real bytes — a bare digest ref would leave the
            // model staring at a hash it can never dereference.
            String ref = artifacts.store(body.getRequirementText()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "text/plain; charset=utf-8");
            builder.requirement(ref,
                    "sha256:" + ref.substring("artifact:sha256:".length()));
            builder.title(body.getTitle() == null
                    ? body.getRequirementText()
                            .replaceAll("\\s+", " ").trim()
                            .substring(0, Math.min(40,
                                    body.getRequirementText().length()))
                    : body.getTitle());
        }
        if (body.getTitle() != null) {
            builder.title(body.getTitle());
        }
        if (body.getMaterials() != null) {
            List<MaterialRef> materials = new ArrayList<MaterialRef>();
            for (MaterialRefDto dto : body.getMaterials()) {
                materials.add(new MaterialRef(dto.getRef(),
                        dto.getDigest(), dto.getMediaType(), null));
            }
            builder.materialRefs(materials);
        }
        if (body.getSourceBaselines() != null) {
            List<com.agent.product.design.v2.api.SourceBaseline>
                    baselines = new ArrayList
                            <com.agent.product.design.v2.api
                                    .SourceBaseline>();
            for (BaselineDto dto : body.getSourceBaselines()) {
                baselines.add(
                        new com.agent.product.design.v2.api
                                .SourceBaseline(dto.getRepoRef(),
                                dto.getCommitOid(), dto.getTreeOid(),
                                dto.getSnapshotRef(),
                                Boolean.TRUE.equals(
                                        dto.getHistoryAvailable())));
            }
            builder.sourceBaselines(baselines);
        }
        if (body.getWorkSpec() != null) {
            builder.workSpec(toDraft(body.getWorkSpec()));
        }
        if (body.getSourceDelivery() != null) {
            DesignSessionCreateRequest.SourceDelivery source = body.getSourceDelivery();
            builder.seedDocuments(com.sage.business.code.producthost.designv2.DesignDeliverySource.resolve(
                    service, documentsPort, artifacts, callerScope(exchange), body.getProjectRef(),
                    source.getSessionId(), source.getDeliveryId(), source.getDigest(), source.getSelectedPaths()));
            SessionSnapshot upstream = service.get(source.getSessionId(), callerScope(exchange));
            if (upstream.getRequirementRef() != null) {
                byte[] original = artifacts.read(upstream.getRequirementRef(), upstream.getRequirementDigest());
                String retained = artifacts.store(original, "text/plain; charset=utf-8");
                List<MaterialRef> retainedMaterials = new ArrayList<MaterialRef>(builder.build().getMaterialRefs());
                retainedMaterials.add(new MaterialRef(retained, "sha256:" + sha256Hex(original), "text/plain; charset=utf-8",
                        "Original source requirement; apply the new requested changes and current authorization"));
                builder.materialRefs(retainedMaterials);
            }
        }
        SessionSnapshot snapshot = service.create(builder.build(),
                commandContext(exchange, null));
        sendJson(exchange, 202, toView(snapshot));
    }

    private void get(HttpExchange exchange, String sessionId)
            throws IOException {
        sendJson(exchange, 200, toView(service.get(sessionId,
                callerScope(exchange))));
    }

    private void start(HttpExchange exchange, String sessionId)
            throws IOException {
        Long expected = expectedSessionVersion(exchange);
        SessionSnapshot snapshot = service.start(sessionId,
                commandContext(exchange, expected));
        sendJson(exchange, 202, toView(snapshot));
    }

    private void sendInput(HttpExchange exchange, String sessionId)
            throws IOException {
        DesignSessionInputRequest body = body(exchange,
                DesignSessionInputRequest.class);
        if (scopedCaller(exchange) && "MATERIAL".equals(body.getKind())) {
            throw new DesignSessionException(DesignSessionErrorCode.SCOPE_DENIED,
                    "Scoped Design requests cannot attach raw CAS refs without a scope grant");
        }
        SessionInput input = toInput(body);
        SessionSnapshot snapshot = service.send(sessionId, input,
                commandContext(exchange,
                        body.getExpectedSessionVersion()));
        sendJson(exchange, 202, toView(snapshot));
    }

    private void changeWorkSpec(HttpExchange exchange, String sessionId)
            throws IOException {
        // The revision body carries {workSpec, text,
        // expectedSessionVersion}; workSpec is not part of
        // DesignSessionInputRequest, so read the node tree directly
        // rather than dropping the field through a typed DTO.
        JsonNode raw = body(exchange, JsonNode.class);
        JsonNode specNode = raw.path("workSpec");
        WorkSpecDto spec = specNode.isObject()
                ? mapper.convertValue(specNode, WorkSpecDto.class)
                : null;
        if (spec == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "workSpec object is required");
        }
        // Two separate CAS tokens: expectedSessionVersion fences the
        // session row, expectedWorkSpecRevision fences the spec ledger —
        // they count different sequences and must not be conflated.
        JsonNode versionNode = raw.path("expectedSessionVersion");
        Long expectedSessionVersion = versionNode.isNumber()
                ? Long.valueOf(versionNode.longValue()) : null;
        JsonNode specRevNode = raw.path("expectedWorkSpecRevision");
        Long expectedWorkSpecRevision = specRevNode.isNumber()
                ? Long.valueOf(specRevNode.longValue()) : null;
        JsonNode textNode = raw.path("text");
        WorkSpecChange change = new WorkSpecChange(
                toDraft(spec),
                textNode.isTextual() ? textNode.asText() : null,
                expectedWorkSpecRevision);
        SessionSnapshot snapshot = service.changeWorkSpec(sessionId,
                change, commandContext(exchange,
                        expectedSessionVersion));
        sendJson(exchange, 202, toView(snapshot));
    }

    private void control(HttpExchange exchange, String sessionId)
            throws IOException {
        DesignSessionControlRequest body = body(exchange,
                DesignSessionControlRequest.class);
        SessionControlKind kind;
        try {
            kind = SessionControlKind.valueOf(
                    body.getKind() == null ? "" : body.getKind());
        } catch (RuntimeException bad) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "control kind must be PAUSE/RESUME/CANCEL");
        }
        SessionSnapshot snapshot = service.control(sessionId,
                new SessionControl(kind, body.getReason()),
                commandContext(exchange,
                        body.getExpectedSessionVersion()));
        sendJson(exchange, 202, toView(snapshot));
    }

    private void events(HttpExchange exchange, String sessionId)
            throws IOException {
        service.get(sessionId, callerScope(exchange));
        String cursor = queryParam(exchange, "cursor");
        int limit = (int) Math.min(longParam(exchange, "limit", 50L),
                MAX_EVENTS_PER_PAGE);
        EventPage page = store.events(sessionId, cursor, limit);
        DesignSessionEventPage out = new DesignSessionEventPage();
        List<DesignSessionEventPage.Entry> entries =
                new ArrayList<DesignSessionEventPage.Entry>();
        for (SessionEvent event : page.getEvents()) {
            DesignSessionEventPage.Entry entry =
                    new DesignSessionEventPage.Entry();
            entry.setSeq(event.getSeq());
            entry.setKind(event.getKind());
            entry.setRunId(event.getRunId());
            entry.setCorrelationId(event.getCorrelationId());
            entry.setSummary(event.getSummary());
            entry.setPayloadRef(event.getPayloadRef());
            entry.setAtMillis(event.getAtMillis());
            entries.add(entry);
        }
        out.setEvents(entries);
        out.setNextCursor(page.getNextCursor());
        out.setHasMore(page.hasMore());
        sendJson(exchange, 200, out);
    }

    private void documents(HttpExchange exchange, String method,
                           String sessionId, String[] segments)
            throws IOException {
        // Ports and journals are persistence adapters; authorize the owning
        // session before all latest/history/content reads through them.
        SessionSnapshot authorized = service.get(sessionId, callerScope(exchange));
        // GET .../documents — listing.
        if ("GET".equals(method) && segments.length == 2) {
            sendJson(exchange, 200, authorized.getDocuments().stream()
                    .map(this::toDocumentDto).toArray());
            return;
        }
        // The document path is a slash-joined tail: the URI is decoded
        // once before routing, so %2F arrives as a real path separator
        // and a single-segment docPath can never carry a nested path.
        // Reserved tail verbs (versions / versions/{v}/content) match
        // first; everything between "documents" and the verb is the
        // document path.
        int tailStart = 2;
        boolean versionsTail = segments.length >= 4
                && "versions".equals(segments[segments.length - 1]);
        boolean versionContentTail = segments.length >= 6
                && "versions".equals(segments[segments.length - 3])
                && "content".equals(segments[segments.length - 1]);
        // GET .../documents/{docPath} — document content; the document
        // port reads the pinned bytes (never drafts a fresh synthesis
        // on read).
        if ("GET".equals(method) && segments.length >= 3
                && !versionsTail && !versionContentTail) {
            String documentPath = documentPath(segments, tailStart,
                    segments.length);
            DocumentPort.DocumentVersion latest = null;
            for (DocumentPort.DocumentVersion version
                    : documentsPort.list(sessionId)) {
                if (version.getPath().equals(documentPath)) {
                    latest = version;
                    break;
                }
            }
            if (latest == null) {
                throw new BusinessException("NOT_FOUND", 404,
                        "no document at " + documentPath);
            }
            byte[] content = documentsPort.read(sessionId,
                    documentPath, latest.getVersion());
            ObjectNode out = mapper.createObjectNode();
            out.put("path", latest.getPath());
            out.put("version", latest.getVersion());
            out.put("sha256", latest.getSha256());
            out.put("bytes", latest.getBytes());
            out.put("author", latest.getAuthor());
            out.put("content", new String(content,
                    java.nio.charset.StandardCharsets.UTF_8));
            sendJson(exchange, 200, out);
            return;
        }
        // GET .../documents/{docPath}/versions — committed version
        // history of one path, oldest first (design 03 §3.1).
        if ("GET".equals(method) && versionsTail) {
            String documentPath = documentPath(segments, tailStart,
                    segments.length - 1);
            List<DocumentPort.DocumentVersion> history =
                    documentsPort.versions(sessionId, documentPath);
            if (history.isEmpty()) {
                throw new BusinessException("NOT_FOUND", 404,
                        "no document at " + documentPath);
            }
            com.fasterxml.jackson.databind.node.ArrayNode rows =
                    mapper.createArrayNode();
            for (DocumentPort.DocumentVersion v : history) {
                ObjectNode row = mapper.createObjectNode();
                row.put("path", v.getPath());
                row.put("version", v.getVersion());
                row.put("sha256", v.getSha256());
                row.put("bytes", v.getBytes());
                row.put("author", v.getAuthor());
                row.put("atMillis", v.getAtMillis());
                rows.add(row);
            }
            ObjectNode out = mapper.createObjectNode();
            out.put("path", documentPath);
            out.set("versions", rows);
            sendJson(exchange, 200, out);
            return;
        }
        // GET .../documents/{docPath}/versions/{version}/content — one
        // pinned historical version's bytes (read-only history, design
        // 03 §3.1 row 95).
        if ("GET".equals(method) && versionContentTail) {
            String documentPath = documentPath(segments, tailStart,
                    segments.length - 3);
            long version;
            try {
                version = Long.parseLong(segments[segments.length - 2]);
            } catch (NumberFormatException bad) {
                throw new BusinessException("INVALID_REQUEST", 400,
                        "version must be an integer");
            }
            byte[] content = documentsPort.read(sessionId,
                    documentPath, version);
            ObjectNode out = mapper.createObjectNode();
            out.put("path", documentPath);
            out.put("version", version);
            out.put("content", new String(content,
                    java.nio.charset.StandardCharsets.UTF_8));
            sendJson(exchange, 200, out);
            return;
        }
        // POST .../documents/{docPath}/versions — user revision; the
        // same joined-tail rule as the reads above.
        if ("POST".equals(method) && versionsTail) {
            DesignSessionInputRequest body = body(exchange,
                    DesignSessionInputRequest.class);
            String documentPath = documentPath(segments, tailStart,
                    segments.length - 1);
            SessionSnapshot snapshot = service.send(sessionId,
                    SessionInput.documentRevision(documentPath,
                            body.getDocumentText(),
                            body.getExpectedDocumentVersion() == null
                                    ? 0L : body
                                            .getExpectedDocumentVersion()),
                    commandContext(exchange,
                            body.getExpectedSessionVersion()));
            sendJson(exchange, 202, toView(snapshot));
            return;
        }
        throw new BusinessException("INVALID_REQUEST", 404,
                "Endpoint not found");
    }

    /** Join the slash-separated tail segments of a document route into
     *  the document path, then decode once — callers already split on
     *  the decoded URI, so literal '/' inside the path arrives as real
     *  separators and reassembles verbatim. */
    private static String documentPath(String[] segments, int from,
                                       int to) {
        StringBuilder joined = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (joined.length() > 0) {
                joined.append('/');
            }
            joined.append(segments[i]);
        }
        if (joined.length() == 0) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "document path is empty");
        }
        return decodePath(joined.toString());
    }

    /** The document path travels as one whole URL-encoded segment. */
    private static String decodePath(String segment) {
        try {
            return java.net.URLDecoder.decode(segment, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable",
                    impossible);
        }
    }

    private void getDelivery(HttpExchange exchange, String sessionId,
                             String deliveryId) throws IOException {
        DeliveryRef delivery = service.getDelivery(sessionId, deliveryId,
                callerScope(exchange));
        sendJson(exchange, 200, toDeliveryView(delivery));
    }

    private static DesignDeliveryView toDeliveryView(
            DeliveryRef delivery) {
        DesignDeliveryView view = new DesignDeliveryView();
        view.setDeliveryId(delivery.getDeliveryId());
        view.setSessionId(delivery.getSessionId());
        view.setWorkSpecRevision(delivery.getWorkSpecRevision());
        view.setConclusion(delivery.getConclusion().name());
        view.setDigest(delivery.getDigest());
        view.setManifestRef(delivery.getManifestRef());
        view.setPrimaryDocumentPath(delivery.getPrimaryDocumentPath());
        view.setDocumentPaths(delivery.getDocumentPaths());
        view.setLimitations(delivery.getLimitations());
        view.setCreatedAt(delivery.getCreatedAt());
        return view;
    }

    private static BaselineDto toBaselineDto(SourceBaseline baseline) {
        BaselineDto dto = new BaselineDto();
        dto.setRepoRef(baseline.getRepoRef());
        dto.setCommitOid(baseline.getCommitOid());
        dto.setTreeOid(baseline.getTreeOid());
        dto.setSnapshotRef(baseline.getSnapshotRef());
        dto.setHistoryAvailable(baseline.isHistoryAvailable());
        return dto;
    }

    /** EffectiveWorkSpec → the wire view. Multi-mode collaboration and
     *  the revision note only exist on the read side; the write shape
     *  stays the single-mode draft. */
    private static WorkSpecDto toSpecView(
            com.agent.product.design.v2.recipe.EffectiveWorkSpec spec) {
        WorkSpecDto dto = new WorkSpecDto();
        dto.setRevision(spec.getRevision());
        dto.setNote(spec.getNote());
        dto.setControlMode(spec.getControl() == null ? null
                : spec.getControl().name());
        dto.setDepth(spec.getDepth() == null ? null
                : spec.getDepth().name());
        List<String> modes = new ArrayList<String>();
        for (com.agent.product.design.v2.recipe.CollaborationMode mode
                : spec.getCollaboration()) {
            modes.add(mode.name());
        }
        dto.setCollaborationModes(modes);
        dto.setCollaborationMode(modes.isEmpty() ? null : modes.get(0));
        dto.setDeliveryOrganization(
                spec.getDeliveryOrganization() == null ? null
                        : spec.getDeliveryOrganization().name());
        List<String> goals = new ArrayList<String>();
        for (com.agent.product.design.v2.recipe.WorkGoal goal
                : spec.getGoals()) {
            goals.add(goal.name());
        }
        dto.setGoals(goals);
        dto.setScope(spec.getScope());
        dto.setNonGoals(spec.getNonGoals());
        List<DocumentSlotDto> slots = new ArrayList<DocumentSlotDto>();
        for (com.agent.product.design.v2.recipe.DocumentSlot slot
                : spec.getDocumentSlots()) {
            DocumentSlotDto entry = new DocumentSlotDto();
            entry.setSlotId(slot.getSlotId());
            entry.setTitle(slot.getTitle());
            entry.setSelection(slot.getSelection() == null ? null
                    : slot.getSelection().name());
            entry.setMilestoneGated(slot.isMilestoneGated());
            slots.add(entry);
        }
        dto.setDocumentSlots(slots);
        List<SkillPickDto> picks = new ArrayList<SkillPickDto>();
        for (com.agent.product.design.v2.skill.SkillSelection pick
                : spec.getSkillSelections()) {
            SkillPickDto entry = new SkillPickDto();
            entry.setSkillRef(pick.getSkillRef());
            entry.setVersion(pick.getVersion());
            entry.setDigest(pick.getDigest());
            entry.setRole(pick.getRole() == null ? null
                    : pick.getRole().name());
            picks.add(entry);
        }
        dto.setSkillSelections(picks);
        if (spec.getBudgetLimits() != null) {
            BudgetDto budget = new BudgetDto();
            budget.setModelTurns(
                    spec.getBudgetLimits().getModelTurns());
            budget.setActiveMillis(
                    spec.getBudgetLimits().getActiveMillis());
            budget.setChildRuns(
                    spec.getBudgetLimits().getChildConcurrency());
            dto.setBudget(budget);
        }
        List<MilestoneDto> milestones = new ArrayList<MilestoneDto>();
        for (com.agent.product.design.v2.recipe.Milestone milestone
                : spec.getMilestones()) {
            MilestoneDto entry = new MilestoneDto();
            entry.setSlotId(milestone.getMilestoneId());
            entry.setTitle(milestone.getTitle());
            milestones.add(entry);
        }
        dto.setMilestones(milestones);
        dto.setAutoSkillLoadAllowed(spec.isAutoSkillLoadAllowed());
        return dto;
    }

    // ------------------------------------------------------------ mapping

    private static SessionInput toInput(DesignSessionInputRequest body) {
        String kind = body.getKind() == null ? "MESSAGE"
                : body.getKind();
        if ("MESSAGE".equals(kind)) {
            return SessionInput.message(body.getText());
        }
        if ("ANSWER".equals(kind)) {
            return SessionInput.answer(body.getInteractionId(),
                    body.getInteractionVersion() == null ? 0L
                            : body.getInteractionVersion(),
                    body.getText());
        }
        if ("MILESTONE_DECISION".equals(kind)) {
            return SessionInput.milestoneDecision(
                    body.getInteractionId(),
                    body.getInteractionVersion() == null ? 0L
                            : body.getInteractionVersion(),
                    body.getDecision());
        }
        if ("MATERIAL".equals(kind)) {
            List<MaterialRef> materials = new ArrayList<MaterialRef>();
            if (body.getMaterialRefs() != null) {
                for (MaterialRefDto dto : body.getMaterialRefs()) {
                    materials.add(new MaterialRef(dto.getRef(),
                            dto.getDigest(), dto.getMediaType(), null));
                }
            }
            return SessionInput.materials(materials);
        }
        if ("DOCUMENT_REVISION".equals(kind)) {
            return SessionInput.documentRevision(body.getDocumentPath(),
                    body.getDocumentText(),
                    body.getExpectedDocumentVersion() == null ? 0L
                            : body.getExpectedDocumentVersion());
        }
        throw new BusinessException("INVALID_REQUEST", 400,
                "input kind must be MESSAGE/ANSWER/MILESTONE_DECISION/"
                        + "MATERIAL/DOCUMENT_REVISION");
    }

    private static WorkSpecDraft toDraft(WorkSpecDto dto) {
        WorkSpecDraft draft = new WorkSpecDraft();
        if (dto.getControlMode() != null) {
            draft.control(ControlMode.valueOf(dto.getControlMode()));
        }
        if (dto.getDepth() != null) {
            draft.depth(Depth.valueOf(dto.getDepth()));
        }
        if (dto.getCollaborationMode() != null) {
            java.util.Set<CollaborationMode> modes =
                    new java.util.LinkedHashSet<CollaborationMode>();
            modes.add(CollaborationMode.valueOf(
                    dto.getCollaborationMode()));
            draft.collaboration(modes);
        }
        if (dto.getDeliveryOrganization() != null) {
            draft.deliveryOrganization(DeliveryOrganization.valueOf(
                    dto.getDeliveryOrganization()));
        }
        if (dto.getGoals() != null) {
            java.util.Set<com.agent.product.design.v2.recipe.WorkGoal>
                    goals = new java.util.LinkedHashSet<
                            com.agent.product.design.v2.recipe.WorkGoal>();
            for (String goal : dto.getGoals()) {
                goals.add(com.agent.product.design.v2.recipe.WorkGoal
                        .valueOf(goal));
            }
            draft.goals(goals);
        }
        if (dto.getScope() != null) {
            draft.scope(dto.getScope());
        }
        if (dto.getNonGoals() != null) {
            draft.nonGoals(dto.getNonGoals());
        }
        if (dto.getDocumentSlots() != null) {
            List<DocumentSlot> slots = new ArrayList<DocumentSlot>();
            for (DocumentSlotDto slot : dto.getDocumentSlots()) {
                slots.add(new DocumentSlot(slot.getSlotId(),
                        slot.getTitle(),
                        slot.getSelection() == null
                                ? RequirementSelection.OPTIONAL
                                : RequirementSelection.valueOf(
                                        slot.getSelection()),
                        Boolean.TRUE.equals(slot.getMilestoneGated())));
            }
            draft.documentSlots(slots);
        }
        if (dto.getSkillSelections() != null) {
            List<SkillSelection> skills =
                    new ArrayList<SkillSelection>();
            for (SkillPickDto pick : dto.getSkillSelections()) {
                com.agent.product.design.v2.skill.SkillRole role =
                        pick.getRole() == null
                                ? com.agent.product.design.v2.skill
                                        .SkillRole.MAIN
                                : com.agent.product.design.v2.skill
                                        .SkillRole.valueOf(
                                                pick.getRole());
                skills.add(new SkillSelection(pick.getSkillRef(),
                        pick.getVersion(), pick.getDigest(), role,
                        true));
            }
            draft.skillSelections(skills);
        }
        if (dto.getMilestones() != null) {
            List<Milestone> milestones = new ArrayList<Milestone>();
            for (MilestoneDto ms : dto.getMilestones()) {
                milestones.add(new Milestone(ms.getSlotId(),
                        ms.getTitle(), null));
            }
            draft.milestones(milestones);
        }
        if (dto.getBudget() != null) {
            draft.budgetLimits(new BudgetLimits(
                    dto.getBudget().getModelTurns() == null ? 40
                            : dto.getBudget().getModelTurns(),
                    dto.getBudget().getActiveMillis() == null
                            ? 1200000L : dto.getBudget().getActiveMillis(),
                    dto.getBudget().getChildRuns() == null ? 4
                            : dto.getBudget().getChildRuns(),
                    0L, 0L));
        }
        return draft;
    }

    private DesignSessionView toView(SessionSnapshot snapshot) {
        DesignSessionView view = new DesignSessionView();
        view.setSessionId(snapshot.getSessionId());
        view.setVersion(snapshot.getVersion());
        view.setState(snapshot.getState().name());
        view.setTitle(snapshot.getTitle());
        view.setProjectRef(snapshot.getProjectRef());
        view.setWorkSpecRevision(snapshot.getWorkSpecRevision());
        view.setConclusion(snapshot.getConclusion() == null ? null
                : snapshot.getConclusion().name());
        view.setLatestDeliveryId(snapshot.getLatestDeliveryId());
        if (snapshot.getDeliveries() != null) {
            List<DesignDeliveryView> deliveries =
                    new ArrayList<DesignDeliveryView>();
            for (DeliveryRef delivery : snapshot.getDeliveries()) {
                deliveries.add(toDeliveryView(delivery));
            }
            view.setDeliveries(deliveries);
        }
        if (snapshot.getWorkSpec() != null) {
            view.setWorkSpec(toSpecView(snapshot.getWorkSpec()));
        }
        if (snapshot.getSourceBaselines() != null) {
            List<BaselineDto> baselines = new ArrayList<BaselineDto>();
            for (SourceBaseline baseline
                    : snapshot.getSourceBaselines()) {
                baselines.add(toBaselineDto(baseline));
            }
            view.setSourceBaselines(baselines);
        }
        if (snapshot.getBudget() != null) {
            DesignSessionView.BudgetUsageDto usage =
                    new DesignSessionView.BudgetUsageDto();
            usage.setModelTurnsUsed(
                    snapshot.getBudget().getModelTurnsUsed());
            usage.setActiveMillisUsed(
                    snapshot.getBudget().getActiveMillisUsed());
            usage.setChildRunsUsed(
                    snapshot.getBudget().getChildRunsUsed());
            usage.setUsageUnknown(
                    snapshot.getBudget().isUsageUnknown());
            view.setBudgetUsage(usage);
        }
        if (snapshot.getOpenWriters() != null) {
            List<DesignSessionView.OpenWriterDto> writers =
                    new ArrayList<DesignSessionView.OpenWriterDto>();
            for (com.agent.product.design.v2.api.OpenWriter writer
                    : snapshot.getOpenWriters()) {
                DesignSessionView.OpenWriterDto dto =
                        new DesignSessionView.OpenWriterDto();
                dto.setWriterId(writer.getWriterId());
                dto.setKind(writer.getKind());
                writers.add(dto);
            }
            view.setOpenWriters(writers);
        }
        view.setWritersUnknown(snapshot.isWritersUnknown());
        view.setExecutorLabel(snapshot.getExecutorLabel());
        view.setCreatedAt(snapshot.getCreatedAt());
        view.setUpdatedAt(snapshot.getUpdatedAt());
        if (snapshot.getCurrentActivityKind() != null) {
            Map<String, String> activity =
                    new LinkedHashMap<String, String>();
            activity.put("kind", snapshot.getCurrentActivityKind());
            activity.put("summary",
                    snapshot.getCurrentActivitySummary());
            view.setCurrentActivity(activity);
        }
        if (snapshot.getWorkingSummaryText() != null) {
            DesignSessionView.WorkingSummaryDto summary =
                    new DesignSessionView.WorkingSummaryDto();
            summary.setText(snapshot.getWorkingSummaryText());
            summary.setUpdatedAt(snapshot.getWorkingSummaryUpdatedAt());
            view.setWorkingSummary(summary);
        }
        List<DesignSessionView.DocumentDto> docs =
                new ArrayList<DesignSessionView.DocumentDto>();
        for (DocumentView doc : snapshot.getDocuments()) {
            docs.add(toDocumentDto(doc));
        }
        view.setDocuments(docs);
        List<DesignSessionView.PendingInteractionDto> pending =
                new ArrayList<DesignSessionView.PendingInteractionDto>();
        for (PendingInteraction p : snapshot.getPendingInteractions()) {
            DesignSessionView.PendingInteractionDto dto =
                    new DesignSessionView.PendingInteractionDto();
            dto.setInteractionId(p.getInteractionId());
            dto.setKind(p.getKind().name());
            dto.setTitle(p.getTitle());
            dto.setBody(p.getBody());
            dto.setVersion(p.getVersion());
            dto.setDocumentRef(p.getDocumentRef());
            dto.setOpenedAt(p.getOpenedAt());
            List<Map<String, String>> options =
                    new ArrayList<Map<String, String>>();
            for (PendingInteraction.Option option : p.getOptions()) {
                Map<String, String> entry =
                        new LinkedHashMap<String, String>();
                entry.put("id", option.getId());
                entry.put("label", option.getLabel());
                options.add(entry);
            }
            dto.setOptions(options);
            pending.add(dto);
        }
        view.setPendingInteractions(pending);
        List<DesignSessionView.WorkRequirementDto> requirements =
                new ArrayList<DesignSessionView.WorkRequirementDto>();
        for (com.agent.product.design.v2.recipe.WorkRequirement req
                : snapshot.getWorkRequirements()) {
            DesignSessionView.WorkRequirementDto dto =
                    new DesignSessionView.WorkRequirementDto();
            dto.setItemKey(req.getItemKey());
            dto.setSlotId(req.getSlotId());
            dto.setKind(req.getKind().name());
            dto.setSelection(req.getSelection().name());
            dto.setStatus(req.getStatus().name());
            dto.setReason(req.getStatusReason());
            dto.setEvidenceRefs(req.getEvidenceRefs());
            requirements.add(dto);
        }
        view.setWorkRequirements(requirements);
        List<DesignSessionView.ActionAvailabilityDto> actions =
                new ArrayList<DesignSessionView.ActionAvailabilityDto>();
        for (ActionAvailability action : snapshot.getAvailableActions()) {
            DesignSessionView.ActionAvailabilityDto dto =
                    new DesignSessionView.ActionAvailabilityDto();
            dto.setAction(action.getAction());
            dto.setEnabled(action.isEnabled());
            dto.setReason(action.getReason());
            actions.add(dto);
        }
        view.setAvailableActions(actions);
        return view;
    }

    private DesignSessionView.DocumentDto toDocumentDto(
            DocumentView doc) {
        DesignSessionView.DocumentDto dto =
                new DesignSessionView.DocumentDto();
        dto.setDocumentId(doc.getDocumentId());
        dto.setPath(doc.getPath());
        dto.setTitle(doc.getTitle());
        dto.setVersion(doc.getVersion());
        dto.setStatus(doc.getStatus().name());
        dto.setSha256(doc.getSha256());
        dto.setBytes(doc.getBytes());
        dto.setAuthor(doc.getAuthor() == null ? null
                : doc.getAuthor().name());
        dto.setSlotId(doc.getSlotId());
        dto.setSourceRef(doc.getSourceRef());
        dto.setSourceVersion(doc.getSourceVersion());
        dto.setSourceDigest(doc.getSourceDigest());
        dto.setSourceSummary(doc.getSourceSummary());
        return dto;
    }

    // ------------------------------------------------------------ context

    private CommandContext commandContext(HttpExchange exchange,
                                          Long expectedSessionVersion) {
        String commandId = exchange.getRequestHeaders()
                .getFirst("Idempotency-Key");
        if (commandId == null || commandId.trim().isEmpty()) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Idempotency-Key is required");
        }
        return new CommandContext(commandId,
                callerScope(exchange), expectedSessionVersion);
    }

    private boolean scopedCaller(HttpExchange exchange) {
        return trustedHeader(exchange, "X-Caller-Organization") != null
                && trustedHeader(exchange, "X-Caller-Principal") != null;
    }

    private CallerScope callerScope(HttpExchange exchange) {
        // Trusted caller scope (design 03 §5): the bearer token IS the
        // service trust boundary — a token-authed EAC facade forwards
        // the member's real scope as X-Caller-* headers so the journal
        // records the true org/principal instead of "local-owner".
        // Absent headers keep the local desktop scope.
        String org = trustedHeader(exchange, "X-Caller-Organization");
        String principal = trustedHeader(exchange, "X-Caller-Principal");
        String project = trustedHeader(exchange, "X-Caller-Project");
        String instance = trustedHeader(exchange, "X-Caller-Instance");
        if (org == null || principal == null) {
            return new CallerScope("local", LOCAL_OWNER, "local",
                    "local-instance");
        }
        return new CallerScope(org, principal,
                project == null ? org : project,
                instance == null ? "eac-service" : instance);
    }

    /** Header value accepted only as a scope field: printable, bounded,
     *  and matching the safe-entity alphabet the journal persists. */
    private static String trustedHeader(HttpExchange exchange,
                                        String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128
                || !trimmed.matches("[A-Za-z0-9][A-Za-z0-9._:@-]*")) {
            return null;
        }
        return trimmed;
    }

    private Long expectedSessionVersion(HttpExchange exchange) {
        String raw = exchange.getRequestHeaders()
                .getFirst("Expected-Session-Version");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException bad) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "Expected-Session-Version must be an integer");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int httpStatus(DesignSessionErrorCode code) {
        switch (code) {
            case INPUT_INVALID:
                return 400;
            case NOT_FOUND:
                return 404;
            case COMMAND_CONFLICT:
            case VERSION_CONFLICT:
            case DOCUMENT_VERSION_CONFLICT:
            case WORK_SPEC_CONFLICT:
                return 409;
            case SCOPE_DENIED:
            case SKILL_SCOPE_DENIED:
                return 403;
            case EVENT_CURSOR_EXPIRED:
                return 410;
            case SESSION_STATE_REJECTED:
            case MILESTONE_PENDING:
            case DELIVERY_REQUIREMENTS_PENDING:
            case INTERACTION_PENDING:
            case WRITERS_NOT_QUIET:
            case DELIVERY_INVALID:
            case EXECUTION_IN_PROGRESS:
                return 422;
            case SKILL_PACKAGE_UNAVAILABLE:
            case EXECUTOR_CAPABILITY_UNAVAILABLE:
            case SOURCE_BASELINE_CHANGED:
            case STORE_UNAVAILABLE:
            case STORE_ATOMICITY_UNAVAILABLE:
            case SESSION_STATE_CORRUPT:
            case RECEIPT_UNKNOWN:
            default:
                return 500;
        }
    }
}
