package com.sage.business.code.adapter.http;

import com.agent.product.code.CommandApprovalDecision;
import com.agent.product.code.CommandApprovalRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.BusinessCommandApprovalCoordinator;
import com.sage.business.code.application.WriteScopeApprovalCoordinator;
import com.sage.business.code.application.executionpolicy.ExecutionOperationFacts;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionCoordinator;
import com.sage.business.code.application.executionpolicy.AutomationControlService;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionPolicyService;
import com.sage.business.code.application.executionpolicy.ExecutionPermissionRequest;
import com.sage.business.code.application.executionpolicy.PermissionApprovalRecord;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import com.agent.product.code.writeapproval.WriteScopeApprovalDecision;
import com.agent.product.code.writeapproval.WriteScopeApprovalRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Focused wire tests for the new v2 policy and approval routes. */
public class ExecutionPermissionApiHandlerTest {
    private static final String TOKEN = "permission-api-test-token";
    private ObjectMapper mapper;
    private Path state;
    private HttpServer server;
    private int port;
    private PermissionApprovalRecord approval;
    private ExecutionPermissionCoordinator coordinator;
    private BusinessCommandApprovalCoordinator commandApprovals;
    private WriteScopeApprovalCoordinator writeApprovals;

    @Before
    public void setUp() throws Exception {
        mapper = new ObjectMapper();
        state = Files.createTempDirectory("permission-api-");
        AtomicJsonStore store = new AtomicJsonStore(state, mapper);
        ExecutionPermissionPolicyService policies =
                new ExecutionPermissionPolicyService(store, mapper);
        coordinator =
                new ExecutionPermissionCoordinator(store, mapper, policies);
        commandApprovals = new BusinessCommandApprovalCoordinator(store, mapper);
        writeApprovals = new WriteScopeApprovalCoordinator(store, mapper);
        AutomationControlService automationControls =
                new AutomationControlService(store, mapper);
        ExecutionOperationFacts facts = new ExecutionOperationFacts();
        facts.setRequiresApproval(true);
        approval = coordinator.prepare(ExecutionPermissionRequest.builder()
                .principalId(ExecutionPermissionApiHandler.LOCAL_SERVICE_PRINCIPAL)
                .projectId("project-1").sessionId("session-1").runId("run-1")
                .workflowId("workflow-1").capabilityId("process.notepad")
                .summary("notepad").effectFingerprint("effect-1")
                .facts(facts).build());
        ExecutionPermissionApiHandler handler = new ExecutionPermissionApiHandler(
                mapper, TOKEN, policies, coordinator,
                ExecutionPermissionApiHandler.LOCAL_SERVICE_PRINCIPAL, true, null,
                automationControls, null, commandApprovals, null, null, writeApprovals);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper,
                Arrays.<V2RouteGroup>asList(handler)));
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void policyReadAndApprovalProjectionAreTyped() throws Exception {
        Response policy = request("GET", "/api/v2/sessions/session-1/execution-policy?projectId=project-1",
                null, null);
        assertEquals(200, policy.status);
        assertEquals("STANDARD", policy.json.path("mode").asText());
        assertEquals("local-service-principal", policy.json.path("principalId").asText());

        Response list = request("GET", "/api/v2/sessions/session-1/approvals", null, null);
        assertEquals(200, list.status);
        assertEquals(1, list.json.path("items").size());
        assertEquals(approval.getApprovalId(), list.json.path("items").get(0).path("approvalId").asText());
        assertEquals("COMMAND", list.json.path("items").get(0).path("kind").asText());

        Response read = request("GET", "/api/v2/approvals/" + approval.getApprovalId(), null, null);
        assertEquals(200, read.status);
        assertTrue(read.json.path("allowedActions").toString().contains("APPROVE"));
    }

    @Test
    public void grantUpdateAndDecisionRequireTheirGuards() throws Exception {
        String body = "{\"expectedVersion\":" + approval.getVersion()
                 + ",\"fingerprint\":\"effect-1\",\"decision\":\"APPROVE\"}";
        Response decision = request("POST", "/api/v2/approvals/" + approval.getApprovalId()
                 + "/decisions", body, "decision-1");
        assertEquals(200, decision.status);
        assertEquals("APPROVED", decision.json.path("decisionStatus").asText());

        Response update = request("PUT", "/api/v2/sessions/session-1/execution-policy?projectId=project-1",
                 "{\"expectedRevision\":1,\"requestedMode\":\"AUTO_APPROVE\"}", "policy-1");
        assertEquals(200, update.status);
        assertEquals("AUTO_APPROVE", update.json.path("mode").asText());
    }

    @Test
    public void v2CommandDecisionWakesProductApprovalWaiter() throws Exception {
        long now = System.currentTimeMillis();
        CommandApprovalRequest staleRequest = new CommandApprovalRequest(
                "product-stale-approval", "run-bridge", "call-stale", "where",
                Collections.singletonList("notepad"), ".", 30000L,
                Collections.<String>emptyList(), "EMPTY", 0, "", now, now + 30000L);
        FutureTask<CommandApprovalDecision> staleWaiting = new FutureTask<CommandApprovalDecision>(
                () -> commandApprovals.handlerFor("run-bridge")
                        .awaitDecision(staleRequest, staleRequest.getExpiresAtMillis()));
        new Thread(staleWaiting, "v2-command-approval-stale-test").start();
        CommandApprovalRequest productRequest = new CommandApprovalRequest(
                "product-approval-1", "run-bridge", "call-bridge", "notepad",
                Collections.<String>emptyList(), ".", 30000L,
                Collections.<String>emptyList(), "EMPTY", 0, "", now, now + 30000L);
        FutureTask<CommandApprovalDecision> waiting = new FutureTask<CommandApprovalDecision>(
                () -> commandApprovals.handlerFor("run-bridge")
                        .awaitDecision(productRequest, productRequest.getExpiresAtMillis()));
        Thread thread = new Thread(waiting, "v2-command-approval-bridge-test");
        thread.start();
        long deadline = System.currentTimeMillis() + 2000L;
        while (commandApprovals.pending("run-bridge", productRequest.getFingerprint()) == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(commandApprovals.pending("run-bridge", productRequest.getFingerprint()) != null);

        ExecutionOperationFacts facts = new ExecutionOperationFacts();
        facts.setRequiresApproval(true);
        PermissionApprovalRecord unified = coordinator.prepare(
                ExecutionPermissionRequest.builder()
                        .principalId(ExecutionPermissionApiHandler.LOCAL_SERVICE_PRINCIPAL)
                        .projectId("project-1").sessionId("session-1").runId("run-bridge")
                        .workflowId("workflow-bridge").capabilityId("process.notepad")
                        .summary("notepad").effectFingerprint(productRequest.getFingerprint())
                        .facts(facts).build());
        try {
            Response wrongVersion = request("POST", "/api/v2/approvals/" + unified.getApprovalId()
                    + "/decisions", "{\"expectedVersion\":" + (unified.getVersion() + 1)
                    + ",\"fingerprint\":\"" + productRequest.getFingerprint()
                    + "\",\"decision\":\"APPROVE\"}", "write-bridge-stale-version");
            assertEquals(409, wrongVersion.status);
            assertTrue("rejected stale version must not release the Product waiter", !waiting.isDone());
            Response decision = request("POST", "/api/v2/approvals/" + unified.getApprovalId()
                    + "/decisions", "{\"expectedVersion\":" + unified.getVersion()
                    + ",\"fingerprint\":\"" + productRequest.getFingerprint()
                    + "\",\"decision\":\"APPROVE\"}", "bridge-decision-1");
            assertEquals(200, decision.status);
            assertEquals("APPROVED", decision.json.path("decisionStatus").asText());
            assertEquals(CommandApprovalDecision.Decision.APPROVE_ONCE,
                    waiting.get(2, TimeUnit.SECONDS).getDecision());
        } finally {
            commandApprovals.interruptAll();
            assertEquals(CommandApprovalDecision.Decision.DENY,
                    staleWaiting.get(2, TimeUnit.SECONDS).getDecision());
        }
    }

    @Test
    public void v2WriteDecisionWakesOnlyMatchingProductApprovalWaiter() throws Exception {
        long now = System.currentTimeMillis();
        WriteScopeApprovalRequest staleRequest = new WriteScopeApprovalRequest(
                "write-stale", "run-write-bridge", "call-stale", "workspace_write",
                "docs/old.md", "WRITE", "OUT_OF_SCOPE",
                Collections.singletonList("src/main/java"), now, now + 30000L);
        FutureTask<WriteScopeApprovalDecision> staleWaiting =
                new FutureTask<WriteScopeApprovalDecision>(() -> writeApprovals
                        .handlerFor("run-write-bridge")
                        .awaitDecision(staleRequest, staleRequest.getExpiresAtMillis()));
        new Thread(staleWaiting, "v2-write-approval-stale-test").start();

        WriteScopeApprovalRequest productRequest = new WriteScopeApprovalRequest(
                "write-current", "run-write-bridge", "call-current", "workspace_write",
                "docs/current.md", "WRITE", "OUT_OF_SCOPE",
                Collections.singletonList("src/main/java"), now, now + 30000L);
        FutureTask<WriteScopeApprovalDecision> waiting =
                new FutureTask<WriteScopeApprovalDecision>(() -> writeApprovals
                        .handlerFor("run-write-bridge")
                        .awaitDecision(productRequest, productRequest.getExpiresAtMillis()));
        new Thread(waiting, "v2-write-approval-bridge-test").start();
        WriteScopeApprovalCoordinator isolatedCoordinator =
                new WriteScopeApprovalCoordinator(
                        new AtomicJsonStore(Files.createTempDirectory("write-approval-isolated-"), mapper),
                        mapper);
        FutureTask<WriteScopeApprovalDecision> isolatedWaiting =
                new FutureTask<WriteScopeApprovalDecision>(() -> isolatedCoordinator
                        .handlerFor("run-write-bridge")
                        .awaitDecision(productRequest, productRequest.getExpiresAtMillis()));
        new Thread(isolatedWaiting, "v2-write-approval-isolated-test").start();
        long deadline = System.currentTimeMillis() + 2000L;
        while ((writeApprovals.pending("run-write-bridge", productRequest.getFingerprint()) == null
                || writeApprovals.pending("run-write-bridge", staleRequest.getFingerprint()) == null
                || isolatedCoordinator.pending("run-write-bridge", productRequest.getFingerprint()) == null)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(writeApprovals.pending("run-write-bridge", productRequest.getFingerprint()) != null);
        assertTrue(writeApprovals.pending("run-write-bridge", staleRequest.getFingerprint()) != null);
        assertTrue(isolatedCoordinator.pending("run-write-bridge", productRequest.getFingerprint()) != null);

        ExecutionOperationFacts facts = new ExecutionOperationFacts();
        facts.setRequiresApproval(true);
        PermissionApprovalRecord unified = coordinator.prepare(
                ExecutionPermissionRequest.builder()
                        .principalId(ExecutionPermissionApiHandler.LOCAL_SERVICE_PRINCIPAL)
                        .projectId("project-1").sessionId("session-1").runId("run-write-bridge")
                        .workflowId("workflow-write-bridge").capabilityId("write.WRITE")
                        .summary("docs/current.md").effectFingerprint(productRequest.getFingerprint())
                        .facts(facts).build());
        try {
            Response wrongVersion = request("POST", "/api/v2/approvals/" + unified.getApprovalId()
                    + "/decisions", "{\"expectedVersion\":" + (unified.getVersion() + 1)
                    + ",\"fingerprint\":\"" + productRequest.getFingerprint()
                    + "\",\"decision\":\"APPROVE\"}", "write-version-guard");
            assertEquals(409, wrongVersion.status);
            assertTrue("rejected stale version must not release write waiter", !waiting.isDone());
            Response decision = request("POST", "/api/v2/approvals/" + unified.getApprovalId()
                    + "/decisions", "{\"expectedVersion\":" + unified.getVersion()
                    + ",\"fingerprint\":\"" + productRequest.getFingerprint()
                    + "\",\"decision\":\"APPROVE\"}", "write-bridge-1");
            assertEquals(200, decision.status);
            assertEquals("APPROVED", decision.json.path("decisionStatus").asText());
            assertEquals(WriteScopeApprovalDecision.Decision.APPROVE_ONCE,
                    waiting.get(2, TimeUnit.SECONDS).getDecision());
            assertTrue("same-run stale fingerprint was incorrectly woken",
                    !staleWaiting.isDone());
            assertTrue("approval crossed into a different coordinator instance",
                    !isolatedWaiting.isDone());

            // Replaying the same v2 mutation is idempotent and cannot create a
            // second legacy decision after the exact pending request is gone.
            Response replay = request("POST", "/api/v2/approvals/" + unified.getApprovalId()
                    + "/decisions", "{\"expectedVersion\":" + unified.getVersion()
                    + ",\"fingerprint\":\"" + productRequest.getFingerprint()
                    + "\",\"decision\":\"APPROVE\"}", "write-bridge-1");
            assertEquals(200, replay.status);
            assertEquals("APPROVED", replay.json.path("decisionStatus").asText());
        } finally {
            writeApprovals.interruptAll();
            isolatedCoordinator.interruptAll();
            assertEquals(WriteScopeApprovalDecision.Decision.DENY,
                    staleWaiting.get(2, TimeUnit.SECONDS).getDecision());
            assertEquals(WriteScopeApprovalDecision.Decision.DENY,
                    isolatedWaiting.get(2, TimeUnit.SECONDS).getDecision());
        }
    }

    @Test
    public void automationControlIsDurableAndIdempotent() throws Exception {
        Response initial = request("GET", "/api/v2/sessions/session-1/automation-controls",
                null, null);
        assertEquals(200, initial.status);
        assertEquals(1L, initial.json.path("version").asLong());
        assertTrue(!initial.json.path("paused").asBoolean());

        Response paused = request("POST", "/api/v2/sessions/session-1/automation-controls",
                "{\"expectedVersion\":1,\"control\":\"PAUSE_AUTOMATION\"}", "automation-1");
        assertEquals(200, paused.status);
        assertTrue(paused.json.path("paused").asBoolean());
        assertEquals(2L, paused.json.path("version").asLong());

        Response replay = request("POST", "/api/v2/sessions/session-1/automation-controls",
                "{\"expectedVersion\":1,\"control\":\"PAUSE_AUTOMATION\"}", "automation-1");
        assertEquals(200, replay.status);
        assertEquals(2L, replay.json.path("version").asLong());
        assertTrue(replay.json.path("paused").asBoolean());
    }

    private Response request(String method, String path, String body, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = connection.getResponseCode();
        java.io.InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[1024];
            for (int read; (read = input.read(buffer)) >= 0;) bytes.write(buffer, 0, read);
            input.close();
        }
        return new Response(status, mapper.readTree(bytes.toByteArray()));
    }

    private static final class Response {
        final int status;
        final JsonNode json;
        Response(int status, JsonNode json) { this.status = status; this.json = json; }
    }
}
