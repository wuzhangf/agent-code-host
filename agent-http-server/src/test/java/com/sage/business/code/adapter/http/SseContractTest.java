package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.contract.v2.event.BusinessEventEnvelope;
import com.sage.business.code.contract.v2.event.BusinessEventStream;
import com.sage.business.code.contract.v2.event.EventAggregateRef;
import com.sage.business.code.contract.v2.event.EventDelivery;
import com.sage.business.code.contract.v2.event.EventQueryResponse;
import com.sage.business.code.contract.v2.event.EventRedactionClass;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * WP3 SSE contract (design §4.7), exercised over real HTTP: Last-Event-ID
 * replay, the backlog/live handoff without a gap, bounded slow-client
 * disconnects, typed 409 for expired cursors, heartbeats that never touch
 * durable state, and one-time browser stream tickets.
 */
public class SseContractTest {
    private static final String TOKEN = "sse-contract-local-bearer-token";
    private Path root;
    private HttpServer server;
    private SqliteEventJournal journal;
    private BusinessEventPublisher publisher;
    private final AtomicReference<LiveEventHub> hub =
            new AtomicReference<LiveEventHub>(new LiveEventHub(64));
    private ObjectMapper mapper;

    @Before
    public void setUp() throws Exception {
        Path base = Paths.get("target", "sse-contract-test").toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, "case-");
        mapper = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        journal = new SqliteEventJournal(root, mapper);
        publisher = new BusinessEventPublisher(journal);
        publisher.addListener(new java.util.function.Consumer<BusinessEventEnvelope>() {
            public void accept(BusinessEventEnvelope event) { hub.get().publish(event); }
        });
        startServer(hub.get());
    }

    private void startServer(LiveEventHub activeHub) throws Exception {
        V2ApiHandler v2 = new V2ApiHandler(journal, activeHub, mapper, TOKEN, null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", v2);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) server.stop(0);
        if (journal != null) journal.close();
        if (root != null) delete(root);
    }

    // ---- contract cases ----

    @Test
    public void replaysAfterLastEventId() throws Exception {
        append("run.status.changed");
        long first = lastCursor();
        append("run.phase.changed");
        append("run.terminal");

        HttpURLConnection connection = connect("/api/v2/events?afterCursor=" + first);
        List<String> ids = new ArrayList<String>();
        List<String> types = new ArrayList<String>();
        int dataSeen = 0;
        for (String line : readLines(connection.getInputStream(), 20_000L)) {
            if (line.startsWith("id: ")) ids.add(line.substring(4));
            if (line.startsWith("event: ")) types.add(line.substring("event: ".length()));
            if (line.startsWith("data: ") && ++dataSeen >= 2) break;
        }
        Assert.assertEquals(2, ids.size());
        Assert.assertEquals(java.util.Arrays.asList("run.phase.changed", "run.terminal"), types);
        Assert.assertTrue(Long.parseLong(ids.get(1)) > Long.parseLong(ids.get(0)));
        Assert.assertTrue(Long.parseLong(ids.get(0)) > first);
        connection.disconnect();
    }

    @Test
    public void noGapBetweenBacklogAndLiveSubscription() throws Exception {
        appendScoped("run.started", "session-a");
        appendScoped("run.status.changed", "session-a");
        // Raw-socket client: see exactly what the wire delivers.
        // Raw-socket client: see exactly what the wire delivers.
        java.net.Socket raw = new java.net.Socket("127.0.0.1", server.getAddress().getPort());
        raw.setSoTimeout(25_000);
        java.io.OutputStream request = raw.getOutputStream();
        request.write(("GET /api/v2/events?sessionId=session-a HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN + "\r\n"
                + "Accept: text/event-stream\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        request.flush();
        // The stream is established; publishing now overlaps the backlog/live
        // handoff exactly where an unguarded implementation would lose events.
        appendScoped("run.terminal", "session-a");
        appendScoped("run.phase.changed", "session-b"); // must stay filtered out
        List<String> types = new ArrayList<String>();
        BufferedReader wireReader = new BufferedReader(
                new InputStreamReader(raw.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 12_000L;
        boolean headerDone = false;
        while (System.currentTimeMillis() < deadline) {
            String line = wireReader.readLine();
            if (line == null) break;
            if (!headerDone) { if (line.isEmpty()) headerDone = true; continue; }
            if (line.startsWith("event: ")) types.add(line.substring("event: ".length()));
            if (types.size() >= 3) break;
        }
        raw.close();
        Assert.assertEquals(java.util.Arrays.asList(
                "run.started", "run.status.changed", "run.terminal"), types);
    }

    @Test
    public void slowClientIsDisconnectedWithoutUnboundedMemory() throws Exception {
        server.stop(0);
        LiveEventHub tiny = new LiveEventHub(16);
        hub.set(tiny);
        startServer(tiny);

        HttpURLConnection connection = connect("/api/v2/events");
        // The client never reads; once its socket buffer fills, handler writes
        // block, the 16-slot queue overflows, and the stream must end with an
        // explicit overflow marker instead of buffering without bound.
        for (int i = 0; i < 4000; i++) appendScoped("tool.started", "session-slow");
        boolean overflow = false;
        for (String line : readLines(connection.getInputStream(), 60_000L)) {
            if (line.startsWith(": overflow")) { overflow = true; break; }
        }
        Assert.assertTrue("expected an overflow marker in the stream", overflow);
        connection.disconnect();
    }

    @Test
    public void expiredCursorReturnsTyped409() throws Exception {
        append("run.started");
        append("run.terminal");
        long highWater = lastCursor();
        try (ControlPlaneDatabase database = new ControlPlaneDatabase(root)) {
            Statement statement = database.connection().createStatement();
            try { statement.execute("DELETE FROM event_journal WHERE cursor <= " + (highWater - 1)); }
            finally { statement.close(); }
        }
        HttpURLConnection connection = open("/api/v2/events?afterCursor=0");
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        Assert.assertEquals(409, connection.getResponseCode());
        JsonNode problem = mapper.readTree(connection.getErrorStream());
        Assert.assertEquals("EVENT_CURSOR_EXPIRED", problem.path("code").asText());
        Assert.assertEquals(highWater, problem.path("snapshotCursor").asLong());
        connection.disconnect();
    }

    @Test
    public void negativeLegacyCursorIsRejectedInsteadOfBeingClamped() throws Exception {
        HttpURLConnection connection = open("/api/v2/events?afterCursor=-1");
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        Assert.assertEquals(400, connection.getResponseCode());
        JsonNode problem = mapper.readTree(connection.getErrorStream());
        Assert.assertEquals("INVALID_REQUEST", problem.path("code").asText());
        connection.disconnect();
    }

    @Test
    public void heartbeatDoesNotAdvanceDurableCursor() throws Exception {
        HttpURLConnection connection = connect("/api/v2/events");
        boolean sawHeartbeat = false;
        for (String line : readLines(connection.getInputStream(), 25_000L)) {
            if (line.startsWith(": heartbeat")) { sawHeartbeat = true; break; }
        }
        Assert.assertTrue("expected a comment heartbeat within the idle window", sawHeartbeat);
        EventQueryResponse replay = journal.readAfter(0L, 10);
        Assert.assertTrue(replay.getEvents().isEmpty());
        Assert.assertEquals(0L, replay.getHighWaterMark());
        connection.disconnect();
    }

    @Test
    public void oneTimeStreamTicketAuthenticatesSingleConnection() throws Exception {
        HttpURLConnection ticketCall = open("/api/v2/stream-tickets", "POST");
        ticketCall.setRequestProperty("Authorization", "Bearer " + TOKEN);
        ticketCall.setDoOutput(true);
        ticketCall.getOutputStream().close();
        Assert.assertEquals(201, ticketCall.getResponseCode());
        JsonNode body = mapper.readTree(ticketCall.getInputStream());
        String ticket = body.path("ticket").asText();
        Assert.assertFalse(ticket.isEmpty());
        ticketCall.disconnect();

        HttpURLConnection first = open("/api/v2/events?ticket=" + ticket);
        Assert.assertEquals(200, first.getResponseCode());
        Assert.assertEquals("text/event-stream; charset=utf-8",
                first.getHeaderField("Content-Type"));
        first.getInputStream().close();
        first.disconnect();

        HttpURLConnection reused = open("/api/v2/events?ticket=" + ticket);
        Assert.assertEquals(401, reused.getResponseCode());
        reused.disconnect();
    }

    @Test
    public void subscriptionLimitIsReturnedBeforeSseHeadersAreCommitted() throws Exception {
        server.stop(0);
        LiveEventHub limited = new LiveEventHub(16, 1);
        hub.set(limited);
        startServer(limited);

        HttpURLConnection first = connect("/api/v2/events");
        HttpURLConnection rejected = open("/api/v2/events");
        rejected.setRequestProperty("Authorization", "Bearer " + TOKEN);
        Assert.assertEquals(429, rejected.getResponseCode());
        JsonNode problem = mapper.readTree(rejected.getErrorStream());
        Assert.assertEquals("EVENT_SUBSCRIPTION_LIMIT_REACHED", problem.path("code").asText());
        Assert.assertEquals(429, problem.path("status").asInt());
        Assert.assertEquals("1", rejected.getHeaderField("Retry-After"));
        rejected.disconnect();

        first.getInputStream().close();
        first.disconnect();
        // HttpServer observes a remote close on the next write; drive that
        // path so the test verifies the handler's finally-based release.
        append("run.status.changed");
        // The server discovers a passive client close on the next heartbeat
        // write, whose interval is intentionally 15 seconds.
        waitForActiveCount(limited, 0L, 20_000L);
    }

    // ---- helpers ----

    /** Appends one minimal durable RUN event through the idempotent publisher. */
    private void append(String type) {
        appendScoped(type, null);
    }

    private void appendScoped(String type, String sessionId) {
        BusinessEventEnvelope event = new BusinessEventEnvelope();
        event.setEventId("evt-test-" + type + "-" + java.util.UUID.randomUUID());
        event.setStream(new BusinessEventStream("RUN", "stream-run", 0L));
        event.setAggregate(new EventAggregateRef("RUN", "stream-run", 0L));
        event.setSessionId(sessionId);
        event.setRunId("stream-run");
        event.setType(type);
        event.setStatus("RUNNING");
        event.setDelivery(EventDelivery.DURABLE);
        event.setOccurredAt(java.time.Instant.now().toString());
        event.setPayload(new java.util.LinkedHashMap<String, Object>()); // per-type allowlist
        event.setRedactionClass(EventRedactionClass.PUBLIC_SAFE);
        publisher.publishDurable(event); // the journal validates the payload allowlist
    }

    private long lastCursor() {
        return journal.readAfter(0L, 1000).getNextCursor();
    }

    private HttpURLConnection connect(String pathAndQuery) throws Exception {
        HttpURLConnection connection = open(pathAndQuery);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        Assert.assertEquals(200, connection.getResponseCode());
        Assert.assertEquals("text/event-stream; charset=utf-8",
                connection.getHeaderField("Content-Type"));
        return connection;
    }

    private HttpURLConnection open(String pathAndQuery) throws Exception {
        return open(pathAndQuery, "GET");
    }

    private HttpURLConnection open(String pathAndQuery, String method) throws Exception {
        URL url = new URL("http://127.0.0.1:" + server.getAddress().getPort() + pathAndQuery);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setReadTimeout(70_000);
        connection.setConnectTimeout(5_000);
        return connection;
    }

    /** Reads raw SSE lines until the deadline; the caller breaks on its markers. */
    private static List<String> readLines(InputStream in, long timeoutMillis) throws Exception {
        List<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + timeoutMillis;
        reader.mark(2048);
        try {
            while (System.currentTimeMillis() < deadline) {
                String line = reader.readLine();
                if (line == null) break;
                lines.add(line);
                reader.mark(2048);
            }
        } catch (java.io.IOException ended) {
            // Stream closed by the peer; the collected lines are the answer.
        }
        return lines;
    }

    private static void waitForActiveCount(LiveEventHub hub, long expected, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (hub.activeCount() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        Assert.assertEquals(expected, hub.activeCount());
    }

    private static void delete(Path path) throws Exception {
        if (path == null || !Files.exists(path)) return;
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach(child -> {
            try { child.toFile().setWritable(true); Files.deleteIfExists(child); }
            catch (Exception ignored) { }
        });
    }
}
