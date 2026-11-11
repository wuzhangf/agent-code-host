package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.agent.product.design.model.StepKind;
import com.agent.product.design.service.DesignTaskService;
import com.agent.product.design.steps.CheckStepDriver;
import com.agent.product.design.steps.HandoffStepDriver;
import com.agent.product.design.steps.StepDriver;
import com.agent.product.design.store.FileDesignTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.design.DesignEventBridge;
import com.sage.business.code.application.design.DesignInteractionBoard;
import com.sage.business.code.application.design.DirectoryDesignProjectPort;
import com.sage.business.code.application.design.FileDesignContentPort;
import com.sage.business.code.application.design.LocalDesignIds;
import com.sage.business.code.application.design.SystemDesignClock;
import com.sage.business.code.application.design.TaskBoundIntakeDriver;
import com.sage.business.code.application.design.UnavailableDesignScheduler;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.infrastructure.attention.SqliteAttentionStore;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP boundary for {@code GET /api/v2/design/content} (10 §G5) and the
 * legacy {@code /api/v2/design/tasks} alias: the route is a sibling of the
 * task subtree, gated on the same bearer token, and must not shadow the
 * canonical task base.
 */
public final class DesignContentRouteTest {

    private static final String TOKEN = "route-test-token";

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;
    private ExecutorService executor;
    private ControlPlaneDatabase database;
    private Path contentRoot;

    @Before
    public void setUp() throws Exception {
        Path root = folder.newFolder("state").toPath();
        Path designRoot = root.resolve("design");
        contentRoot = designRoot.resolve("content");
        Files.createDirectories(contentRoot);

        FileDesignTaskStore files = new FileDesignTaskStore(
                designRoot.resolve("tasks"), designRoot.resolve("drafts"),
                designRoot.resolve("bundles"));
        SystemDesignClock clock = new SystemDesignClock();
        Map<StepKind, StepDriver> drivers =
                new LinkedHashMap<StepKind, StepDriver>();
        drivers.put(StepKind.S0_INTAKE, new TaskBoundIntakeDriver());
        drivers.put(StepKind.S6_CHECK, new CheckStepDriver());
        drivers.put(StepKind.S7_HANDOFF, new HandoffStepDriver());
        DesignTaskService service = new DesignTaskService(files, files,
                new FileDesignContentPort(contentRoot),
                new DirectoryDesignProjectPort(designRoot.resolve("projects")),
                new LocalDesignIds(), clock,
                new UnavailableDesignScheduler("route test"), drivers,
                new com.agent.product.design.artifact
                        .FileDesignArtifactPort(), 3);
        database = new ControlPlaneDatabase(root);
        SqliteEventJournal journal = new SqliteEventJournal(database, mapper);
        DesignEventBridge bridge = new DesignEventBridge(files,
                new BusinessEventPublisher(journal),
                new SqliteAttentionStore(database), clock, mapper);
        DesignInteractionBoard board =
                new DesignInteractionBoard(files, files, clock);

        DesignApiHandler design = new DesignApiHandler(mapper, TOKEN,
                service, files, board, bridge,
                new FileDesignContentPort(contentRoot));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper,
                Collections.<V2RouteGroup>singletonList(design)));
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        if (database != null) database.close();
    }

    @Test
    public void contentListReturnsRefsWithDigests() throws Exception {
        Files.write(contentRoot.resolve("api.md"),
                "openapi body".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = get("/api/v2/design/content");
        assertEquals(200, response.statusCode());
        JsonNode items = mapper.readTree(response.body()).path("items");
        assertEquals(1, items.size());
        assertEquals("api.md", items.get(0).path("ref").asText());
        assertEquals(FileDesignContentPort.sha256Digest("openapi body"),
                items.get(0).path("sha256").asText());
        assertEquals(12, items.get(0).path("bytes").asInt());
    }

    @Test
    public void contentListRequiresBearerAndPostValidates() throws Exception {
        HttpResponse<String> anonymous = client.send(HttpRequest.newBuilder(
                uri("/api/v2/design/content")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
        assertEquals("UNAUTHORIZED", mapper.readTree(anonymous.body())
                .path("code").asText());

        // POST is the live S6 publish route — an empty body validates
        // to INVALID_REQUEST (400), not method-denied.
        HttpRequest post = HttpRequest.newBuilder(
                uri("/api/v2/design/content"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> denied = client.send(post,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, denied.statusCode());
        assertEquals("INVALID_REQUEST", mapper.readTree(denied.body())
                .path("code").asText());
    }

    @Test
    public void legacyDesignTasksAliasStillResolves() throws Exception {
        HttpResponse<String> response = get("/api/v2/design/tasks");
        assertEquals(200, response.statusCode());
        assertTrue(mapper.readTree(response.body()).has("items"));
    }

    @Test
    public void unknownDesignSiblingFallsThroughTo404() throws Exception {
        HttpResponse<String> response = get("/api/v2/design/bogus");
        assertEquals(404, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + TOKEN).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + path);
    }
}
