package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agent.product.code.CommandApprovalRequest;
import com.sage.business.code.application.BusinessCommandApprovalCoordinator;
import com.sage.business.code.application.WriteScopeApprovalCoordinator;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionCoordinator;
import com.sage.business.code.application.executionpolicy.AutomationControlService;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionPolicy;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionPolicyScope;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionPolicyService;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionPolicyUpdate;
import com.sage.business.code.application.executionpolicy.PermissionApprovalRecord;
import com.sage.business.code.application.executionpolicy.PermissionApprovalStatus;
import com.sage.business.code.application.executionpolicy.PermissionDecisionIntent;
import com.sage.business.code.application.executionpolicy.PermissionMode;
import com.sage.business.code.contract.v2.approval.ApprovalAdvanceRequest;
import com.sage.business.code.contract.v2.approval.ApprovalDecisionRequest;
import com.sage.business.code.contract.v2.approval.ApprovalListResponse;
import com.sage.business.code.contract.v2.approval.ApprovalRequestV2;
import com.sage.business.code.contract.v2.executionpolicy.ExecutionPolicyResponse;
import com.sage.business.code.contract.v2.executionpolicy.ExecutionPolicyUpdateRequest;
import com.sage.business.code.contract.v2.automation.AutomationControlRequest;
import com.sage.business.code.contract.v2.automation.AutomationControlResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * V2 execution-policy and unified-approval projection routes.  The loopback
 * composition authenticates the configured bearer token at the HTTP boundary
 * and maps it to one deterministic service principal.  The principal is never
 * accepted from request JSON; non-loopback compositions can keep the explicit
 * fail-closed constructor overload until they provide an equivalent trusted
 * identity provider.
 */
public final class ExecutionPermissionApiHandler extends V2HttpSupport
        implements V2RouteGroup {
    /** Stable local identity; it is not accepted from request JSON. */
    public static final String LOCAL_SERVICE_PRINCIPAL = "local-service-principal";

    private final ExecutionPermissionPolicyService policies;
    private final ExecutionPermissionCoordinator approvals;
    private final boolean trustedPrincipal;
    private final String principalId;
    private final SessionProjectResolver sessionProjectResolver;
    private final AutomationControlService automationControls;
    /** WU-11: optional unified BUDGET projection; null keeps legacy behavior. */
    private final com.sage.business.code.application.executionpolicy.BudgetApprovalBridge budgetBridge;
    /** Bridges a unified v2 decision to a Product call waiting on the legacy latch. */
    private final BusinessCommandApprovalCoordinator commandApprovals;
    /** Bridges unified WRITE decisions to the current Product write waiter. */
    private final WriteScopeApprovalCoordinator writeApprovals;
    /** Authoritative effective-policy resolver (FULL_ACCESS semantics 2). */
    private final com.sage.business.code.application.executionpolicy
            .EffectiveExecutionPolicyResolver policyResolver;
    /** Pending-approval reconciler wired for grant-scoped continuation. */
    private final com.sage.business.code.application.executionpolicy
            .FullAccessPendingReconciler reconciler;

    /** Resolves the server-owned project scope for a session policy request. */
    public interface SessionProjectResolver {
        String projectIdFor(String sessionId);
    }

    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals) {
        this(mapper, token, policies, approvals, LOCAL_SERVICE_PRINCIPAL, true, null, null);
    }

    /**
     * Composition-root overload for an authenticated principal provider.  A
     * caller that cannot prove the principal came from its trusted transport
     * must pass {@code false}; policy grant mutations then remain unavailable.
     */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal) {
        this(mapper, token, policies, approvals, principalId, trustedPrincipal, null, null);
    }

    /** Composition-root overload that binds session policy scope to a session. */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal, SessionProjectResolver sessionProjectResolver) {
        this(mapper, token, policies, approvals, principalId, trustedPrincipal,
                sessionProjectResolver, null);
    }

    /** Full composition constructor with durable automation queue controls. */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal, SessionProjectResolver sessionProjectResolver,
            AutomationControlService automationControls) {
        this(mapper, token, policies, approvals, principalId, trustedPrincipal,
                sessionProjectResolver, automationControls, null);
    }

    /** WU-11 composition constructor with the unified BUDGET approval bridge. */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal, SessionProjectResolver sessionProjectResolver,
            AutomationControlService automationControls,
            com.sage.business.code.application.executionpolicy.BudgetApprovalBridge budgetBridge) {
        this(mapper, token, policies, approvals, principalId, trustedPrincipal,
                sessionProjectResolver, automationControls, budgetBridge, null);
    }

    /** Full composition constructor with the Product command approval bridge. */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal, SessionProjectResolver sessionProjectResolver,
            AutomationControlService automationControls,
            com.sage.business.code.application.executionpolicy.BudgetApprovalBridge budgetBridge,
            BusinessCommandApprovalCoordinator commandApprovals) {
        this(mapper, token, policies, approvals, principalId, trustedPrincipal,
                sessionProjectResolver, automationControls, budgetBridge,
                commandApprovals, null, null, null);
    }

    /** FULL_ACCESS semantics 2 composition: resolver + pending reconciler. */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal, SessionProjectResolver sessionProjectResolver,
            AutomationControlService automationControls,
            com.sage.business.code.application.executionpolicy.BudgetApprovalBridge budgetBridge,
            BusinessCommandApprovalCoordinator commandApprovals,
            com.sage.business.code.application.executionpolicy
                    .EffectiveExecutionPolicyResolver policyResolver,
            com.sage.business.code.application.executionpolicy
                    .FullAccessPendingReconciler reconciler) {
        this(mapper, token, policies, approvals, principalId, trustedPrincipal,
                sessionProjectResolver, automationControls, budgetBridge,
                commandApprovals, policyResolver, reconciler, null);
    }

    /** Full composition constructor with both Product approval waiters. */
    public ExecutionPermissionApiHandler(ObjectMapper mapper, String token,
            ExecutionPermissionPolicyService policies,
            ExecutionPermissionCoordinator approvals, String principalId,
            boolean trustedPrincipal, SessionProjectResolver sessionProjectResolver,
            AutomationControlService automationControls,
            com.sage.business.code.application.executionpolicy.BudgetApprovalBridge budgetBridge,
            BusinessCommandApprovalCoordinator commandApprovals,
            com.sage.business.code.application.executionpolicy
                    .EffectiveExecutionPolicyResolver policyResolver,
            com.sage.business.code.application.executionpolicy
                    .FullAccessPendingReconciler reconciler,
            WriteScopeApprovalCoordinator writeApprovals) {
        super(mapper, token);
        if (policies == null || approvals == null || principalId == null
                || !principalId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("permission services and principal are required");
        }
        this.policies = policies;
        this.approvals = approvals;
        this.principalId = principalId;
        this.trustedPrincipal = trustedPrincipal;
        this.sessionProjectResolver = sessionProjectResolver;
        this.automationControls = automationControls;
        this.budgetBridge = budgetBridge;
        this.commandApprovals = commandApprovals;
        this.writeApprovals = writeApprovals;
        this.policyResolver = policyResolver;
        this.reconciler = reconciler;
    }

    @Override
    public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (path.matches("/api/v2/sessions/[^/]+/automation-controls")) {
            String sessionId = path.substring("/api/v2/sessions/".length(),
                    path.length() - "/automation-controls".length());
            safeEntityId(sessionId, "session");
            requireAccess(exchange);
            if (automationControls == null || !trustedPrincipal) {
                throw new BusinessException("AUTOMATION_CONTROL_UNAVAILABLE", 503,
                        "automation queue control is not wired for this service");
            }
            if ("GET".equals(method)) {
                sendJson(exchange, 200, automation(automationControls.get(principalId, sessionId)));
                return true;
            }
            if (!"POST".equals(method)) throw methodNotAllowed();
            String idempotencyKey = mutationKey(exchange);
            AutomationControlRequest request = body(exchange, AutomationControlRequest.class);
            if (request == null || request.getExpectedVersion() == null) {
                throw new BusinessException("INVALID_REQUEST", 400,
                        "expectedVersion is required");
            }
            sendJson(exchange, 200, automation(automationControls.apply(principalId, sessionId,
                    request.getControl(), request.getExpectedVersion().longValue(), idempotencyKey)));
            return true;
        }
        if (path.matches("/api/v2/sessions/[^/]+/execution-policy")) {
            String sessionId = path.substring("/api/v2/sessions/".length(),
                    path.length() - "/execution-policy".length());
            safeEntityId(sessionId, "session");
            requireAccess(exchange);
            if ("GET".equals(method)) {
                // A Session policy is scoped by the concrete session id. A
                // project query parameter is accepted only as a legacy hint;
                // it is never used as the policy storage key. The effective
                // view always comes from the authoritative resolver so a
                // session without its own row reports the resolved source.
                validateSessionProjectHint(exchange, sessionId);
                ExecutionPermissionPolicy effective = policyResolver == null
                        ? policies.getSession(principalId, sessionId)
                        : policyResolver.resolve(principalId, sessionId,
                                resolveProjectHint(sessionId));
                sendJson(exchange, 200, policy(effective));
                return true;
            }
            if ("PUT".equals(method)) {
                validateSessionProjectHint(exchange, sessionId);
                sendJson(exchange, 200, updatePolicyForSession(exchange, sessionId));
                return true;
            }
            throw methodNotAllowed();
        }
        if ("/api/v2/me/execution-policy-defaults".equals(path)) {
            requireAccess(exchange);
            if ("GET".equals(method)) {
                sendJson(exchange, 200, policy(policies.getUserDefault(principalId)));
                return true;
            }
            if ("PUT".equals(method)) {
                if (!trustedPrincipal) {
                    throw new BusinessException("EXECUTION_POLICY_UNAVAILABLE", 503,
                            "A trusted authenticated principal is required to change execution policy");
                }
                ExecutionPolicyUpdateRequest request = body(exchange,
                        ExecutionPolicyUpdateRequest.class);
                ExecutionPermissionPolicyUpdate update = parsePolicyUpdate(exchange, request);
                sendJson(exchange, 200,
                        policy(policies.updateUserDefault(principalId, update,
                                mutationKey(exchange))));
                return true;
            }
            throw methodNotAllowed();
        }
        if (path.matches("/api/v2/projects/[^/]+/execution-policy-defaults")) {
            String projectId = path.substring("/api/v2/projects/".length(),
                    path.length() - "/execution-policy-defaults".length());
            safeEntityId(projectId, "project");
            requireAccess(exchange);
            if ("GET".equals(method)) {
                sendJson(exchange, 200, policy(policies.get(principalId, projectId)));
                return true;
            }
            if ("PUT".equals(method)) {
                sendJson(exchange, 200, updatePolicy(exchange, projectId));
                return true;
            }
            throw methodNotAllowed();
        }
        if (path.matches("/api/v2/sessions/[^/]+/approvals")) {
            String sessionId = path.substring("/api/v2/sessions/".length(),
                    path.length() - "/approvals".length());
            safeEntityId(sessionId, "session");
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            int limit = (int) longParam(exchange, "limit", 50L);
            if (limit < 1 || limit > 100) {
                throw new BusinessException("INVALID_REQUEST", 400,
                        "limit must be between 1 and 100");
            }
            int offset = parseCursor(queryParam(exchange, "cursor"));
            List<PermissionApprovalRecord> records = approvals.list(
                    principalId, sessionId, offset, limit + 1);
            boolean hasNext = records.size() > limit;
            if (hasNext) records = new ArrayList<PermissionApprovalRecord>(records.subList(0, limit));
            ApprovalListResponse response = new ApprovalListResponse();
            List<ApprovalRequestV2> items = new ArrayList<ApprovalRequestV2>();
            long projectionVersion = 0L;
            for (PermissionApprovalRecord record : records) {
                items.add(projection(record));
                projectionVersion = Math.max(projectionVersion, record.version);
            }
            // WU-11: budget attention rides the same unified list.  Budget
            // approvals are rare and short-lived; they are appended after the
            // cursor page without disturbing its ordering guarantees.
            if (budgetBridge != null) {
                for (Map<String, Object> budget : budgetBridge.listForSession(sessionId)) {
                    items.add(budgetProjection(budget));
                }
            }
            response.setItems(items);
            response.setProjectionVersion(projectionVersion);
            response.setServerTime(Instant.now().toString());
            response.setNextCursor(hasNext ? String.valueOf(offset + limit) : null);
            sendJson(exchange, 200, response);
            return true;
        }
        if (path.matches("/api/v2/approvals/[^/]+")) {
            String approvalId = path.substring("/api/v2/approvals/".length());
            safeEntityId(approvalId, "approval");
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            if (budgetBridge != null) {
                Map<String, Object> budget = budgetBridge.get(approvalId);
                if (budget != null) {
                    sendJson(exchange, 200, budgetProjection(budget));
                    return true;
                }
            }
            sendJson(exchange, 200, projection(approvals.get(approvalId, principalId)));
            return true;
        }
        if (path.matches("/api/v2/approvals/[^/]+/decisions")) {
            String approvalId = path.substring("/api/v2/approvals/".length(),
                    path.length() - "/decisions".length());
            safeEntityId(approvalId, "approval");
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            String idempotencyKey = mutationKey(exchange);
            ApprovalDecisionRequest request = body(exchange, ApprovalDecisionRequest.class);
            String fingerprint = request == null ? null : firstFingerprint(
                    request.getFingerprint(), request.getRequestFingerprint());
            validateGuard(request == null ? null : request.getExpectedVersion(), fingerprint);
            PermissionDecisionIntent intent = decision(request.getDecision());
            if (budgetBridge != null && budgetBridge.get(approvalId) != null) {
                // WU-11: budget decisions are guarded by fingerprint and the
                // projected version; the approved vector is always copied from
                // the durable request, never from the decision body.
                Map<String, Object> decided = budgetBridge.decide(approvalId,
                        request.getExpectedVersion().longValue(), fingerprint,
                        intent == PermissionDecisionIntent.APPROVE);
                sendJson(exchange, 200, budgetProjection(decided));
                return true;
            }
            PermissionApprovalRecord result = decideAndWakeCommand(approvalId,
                    request.getExpectedVersion().longValue(), fingerprint, intent,
                    idempotencyKey);
            sendJson(exchange, 200, projection(result));
            return true;
        }
        if (path.matches("/api/v2/approvals/[^/]+/(advance|controls)")) {
            boolean controlRoute = path.endsWith("/controls");
            String approvalId = path.substring("/api/v2/approvals/".length(),
                    path.length() - (controlRoute ? "/controls".length() : "/advance".length()));
            safeEntityId(approvalId, "approval");
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            // The key is required even though the state transition is guarded
            // by version; this keeps all v2 mutations retry-auditable.
            String idempotencyKey = mutationKey(exchange);
            ApprovalAdvanceRequest request = body(exchange, ApprovalAdvanceRequest.class);
            String fingerprint = request == null ? null : firstFingerprint(
                    request.getFingerprint(), request.getRequestFingerprint());
            if (request == null || request.getExpectedVersion() == null
                    || request.getExpectedVersion().longValue() < 1L) {
                throw new BusinessException("INVALID_REQUEST", 400,
                        "expectedVersion is required and must be positive");
            }
            if (request.getControl() != null
                    && !request.getControl().trim().isEmpty()) {
                String control = request.getControl().trim().toUpperCase(java.util.Locale.ROOT);
                if ("CANCEL".equals(control)) {
                    if (fingerprint == null || fingerprint.trim().isEmpty()) {
                        // The compatibility control body historically did not
                        // carry a fingerprint. Bind it to the authoritative
                        // prepared record rather than trusting the client.
                        fingerprint = approvals.get(approvalId, principalId).effectFingerprint;
                    }
                    validateGuard(request.getExpectedVersion(), fingerprint);
                    PermissionApprovalRecord result = decideAndWakeCommand(approvalId,
                            request.getExpectedVersion().longValue(), fingerprint,
                            PermissionDecisionIntent.CANCEL, idempotencyKey);
                    sendJson(exchange, 200, projection(result));
                    return true;
                }
                validateGuard(request.getExpectedVersion(), fingerprint);
                PermissionApprovalRecord result = approvals.controlForPrincipal(approvalId,
                        principalId, request.getExpectedVersion().longValue(), fingerprint,
                        control, idempotencyKey);
                sendJson(exchange, 200, projection(result));
                return true;
            }
            validateGuard(request.getExpectedVersion(), fingerprint);
            PermissionApprovalRecord result = approvals.advanceDueForPrincipal(approvalId,
                    principalId, request.getExpectedVersion().longValue(), fingerprint);
            sendJson(exchange, 200, projection(result));
            return true;
        }
        return false;
    }

    /**
     * A session id is not a project id.  Production composition supplies the
     * resolver from the session service; the query parameter remains a narrow
     * compatibility seam for callers that explicitly provide the server
     * context.  When neither is available, fail closed rather than silently
     * creating a policy under the wrong scope.
     */
    private String sessionProject(HttpExchange exchange, String sessionId) {
        String requested = queryParam(exchange, "projectId");
        String resolved = sessionProjectResolver == null ? null
                : sessionProjectResolver.projectIdFor(sessionId);
        if (resolved != null && !resolved.trim().isEmpty()) {
            resolved = resolved.trim();
            safeEntityId(resolved, "project");
            if (requested != null && !requested.trim().isEmpty()
                    && !resolved.equals(requested.trim())) {
                throw new BusinessException("PROJECT_SCOPE_MISMATCH", 409,
                        "projectId does not match the session project");
            }
            return resolved;
        }
        if (requested == null || requested.trim().isEmpty()) {
            throw new BusinessException("EXECUTION_POLICY_UNAVAILABLE", 503,
                    "The session project scope is unavailable");
        }
        requested = requested.trim();
        safeEntityId(requested, "project");
        return requested;
    }

    /** Server-owned project association for the session, when wired. */
    private String resolveProjectHint(String sessionId) {
        if (sessionProjectResolver == null) return null;
        String resolved = sessionProjectResolver.projectIdFor(sessionId);
        return resolved == null || resolved.trim().isEmpty() ? null : resolved.trim();
    }

    /** Optional compatibility project hint; SESSION policy identity is the session id. */
    private void validateSessionProjectHint(HttpExchange exchange, String sessionId) {
        String requested = queryParam(exchange, "projectId");
        String resolved = sessionProjectResolver == null ? null
                : sessionProjectResolver.projectIdFor(sessionId);
        if (resolved != null && !resolved.trim().isEmpty()
                && requested != null && !requested.trim().isEmpty()
                && !resolved.trim().equals(requested.trim())) {
            throw new BusinessException("PROJECT_SCOPE_MISMATCH", 409,
                    "projectId does not match the session project");
        }
    }

    private Object updatePolicy(HttpExchange exchange, String projectId)
            throws IOException {
        String key = mutationKey(exchange);
        if (!trustedPrincipal) {
            throw new BusinessException("EXECUTION_POLICY_UNAVAILABLE", 503,
                    "A trusted authenticated principal is required to change execution policy");
        }
        ExecutionPolicyUpdateRequest request = body(exchange,
                ExecutionPolicyUpdateRequest.class);
        if (request == null || request.getExpectedRevision() == null
                || request.getExpectedRevision().longValue() < 1L) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "expectedRevision is required and must be positive");
        }
        ExecutionPermissionPolicyUpdate update = mapPolicyUpdate(request);
        return policy(policies.update(ExecutionPermissionPolicyScope.project(
                principalId, projectId), update, key));
    }

    private Object updatePolicyForSession(HttpExchange exchange, String sessionId)
            throws IOException {
        ExecutionPolicyUpdateRequest request = body(exchange,
                ExecutionPolicyUpdateRequest.class);
        ExecutionPermissionPolicyUpdate update = parsePolicyUpdate(exchange, request);
        ExecutionPermissionPolicy updated =
                policies.updateSession(principalId, sessionId, update, mutationKey(exchange));
        ExecutionPolicyResponse response = policy(updated);
        // FULL_ACCESS semantics 2: the grant is durable the moment updateSession
        // commits, so pending work is continued server-side — never gated on a
        // live frontend. An active v2 grant always continues still-valid
        // pending approvals (design A12); an explicit RETAIN is the only opt
        // out. RECONCILE_SESSION (or an explicit approval subset) additionally
        // re-evaluates this scope's waiting approvals for any other mode.
        String action = update.getPendingApprovalAction() == null
                ? "" : update.getPendingApprovalAction().trim().toUpperCase(java.util.Locale.ROOT);
        java.util.List<String> explicitIds = update.getApplyToPendingApprovalIds();
        boolean grantContinuesPending = updated.isFullAccessGrantActive()
                && updated.getPermissionSemanticsVersion()
                        >= ExecutionPermissionPolicy.SEMANTICS_FULL_ACCESS_V2
                && !"RETAIN".equals(action);
        if (reconciler != null
                && (grantContinuesPending
                    || "RECONCILE_SESSION".equals(action)
                    || explicitIds != null && !explicitIds.isEmpty())) {
            com.sage.business.code.application.executionpolicy
                    .FullAccessPendingReconciler.Result result = explicitIds != null
                            && !explicitIds.isEmpty()
                    ? reconciler.reconcileApprovals(sessionId, explicitIds, updated)
                    : reconciler.reconcileSession(sessionId, updated);
            response.setReconciledPendingApprovals(Integer.valueOf(result.continued));
        }
        return response;
    }

    private ExecutionPermissionPolicyUpdate parsePolicyUpdate(HttpExchange exchange,
            ExecutionPolicyUpdateRequest request) throws IOException {
        if (!trustedPrincipal) {
            throw new BusinessException("EXECUTION_POLICY_UNAVAILABLE", 503,
                    "A trusted authenticated principal is required to change execution policy");
        }
        if (request == null || request.getExpectedRevision() == null
                || request.getExpectedRevision().longValue() < 1L) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "expectedRevision is required and must be positive");
        }
        return mapPolicyUpdate(request);
    }

    private ExecutionPermissionPolicyUpdate mapPolicyUpdate(
            ExecutionPolicyUpdateRequest request) {
        // FULL_ACCESS semantics 2 (design 2026-09-30): selecting the mode is
        // the authorization. fullAccessConfirmation/acknowledgementVersion
        // remain tolerated for legacy v1 clients but are not required for a
        // semantics-2 update; the service still enforces the legacy contract
        // when semantics < 2.
        ExecutionPermissionPolicyUpdate update = new ExecutionPermissionPolicyUpdate();
        update.setExpectedRevision(request.getExpectedRevision());
        if (request.getRequestedMode() != null) {
            try { update.setMode(PermissionMode.parse(request.getRequestedMode())); }
            catch (IllegalArgumentException invalid) {
                throw new BusinessException("INVALID_REQUEST", 400, "requestedMode is invalid");
            }
        }
        update.setRoutineDelayMillis(request.getRoutineDelayMillis());
        update.setPlanDelayMillis(request.getPlanDelayMillis());
        update.setBudgetDelayMillis(request.getBudgetDelayMillis());
        update.setMaxAutomaticBudgetApprovals(request.getMaxAutomaticBudgetApprovals());
        update.setApprovalTtlMillis(request.getApprovalTtlMillis());
        update.setFullAccessConfirmation(request.isFullAccessConfirmation());
        update.setPermissionSemanticsVersion(request.getPermissionSemanticsVersion());
        String action = request.getPendingApprovalAction();
        if (action != null && !action.trim().isEmpty()) {
            String normalized = action.trim().toUpperCase(java.util.Locale.ROOT);
            if (!"RETAIN".equals(normalized) && !"RECONCILE_SESSION".equals(normalized)) {
                throw new BusinessException("INVALID_REQUEST", 400,
                        "pendingApprovalAction must be RETAIN or RECONCILE_SESSION");
            }
            update.setPendingApprovalAction(normalized);
        }
        if (request.getApplyToPendingApprovalIds() != null) {
            update.setApplyToPendingApprovalIds(request.getApplyToPendingApprovalIds());
        }
        return update;
    }

    private ExecutionPolicyResponse policy(ExecutionPermissionPolicy value) {
        ExecutionPolicyResponse response = new ExecutionPolicyResponse();
        response.setSchemaVersion(value.getSchemaVersion());
        response.setPrincipalId(value.getPrincipalId());
        response.setProjectId(value.getProjectId());
        response.setRevision(value.getRevision());
        response.setMode(value.getMode().name());
        response.setManagedModeCeiling(policies.managedModeCeiling().name());
        List<String> supported = new ArrayList<String>();
        for (PermissionMode mode : PermissionMode.values()) {
            if (mode.ordinal() <= policies.managedModeCeiling().ordinal()) {
                supported.add(mode.name());
            }
        }
        response.setSupportedModes(supported);
        response.setRoutineDelayMillis(value.getRoutineDelayMillis());
        response.setPlanDelayMillis(value.getPlanDelayMillis());
        response.setBudgetDelayMillis(value.getBudgetDelayMillis());
        response.setMaxAutomaticBudgetApprovals(value.getMaxAutomaticBudgetApprovals());
        response.setApprovalTtlMillis(value.getApprovalTtlMillis());
        response.setFullAccessConfirmed(value.isFullAccessConfirmed());
        response.setPolicyFingerprint(value.fingerprint());
        response.setServerTime(Instant.now().toString());
        // FULL_ACCESS semantics 2 projection: scope identity, grant metadata
        // and the resolution provenance the UI needs to render "effective"
        // truth rather than a guessed local mode.
        response.setScopeType(value.getScopeType());
        response.setScopeId(value.getScopeId());
        response.setRequestedMode(value.effectiveRequestedMode() == null
                ? null : value.effectiveRequestedMode().name());
        response.setEffectiveMode(value.getMode() == null ? null : value.getMode().name());
        response.setPermissionSemanticsVersion(Integer.valueOf(
                value.getPermissionSemanticsVersion() <= 0
                        ? 1 : value.getPermissionSemanticsVersion()));
        response.setApprovalBehavior(value.isFullAccessGrantActive()
                ? "ALLOW_ALL"
                : com.sage.business.code.application.executionpolicy.PermissionMode.AUTO_APPROVE
                        .equals(value.getMode()) ? "DELAYED" : "MANUAL");
        response.setPolicySource(value.getPolicySource());
        response.setGrantRevision(Long.valueOf(value.getGrantRevision()));
        response.setGrantSource(value.getGrantSource());
        response.setGrantedAt(value.getGrantedAtMillis() > 0L
                ? Instant.ofEpochMilli(value.getGrantedAtMillis()).toString() : null);
        response.setGrantActor(value.getGrantActor());
        response.setActivationStatus(value.getActivationStatus());
        response.setBlockingReasons(value.getBlockingReasons());
        return response;
    }

    private AutomationControlResponse automation(AutomationControlService.State state) {
        AutomationControlResponse response = new AutomationControlResponse();
        response.setSchemaVersion(state.getSchemaVersion());
        response.setPrincipalId(state.getPrincipalId());
        response.setSessionId(state.getSessionId());
        response.setPaused(state.isPaused());
        response.setVersion(state.getVersion());
        response.setUpdatedAtMillis(state.getUpdatedAtMillis());
        response.setServerTime(Instant.now().toString());
        return response;
    }

    /**
     * WU-11: unified projection of a durable budget approval.  The public
     * shape keeps the same guards as the other kinds (fingerprint, version,
     * expiry) and exposes only the server-owned budget vectors.
     */
    private ApprovalRequestV2 budgetProjection(Map<String, Object> budget) {
        ApprovalRequestV2 response = new ApprovalRequestV2();
        String status = text(budget.get("status"));
        response.setApprovalId(text(budget.get("approvalId")));
        response.setRunId(text(budget.get("runId")));
        response.setKind("BUDGET");
        response.setDomainRef("budget");
        response.setDomainVersion(1L);
        response.setRequestVersion(longValue(budget.get("version")));
        response.setDecisionStatus(status);
        response.setExecutionStatus(status);
        response.setRequestFingerprint(text(budget.get("requestFingerprint")));
        response.setRiskCategory("BUDGET");
        response.setRiskEvidence(Collections.<String>emptyList());
        String reasonCode = text(budget.get("reasonCode"));
        response.setPublicSummary("budget top-up requested"
                + (reasonCode == null || reasonCode.trim().isEmpty()
                        ? "" : " (" + reasonCode + ")"));
        Object expiresAt = budget.get("expiresAt");
        response.setExpiresAt(expiresAt == null ? null
                : Instant.ofEpochMilli(Long.parseLong(String.valueOf(expiresAt))).toString());
        response.setServerTime(Instant.now().toString());
        response.setAllowedActions("PENDING".equals(status)
                ? Arrays.asList("APPROVE", "DENY") : Collections.<String>emptyList());
        response.setVersion(longValue(budget.get("version")));
        if ("APPROVED".equals(status)) {
            response.setDecision("APPROVE");
        } else if ("DENIED".equals(status)) {
            response.setDecision("DENY");
        }
        response.setDecisionSource("APPROVED".equals(status) || "DENIED".equals(status)
                ? "UNIFIED_APPROVALS" : null);
        Object requested = budget.get("requestedTopUp");
        if (requested instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> requestedVector = (Map<String, Object>) requested;
            response.setRequestedTopUp(requestedVector);
        }
        Object ceiling = budget.get("maximumAllowedTopUp");
        if (ceiling instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ceilingVector = (Map<String, Object>) ceiling;
            response.setMaximumAllowedTopUp(ceilingVector);
        }
        return response;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException ignored) { return 0L; }
    }

    private ApprovalRequestV2 projection(PermissionApprovalRecord record) {
        ApprovalRequestV2 response = new ApprovalRequestV2();
        response.setApprovalId(record.approvalId);
        response.setSessionId(record.sessionId);
        response.setRunId(record.runId);
        response.setAgentId(record.capabilityId);
        response.setKind(kind(record.capabilityId));
        response.setDomainRef(record.capabilityId);
        response.setDomainVersion(record.version);
        response.setRequestVersion(record.version);
        response.setDecisionStatus(record.status);
        response.setExecutionStatus(record.status);
        response.setRequestFingerprint(record.effectFingerprint);
        response.setPolicyFingerprint(record.policyFingerprint);
        response.setGrantRevision(Long.valueOf(record.grantRevision));
        response.setRequiresUserAction(Boolean.valueOf(record.requiresUserAction));
        response.setDispatchRoute(record.dispatchRoute);
        response.setOperationId(record.operationId);
        response.setReplacesApprovalId(record.replacesApprovalId);
        response.setRiskCategory(record.dangerousCategory);
        response.setRiskEvidence(Collections.<String>emptyList());
        response.setPublicSummary(record.summary);
        response.setAutoApproveAt(record.autoApproveAtMillis > 0L
                ? Instant.ofEpochMilli(record.autoApproveAtMillis).toString() : null);
        response.setExpiresAt(Instant.ofEpochMilli(record.expiresAtMillis).toString());
        response.setServerTime(Instant.now().toString());
        response.setAllowedActions(actions(record.status));
        response.setVersion(record.version);
        response.setDecision(record.decision);
        response.setDecisionSource(record.decisionSource);
        return response;
    }

    private static List<String> actions(String status) {
        if (PermissionApprovalStatus.WAITING_DELAY.name().equals(status)) {
            return Arrays.asList("ADVANCE", "DENY", "CANCEL", "PAUSE_DELAY");
        }
        if (PermissionApprovalStatus.PAUSED_DELAY.name().equals(status)) {
            return Arrays.asList("APPROVE", "DENY", "CANCEL", "RESUME_DELAY");
        }
        if (PermissionApprovalStatus.PENDING.name().equals(status)) {
            // EXECUTE is an explicit user action that skips any remaining
            // delay for this one prepared request; the server still applies
            // the same fingerprint/version/policy guards as APPROVE.
            return Arrays.asList("APPROVE", "EXECUTE", "DENY", "CANCEL");
        }
        return Collections.emptyList();
    }

    private static String kind(String capability) {
        if (capability == null) return "UNKNOWN";
        String value = capability.toLowerCase(java.util.Locale.ROOT);
        // Extension-plane approvals carry capability ids like
        // mcp.tool.invoke / skill.script.run — label before the generic
        // substring checks so they never degrade to UNKNOWN (WU08).
        if (value.startsWith("mcp.") || value.startsWith("skill.")) {
            return "EXTENSION";
        }
        if (value.contains("budget")) return "BUDGET";
        if (value.contains("sandbox")) return "SANDBOX";
        // Command approvals created by UnifiedExecutionPermissionAdapter use
        // the durable domain id process.<executable>.  Do not leave these as
        // UNKNOWN merely because the Product capability is named
        // com.agent.process.exec rather than containing "command".
        if (value.startsWith("process.")) return "COMMAND";
        if (value.contains("write")) return "WRITE";
        if (value.contains("command")) return "COMMAND";
        if (value.contains("spec")) return "SPEC";
        if (value.contains("replan")) return "REPLAN";
        if (value.contains("plan")) return "PLAN";
        return "UNKNOWN";
    }

    private static PermissionDecisionIntent decision(String value) {
        if (value == null) throw new BusinessException("INVALID_REQUEST", 400,
                "decision is required");
        try {
            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
            if ("EXECUTE".equals(normalized)) normalized = "APPROVE";
            if ("REJECT".equals(normalized)) normalized = "DENY";
            return PermissionDecisionIntent.valueOf(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "decision must be APPROVE, DENY or CANCEL");
        }
    }

    /**
     * Apply a v2 decision and wake the Product command waiter that created the
     * unified record. The two records have different ids: the unified record
     * is the service-owned approval id, while the Product waiter owns the
     * command invocation id. The fingerprint and run id bind the bridge.
     */
    private PermissionApprovalRecord decideAndWakeCommand(String approvalId,
            long expectedVersion, String fingerprint, PermissionDecisionIntent intent,
            String idempotencyKey) {
        PermissionApprovalRecord owner = approvals.get(approvalId, principalId);
        // Validate the client guard before waking the legacy Product latch.
        // Otherwise a stale/wrong fingerprint could release a real process
        // request even though the common approval mutation is rejected.
        if (owner.effectFingerprint == null
                || fingerprint == null
                || !owner.effectFingerprint.equals(fingerprint)) {
            throw new BusinessException("APPROVAL_FINGERPRINT_MISMATCH", 409,
                    "approval fingerprint does not match");
        }
        PermissionApprovalRecord result;
        try {
            result = approvals.decideForPrincipal(approvalId, principalId, expectedVersion,
                    fingerprint, intent, idempotencyKey);
        } catch (BusinessException race) {
            // A durable dispatcher or another owner may already have recorded
            // the matching common decision before this HTTP request gets the
            // coordinator lock. Treat the already-applied identical decision
            // as the successful response for this idempotent user action.
            if (!isDecisionRace(race.getCode())) throw race;
            PermissionApprovalRecord current = approvals.get(approvalId, principalId);
            if (!decisionMatches(current, intent)) throw race;
            result = current;
        }
        // Persist all guards and the decision before releasing a live Product
        // latch. A rejected version must never start a process or a write.
        wakeCommandWaiter(result, intent);
        return result;
    }

    private static boolean isDecisionRace(String code) {
        return "APPROVAL_NOT_PENDING".equals(code)
                || "STATE_VERSION_CONFLICT".equals(code)
                || "IDEMPOTENCY_CONFLICT".equals(code);
    }

    private void wakeCommandWaiter(PermissionApprovalRecord owner,
            PermissionDecisionIntent intent) {
        if (owner == null || owner.runId == null || owner.runId.trim().isEmpty()) return;
        String approvalKind = kind(owner.capabilityId);
        if ("WRITE".equals(approvalKind)) {
            wakeWriteWaiter(owner, intent);
            return;
        }
        if (commandApprovals == null || !"COMMAND".equals(approvalKind)) return;
        // A run may have an older timed-out Product request in the same
        // process while the unified record belongs to the newer request.
        // Match the bridge by its immutable effect fingerprint, never by an
        // arbitrary first entry for the run.
        CommandApprovalRequest pending = commandApprovals.pending(
                owner.runId, owner.effectFingerprint);
        if (pending == null) return;
        if (owner.effectFingerprint == null
                || !owner.effectFingerprint.equals(pending.getFingerprint())) {
            throw new BusinessException("APPROVAL_FINGERPRINT_MISMATCH", 409,
                    "unified approval does not match the pending process request");
        }
        String legacyDecision = intent == PermissionDecisionIntent.APPROVE
                ? "APPROVE_ONCE" : "DENY";
        commandApprovals.decide(owner.runId, pending.getApprovalId(),
                pending.getFingerprint(), legacyDecision);
    }

    private void wakeWriteWaiter(PermissionApprovalRecord owner,
            PermissionDecisionIntent intent) {
        if (writeApprovals == null || owner.effectFingerprint == null) return;
        String legacyDecision = intent == PermissionDecisionIntent.APPROVE
                ? "APPROVE_ONCE" : "DENY";
        com.agent.product.code.writeapproval.WriteScopeApprovalRequest pending =
                writeApprovals.pending(owner.runId, owner.effectFingerprint);
        if (pending == null) return;
        writeApprovals.decide(owner.runId, pending.getApprovalId(),
                pending.getFingerprint(), legacyDecision);
    }

    private static boolean decisionMatches(PermissionApprovalRecord record,
            PermissionDecisionIntent intent) {
        if (record == null || intent == null) return false;
        if (intent == PermissionDecisionIntent.APPROVE) {
            return PermissionApprovalStatus.APPROVED.name().equals(record.status)
                    && "ALLOW".equals(record.decision);
        }
        if (intent == PermissionDecisionIntent.CANCEL) {
            return PermissionApprovalStatus.CANCELLED.name().equals(record.status);
        }
        return PermissionApprovalStatus.DENIED.name().equals(record.status)
                || PermissionApprovalStatus.EXPIRED.name().equals(record.status)
                || PermissionApprovalStatus.STALE.name().equals(record.status);
    }

    private static void validateGuard(Long version, String fingerprint) {
        if (version == null || version.longValue() < 1L
                || fingerprint == null || fingerprint.trim().isEmpty()
                || fingerprint.length() > 256) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "expectedVersion and fingerprint are required");
        }
    }

    private static String firstFingerprint(String first, String second) {
        return first == null || first.trim().isEmpty() ? second : first;
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) return 0;
        try {
            long value = Long.parseLong(cursor.trim());
            if (value < 0L || value > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) value;
        } catch (NumberFormatException invalid) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "cursor must be a non-negative integer");
        }
    }
}
