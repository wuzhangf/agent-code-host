package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/** Opt-in real model baseline through the existing ACS Product composition and thin binding. */
public class NativeFleetAcsModelIT extends FoundationProtocolAcceptanceTest {
    private HttpServer upstreamRelay;
    private ExecutorService upstreamPool;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<ObjectNode> facts = Collections.synchronizedList(new ArrayList<ObjectNode>());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    @Override @Before public void start() throws Exception {
        Assume.assumeTrue("Explicit live-model opt-in required", "1".equals(System.getenv("NWF_ACS_LIVE")));
        Assert.assertEquals("deepseek-v4.1-flash", System.getenv("MODEL_DEFAULT_MODEL"));
        Assert.assertEquals("https://opencode.ai/zen/go/v1", System.getenv("MODEL_BASE_URL"));
        Assert.assertNotNull(System.getenv("MODEL_API_KEY"));
        super.start();
    }
    @Override protected Path createFixtureRoot() throws Exception {
        Path runs = Paths.get(".local", "nwf-001"); Files.createDirectories(runs);
        return Files.createTempDirectory(runs, "acs-live-").toAbsolutePath();
    }
    @Override protected boolean deleteFixtureRoot() { return false; }

    @Override protected void configureCollaboration(ServiceConfig config) {
        try {
            upstreamRelay = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstreamPool = Executors.newCachedThreadPool(); upstreamRelay.setExecutor(upstreamPool);
            upstreamRelay.createContext("/v1/chat/completions", exchange -> {
                try {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    JsonNode request = mapper.readTree(bytes);
                    if (!System.getenv("MODEL_DEFAULT_MODEL").equals(request.path("model").asText()) || calls.incrementAndGet() > 12) {
                        exchange.sendResponseHeaders(409, -1); return;
                    }
                    HttpRequest.Builder outgoing = HttpRequest.newBuilder(URI.create(System.getenv("MODEL_BASE_URL") + "/chat/completions"))
                            .timeout(Duration.ofSeconds(90)).header("Authorization", "Bearer " + System.getenv("MODEL_API_KEY"))
                            .header("Content-Type", "application/json");
                    for (Map.Entry<String,String> variable : System.getenv().entrySet())
                        if (variable.getKey().startsWith("LLM_HEADER_")) outgoing.header(variable.getKey().substring(11).replace('_', '-'), variable.getValue());
                    HttpResponse<byte[]> response = http.send(outgoing.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofByteArray());
                    facts.add(mapper.createObjectNode().put("model", request.path("model").asText()).put("httpStatus", response.statusCode())
                            .put("requestSha256", com.sage.business.code.infrastructure.Hashing.sha256(new String(bytes, StandardCharsets.UTF_8)))
                            .put("responseSha256", com.sage.business.code.infrastructure.Hashing.sha256(new String(response.body(), StandardCharsets.UTF_8))));
                    exchange.getResponseHeaders().set("Content-Type", response.headers().firstValue("content-type").orElse("application/json"));
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length); exchange.getResponseBody().write(response.body());
                } catch (Exception unavailable) { exchange.sendResponseHeaders(502, -1); }
                finally { exchange.close(); }
            });
            upstreamRelay.start();
            Properties catalog = new Properties(); catalog.setProperty("PROVIDER_1_ID", "nwf-live");
            catalog.setProperty("PROVIDER_1_DIALECT", "OPENAI_CHAT_COMPLETIONS");
            catalog.setProperty("PROVIDER_1_BASE_URL", "http://127.0.0.1:" + upstreamRelay.getAddress().getPort() + "/v1");
            catalog.setProperty("PROVIDER_1_API_KEY_ENV", "MODEL_API_KEY");
            catalog.setProperty("PROVIDER_1_MODEL_1_ID", System.getenv("MODEL_DEFAULT_MODEL"));
            catalog.setProperty("PROVIDER_1_MODEL_1_CAPABILITIES", "NATIVE_TOOL_CALLING");
            catalog.setProperty("PROVIDER_1_MODEL_1_MAX_OUTPUT_TOKENS", "2048");
            catalog.setProperty("MODEL_DEFAULT_MODEL", System.getenv("MODEL_DEFAULT_MODEL")); config.setCatalogFileProperties(catalog);
        } catch (Exception failure) { throw new IllegalStateException("NWF_LIVE_CONFIGURATION_FAILED", failure); }
    }

    @Override @Test @Ignore("Pause/restart baseline is independently covered by FoundationProtocolAcceptanceTest")
    public void twoClientsObserveFreezePauseResumeAndStopActualProduct() { }

    @Override @Test public void ordinaryHostSessionKeepsMultiTurnAndPersistedAssistantBodiesAcrossClients() throws Exception {
        String marker = "NWF_ACS_" + UUID.randomUUID().toString().replace("-", "");
        JsonNode manifest = api("GET", "/worker-host/v1/manifest", null, a, null, 200);
        Assert.assertEquals("acs", manifest.path("engine").path("id").asText());
        ObjectNode request = mapper.createObjectNode().put("workspaceAlias", "sample").put("interactionMode", "ACT").put("intent", "EXPLAIN")
                .put("executionMode", "INSPECT").put("autonomyLevel", "OFF").put("verificationLevel", "NONE").put("initialWorkspaceListing", false).put("maxModelTurns", 5);
        JsonNode created = api("POST", "/worker-host/v1/sessions", "nwf-acs-create", a, request, 201);
        String sid = created.path("sessionId").asText();
        Assert.assertEquals(sid, api("POST", "/api/v2/sessions", "nwf-acs-create", b, request, 201).path("sessionId").asText());
        List<String> runs = new ArrayList<String>();
        for (int turn = 1; turn <= 2; turn++) {
            JsonNode current = api("GET", "/api/v2/sessions/" + sid, null, b, null, 200);
            ObjectNode message = mapper.createObjectNode().put("clientMessageId", "nwf-live-" + turn).put("expectedSessionVersion", current.path("sessionVersion").asLong())
                    .put("content", "Reply exactly " + marker + ". Do not call tools or modify files."); message.putArray("attachments");
            JsonNode accepted = api("POST", "/worker-host/v1/sessions/" + sid + "/messages", "nwf-live-" + turn, a, message, 202);
            String runId = accepted.path("runId").asText(); Assert.assertFalse(runId.isEmpty()); runs.add(runId);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            boolean succeeded = false;
            while (System.nanoTime() < deadline) {
                JsonNode run = api("GET", "/worker-host/v1/runs/" + runId, null, b, null, 200);
                if ("SUCCEEDED".equals(run.path("sourceStatus").asText())) { succeeded = true; break; }
                Assert.assertFalse("Real ACS model execution failed", Arrays.asList("FAILED", "CANCELLED").contains(run.path("sourceStatus").asText()));
                Thread.sleep(200);
            }
            Assert.assertTrue("Real ACS run deadline exceeded", succeeded);
        }
        JsonNode history = api("GET", "/worker-host/v1/sessions/" + sid + "/messages?limit=30", null, b, null, 200);
        Assert.assertTrue(history.toString().contains(marker));
        Assert.assertEquals(history, api("GET", "/api/v2/sessions/" + sid + "/messages?limit=30", null, a, null, 200));
        Assert.assertTrue(calls.get() >= 2);
        ObjectNode evidence = mapper.createObjectNode().put("result", "PASS_SUBSET").put("model", System.getenv("MODEL_DEFAULT_MODEL"))
                .put("endpoint", System.getenv("MODEL_BASE_URL")).put("sessionId", sid).put("storeId", manifest.path("storeId").asText())
                .put("twoOriginalSourceRuns", runs.size()).put("calls", calls.get()).put("source", "REAL_ACS_PRODUCT_HTTP_ADAPTER");
        evidence.set("modelFacts", mapper.valueToTree(facts)); evidence.set("runIds", mapper.valueToTree(runs));
        Files.write(root.resolve("evidence.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
    }

    @Override @After public void close() throws Exception {
        try { super.close(); } finally { if (upstreamRelay != null) upstreamRelay.stop(0); if (upstreamPool != null) upstreamPool.shutdownNow(); }
    }
}
