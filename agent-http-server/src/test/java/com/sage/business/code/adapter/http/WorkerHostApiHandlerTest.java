package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.application.event.*;
import com.sage.business.code.application.session.SessionService;
import com.sage.business.code.contract.v2.capability.CapabilityResponse;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sage.business.code.infrastructure.session.SqliteSessionRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

/** NWF W0: same real SQLite session/receipt authority through both consumer paths. */
public class WorkerHostApiHandlerTest {
    private static final String TOKEN = "worker-host-binding-test-token";
    private final ObjectMapper mapper = new ObjectMapper();
    private Path root, workspace;
    private ControlPlaneDatabase database;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final String store = UUID.randomUUID().toString();

    @Before public void start() throws Exception {
        root = Files.createTempDirectory("nwf-acs-"); workspace = root.resolve("source"); Files.createDirectory(workspace);
        Files.write(workspace.resolve("README.md"), "baseline\n".getBytes(StandardCharsets.UTF_8));
        git("init"); git("add", "."); git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "baseline");
        database = new ControlPlaneDatabase(root.resolve("state"));
        SqliteEventJournal journal = new SqliteEventJournal(database, mapper);
        BusinessEventPublisher publisher = new BusinessEventPublisher(journal);
        SessionService sessions = new SessionService(new SqliteSessionRepository(database, mapper),
                new WorkspaceRegistry(Collections.singletonMap("fixture", workspace.toString())), mapper, publisher, null);
        ClientSessionRegistry clients = new ClientSessionRegistry(store, UUID.randomUUID().toString());
        List<V2RouteGroup> groups = new ArrayList<V2RouteGroup>();
        groups.add(new V2ApiHandler(journal, new LiveEventHub(), mapper, TOKEN, null, sessions));
        final CapabilityResponse source = new CapabilityResponse();
        CapabilityResponse.OperationCapability op = new CapabilityResponse.OperationCapability();
        op.setId("run.commandReceiptV1"); op.setAvailability("DISABLED"); op.setReasonCode("ROLLOUT_DISABLED");
        source.setOperations(Collections.singletonList(op));
        groups.add(new WorkerHostApiHandler(mapper, TOKEN, clients, () -> source, groups));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new V2ApiDispatcher(mapper, groups, clients, false));
        executor = java.util.concurrent.Executors.newCachedThreadPool(); server.setExecutor(executor); server.start();
    }

    @After public void stop() throws Exception {
        if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); if (database != null) database.close();
        if (root != null) try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> { try {
                if (!p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new IllegalStateException("outside test root");
                p.toFile().setWritable(true); Files.deleteIfExists(p);
            } catch (Exception e) { throw new RuntimeException(e); } });
        }
    }

    @Test public void aliasAndOriginalConsumeOneDurableSessionAndIdempotencyKey() throws Exception {
        String body = "{\"workspaceAlias\":\"fixture\",\"executionMode\":\"INSPECT\",\"verificationLevel\":\"NONE\",\"writeAllow\":[],\"writeForbid\":[]}";
        Reply create = request("POST", "/worker-host/v1/sessions", "original-key", body, TOKEN, store);
        Assert.assertEquals(201, create.status); String id = create.json.path("sessionId").asText(); Assert.assertFalse(id.isEmpty());
        Reply replay = request("POST", "/api/v2/sessions", "original-key", body, TOKEN, null);
        Assert.assertEquals(201, replay.status); Assert.assertEquals(id, replay.json.path("sessionId").asText());
        Reply original = request("GET", "/api/v2/sessions/" + id, null, null, TOKEN, null);
        Reply alias = request("GET", "/api/v2/worker-host/v1/sessions/" + id, null, null, TOKEN, store);
        Assert.assertEquals(200, alias.status); Assert.assertEquals(original.json, alias.json);
        Reply conflict = request("POST", "/worker-host/v1/sessions", "original-key", body.replace("INSPECT", "MODIFY"), TOKEN, store);
        Assert.assertEquals(409, conflict.status);
        Reply fork = request("POST", "/worker-host/v1/sessions/" + id + "/forks", "fork-key",
                "{\"expectedSessionVersion\":1,\"copyPolicy\":\"MESSAGES_ONLY\"}", TOKEN, store);
        Assert.assertEquals(201, fork.status); Assert.assertEquals(id, fork.json.path("parentSessionId").asText());
        Assert.assertEquals(200, request("GET", "/api/v2/sessions/" + fork.json.path("sessionId").asText(), null, null, TOKEN, null).status);
    }

    @Test public void discoveryUsesOriginalIdentityAndDoesNotInventParityQualification() throws Exception {
        Reply result = request("GET", "/worker-host/v1/manifest", null, null, TOKEN, store);
        Assert.assertEquals(200, result.status); Assert.assertEquals("worker-host/1", result.json.path("schemaVersion").asText());
        Assert.assertEquals(store, result.json.path("storeId").asText()); Assert.assertEquals("acs", result.json.path("engine").path("id").asText());
        JsonNode capability = result.json.path("capabilities").get(0);
        Assert.assertEquals("NOT_RUN", capability.path("qualification").asText());
        Assert.assertEquals("UNAVAILABLE", capability.path("availability").asText());
        Assert.assertEquals("ROLLOUT_DISABLED", capability.path("reasonCode").asText());
    }

    @Test public void aliasesRetainAuthenticationStoreAndRegisteredControlGuards() throws Exception {
        Assert.assertEquals(401, request("GET", "/worker-host/v1/manifest", null, null, null, null).status);
        Reply changed = request("GET", "/worker-host/v1/sessions", null, null, TOKEN, "other-store");
        Assert.assertEquals(409, changed.status); Assert.assertEquals("STORE_IDENTITY_CHANGED", changed.json.path("code").asText());
        Assert.assertEquals(428, request("POST", "/worker-host/v1/runs/unknown/commands", "stop-key", "{}", TOKEN, store).status);
        Assert.assertEquals(428, request("POST", "/api/v2/worker-host/v1/operations/lookup", "lookup-key", "{}", TOKEN, store).status);
        Assert.assertEquals(404, request("GET", "/worker-host/v1/admin", null, null, TOKEN, store).status);
    }

    private Reply request(String method, String path, String key, String body, String token, String expectedStore) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + server.getAddress().getPort() + path).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000); c.setRequestMethod(method);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (key != null) c.setRequestProperty("Idempotency-Key", key);
        if (expectedStore != null) c.setRequestProperty("X-Worker-Local-Store", expectedStore);
        if (body != null) { c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json"); try (java.io.OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int status = c.getResponseCode();
        JsonNode json; try (java.io.InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) { json = mapper.readTree(in); }
        c.disconnect(); return new Reply(status, json);
    }
    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList("git", "-C", workspace.toString())); command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (java.io.InputStream in = p.getInputStream()) { while (in.read() != -1) { } }
        Assert.assertEquals(0, p.waitFor());
    }
    private static final class Reply { final int status; final JsonNode json; Reply(int status, JsonNode json) { this.status = status; this.json = json; } }
}
