package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.agentcode.runtime.spi.jdbc.JdbcTransactionContext;
import com.sage.agentcode.runtime.spi.model.ExtensionBindingRecord;
import com.sage.agentcode.runtime.spi.model.ExtensionInvocationRecord;
import com.sage.business.code.application.extension.ExtensionPlane;
import com.sage.business.code.contract.v2.extension.ExecutionExtensionsResponse;
import com.sage.business.code.contract.v2.extension.ExtensionBindingView;
import com.sage.business.code.contract.v2.extension.ExtensionInvocationActionRequest;
import com.sage.business.code.contract.v2.extension.ExtensionInvocationListResponse;
import com.sage.business.code.contract.v2.extension.ExtensionInvocationView;
import com.sage.business.code.contract.v2.extension.ExtensionTestExecutionRequest;
import com.sage.business.code.contract.v2.extension.ExtensionTestExecutionResponse;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Execution-scoped extension evidence routes (design 2026-09-29 section 10.1):
 * POST /api/v2/extension-test-executions (governed debug execution),
 * GET /api/v2/executions/{id}/extensions,
 * GET /api/v2/executions/{id}/extension-invocations (cursor paged).
 * The pre-contract private paths /api/v2/extensions/test and
 * /api/v2/executions/{id}/extensions/invocations stay one release cycle as
 * deprecated aliases delegating to the same handlers, auth and idempotency
 * implementation. Read-only views come straight from the durable stores —
 * never fabricated.
 */
public final class ExtensionApiHandler extends V2HttpSupport implements V2RouteGroup {

    private static final String PRINCIPAL = "local-owner";
    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 200;
    private final ExtensionPlane extension;

    public ExtensionApiHandler(ObjectMapper mapper, String token, ExtensionPlane extension) {
        super(mapper, token);
        this.extension = extension;
    }

    @Override public boolean handleGroup(HttpExchange exchange, String path, String method)
            throws IOException {
        if (path.matches("/api/v2/executions/[^/]+/extensions")) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            String executionId = path.split("/")[4];
            sendJson(exchange, 200, extensionsView(executionId));
            return true;
        }
        // Public contract path (source OpenAPI) and the deprecated private
        // alias delegate to the identical view and authorization.
        if (path.matches("/api/v2/executions/[^/]+/extension-invocations")
                || path.matches("/api/v2/executions/[^/]+/extensions/invocations")) {
            requireAccess(exchange);
            if (!"GET".equals(method)) throw methodNotAllowed();
            String executionId = path.split("/")[4];
            sendJson(exchange, 200, invocationsView(executionId,
                    queryParam(exchange, "cursor"), limitParam(exchange)));
            return true;
        }
        // WU06 (design §10.1): execution-level recovery actions — CANCEL,
        // RESUME_INPUT (MRTR) and the read-only RECONCILE evidence view.
        if (path.matches("/api/v2/executions/[^/]+/extension-invocations/[^/]+/actions")) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            String[] parts = path.split("/");
            String executionId = parts[4];
            String invocationId = parts[6];
            ExtensionInvocationActionRequest request =
                    body(exchange, ExtensionInvocationActionRequest.class);
            sendJson(exchange, 200, invocationAction(executionId, invocationId, request));
            return true;
        }
        if ("/api/v2/extension-test-executions".equals(path)
                || "/api/v2/extensions/test".equals(path)) {
            requireAccess(exchange);
            if (!"POST".equals(method)) throw methodNotAllowed();
            mutationKey(exchange);
            ExtensionTestExecutionRequest request =
                    body(exchange, ExtensionTestExecutionRequest.class);
            sendJson(exchange, 201, testExecution(request));
            return true;
        }
        return false;
    }

    private int limitParam(HttpExchange exchange) {
        long value = longParam(exchange, "limit", DEFAULT_PAGE);
        if (value < 1 || value > MAX_PAGE) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "limit must be between 1 and " + MAX_PAGE);
        }
        return (int) value;
    }

    /** Offset cursor: opaque on the wire, monotonic within one listing. */
    private static int cursorOffset(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) return 0;
        try {
            int value = Integer.parseInt(cursor.trim());
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "cursor is not a valid page cursor");
        }
    }

    private void requirePlane() {
        if (extension == null) {
            throw new BusinessException("EXT_HOST_NOT_WIRED", 503,
                    "the extension host is not assembled on this service");
        }
    }

    private ExecutionExtensionsResponse extensionsView(final String executionId) {
        requirePlane();
        final ExecutionExtensionsResponse out = new ExecutionExtensionsResponse();
        out.setExecutionId(executionId);
        extension.stores.invocations(); // touch for null-safety of assembly
        final ExtensionBindingRecord[] latest = new ExtensionBindingRecord[1];
        // Reads run inside a control-plane transaction for consistency.
        com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase db =
                database();
        db.inTransaction(connection -> {
            JdbcTransactionContext c = new JdbcTransactionContext("control-plane", connection);
            latest[0] = extension.stores.bindings().findLatest(c, executionId).orElse(null);
            return null;
        });
        if (latest[0] == null) {
            out.setBinding(null);
            return out;
        }
        ExtensionBindingView view = new ExtensionBindingView();
        view.setBindingId(latest[0].bindingId);
        view.setExecutionId(latest[0].executionId);
        view.setRevision(latest[0].revision);
        view.setSnapshotDigest(latest[0].snapshotDigest);
        view.setCreatedAt(String.valueOf(latest[0].createdAt));
        out.setBinding(view);
        return out;
    }

    private ExtensionInvocationListResponse invocationsView(final String executionId,
            String cursor, int limit) {
        requirePlane();
        final ExtensionInvocationListResponse out = new ExtensionInvocationListResponse();
        final List<ExtensionInvocationView> views = new ArrayList<ExtensionInvocationView>();
        com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase db =
                database();
        final List<ExtensionInvocationRecord> records = new ArrayList<ExtensionInvocationRecord>();
        db.inTransaction(connection -> {
            JdbcTransactionContext c = new JdbcTransactionContext("control-plane", connection);
            records.addAll(extension.stores.invocations().listByExecution(c, executionId));
            return null;
        });
        int from = cursorOffset(cursor);
        for (int i = from; i < records.size() && views.size() < limit; i++) {
            views.add(view(records.get(i)));
        }
        if (from + views.size() < records.size()) {
            out.setNextCursor(String.valueOf(from + views.size()));
        }
        out.setItems(views);
        return out;
    }

    /**
     * Execution-scoped invocation actions (design §10.1, WU06). CANCEL goes
     * through the governed cancel path (ownership, honest effect outcome,
     * exact wire cancellation); RESUME_INPUT resumes a WAITING_INPUT call
     * on the same logical invocation; RECONCILE is a read-only re-read of
     * the durable row — it never re-sends the wire request.
     */
    private ExtensionInvocationView invocationAction(final String executionId,
            final String invocationId, ExtensionInvocationActionRequest request) {
        requirePlane();
        if (request == null || request.getAction() == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "action is required (CANCEL / RESUME_INPUT / RECONCILE)");
        }
        String action = request.getAction().trim()
                .toUpperCase(java.util.Locale.ROOT);
        if ("CANCEL".equals(action)) {
            extension.invocations.cancel(PRINCIPAL, executionId, invocationId);
        } else if ("RESUME_INPUT".equals(action)) {
            JsonNode input = request.getInput() == null
                    ? null : mapper.valueToTree(request.getInput());
            extension.invocations.resume(PRINCIPAL, executionId, invocationId, input);
        } else if ("RECONCILE".equals(action)) {
            // Read-only below — a reconcile never re-sends the request.
        } else {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "unknown invocation action " + request.getAction());
        }
        final ExtensionInvocationRecord[] row = new ExtensionInvocationRecord[1];
        database().inTransaction(connection -> {
            JdbcTransactionContext c = new JdbcTransactionContext("control-plane", connection);
            row[0] = extension.stores.invocations().find(c, invocationId).orElse(null);
            return null;
        });
        if (row[0] == null || !row[0].executionId.equals(executionId)) {
            throw new BusinessException("EXT_INVOCATION_UNKNOWN", 404,
                    "unknown invocation " + invocationId);
        }
        return view(row[0]);
    }

    private static ExtensionInvocationView view(ExtensionInvocationRecord record) {
        ExtensionInvocationView view = new ExtensionInvocationView();
        view.setInvocationId(record.invocationId);
        view.setExecutionId(record.executionId);
        view.setBindingId(record.bindingId);
        view.setKind(record.kind);
        ExtensionInvocationView.Target target = new ExtensionInvocationView.Target();
        if ("MCP_TOOL".equals(record.kind)) {
            target.setToolId(record.targetId);
        } else {
            target.setSkillId(record.targetId);
        }
        view.setTarget(target);
        view.setState(record.state);
        view.setEffectOutcome(record.effectOutcome);
        view.setApprovalId(record.approvalId);
        if (record.resultRef != null) {
            ExtensionInvocationView.Result result =
                    new ExtensionInvocationView.Result();
            result.setContentRef(record.resultRef);
            view.setResult(result);
        }
        view.setErrorCode(record.errorCode);
        view.setAttempts(record.attempts);
        view.setCreatedAt(String.valueOf(record.createdAt));
        view.setUpdatedAt(String.valueOf(record.updatedAt));
        return view;
    }

    /**
     * Debug execution (design section 10.1): a governed, execution-scoped
     * tool call with identity, approval and evidence — never an anonymous
     * proxy. The request must name exactly one target operation; taking the
     * first of several was a pre-contract behavior and is now rejected.
     * WU05 (G10): the call runs through the standard invoke pipeline behind
     * a REAL binding/2 generated from the request selection — the same
     * ownership, pin and approval discipline as model-driven calls, never a
     * null binding.
     */
    private ExtensionTestExecutionResponse testExecution(ExtensionTestExecutionRequest request) {
        requirePlane();
        if (request == null || request.getExtensions() == null
                || request.getExtensions().getMcpServers() == null) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "extensions.mcpServers must name exactly one tool");
        }
        if (request.getExtensions().getMcpServers().size() != 1
                || request.getExtensions().getMcpServers().get(0).getToolIds() == null
                || request.getExtensions().getMcpServers().get(0).getToolIds().size() != 1) {
            throw new BusinessException("INVALID_REQUEST", 400,
                    "extensions.mcpServers must name exactly one server entry "
                            + "with exactly one toolId");
        }
        String serverId = request.getExtensions().getMcpServers().get(0).getServerId();
        String toolId = request.getExtensions().getMcpServers().get(0).getToolIds().get(0);
        String sessionId = request.getSessionId() == null ? "debug" : request.getSessionId();
        String executionId = "exec-test-" + java.util.UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12);
        String bindingId = extension.bindings.bind(executionId, sessionId, PRINCIPAL,
                null, request.getExtensions()).getBindingId();
        JsonNode args = request.getArguments() == null
                ? null : mapper.valueToTree(request.getArguments());
        com.sage.business.code.application.mcp.McpToolInvokeService.Outcome outcome =
                extension.invocations.invoke(PRINCIPAL, null, executionId, sessionId,
                        bindingId, serverId, toolId, null, args);
        ExtensionTestExecutionResponse response = new ExtensionTestExecutionResponse();
        response.setExecutionId(executionId);
        response.setStatus(outcome.state);
        return response;
    }

    /** The control-plane database backing the stores (factory-injected). */
    private com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase database() {
        com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase db =
                this.database;
        if (db == null) {
            throw new BusinessException("EXT_HOST_NOT_WIRED", 503,
                    "control-plane database is unavailable");
        }
        return db;
    }

    private com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase database;

    /** Wiring setter — the factory supplies the control-plane DB. */
    public void setDatabase(
            com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase value) {
        this.database = value;
    }
}
