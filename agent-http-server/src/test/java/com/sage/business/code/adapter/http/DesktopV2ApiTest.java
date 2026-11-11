package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sage.business.code.application.BusinessCodeExecutor;
import com.sage.business.code.application.TaskApplicationService;
import com.sage.business.code.application.TaskCanonicalizer;
import com.sage.business.code.application.evidence.EvidenceQueryService;
import com.sage.business.code.application.model.PublicModelCatalogService;
import com.sage.business.code.application.project.NativeOpenIntentService;
import com.sage.business.code.application.project.ProjectFileReadService;
import com.sage.business.code.application.project.ProjectQueryService;
import com.sage.business.code.application.project.ProjectRegistrationService;
import com.sage.business.code.application.review.RunReviewService;
import com.sage.business.code.application.run.RunChangeArtifactService;
import com.sage.business.code.application.run.RunQueryService;
import com.sage.business.code.adapter.gui.WorkspaceDiffService;
import com.sage.business.code.contract.ApprovalRequest;
import com.sage.business.code.contract.BusinessCodeTaskContract;
import com.sage.business.code.contract.RunRequest;
import com.sage.business.code.contract.v2.attention.AttentionItemResponse;
import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import com.sage.business.code.contract.v2.project.NativeOpenIntentResponse;
import com.sage.business.code.domain.ApprovedBusinessTask;
import com.sage.business.code.domain.BusinessExecutionResult;
import com.sage.business.code.domain.BusinessRunContext;
import com.sage.business.code.domain.RunRecord;
import com.sage.business.code.domain.RunStatus;
import com.sage.business.code.domain.TaskRecord;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * EW1 desktop-plane HTTP contract: capability handshake, project
 * registration/query, run view/interrupt guard/evidence/diff-degradation and
 * model catalog, served through the same V2ApiDispatcher the launcher wires.
 */
public class DesktopV2ApiTest {
    private static final String TOKEN = "desktop-v2-api-contract-bearer";

    private Path root;
    private Path workspace;
    private Path secondWorkspace;
    private TaskApplicationService app;
    private WorkspaceRegistry registry;
    private ProjectRegistrationService registrations;
    private NativeOpenIntentService nativeOpen;
    private HttpServer server;
    private ExecutorService httpExecutor;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Before public void setUp() throws Exception {
        Path base = Paths.get("target", "desktop-v2-api-test").toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, "case-");
        workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve("src/main/java"));
        server = null;
        httpExecutor = null;
    }

    /** Full wiring used by most tests; the plain-dir variant reuses start(). */
    private void start(Path registeredWorkspace) throws Exception {
        gitFixture(registeredWorkspace);
        secondWorkspace = root.resolve("second-workspace");
        Files.createDirectories(secondWorkspace);
        AtomicJsonStore store = new AtomicJsonStore(root.resolve("state"), mapper);
        registry = new WorkspaceRegistry(Collections.singletonMap("sample", registeredWorkspace.toString()));
        BusinessCodeExecutor fake = new BusinessCodeExecutor() {
            public BusinessExecutionResult execute(ApprovedBusinessTask t, BusinessRunContext c) {
                Map<String, Object> verification = new LinkedHashMap<String, Object>();
                verification.put("status", "PASSED");
                verification.put("command", "mvn -q test");
                return new BusinessExecutionResult(RunStatus.SUCCEEDED, "product-" + c.getRunId(),
                        "ok", Collections.singletonList("src/main/java/Example.java"),
                        verification, null, null, "trace://diag/1");
            }
        };
        app = new TaskApplicationService(store, new TaskCanonicalizer(registry, mapper), registry,
                fake, mapper, root.resolve("locks"));
        Supplier<List<AttentionItemResponse>> attention =
                new Supplier<List<AttentionItemResponse>>() {
                    public List<AttentionItemResponse> get() { return Collections.emptyList(); }
        };
        RunQueryService runQuery = new RunQueryService(app, store);
        ProjectQueryService projectQuery = new ProjectQueryService(registry, runQuery, attention);
        registrations = new ProjectRegistrationService(registry, store, mapper);
        nativeOpen = new NativeOpenIntentService(registry, TOKEN, "svc-test-0001");
        WorkspaceDiffService workspaceDiffs = new WorkspaceDiffService(registry);
        RunChangeArtifactService changeArtifacts = new RunChangeArtifactService(
                store, app, workspaceDiffs, registry);
        app.addRunStateListener(changeArtifacts);
        EvidenceQueryService evidenceQuery = new EvidenceQueryService(app, changeArtifacts);
        RunReviewService runReviews = new RunReviewService(store);
        List<V2RouteGroup> groups = Arrays.<V2RouteGroup>asList(
                new CapabilityApiHandler(mapper, TOKEN, capability()),
                new ProjectApiHandler(mapper, TOKEN, projectQuery, registrations, nativeOpen,
                        new ProjectFileReadService(registry)),
                new RunApiHandler(mapper, TOKEN, runQuery, evidenceQuery, changeArtifacts,
                        runReviews),
                new ModelCatalogApiHandler(mapper, TOKEN,
                        new PublicModelCatalogService(root.resolve("absent-model.properties"))),
                new V2ApiHandler(new com.sage.business.code.infrastructure.event.SqliteEventJournal(
                        new com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase(root.resolve("state")),
                        mapper), new com.sage.business.code.application.event.LiveEventHub(),
                        mapper, TOKEN, attention));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper, groups));
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
    }

    private static CapabilityResponse capability() {
        CapabilityResponse response = new CapabilityResponse();
        response.setServiceInstanceId("svc-test-0001");
        response.setProtocolVersion("2.2");
        response.setMinDesktopProtocol("2.0");
        CapabilityResponse.FeatureFlags features = new CapabilityResponse.FeatureFlags();
        features.setSessionV2(true);
        features.setDurableEvents(true);
        // EW4: mirrors the production capability composition
        features.setVisibleOutputStream(true);
        features.setMission(true);
        response.setFeatures(features);
        CapabilityResponse.CapabilityLimits limits = new CapabilityResponse.CapabilityLimits();
        limits.setMaxDiffBytes(2097152L);
        limits.setMaxEventPage(500);
        limits.setMaxVisibleDeltaChars(8192);
        response.setLimits(limits);
        response.setOperations(Collections.<CapabilityResponse.OperationCapability>emptyList());
        return response;
    }

    @After public void tearDown() throws Exception {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
        if (app != null) app.close();
        try { delete(root); } catch (RuntimeException ignored) { /* Windows handles */ }
    }

    @Test public void capabilityHandshakeServesVersionedFeatureFlags() throws Exception {
        start(workspace);
        Response response = get("/api/v2/capabilities");
        assertEquals(200, response.status);
        JsonNode body = mapper.readTree(response.body);
        assertEquals("2.2", body.path("protocolVersion").asText());
        assertEquals("2.0", body.path("minDesktopProtocol").asText());
        assertFalse(body.path("serviceInstanceId").asText().isEmpty());
        assertTrue(body.path("features").path("sessionV2").asBoolean());
        // EW4: the durable run.output.append stream is wired end to end
        assertTrue(body.path("features").path("visibleOutputStream").asBoolean());
        assertFalse(body.path("features").path("worktreeExecution").asBoolean());
        assertEquals(500, body.path("limits").path("maxEventPage").asInt());
        // authentication boundary
        assertEquals(401, getNoAuth("/api/v2/capabilities").status);
    }

    @Test public void projectsListAndStatusAreRootFree() throws Exception {
        start(workspace);
        Response list = get("/api/v2/projects");
        assertEquals(200, list.status);
        JsonNode items = mapper.readTree(list.body);
        assertEquals(1, items.size());
        assertEquals("sample", items.get(0).path("projectId").asText());
        assertEquals("AVAILABLE", items.get(0).path("authorityStatus").asText());
        assertFalse(items.toString().contains(workspace.toString()));

        Response status = get("/api/v2/projects/sample/status");
        assertEquals(200, status.status);
        JsonNode statusBody = mapper.readTree(status.body);
        assertFalse(statusBody.path("workspaceId").asText().isEmpty());
        assertFalse(statusBody.path("fingerprint").asText().isEmpty());
        assertEquals(0, statusBody.path("dirtyCount").asInt());

        Response preflight = get("/api/v2/projects/sample/session-preflight");
        assertEquals(200, preflight.status);
        JsonNode preflightBody = mapper.readTree(preflight.body);
        assertEquals("sample", preflightBody.path("workspaceAlias").asText());
        assertEquals("AVAILABLE", preflightBody.path("authorityStatus").asText());
        assertTrue(preflightBody.path("allowedIntents").toString().contains("CODE"));
        // Policy 2026-08-30: the scope suggestion enumerates existing
        // top-level entries; the fixture workspace only contains src/main/java.
        assertEquals("src", preflightBody.path("suggestedWriteScope").get(0).asText());
        assertFalse(preflightBody.path("revision").asText().isEmpty());
        assertFalse(preflightBody.path("policyFingerprint").asText().isEmpty());
        assertFalse(preflightBody.toString().contains(workspace.toString()));

        Response missing = get("/api/v2/projects/nope");
        assertEquals(404, missing.status);
        assertEquals("PROJECT_NOT_FOUND", problemCode(missing.body));
    }

    @Test public void registrationIsIdempotentPolicyGatedAndRootFreeInProjection() throws Exception {
        start(workspace);
        String target = secondWorkspace.toAbsolutePath().toString();
        gitFixture(secondWorkspace);
        String body = "{\"absolutePath\":\"" + target.replace('\\', '/') + "\"}";

        Response first = post("/api/v2/projects/registrations", "register-1", body);
        assertEquals(201, first.status);
        JsonNode created = mapper.readTree(first.body);
        String projectId = created.path("projectId").asText();
        assertFalse(projectId.isEmpty());
        assertFalse(created.path("alreadyRegistered").asBoolean());

        Response replay = post("/api/v2/projects/registrations", "register-1", body);
        assertEquals(201, replay.status);
        assertEquals(projectId, mapper.readTree(replay.body).path("projectId").asText());

        Response again = post("/api/v2/projects/registrations", "register-2", body);
        assertEquals(201, again.status);
        JsonNode repeated = mapper.readTree(again.body);
        assertEquals(projectId, repeated.path("projectId").asText());
        assertTrue(repeated.path("alreadyRegistered").asBoolean());

        // the dynamic project is immediately visible without the canonical root
        Response list = get("/api/v2/projects");
        assertTrue(mapper.readTree(list.body).toString().contains(projectId));
        assertFalse(mapper.readTree(list.body).toString().contains(target.replace('\\', '/')));

        // relative paths are rejected fail-closed
        Response relative = post("/api/v2/projects/registrations", "register-3",
                "{\"absolutePath\":\"relative/path\"}");
        assertEquals(400, relative.status);

        // A Git subdirectory is also a valid workspace boundary; its status
        // paths are normalized back to the selected directory.
        Path nestedGitDirectory = workspace.resolve("src");
        Response nestedGit = post("/api/v2/projects/registrations", "register-nested",
                "{\"absolutePath\":\"" + nestedGitDirectory.toAbsolutePath().toString().replace('\\', '/') + "\"}");
        assertEquals(201, nestedGit.status);
        Response nestedSummary = get("/api/v2/projects/"
                + mapper.readTree(nestedGit.body).path("projectId").asText());
        assertEquals("GIT", mapper.readTree(nestedSummary.body).path("vcsKind").asText());

        // Ordinary directories are valid projects; Git-only capabilities stay
        // unavailable in the project projection instead of blocking registration.
        Path plain = Files.createTempDirectory("plain-");
        Response notGit = post("/api/v2/projects/registrations", "register-4",
                "{\"absolutePath\":\"" + plain.toAbsolutePath().toString().replace('\\', '/') + "\"}");
        assertEquals(201, notGit.status);
        JsonNode plainProject = mapper.readTree(notGit.body);
        assertFalse(plainProject.path("projectId").asText().isEmpty());
        Response plainSummary = get("/api/v2/projects/" + plainProject.path("projectId").asText());
        assertEquals(200, plainSummary.status);
        assertEquals("FILESYSTEM", mapper.readTree(plainSummary.body).path("vcsKind").asText());
        Response plainPreflight = get("/api/v2/projects/" + plainProject.path("projectId").asText()
                + "/session-preflight");
        assertEquals(200, plainPreflight.status);

        // native open intent: signed, expiring, verifiable
        Response intent = post("/api/v2/projects/" + projectId + "/native-open-intents",
                "intent-1", "{\"action\":\"USER_TERMINAL\"}");
        assertEquals(201, intent.status);
        NativeOpenIntentResponse parsed = mapper.readValue(intent.body, NativeOpenIntentResponse.class);
        assertEquals("USER_TERMINAL", parsed.getAction());
        assertTrue(nativeOpen.verify(parsed));
        parsed.setRoot("C:/tampered");
        assertFalse(nativeOpen.verify(parsed));
        // intents are a mutation: Idempotency-Key is mandatory
        assertEquals(400, postNoKey("/api/v2/projects/" + projectId + "/native-open-intents",
                "{\"action\":\"REVEAL\"}").status);

        // REVEAL_FILE carries the server-validated workspace-relative ref
        Response fileIntent = post("/api/v2/projects/" + projectId + "/native-open-intents",
                "intent-file-1", "{\"action\":\"REVEAL_FILE\",\"fileRef\":\"README.md\"}");
        assertEquals(201, fileIntent.status);
        NativeOpenIntentResponse fileParsed = mapper.readValue(fileIntent.body,
                NativeOpenIntentResponse.class);
        assertEquals("REVEAL_FILE", fileParsed.getAction());
        assertEquals("README.md", fileParsed.getFileRef());
        assertTrue(nativeOpen.verify(fileParsed));
        fileParsed.setFileRef("src/main/java/Other.java");
        assertFalse(nativeOpen.verify(fileParsed));

        // fileRef is required for REVEAL_FILE and rejected for project-level actions
        assertEquals(400, post("/api/v2/projects/" + projectId + "/native-open-intents",
                "intent-file-2", "{\"action\":\"REVEAL_FILE\"}").status);
        assertEquals(400, post("/api/v2/projects/" + projectId + "/native-open-intents",
                "intent-file-3", "{\"action\":\"REVEAL\",\"fileRef\":\"README.md\"}").status);

        // traversal and missing files fail closed
        Response fileTraversal = post("/api/v2/projects/" + projectId + "/native-open-intents",
                "intent-file-4", "{\"action\":\"REVEAL_FILE\",\"fileRef\":\"../escape.txt\"}");
        assertEquals(400, fileTraversal.status);
        assertEquals("FILE_REF_INVALID", problemCode(fileTraversal.body));
        Response fileMissing = post("/api/v2/projects/" + projectId + "/native-open-intents",
                "intent-file-5", "{\"action\":\"REVEAL_FILE\",\"fileRef\":\"missing.txt\"}");
        assertEquals(404, fileMissing.status);
        assertEquals("FILE_NOT_FOUND", problemCode(fileMissing.body));
    }

    @Test public void runViewInterruptGuardsAndEvidenceAreHonest() throws Exception {
        start(workspace);
        // full lifecycle through the fake executor
        RunRecord run = createApproveStart();
        // wait for terminal state via the run view (authoritative source)
        JsonNode view = null;
        for (int i = 0; i < 200; i++) {
            Response current = get("/api/v2/runs/" + run.runId);
            assertEquals(200, current.status);
            view = mapper.readTree(current.body);
            String state = view.path("state").asText();
            if ("COMPLETED".equals(state) || "FAILED".equals(state)) break;
            Thread.sleep(20);
        }
        assertNotNull(view);
        assertEquals("COMPLETED", view.path("state").asText());
        assertEquals("SUCCEEDED", view.path("sourceStatus").asText());
        assertEquals(1, view.path("changedFileCount").asInt());
        assertFalse(view.path("stateVersion").asLong() < 1L);

        // interrupt requires an Idempotency-Key
        Response noKey = postNoKey("/api/v2/runs/" + run.runId + "/interrupt", "{}");
        assertEquals(400, noKey.status);

        // stale optimistic guard fails closed before any mutation
        Response stale = post("/api/v2/runs/" + run.runId + "/interrupt", "interrupt-stale",
                "{\"expectedStateVersion\":999}");
        assertEquals(409, stale.status);
        assertEquals("STATE_VERSION_CONFLICT", problemCode(stale.body));

        // evidence: every layer reported honestly, bundle incomplete
        Response evidence = get("/api/v2/runs/" + run.runId + "/evidence");
        assertEquals(200, evidence.status);
        JsonNode bundle = mapper.readTree(evidence.body);
        assertFalse(bundle.path("complete").asBoolean());
        Map<String, String> layerStatus = new LinkedHashMap<String, String>();
        for (JsonNode item : bundle.path("items")) {
            layerStatus.put(item.path("layer").asText(), item.path("status").asText());
        }
        assertEquals("PRESENT", layerStatus.get("SOURCE"));
        assertEquals("PRESENT", layerStatus.get("DETERMINISTIC_TEST"));
        assertEquals("PRESENT", layerStatus.get("RUNTIME"));
        assertEquals("PRESENT", layerStatus.get("TRACE"));
        assertEquals("NOT_RUN", layerStatus.get("LIVE_MODEL"));
        assertEquals("NOT_APPLICABLE", layerStatus.get("SCENARIO_ORACLE"));
        // never fabricate GUI/package evidence
        assertEquals("NOT_APPLICABLE", layerStatus.get("PACKAGE"));
        assertEquals("NOT_APPLICABLE", layerStatus.get("GUI"));

        // terminal diff is served from the persisted change artifact
        Response diff = get("/api/v2/runs/" + run.runId + "/diff");
        assertEquals(200, diff.status);
        JsonNode diffBody = mapper.readTree(diff.body);
        assertEquals(run.runId, diffBody.path("runId").asText());
        assertTrue(diffBody.path("artifactId").asText().startsWith("change-"));
        assertTrue(diffBody.path("complete").asBoolean());

        // review is a durable, honest NOT_REQUESTED projection until a review worker exists
        Response review = get("/api/v2/runs/" + run.runId + "/review");
        assertEquals(200, review.status);
        JsonNode reviewBody = mapper.readTree(review.body);
        assertEquals("NOT_REQUESTED", reviewBody.path("status").asText());
        assertFalse(reviewBody.path("complete").asBoolean());

        // file reads are bounded service projections, never renderer filesystem reads
        Response file = get("/api/v2/projects/sample/files/README.md");
        assertEquals(200, file.status);
        JsonNode fileBody = mapper.readTree(file.body);
        assertEquals("sample", fileBody.path("projectId").asText());
        assertEquals("README.md", fileBody.path("fileRef").asText());
        assertEquals("fixture" + System.lineSeparator(), fileBody.path("contentText").asText());
        assertEquals("UTF-8", fileBody.path("encoding").asText());
        assertFalse(fileBody.path("stale").asBoolean());

        Response traversal = get("/api/v2/projects/sample/files/%2E%2E%2FREADME.md");
        assertEquals(400, traversal.status);
        assertEquals("FILE_REF_INVALID", problemCode(traversal.body));

        // run list includes the finished run
        Response list = get("/api/v2/runs?workspaceAlias=sample");
        assertEquals(200, list.status);
        assertTrue(mapper.readTree(list.body).path("items").size() >= 1);
    }

    @Test public void modelCatalogStaysPublicSafeWithoutConfiguration() throws Exception {
        start(workspace);
        Response response = get("/api/v2/model-catalog");
        assertEquals(200, response.status);
        JsonNode body = mapper.readTree(response.body);
        assertEquals(1, body.path("items").size());
        JsonNode entry = body.path("items").get(0);
        assertEquals("unconfigured", entry.path("modelAlias").asText());
        assertFalse(entry.path("configured").asBoolean());
        assertEquals("UNCONFIGURED", entry.path("availability").asText());
        assertFalse(body.toString().toLowerCase().contains("apikey"));
    }

    @Test public void unknownPathsReturnOneCanonicalNotFound() throws Exception {
        start(workspace);
        Response missing = get("/api/v2/does-not-exist");
        assertEquals(404, missing.status);
        assertEquals("INVALID_REQUEST", problemCode(missing.body));
    }

    // ---- fixtures ----

    /** Creates, approves and starts one run through the fake executor. */
    private RunRecord createApproveStart() {
        BusinessCodeTaskContract contract = new BusinessCodeTaskContract();
        contract.setSchemaVersion(BusinessCodeTaskContract.SCHEMA_VERSION);
        contract.setWorkspace("sample");
        contract.setMode("MODIFY");
        contract.setGoal("Add a bounded test change");
        BusinessCodeTaskContract.AcceptanceCriterion ac = new BusinessCodeTaskContract.AcceptanceCriterion();
        ac.setId("AC-1");
        ac.setGiven("a request");
        ac.setWhen("it is submitted");
        ac.setThen(Collections.singletonList("a result is returned"));
        ac.setObservable("result is visible");
        contract.setAcceptanceCriteria(Collections.singletonList(ac));
        BusinessCodeTaskContract.WriteScope scope = new BusinessCodeTaskContract.WriteScope();
        scope.setAllow(Collections.singletonList("src/main/java"));
        contract.setWriteScope(scope);
        BusinessCodeTaskContract.Verification v = new BusinessCodeTaskContract.Verification();
        v.setLevel("VERIFY");
        v.setOffline(false);
        contract.setVerification(v);

        TaskRecord task = app.create(contract, "create-1");
        ApprovalRequest approval = new ApprovalRequest();
        approval.setRevision(task.revision);
        approval.setFingerprint(task.fingerprint);
        app.approve(task.taskId, approval, "approve-1");
        RunRequest request = new RunRequest();
        request.setRevision(task.revision);
        request.setFingerprint(task.fingerprint);
        return app.start(task.taskId, request, "start-1");
    }

    private void gitFixture(Path dir) throws Exception {
        Files.write(dir.resolve("README.md"), Collections.singletonList("fixture"));
        git(dir, "init");
        git(dir, "config", "user.email", "test@example.invalid");
        git(dir, "config", "user.name", "test");
        git(dir, "add", ".");
        git(dir, "commit", "-m", "initial");
    }

    private void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<String>();
        command.add("git"); command.add("-C"); command.add(dir.toString());
        Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(process.getInputStream(), out); copy(process.getErrorStream(), out);
        if (process.waitFor() != 0) throw new IOException(out.toString("UTF-8"));
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private Response get(String path) throws Exception { return call("GET", path, null, null, true); }
    private Response getNoAuth(String path) throws Exception { return call("GET", path, null, null, false); }

    private Response post(String path, String key, String body) throws Exception {
        return call("POST", path, key, body, true);
    }

    private Response postNoKey(String path, String body) throws Exception {
        return call("POST", path, null, body, true);
    }

    private Response call(String method, String path, String key, String body, boolean auth)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port() + path).openConnection();
        connection.setRequestMethod(method);
        if (auth) connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = connection.getOutputStream(); out.write(bytes); out.close();
        }
        InputStream stream = connection.getResponseCode() < 400
                ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (stream != null) { copy(stream, out); stream.close(); }
        return new Response(connection.getResponseCode(), out.toString("UTF-8"));
    }

    private String problemCode(String body) throws IOException {
        return mapper.readTree(body).path("code").asText();
    }

    private static void delete(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach(value -> {
            try { Files.deleteIfExists(value); } catch (IOException ignored) { }
        });
    }

    private static final class Response {
        final int status; final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}
