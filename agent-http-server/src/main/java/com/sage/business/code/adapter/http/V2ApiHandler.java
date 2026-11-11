package com.sage.business.code.adapter.http;
import com.sage.business.code.application.run.FailureDescriptorAdapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.adapter.http.stream.LongRequestAdmission;
import com.sage.business.code.application.session.SessionService;
import com.sage.business.code.application.spec.SpecService;
import com.sage.business.code.application.policy.RiskDecisionAuditStore;
import com.sage.business.code.application.policy.RiskPolicyEngine;
import com.sage.business.code.application.mission.MissionService;
import com.sage.business.code.application.mission.MissionScheduler;
import com.sage.business.code.contract.v2.session.SessionArchiveRequest;
import com.sage.business.code.contract.v2.session.SessionCreateRequest;
import com.sage.business.code.contract.v2.session.SessionForkRequest;
import com.sage.business.code.contract.v2.session.SessionMessageRequest;
import com.sage.business.code.contract.v2.session.SessionPatchRequest;
import com.sage.business.code.contract.v2.session.SessionModelSettingsPatchRequest;
import com.sage.business.code.contract.v2.session.SessionModelSettingsResponse;
import com.sage.business.code.contract.v2.spec.SpecApprovalDecisionRequest;
import com.sage.business.code.contract.v2.spec.SpecCreateRequest;
import com.sage.business.code.contract.v2.spec.SpecRevisionRequest;
import com.sage.business.code.contract.v2.mission.MissionApprovalDecisionRequest;
import com.sage.business.code.contract.v2.mission.MissionCommandRequest;
import com.sage.business.code.contract.v2.mission.MissionCreateRequest;
import com.sage.business.code.contract.v2.mission.MissionPlanRequest;
import com.sage.business.code.contract.v2.policy.EffectiveRiskPolicyResponse;
import com.sage.business.code.contract.v2.policy.RiskEvaluateRequest;
import com.sage.business.code.contract.v2.attention.AttentionItemResponse;
import com.sage.business.code.contract.v2.event.BusinessEventEnvelope;
import com.sage.business.code.contract.v2.event.EventCursorExpiredProblem;
import com.sage.business.code.contract.v2.event.EventQueryResponse;
import com.sage.business.code.infrastructure.event.EventJournal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * API v2 event plane (design §4.7/§4.8): durable SSE streams with
 * Last-Event-ID replay, bounded slow-client disconnects, comment heartbeats,
 * one-time short-lived stream tickets for browsers, the attention projection
 * and a JSON journal replay used by polling fallbacks.
 *
 * This is the legacy route group of the /api/v2 dispatcher (desktop design
 * §11.3); new endpoints live in their own {@link V2RouteGroup} classes.
 */
public final class V2ApiHandler implements HttpHandler, V2RouteGroup {
    private static final Logger LOG = LoggerFactory.getLogger(V2ApiHandler.class);
    static final long TICKET_TTL_MILLIS = 60_000L;
    static final long HEARTBEAT_IDLE_MILLIS = 15_000L;
    private static final int BACKLOG_PAGE = 500;
    private static final int MAX_SSE_FRAME_BYTES = 262_144;

    private final EventJournal journal;
    private final LiveEventHub hub;
    private final ObjectMapper mapper;
    private final String token;
    private final java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier;
    private final SessionService sessions;
    private final SpecService specs;
    private final RiskPolicyEngine riskPolicy;
    private final RiskDecisionAuditStore riskAudits;
    private final MissionService missions;
    private final MissionScheduler missionScheduler;
    private final LongRequestAdmission longRequests;
    private final Map<String, Long> tickets = new ConcurrentHashMap<String, Long>();
    /** Paginated history reads; wired by the composition root when the feature flag is on. */
    private volatile com.sage.business.code.application.session.SessionHistoryQueryService historyQueries;
    /** Session-scoped execution evidence reads (design 2026-09-26-evidence §10.2). */
    private volatile com.sage.business.code.application.session.SessionExecutionQueryService executionQueries;

    /** History pagination is capability-gated; the factory wires it only when enabled. */
    public void withHistoryQueries(
            com.sage.business.code.application.session.SessionHistoryQueryService value) {
        this.historyQueries = value;
    }

    /** Execution evidence reads share the session read port + workspace registry. */
    public void withExecutionQueries(
            com.sage.business.code.application.session.SessionExecutionQueryService value) {
        this.executionQueries = value;
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier) {
        this(journal, hub, mapper, token, attentionSupplier, null, null, null, null, null);
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier,
            SessionService sessions) {
        this(journal, hub, mapper, token, attentionSupplier, sessions, null, null, null, null);
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier,
            SessionService sessions, SpecService specs) {
        this(journal, hub, mapper, token, attentionSupplier, sessions, specs, null, null, null);
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier,
            SessionService sessions, SpecService specs, RiskPolicyEngine riskPolicy,
            RiskDecisionAuditStore riskAudits) {
        this(journal, hub, mapper, token, attentionSupplier, sessions, specs, riskPolicy, riskAudits, null);
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier,
            SessionService sessions, SpecService specs, RiskPolicyEngine riskPolicy,
            RiskDecisionAuditStore riskAudits, MissionService missions) {
        this(journal, hub, mapper, token, attentionSupplier, sessions, specs, riskPolicy,
                riskAudits, missions, null);
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier,
            SessionService sessions, SpecService specs, RiskPolicyEngine riskPolicy,
            RiskDecisionAuditStore riskAudits, MissionService missions,
            MissionScheduler missionScheduler) {
        this(journal, hub, mapper, token, attentionSupplier, sessions, specs, riskPolicy,
                riskAudits, missions, missionScheduler, new LongRequestAdmission());
    }

    public V2ApiHandler(EventJournal journal, LiveEventHub hub, ObjectMapper mapper,
            String token, java.util.function.Supplier<List<AttentionItemResponse>> attentionSupplier,
            SessionService sessions, SpecService specs, RiskPolicyEngine riskPolicy,
            RiskDecisionAuditStore riskAudits, MissionService missions,
            MissionScheduler missionScheduler, LongRequestAdmission longRequests) {
        if (journal == null || hub == null || mapper == null || token == null) {
            throw new IllegalArgumentException("journal, hub, mapper and token are required");
        }
        if (longRequests == null) throw new IllegalArgumentException("long request admission is required");
        this.journal = journal;
        this.hub = hub;
        this.mapper = mapper;
        this.token = token;
        this.attentionSupplier = attentionSupplier;
        this.sessions = sessions;
        this.specs = specs;
        this.riskPolicy = riskPolicy;
        this.riskAudits = riskAudits;
        this.missions = missions;
        this.missionScheduler = missionScheduler;
        this.longRequests = longRequests;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!handleGroup(exchange, exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod())) {
                throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 404,
                        "Endpoint not found");
            }
        } catch (com.sage.business.code.domain.BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(), failure.getMessage(), failure.getDetails());
        } finally {
            exchange.close();
        }
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        try {
            if ("/api/v2/stream-tickets".equals(path)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                requireBearer(exchange);
                sendJson(exchange, 201, Collections.singletonMap("ticket", issueTicket()));
                return true;
            }
            if ("/api/v2/attention".equals(path)) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                List<AttentionItemResponse> items = attentionSupplier == null
                        ? Collections.<AttentionItemResponse>emptyList()
                        : attentionSupplier.get();
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("items", items);
                sendJson(exchange, 200, response);
                return true;
            }
            if ("/api/v2/events/journal".equals(path)) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                long afterCursor = longParam(exchange, "afterCursor", 0L);
                int limit = (int) longParam(exchange, "limit", 200L);
                sendJson(exchange, 200, journal.readAfter(afterCursor, clamp(limit)));
                return true;
            }
            if ("/api/v2/policies/effective".equals(path)) {
                if (!"GET".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                if (riskPolicy == null) throw new com.sage.business.code.domain.BusinessException(
                        "FEATURE_DISABLED", 503, "Risk policy is not enabled on this service");
                sendJson(exchange, 200, riskPolicy.effective(queryParam(exchange, "sessionId")));
                return true;
            }
            if ("/api/v2/policies/evaluate".equals(path)) {
                if (!"POST".equals(method)) throw methodNotAllowed();
                requireAccess(exchange);
                if (riskPolicy == null) throw new com.sage.business.code.domain.BusinessException(
                        "FEATURE_DISABLED", 503, "Risk policy is not enabled on this service");
                RiskEvaluateRequest request = body(exchange, RiskEvaluateRequest.class);
                sendJson(exchange, 200, riskPolicy.evaluateAndAudit(request, riskAudits, null, null));
                return true;
            }
            if (path.equals("/api/v2/missions") || path.startsWith("/api/v2/missions/")) {
                if (handleMission(exchange, path, method)) return true;
            }
            if (path.equals("/api/v2/sessions") || path.startsWith("/api/v2/sessions/")) {
                if (handleSession(exchange, path, method)) return true;
            }
            if (path.startsWith("/api/v2/specs/") || path.matches("/api/v2/sessions/[^/]+/specs")) {
                if (handleSpec(exchange, path, method)) return true;
            }
            boolean sessionPath = path.matches("/api/v2/sessions/[^/]+/events");
            boolean allEvents = "/api/v2/events".equals(path);
            if ((sessionPath || allEvents) && "GET".equals(method)) {
                requireStreamAccess(exchange);
                String sessionId = sessionPath
                        ? path.substring("/api/v2/sessions/".length(),
                                path.length() - "/events".length())
                        : queryParam(exchange, "sessionId");
                String missionId = allEvents ? queryParam(exchange, "missionId") : null;
                stream(exchange, sessionId, missionId);
                return true;
            }
            return false;
        } catch (com.sage.business.code.domain.BusinessException failure) {
            sendProblem(exchange, failure.getCode(), failure.getHttpStatus(), failure.getMessage(), failure.getDetails());
            return true;
        }
    }

    private boolean handleMission(HttpExchange exchange, String path, String method)
            throws IOException {
        if (missions == null) throw new com.sage.business.code.domain.BusinessException(
                "FEATURE_DISABLED", 503, "Mission is not enabled on this service");
        String[] parts = path.split("/");
        if ("/api/v2/missions".equals(path)) {
            requireAccess(exchange);
            if ("GET".equals(method)) { sendJson(exchange, 200, missions.list(queryParam(exchange, "sessionId"))); return true; }
            if ("POST".equals(method)) { sendJson(exchange, 201, missions.create(queryParam(exchange, "sessionId"), body(exchange, MissionCreateRequest.class), mutationKey(exchange))); return true; }
            throw methodNotAllowed();
        }
        if (parts.length < 5 || !"missions".equals(parts[3])) return false;
        String missionId=parts[4]; safeEntityId(missionId,"mission"); requireAccess(exchange);
        if (parts.length==5 && "GET".equals(method)) { sendJson(exchange,200,missions.get(missionId)); return true; }
        if (parts.length==6 && "plan".equals(parts[5]) && "POST".equals(method)) { sendJson(exchange,200,missions.plan(missionId,body(exchange,MissionPlanRequest.class),mutationKey(exchange))); return true; }
        if (parts.length==8 && "approvals".equals(parts[5]) && "decisions".equals(parts[7]) && "POST".equals(method)) { safeEntityId(parts[6],"approval");MissionApprovalDecisionRequest r=body(exchange,MissionApprovalDecisionRequest.class);if(r.getApprovalId()==null)r.setApprovalId(parts[6]);sendJson(exchange,200,missions.approve(missionId,r,mutationKey(exchange)));return true; }
        if (parts.length==6 && "start".equals(parts[5]) && "POST".equals(method)) {sendJson(exchange,200,startMission(missionId,body(exchange,MissionCommandRequest.class),mutationKey(exchange)));return true;}
        if (parts.length==6 && "pause".equals(parts[5]) && "POST".equals(method)) {sendJson(exchange,200,missions.pause(missionId,body(exchange,MissionCommandRequest.class),mutationKey(exchange)));return true;}
        if (parts.length==6 && "resume".equals(parts[5]) && "POST".equals(method)) {sendJson(exchange,200,resumeMission(missionId,body(exchange,MissionCommandRequest.class),mutationKey(exchange)));return true;}
        if (parts.length==6 && "cancel".equals(parts[5]) && "POST".equals(method)) {sendJson(exchange,200,missions.cancel(missionId,body(exchange,MissionCommandRequest.class),mutationKey(exchange)));return true;}
        if (parts.length==6 && "replan".equals(parts[5]) && "POST".equals(method)) {sendJson(exchange,200,missions.replan(missionId,body(exchange,MissionPlanRequest.class),mutationKey(exchange)));return true;}
        if (parts.length==6 && "events".equals(parts[5]) && "GET".equals(method)) {requireStreamAccess(exchange);stream(exchange,null,missionId);return true;}
        if (parts.length==6 && "artifacts".equals(parts[5]) && "GET".equals(method)) {sendJson(exchange,200,missions.artifacts(missionId));return true;}
        return false;
    }

    private com.sage.business.code.contract.v2.mission.MissionResponse startMission(
            String missionId, MissionCommandRequest request, String key) {
        com.sage.business.code.contract.v2.mission.MissionResponse response =
                missions.start(missionId, request, key);
        if (missionScheduler != null) missionScheduler.schedule(missionId);
        return response;
    }

    private com.sage.business.code.contract.v2.mission.MissionResponse resumeMission(
            String missionId, MissionCommandRequest request, String key) {
        com.sage.business.code.contract.v2.mission.MissionResponse response =
                missions.resume(missionId, request, key);
        if (missionScheduler != null) missionScheduler.schedule(missionId);
        return response;
    }

    private boolean handleSpec(HttpExchange exchange, String path, String method)
            throws IOException {
        if (specs == null) throw new com.sage.business.code.domain.BusinessException(
                "FEATURE_DISABLED", 503, "Spec v2 is not enabled on this service");
        String[] parts = path.split("/");
        if (parts.length == 6 && "sessions".equals(parts[3]) && "specs".equals(parts[5])) {
            requireAccess(exchange);
            safeEntityId(parts[4], "session");
            if (!"POST".equals(method)) throw methodNotAllowed();
            sendJson(exchange, 201, specs.create(parts[4], body(exchange, SpecCreateRequest.class), mutationKey(exchange)));
            return true;
        }
        if (parts.length < 5 || !"specs".equals(parts[3])) return false;
        String specId = parts[4]; safeEntityId(specId, "spec"); requireAccess(exchange);
        if (parts.length == 5 && "GET".equals(method)) { sendJson(exchange, 200, specs.get(specId)); return true; }
        if (parts.length == 6 && "revisions".equals(parts[5]) && "POST".equals(method)) {
            sendJson(exchange, 200, specs.revise(specId, body(exchange, SpecRevisionRequest.class), mutationKey(exchange))); return true;
        }
        if (parts.length == 8 && "approvals".equals(parts[5]) && "decisions".equals(parts[7])
                && "POST".equals(method)) {
            safeEntityId(parts[6], "approval");
            SpecApprovalDecisionRequest request = body(exchange, SpecApprovalDecisionRequest.class);
            if (request.getApprovalId() == null) request.setApprovalId(parts[6]);
            sendJson(exchange, 200, specs.decide(specId, request, mutationKey(exchange))); return true;
        }
        if (parts.length == 6 && "convert-to-act".equals(parts[5]) && "POST".equals(method)) {
            sendJson(exchange, 201, specs.convertToAct(specId, mutationKey(exchange))); return true;
        }
        return false;
    }

    private boolean handleSession(HttpExchange exchange, String path, String method)
            throws IOException {
        if (sessions == null && !path.matches("/api/v2/sessions/[^/]+/events")) {
            throw new com.sage.business.code.domain.BusinessException("FEATURE_DISABLED", 503,
                    "Session v2 is not enabled on this service");
        }
        if (sessions == null) return false;
        String[] parts = path.split("/");
        if ("/api/v2/sessions".equals(path)) {
            requireAccess(exchange);
            if ("GET".equals(method)) { sendJson(exchange, 200, sessions.list()); return true; }
            if ("POST".equals(method)) {
                sendJson(exchange, 201, sessions.create(body(exchange, SessionCreateRequest.class),
                        mutationKey(exchange), sessionOrigin(exchange)));
                return true;
            }
            throw methodNotAllowed();
        }
        // 2026-09-05 design §5.1: the summaries collection must be matched
        // BEFORE the {sessionId} routes — "summaries" is not a session id.
        if (parts.length == 5 && "summaries".equals(parts[4])) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (historyQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "History pagination is not enabled on this service");
            sendJson(exchange, 200, historyQueries.summaries(
                    queryParam(exchange, "workspaceAlias"),
                    queryParam(exchange, "archived"),
                    queryParam(exchange, "q"),
                    intParam(exchange, "limit"),
                    queryParam(exchange, "cursor")));
            return true;
        }
        if (parts.length < 5 || !"api".equals(parts[1]) || !"v2".equals(parts[2])
                || !"sessions".equals(parts[3])) throw new com.sage.business.code.domain.BusinessException(
                        "INVALID_REQUEST", 404, "Endpoint not found");
        String sessionId = parts[4];
        safeEntityId(sessionId, "session");
        if (parts.length == 6 && "model-settings".equals(parts[5])) {
            requireAccess(exchange);
            if ("GET".equals(method)) {
                sendJson(exchange, 200, sessions.getModelSettings(sessionId));
                return true;
            }
            if ("PATCH".equals(method)) {
                SessionModelSettingsResponse response = sessions.patchModelSettings(sessionId,
                        body(exchange, SessionModelSettingsPatchRequest.class), mutationKey(exchange));
                int status = "PENDING".equals(response.getTransitionState()) ? 202 : 200;
                sendJson(exchange, status, response);
                return true;
            }
            throw methodNotAllowed();
        }
        if (parts.length == 5) {
            requireAccess(exchange);
            if ("GET".equals(method)) { sendJson(exchange, 200, sessions.get(sessionId)); return true; }
            if ("PATCH".equals(method)) {
                sendJson(exchange, 200, sessions.patch(sessionId,
                        body(exchange, SessionPatchRequest.class), mutationKey(exchange)));
                return true;
            }
            throw methodNotAllowed();
        }
        // 长输入设计 §6.3（WU-3）：附件内容读取深层路由——必须在
        // parts.length != 6 分支之前匹配并严格检查完整路径段
        // （/api/v2/sessions/{sid}/messages/{mid}/attachments/{aid}/content）。
        // split("/") 首段为空：["",api,v2,sessions,sid,messages,mid,attachments,aid,content] 共 10 段。
        if (parts.length == 10 && "messages".equals(parts[5])
                && "attachments".equals(parts[7]) && "content".equals(parts[9])
                && "GET".equals(method)) {
            requireAccess(exchange);
            safeEntityId(parts[6], "message");
            safeAttachmentId(parts[8]);
            String content = sessions.attachmentContent(sessionId, parts[6], parts[8]);
            byte[] body = content == null ? new byte[0]
                    : content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            // §6.3：Markdown 也不在下载页解释为 HTML；不缓存。
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"attachment.txt\"");
            exchange.sendResponseHeaders(200, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
            return true;
        }
        // 多模态设计 §7.8（WU-10）：图片 bytes 读取端点，紧邻既有 content
        // 分派新增（不接入 /api/v2/client-sessions）。
        // /api/v2/sessions/{sid}/messages/{mid}/attachments/{aid}/bytes
        // split("/") 首段为空：共 10 段，末段 "bytes"。
        if (parts.length == 10 && "messages".equals(parts[5])
                && "attachments".equals(parts[7]) && "bytes".equals(parts[9])
                && "GET".equals(method)) {
            requireAccess(exchange);
            safeEntityId(parts[6], "message");
            safeAttachmentId(parts[8]);
            // 归属校验在服务层先于 blob 读取；bytes 原样返回（≤4 MiB）。
            SessionService.AttachmentBytes bytes = sessions.attachmentBytes(
                    sessionId, parts[6], parts[8]);
            exchange.getResponseHeaders().set("Content-Type", bytes.getMediaType());
            exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.getData().length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes.getData());
            out.close();
            return true;
        }
        // /api/v2/sessions/{sid}/messages/{mid}/content — split("/") 首段为空:
        // ["",api,v2,sessions,sid,messages,mid,content] 共 8 段。
        if (parts.length == 8 && "messages".equals(parts[5]) && "content".equals(parts[7])
                && "GET".equals(method)) {
            // 2026-09-05 design §7.2: bounded UTF-8 segments of one message
            // body; cursors bind message id + content revision.
            requireAccess(exchange);
            safeEntityId(parts[6], "message");
            if (historyQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "History pagination is not enabled on this service");
            sendJson(exchange, 200, historyQueries.messageContent(sessionId, parts[6],
                    queryParam(exchange, "cursor"), intParam(exchange, "maxBytes")));
            return true;
        }
        // Design 2026-09-26-evidence §10.2: session-scoped execution
        // evidence reads. Deep routes must match before the
        // parts.length != 6 early-return below.
        // /sessions/{sid}/executions/{eid}/artifacts/{path...} — 9+ segments.
        if (parts.length >= 9 && "executions".equals(parts[5])
                && "artifacts".equals(parts[7]) && "GET".equals(method)) {
            requireAccess(exchange);
            if (executionQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "Execution evidence queries are not enabled on this service");
            safeEntityId(parts[6], "execution");
            StringBuilder artifactPath = new StringBuilder();
            for (int i = 8; i < parts.length; i++) {
                if (parts[i] == null || parts[i].isEmpty()
                        || parts[i].contains("..")) {
                    throw new com.sage.business.code.domain.BusinessException(
                            "INVALID_REQUEST", 400, "invalid artifact path");
                }
                if (artifactPath.length() > 0) artifactPath.append('/');
                artifactPath.append(parts[i]);
            }
            sendJson(exchange, 200, executionQueries.artifact(
                    sessionId, parts[6], artifactPath.toString(),
                    Long.valueOf(longParam(exchange, "offset", 0L)),
                    intParam(exchange, "maxBytes")));
            return true;
        }
        // /sessions/{sid}/executions/{eid} — 7 segments.
        if (parts.length == 7 && "executions".equals(parts[5])
                && "GET".equals(method)) {
            requireAccess(exchange);
            if (executionQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "Execution evidence queries are not enabled on this service");
            safeEntityId(parts[6], "execution");
            sendJson(exchange, 200, executionQueries.get(sessionId, parts[6]));
            return true;
        }
        if(parts.length==7&&"failed-turn-retries".equals(parts[5])&&"GET".equals(method)){
            requireAccess(exchange);safeEntityId(parts[6],"retry");sendJson(exchange,200,sessions.failedTurnRetry(sessionId,parts[6]));return true;
        }
        if (parts.length != 6) return false; // the event route below owns /events
        if ("executions".equals(parts[5]) && "GET".equals(method)) {
            requireAccess(exchange);
            if (executionQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "Execution evidence queries are not enabled on this service");
            sendJson(exchange, 200, executionQueries.list(sessionId,
                    queryParam(exchange, "runId"),
                    queryParam(exchange, "kind"),
                    queryParam(exchange, "toolId"),
                    intParam(exchange, "limit"),
                    queryParam(exchange, "cursor")));
            return true;
        }
        if ("messages".equals(parts[5]) && "GET".equals(method)) {
            // 2026-09-05 design §7.1: keyset message page (tail/before/after/around).
            requireAccess(exchange);
            if (historyQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "History pagination is not enabled on this service");
            sendJson(exchange, 200, historyQueries.messages(sessionId,
                    intParam(exchange, "limit"),
                    queryParam(exchange, "cursor"),
                    queryParam(exchange, "aroundMessageId")));
            return true;
        }
        if ("summary".equals(parts[5]) && "GET".equals(method)) {
            requireAccess(exchange);
            if (historyQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "History pagination is not enabled on this service");
            sendJson(exchange, 200, historyQueries.summary(sessionId,
                    "refresh".equalsIgnoreCase(queryParam(exchange, "probe"))));
            return true;
        }
        if ("metadata".equals(parts[5]) && "GET".equals(method)) {
            requireAccess(exchange);
            if (historyQueries == null) throw new com.sage.business.code.domain.BusinessException(
                    "FEATURE_DISABLED", 503, "History pagination is not enabled on this service");
            sendJson(exchange, 200, historyQueries.metadata(sessionId));
            return true;
        }
        if ("failed-turn-retries".equals(parts[5])) {
            requireAccess(exchange);
            if(parts.length==6&&"POST".equals(method)){
                sendJson(exchange,202,sessions.retryFailedTurn(sessionId,body(exchange,com.sage.business.code.contract.v2.session.SessionFailedTurnRetryRequest.class),mutationKey(exchange)));return true;
            }
            if(parts.length==7&&"GET".equals(method)){sendJson(exchange,200,sessions.failedTurnRetry(sessionId,parts[6]));return true;}
            throw methodNotAllowed();
        }
        if ("messages".equals(parts[5]) && "POST".equals(method)) {
            requireAccess(exchange);
            sendJson(exchange, 202, sessions.sendMessage(sessionId,
                    body(exchange, SessionMessageRequest.class), mutationKey(exchange)));
            return true;
        }
        if ("forks".equals(parts[5]) && "POST".equals(method)) {
            requireAccess(exchange);
            sendJson(exchange, 201, sessions.fork(sessionId,
                    body(exchange, SessionForkRequest.class), mutationKey(exchange)));
            return true;
        }
        if ("archive".equals(parts[5]) && "POST".equals(method)) {
            requireAccess(exchange);
            sendJson(exchange, 200, sessions.archive(sessionId,
                    body(exchange, SessionArchiveRequest.class), mutationKey(exchange)));
            return true;
        }
        return false;
    }

    /**
     * Server-owned provenance derived only from the authenticated client
     * session. A client-supplied request origin is never trusted; when no
     * registered client context exists the honest result is an absent origin.
     */
    private com.sage.business.code.contract.v2.session.SessionOrigin sessionOrigin(HttpExchange exchange) {
        String kind = (String) exchange.getAttribute(V2ApiDispatcher.CLIENT_KIND_ATTRIBUTE);
        String display = (String) exchange.getAttribute(V2ApiDispatcher.CLIENT_DISPLAY_ATTRIBUTE);
        if (kind == null && display == null) return null;
        com.sage.business.code.contract.v2.session.SessionOrigin origin =
                new com.sage.business.code.contract.v2.session.SessionOrigin();
        origin.setClientType(kind == null ? "LOCAL_API" : kind);
        // Structured initiator comes only from the registered client kind:
        // MOBILE/ELECTRON/WEB_TAB/SWING/CLI are human entry points; WORKER is
        // an execution agent that carries dispatched work, not an initiator,
        // so it classifies as SYSTEM rather than AGENT (an AGENT origin needs
        // a real agent-created thread contract, never a kind guess). A null
        // kind (LOCAL_API) proves nothing and stays null → UNKNOWN (R06).
        if(kind==null){
            origin.setActorKind(null);
        }else if("WORKER".equals(kind)){
            origin.setActorKind("SYSTEM");
        }else{
            origin.setActorKind("HUMAN");
        }
        origin.setAppDisplayName(display);
        origin.setDeviceDisplayName(display);
        origin.setRecordedAt(Long.valueOf(System.currentTimeMillis()));
        return origin;
    }

    // ---- SSE ----

    private void stream(HttpExchange exchange, String sessionId, String missionId)
            throws IOException {
        long afterCursor = requestedCursor(exchange);
        EventQueryResponse probe = journal.readAfter(Math.max(0L, afterCursor), 1);
        // A cursor below the retained floor may have missed deleted events.
        if (probe.getRetentionFloor() > 1L && afterCursor < probe.getRetentionFloor() - 1L) {
            EventCursorExpiredProblem problem = new EventCursorExpiredProblem();
            problem.setRequestedCursor(afterCursor);
            problem.setRetentionFloor(probe.getRetentionFloor());
            problem.setSnapshotCursor(probe.getHighWaterMark());
            byte[] body = mapper.writeValueAsBytes(problem);
            exchange.getResponseHeaders().set("Content-Type", "application/problem+json; charset=utf-8");
            exchange.sendResponseHeaders(409, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
            LOG.info("sse.cursor_expired event=sse.cursor_expired requested={} floor={}",
                    afterCursor, probe.getRetentionFloor());
            return;
        }
        LiveEventHub.Scope scope = sessionId != null ? LiveEventHub.Scope.ofSession(sessionId)
                : missionId != null ? new LiveEventHub.Scope(null, missionId)
                : LiveEventHub.Scope.all();
        LiveEventHub.Subscription subscription = null;
        LongRequestAdmission.Handle reservation = null;
        OutputStream out = null;
        long lastEmitted = afterCursor;
        try {
            reservation = longRequests.reserve(LongRequestAdmission.Kind.CONTROL_STREAM,
                    "local-owner", sessionId != null ? sessionId
                            : missionId != null ? missionId : "all");
            // Admission must happen before committing the HTTP response, so a
            // full service maps to a real 429 Problem instead of a misleading
            // 200 stream whose body contains an error.
            subscription = hub.subscribe(scope);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            // This route retains the legacy numeric afterCursor contract. New
            // clients must use /api/v2/event-snapshots for opaque scoped cursors.
            exchange.getResponseHeaders().set("X-CodeAgent-Events-Protocol", "legacy-events/1");
            exchange.sendResponseHeaders(200, 0);
            out = exchange.getResponseBody();

             // Register first so nothing between the backlog read and live tailing is
             // lost; buffered duplicates are removed by strict cursor monotonicity.
             // Phase 1: replay through a fixed barrier captured before registration.
             // Without this fence, a continuously publishing service can keep the
             // backlog loop chasing a moving high-water mark and delay live delivery.
             long backlogHighWaterMark = probe.getHighWaterMark();
             long pageFrom = afterCursor;
             while (pageFrom < backlogHighWaterMark) {
                 EventQueryResponse page = journal.readAfter(pageFrom, BACKLOG_PAGE);
                 if (page == null || page.getEvents() == null) {
                     throw new IOException("Event journal returned an invalid backlog page");
                 }
                 List<BusinessEventEnvelope> pageEvents = page.getEvents();
                 if (page.getHighWaterMark() < backlogHighWaterMark
                         || page.getHighWaterMark() < pageFrom
                         || page.getNextCursor() < pageFrom
                         || page.getNextCursor() > page.getHighWaterMark()
                         || (!pageEvents.isEmpty() && page.getNextCursor() <= pageFrom)) {
                     throw new IOException("Event journal returned a non-progressing backlog page");
                 }
                 long lastPageCursor = pageFrom;
                 boolean crossedBarrier = false;
                 for (BusinessEventEnvelope event : pageEvents) {
                     if (event == null || event.getCursor() <= lastPageCursor
                             || event.getCursor() > page.getHighWaterMark()) {
                         throw new IOException("Event journal returned invalid backlog cursors");
                     }
                     lastPageCursor = event.getCursor();
                     if (event.getCursor() > backlogHighWaterMark) {
                         crossedBarrier = true;
                         continue;
                     }
                     if (scope.matches(event) && event.getCursor() > lastEmitted) {
                         writeFrame(event, out);
                         lastEmitted = event.getCursor();
                     }
                 }
                 if (!pageEvents.isEmpty() && page.getNextCursor() != lastPageCursor) {
                     throw new IOException("Event journal returned an inconsistent backlog cursor");
                 }
                 out.flush();
                 if (pageEvents.isEmpty()) {
                     if (page.getNextCursor() <= pageFrom) {
                         throw new IOException("Event journal returned an empty backlog page without progress");
                     }
                     pageFrom = page.getNextCursor();
                     continue;
                 }
                 if (crossedBarrier) break;
                 pageFrom = page.getNextCursor();
             }
            // Phase 2: emit what buffered while the backlog was being read.
            for (BusinessEventEnvelope buffered : subscription.drain()) {
                if (buffered.getCursor() > lastEmitted) {
                    writeFrame(buffered, out);
                    lastEmitted = buffered.getCursor();
                }
            }
            out.flush();
            while (true) {
                if (subscription.isOverflowed()) {
                    // Slow client: stop buffering, tell the client to resume.
                    out.write(": overflow; reconnect with Last-Event-ID\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    break;
                }
                BusinessEventEnvelope event = subscription.poll(HEARTBEAT_IDLE_MILLIS);
                if (event == null) {
                    out.write(String.format(": heartbeat %d%n", System.currentTimeMillis())
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    continue;
                }
                if (event.getCursor() <= lastEmitted || !scope.matches(event)) continue;
                writeFrame(event, out);
                out.flush();
                lastEmitted = event.getCursor();
            }
        } catch (IOException clientGone) {
            LOG.info("sse.client_disconnected event=sse.client_disconnected cursor={}", lastEmitted);
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

    private static List<BusinessEventEnvelope> filter(List<BusinessEventEnvelope> events,
            LiveEventHub.Scope scope) {
        if (events == null || events.isEmpty()) return Collections.emptyList();
        List<BusinessEventEnvelope> matched = new ArrayList<BusinessEventEnvelope>(events.size());
        for (BusinessEventEnvelope event : events) {
            if (scope.matches(event)) matched.add(event);
        }
        return matched;
    }

    private void writeFrame(BusinessEventEnvelope event, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("id: ").append(event.getCursor()).append('\n');
        sb.append("event: ").append(event.getType()).append('\n');
        sb.append("data: ").append(mapper.writeValueAsString(event)).append("\n\n");
        byte[] frame = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (frame.length > MAX_SSE_FRAME_BYTES) {
            out.write(": frame-limit-exceeded; reconnect with Last-Event-ID\n\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            throw new IOException("SSE frame exceeds configured byte limit");
        }
        out.write(frame);
    }

    // ---- access ----

    /** Bearer token or a single-use stream ticket; never a long-lived URL token. */
    private void requireStreamAccess(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (("Bearer " + token).equals(authorization)) return;
        String ticket = queryParam(exchange, "ticket");
        if (ticket == null || !consumeTicket(ticket)) {
            throw new com.sage.business.code.domain.BusinessException("UNAUTHORIZED", 401,
                    "Bearer token or a valid one-time stream ticket is required");
        }
    }

    private void requireAccess(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + token).equals(authorization)) {
            throw new com.sage.business.code.domain.BusinessException("UNAUTHORIZED", 401,
                    "Bearer token is required");
        }
    }

    private void requireBearer(HttpExchange exchange) { requireAccess(exchange); }

    String issueTicket() {
        String ticket = java.util.UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, System.currentTimeMillis() + TICKET_TTL_MILLIS);
        sweepTickets();
        return ticket;
    }

    boolean consumeTicket(String ticket) {
        sweepTickets();
        Long expiry = tickets.remove(ticket);
        return expiry != null && expiry >= System.currentTimeMillis();
    }

    private void sweepTickets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> entries = tickets.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue() < now) entries.remove();
        }
    }

    // ---- plumbing ----

    private static long requestedCursor(HttpExchange exchange) {
        String queryCursor = queryParam(exchange, "afterCursor");
        String headerCursor = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (queryCursor != null && !queryCursor.trim().isEmpty()
                && headerCursor != null && !headerCursor.trim().isEmpty()) {
            try {
                long queryValue = parseLegacyCursor(queryCursor);
                long headerValue = parseLegacyCursor(headerCursor);
                if (queryValue != headerValue) {
                    throw new com.sage.business.code.domain.BusinessException(
                            "CURSOR_ARGUMENT_CONFLICT", 400,
                            "afterCursor and Last-Event-ID must identify the same cursor");
                }
            } catch (NumberFormatException invalid) {
                throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                        "afterCursor must be an integer");
            }
        }
        String raw = queryCursor;
        if (raw == null || raw.trim().isEmpty()) raw = headerCursor;
        if (raw == null || raw.trim().isEmpty()) return 0L;
        return parseLegacyCursor(raw);
    }

    private static long parseLegacyCursor(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0L) {
                throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                        "afterCursor must be a non-negative integer");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "afterCursor must be an integer");
        }
    }

    private static Integer intParam(HttpExchange exchange, String name) {
        String value = queryParam(exchange, name);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Integer.valueOf(Integer.parseInt(value.trim()));
        } catch (NumberFormatException invalid) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    name + " must be an integer");
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            if (name.equals(pair.substring(0, equals))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                } catch (java.io.UnsupportedEncodingException impossible) {
                    throw new IllegalStateException("UTF-8 is unavailable", impossible);
                }
            }
        }
        return null;
    }

    private static long longParam(HttpExchange exchange, String name, long fallback) {
        String raw = queryParam(exchange, name);
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try { return Long.parseLong(raw.trim()); }
        catch (NumberFormatException invalid) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    name + " must be an integer");
        }
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(1000, limit));
    }

    private static String mutationKey(HttpExchange exchange) {
        String value = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        if (value == null || value.trim().isEmpty() || value.length() > 128) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Idempotency-Key is required and limited to 128 characters");
        }
        return value;
    }

    private <T> T body(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] raw = readLimited(exchange.getRequestBody());
        if (raw.length == 0) throw new com.sage.business.code.domain.BusinessException(
                "INVALID_REQUEST", 400, "Request body is required");
        try { return mapper.readValue(raw, type); }
        catch (Exception failure) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "Request body cannot be parsed");
        }
    }

    private static byte[] readLimited(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read; long total = 0L;
        try {
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                // 多模态设计 §7.4（WU-10）：HTTP body 读取与服务层附件校验
                // 引用同一 InputLimits 上限（含 Base64 膨胀 + 正文 + 64 KiB
                // envelope 预留）；超额在完整解码前停止。
                if (total > com.sage.business.code.contract.input.InputLimits.CURRENT
                        .maxRequestBodyBytes()) {
                    throw new com.sage.business.code.domain.BusinessException(
                            "INVALID_REQUEST", 413, "Request body exceeds "
                                    + com.sage.business.code.contract.input.InputLimits.CURRENT
                                            .maxRequestBodyBytes() + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally { input.close(); }
    }

    private static void safeEntityId(String value, String kind) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    kind + " id is invalid");
        }
    }

    /** 附件内部 id 允许 clientAttachmentId 的 ":" 形式（MessageInputCodec 规范）。 */
    private static void safeAttachmentId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 400,
                    "attachment id is invalid");
        }
    }

    private static com.sage.business.code.domain.BusinessException methodNotAllowed() {
        return new com.sage.business.code.domain.BusinessException("INVALID_REQUEST", 405,
                "Method not allowed");
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    private void sendProblem(HttpExchange exchange, String code, int status, String message,
            Map<String, Object> details)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<String, Object>();
        problem.put("code", code);
        problem.put("status", status);
        problem.put("message", message);
        if (details != null) problem.putAll(details);
        if (!problem.containsKey("failure")) {
            problem.put("failure", FailureDescriptorAdapter.from(null, mapper, code, message));
        }
        byte[] body = mapper.writeValueAsBytes(problem);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json; charset=utf-8");
        if (status == 429) {
            exchange.getResponseHeaders().set("Retry-After", "1");
        }
        try {
            exchange.sendResponseHeaders(status, body.length);
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.close();
        } catch (IOException ignored) {
            // The stream may already be committed; nothing else can be sent.
        }
    }
}
