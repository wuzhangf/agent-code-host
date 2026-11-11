package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.application.gui.GuiActiveRunSummaryResolver;
import com.sage.business.code.application.gui.GuiMessage;
import com.sage.business.code.application.session.SessionHistoryQueryService;
import com.sage.business.code.application.session.SessionRecordV2;
import com.sage.business.code.application.session.SessionRepository;
import com.sage.business.code.application.session.SessionService;
import com.sage.business.code.domain.RunStatus;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sage.business.code.infrastructure.gui.GuiSessionStore;
import com.sage.business.code.infrastructure.session.HistoryCursorCodec;
import com.sage.business.code.infrastructure.session.SqliteSessionHistoryReadRepository;
import com.sage.business.code.infrastructure.session.SqliteSessionRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * HP-3 HTTP contract (2026-09-05 design §11 HP-3 acceptance): the five
 * paginated history routes served through the real dispatcher and handler,
 * with the typed 400/404/409/410/503 error contract, capability-off fail-closed
 * behavior, and the legacy array/session routes untouched. Bearer
 * authentication reuses the shared V2 handler path.
 */
public class DesktopHistoryPaginationApiTest {
    private static final String TOKEN = "desktop-history-pagination-bearer";

    private Path root;
    private Path workspace;
    private ControlPlaneDatabase database;
    private GuiSessionStore guiStore;
    private SqliteSessionRepository repository;
    private ObjectMapper mapper;
    private HttpServer server;
    private HttpServer closedServer;
    private ExecutorService httpExecutor;
    private ExecutorService closedExecutor;

    @Before public void setUp() throws Exception {
        Path base = Paths.get("target", "desktop-history-pagination-test").toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, "case-");
        workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve("src/main/java"));
        gitFixture(workspace);
        mapper = new ObjectMapper();
        database = new ControlPlaneDatabase(root.resolve("state"));
        SqliteEventJournal journal = new SqliteEventJournal(database, mapper);
        BusinessEventPublisher events = new BusinessEventPublisher(journal);
        repository = new SqliteSessionRepository(database, mapper);
        guiStore = new GuiSessionStore(root.resolve("state"), mapper);

        WorkspaceRegistry registry = new WorkspaceRegistry(
                Collections.singletonMap("sample", workspace.toString()));
        Map<String, RunStatus> runStatuses = new HashMap<String, RunStatus>();
        runStatuses.put("run-live", RunStatus.RUNNING);
        GuiActiveRunSummaryResolver resolver = new GuiActiveRunSummaryResolver(guiStore,
                new GuiActiveRunSummaryResolver.RunStatusLookup() {
                    @Override public Map<String, RunStatus> statuses(java.util.Collection<String> ids) {
                        Map<String, RunStatus> out = new LinkedHashMap<String, RunStatus>();
                        for (String id : ids) if (runStatuses.containsKey(id)) out.put(id, runStatuses.get(id));
                        return out;
                    }
                });
        SessionService sessions = new SessionService(repository, registry, mapper, events, null);
        SessionHistoryQueryService history = new SessionHistoryQueryService(
                new SqliteSessionHistoryReadRepository(database), resolver, mapper,
                "svc-history-test",
                new HistoryCursorCodec(mapper, "local-owner",
                        "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                        "boot-1", 10L * 60L * 1000L));

        V2ApiHandler handler = new V2ApiHandler(journal, new LiveEventHub(), mapper, TOKEN,
                () -> Collections.emptyList(), sessions);
        handler.withHistoryQueries(history);
        server = start(handler);

        V2ApiHandler disabled = new V2ApiHandler(journal, new LiveEventHub(), mapper, TOKEN,
                () -> Collections.emptyList(), sessions);
        // Same wiring minus withHistoryQueries: the capability-off fail-closed path.
        closedServer = start(disabled);
    }

    private HttpServer start(V2ApiHandler handler) throws Exception {
        HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        created.createContext("/api/v2", handler);
        ExecutorService executor = Executors.newCachedThreadPool();
        created.setExecutor(executor);
        created.start();
        if (created == server) httpExecutor = executor; else closedExecutor = executor;
        return created;
    }

    @After public void tearDown() throws Exception {
        if (server != null) server.stop(0);
        if (closedServer != null) closedServer.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
        if (closedExecutor != null) closedExecutor.shutdownNow();
        if (guiStore != null) guiStore.close();
        if (database != null) database.close();
        try { delete(root); } catch (RuntimeException ignored) { /* Windows handles */ }
    }

    @Test public void paginationRoutesServeTypedPagesAndErrors() throws Exception {
        seedSessions(6);
        seedMessages("sess-000", 25);

        // /summaries: keyset pages, no repeats, explicit end.
        Response page1 = get(server, "/api/v2/sessions/summaries?limit=2&archived=ALL");
        assertEquals(200, page1.status);
        JsonNode body1 = mapper.readTree(page1.body);
        assertEquals("session-summary-page/1", body1.path("schemaVersion").asText());
        assertEquals("svc-history-test", body1.path("serviceInstanceId").asText());
        assertEquals(2, body1.path("items").size());
        assertEquals("REVISION_VALIDATED", body1.path("consistency").asText());
        String cursor = body1.path("nextCursor").asText(null);

        java.util.Set<String> seen = new java.util.HashSet<String>();
        seen.add(body1.path("items").get(0).path("sessionId").asText());
        seen.add(body1.path("items").get(1).path("sessionId").asText());
        int pages = 1;
        while (cursor != null) {
            Response next = get(server, "/api/v2/sessions/summaries?limit=2&archived=ALL&cursor="
                    + urlEncode(cursor));
            assertEquals(200, next.status);
            JsonNode body = mapper.readTree(next.body);
            for (JsonNode item : body.path("items")) {
                assertTrue("no repeats across pages", seen.add(item.path("sessionId").asText()));
            }
            cursor = body.path("hasMore").asBoolean() ? body.path("nextCursor").asText(null) : null;
            pages += 1;
        }
        assertEquals(6, seen.size());
        assertTrue(pages >= 3);

        // summary / metadata: typed projections without a messages field.
        Response summary = get(server, "/api/v2/sessions/sess-000/summary");
        assertEquals(200, summary.status);
        assertEquals("sess-000", mapper.readTree(summary.body).path("sessionId").asText());
        Response metadata = get(server, "/api/v2/sessions/sess-000/metadata");
        assertEquals(200, metadata.status);
        JsonNode metadataBody = mapper.readTree(metadata.body);
        // Session insert (1) + 25 seeded messages (design §6.2: messages bump both).
        assertEquals("26", metadataBody.path("readRevision").asText());
        assertEquals("26", metadataBody.path("transcriptRevision").asText());
        assertNull("metadata must not define a messages field", metadataBody.get("messages"));

        // messages: tail page + before chain; a large body arrives DEFERRED.
        Response tail = get(server, "/api/v2/sessions/sess-000/messages?limit=10");
        assertEquals(200, tail.status);
        JsonNode tailBody = mapper.readTree(tail.body);
        assertEquals("session-message-page/1", tailBody.path("schemaVersion").asText());
        assertEquals(10, tailBody.path("items").size());
        assertTrue(tailBody.path("hasOlder").asBoolean());
        String beforeCursor = tailBody.path("beforeCursor").asText(null);
        Response older = get(server, "/api/v2/sessions/sess-000/messages?limit=10&cursor="
                + urlEncode(beforeCursor));
        assertEquals(200, older.status);
        JsonNode olderBody = mapper.readTree(older.body);
        String tailFirst = tailBody.path("items").get(0).path("id").asText();
        String olderLast = olderBody.path("items").get(olderBody.path("items").size() - 1)
                .path("id").asText();
        assertEquals("keyset chains must be contiguous", tailFirst, nextId(olderLast));

        // Error contract.
        Response tampered = get(server, "/api/v2/sessions/sess-000/messages?limit=10&cursor="
                + urlEncode(beforeCursor.substring(0, beforeCursor.length() - 3) + "xyz"));
        assertEquals(400, tampered.status);
        assertEquals("INVALID_HISTORY_CURSOR", problemCode(tampered.body));
        Response bothParams = get(server, "/api/v2/sessions/sess-000/messages?limit=10&cursor="
                + urlEncode(beforeCursor) + "&aroundMessageId=m-000");
        assertEquals(400, bothParams.status);
        assertEquals("INVALID_REQUEST", problemCode(bothParams.body));
        Response missing = get(server, "/api/v2/sessions/absent/messages?limit=10");
        assertEquals(404, missing.status);
        Response badBytes = get(server, "/api/v2/sessions/sess-000/messages/m-000/content?maxBytes=100");
        assertEquals(400, badBytes.status);
        Response missingContent = get(server, "/api/v2/sessions/sess-000/messages/absent/content");
        assertEquals(404, missingContent.status);

        // Unauthorized requests fail before any data leaves the service.
        Response anonymous = getNoAuth(server, "/api/v2/sessions/summaries?limit=2");
        assertEquals(401, anonymous.status);
    }

    @Test public void deferredBodySegmentsReassembleOverHttp() throws Exception {
        seedSessions(1);
        StringBuilder body = new StringBuilder();
        String paragraph = "中文段落 emoji 😀🎯 与代码 `x = 1`;\n";
        while (body.length() < 120_000) body.append(paragraph);
        insertMessage("sess-000", "u-big", body.toString(), System.currentTimeMillis());

        Response page = get(server, "/api/v2/sessions/sess-000/messages?limit=10");
        JsonNode items = mapper.readTree(page.body).path("items");
        JsonNode deferred = items.get(0);
        assertEquals("DEFERRED", deferred.path("contentState").asText());
        assertTrue(deferred.path("content").isNull());
        assertEquals(body.toString().getBytes(StandardCharsets.UTF_8).length,
                deferred.path("contentBytes").asLong());

        StringBuilder reassembled = new StringBuilder();
        String cursor = null;
        String revision = deferred.path("contentRevision").asText();
        int segments = 0;
        do {
            String suffix = cursor == null ? "?maxBytes=4096"
                    : "?maxBytes=4096&cursor=" + urlEncode(cursor);
            Response segment = get(server,
                    "/api/v2/sessions/sess-000/messages/u-big/content" + suffix);
            assertEquals(200, segment.status);
            JsonNode segmentBody = mapper.readTree(segment.body);
            assertEquals(revision, segmentBody.path("contentRevision").asText());
            reassembled.append(segmentBody.path("text").asText());
            cursor = segmentBody.path("nextCursor").asText(null);
            segments += 1;
            assertTrue("bounded segments", segments < 200);
        } while (cursor != null);
        assertEquals(body.toString(), reassembled.toString());
    }

    @Test public void capabilityOffFailsClosedAndLegacyRoutesStayIntact() throws Exception {
        seedSessions(3);
        seedMessages("sess-000", 5);

        // Capability not wired: the new routes answer 503 FEATURE_DISABLED.
        Response summaries = get(closedServer, "/api/v2/sessions/summaries?limit=2");
        assertEquals(503, summaries.status);
        assertEquals("FEATURE_DISABLED", problemCode(summaries.body));
        Response messagesPage = get(closedServer, "/api/v2/sessions/sess-000/messages?limit=2");
        assertEquals(503, messagesPage.status);
        assertEquals("FEATURE_DISABLED", problemCode(messagesPage.body));

        // Legacy routes are untouched on both instances (old clients keep working).
        for (HttpServer instance : new HttpServer[]{server, closedServer}) {
            Response list = get(instance, "/api/v2/sessions");
            assertEquals(200, list.status);
            assertTrue(mapper.readTree(list.body).isArray());
            Response detail = get(instance, "/api/v2/sessions/sess-000");
            assertEquals(200, detail.status);
            assertTrue(mapper.readTree(detail.body).path("messages").isArray());
        }
    }

    // ---- fixture ----

    private void seedSessions(int count) throws Exception {
        long base = System.currentTimeMillis() - 1_000_000L;
        for (int i = 0; i < count; i++) {
            final String sessionId = String.format("sess-%03d", i);
            final long createdAt = base + i * 1000L;
            final long updatedAt = base + (count - i) * 1000L;
            repository.inTransaction(new SessionRepository.TransactionWork<Void>() {
                @Override public Void execute(java.sql.Connection connection) throws Exception {
                    SessionRecordV2 session = new SessionRecordV2();
                    session.sessionId = sessionId;
                    session.title = "title " + sessionId;
                    session.workspaceAlias = "sample";
                    session.executionMode = "MODIFY";
                    session.interactionMode = "ACT";
                    session.autonomyLevel = "LOW";
                    session.verificationLevel = "VERIFY";
                    session.status = "ACTIVE";
                    session.schemaVersion = "business-session/2.0";
                    session.sessionVersion = 1L;
                    session.createdAt = createdAt;
                    session.updatedAt = updatedAt;
                    repository.insert(connection, session);
                    return null;
                }
            });
        }
    }

    private void seedMessages(String sessionId, int count) {
        long base = 1_700_000_000_000L;
        for (int i = 0; i < count; i++) {
            insertMessage(sessionId, String.format("m-%03d", i), "body " + i, base + i);
        }
    }

    private void insertMessage(String sessionId, String id, String content, long time) {
        GuiMessage message = new GuiMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setClientMessageId("client-" + id);
        message.setRole(GuiMessage.ROLE_USER);
        message.setContent(content);
        message.setStatus(GuiMessage.STATUS_SUCCEEDED);
        message.setCreatedAt(time);
        message.setUpdatedAt(time);
        guiStore.insertMessage(message);
    }

    private static String nextId(String id) {
        // m-002 -> m-003 (fixture ids are zero-padded, contiguous, ascending)
        int index = Integer.parseInt(id.substring(2));
        return String.format("m-%03d", index + 1);
    }

    private static String urlEncode(String value) throws Exception {
        return java.net.URLEncoder.encode(value, "UTF-8");
    }

    private void gitFixture(Path dir) throws Exception {
        ProcessBuilder[] commands = {
            new ProcessBuilder("git", "init").directory(dir.toFile()),
            new ProcessBuilder("git", "config", "user.email", "test@example.invalid").directory(dir.toFile()),
            new ProcessBuilder("git", "config", "user.name", "test").directory(dir.toFile()),
            new ProcessBuilder("git", "add", ".").directory(dir.toFile()),
            new ProcessBuilder("git", "commit", "-m", "initial").directory(dir.toFile())
        };
        for (ProcessBuilder command : commands) {
            Process process = command.start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        }
    }

    private int port(HttpServer instance) {
        return instance.getAddress().getPort();
    }

    private Response get(HttpServer instance, String path) throws Exception {
        return call(instance, "GET", path, null, null, true);
    }

    private Response getNoAuth(HttpServer instance, String path) throws Exception {
        return call(instance, "GET", path, null, null, false);
    }

    private Response call(HttpServer instance, String method, String path, String key, String body,
            boolean auth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port(instance) + path).openConnection();
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
        if (stream != null) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) out.write(buffer, 0, read);
            stream.close();
        }
        return new Response(connection.getResponseCode(), out.toString("UTF-8"));
    }

    private String problemCode(String body) throws Exception {
        return mapper.readTree(body).path("code").asText();
    }

    private static void delete(Path path) throws Exception {
        if (path == null || !Files.exists(path)) return;
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach(value -> {
            try { Files.deleteIfExists(value); } catch (Exception ignored) { }
        });
    }

    private static final class Response {
        final int status; final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
}
