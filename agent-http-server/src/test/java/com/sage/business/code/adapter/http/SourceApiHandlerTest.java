package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.source.ReadOnlySourceService;
import com.sage.business.code.application.source.ReadOnlySourceSettings;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * EW10.2 HTTP wiring for POST /api/v2/sources/fetch: bearer + Idempotency-Key
 * enforcement, problem mapping and the readOnlySources fail-closed gate, all
 * served through the same dispatcher the launcher wires.
 */
public class SourceApiHandlerTest {
    private static final String TOKEN = "source-api-contract-bearer";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private String base;

    /** Fake transport: no network; one scripted public-host outcome. */
    private static final class StubTransport implements ReadOnlySourceService.Transport {
        @Override public java.net.InetAddress[] resolve(String host) throws java.io.IOException {
            if ("docs.example.com".equals(host)) {
                return new java.net.InetAddress[] {
                        java.net.InetAddress.getByAddress(host, new byte[] { 93, (byte) 184, (byte) 216, 34 }) };
            }
            throw new java.net.UnknownHostException(host);
        }

        @Override public ReadOnlySourceService.Outcome get(java.net.URI uri, int connectTimeoutMillis,
                int readTimeoutMillis, int maxBytes) {
            return new ReadOnlySourceService.Outcome(200, "text/markdown",
                    null, "# doc".getBytes(StandardCharsets.UTF_8), false);
        }
    }

    private static ReadOnlySourceSettings settings(boolean enabled) {
        Properties properties = new Properties();
        properties.setProperty("source.readonly.enabled", Boolean.toString(enabled));
        return ReadOnlySourceSettings.fromProperties(properties);
    }

    @Before public void setUp() throws Exception {
        start(settings(true));
    }

    private void start(ReadOnlySourceSettings settings) throws Exception {
        List<V2RouteGroup> groups = Arrays.<V2RouteGroup>asList(
                new SourceApiHandler(mapper, TOKEN, new ReadOnlySourceService(settings, new StubTransport())));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper, groups));
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void tearDown() {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    private JsonNode fetch(String token, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/v2/sources/fetch")
                .openConnection();
        connection.setRequestMethod("POST");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        connection.setDoOutput(true);
        if (body != null) {
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = connection.getResponseCode();
        InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[4096]; int read;
            while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            stream.close();
        }
        connection.disconnect();
        return mapper.readTree(buffer.toString("UTF-8"));
    }

    private int statusOf(String token, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/v2/sources/fetch")
                .openConnection();
        connection.setRequestMethod("POST");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        connection.setDoOutput(true);
        if (body != null) {
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private static String body(String sourceType, String uri) {
        return "{\"sourceType\":\"" + sourceType + "\",\"uri\":\"" + uri + "\"}";
    }

    @Test public void fetchServesProvenanceWithUntrustedMarker() throws Exception {
        assertEquals(200, statusOf(TOKEN, "key-source-1",
                body("DOCS", "https://docs.example.com/guide")));
        JsonNode response = fetch(TOKEN, "key-source-1",
                body("DOCS", "https://docs.example.com/guide"));
        assertEquals(Boolean.TRUE, response.path("untrustedContent").asBoolean());
        assertEquals("DOCS", response.path("sourceType").asText());
        assertEquals("https://docs.example.com/guide", response.path("sourceUri").asText());
        assertEquals("text/markdown", response.path("contentType").asText());
        assertEquals("# doc", response.path("contentText").asText());
        assertEquals(0, response.path("redirectCount").asInt());
    }

    @Test public void authAndIdempotencyKeyAreEnforced() throws Exception {
        assertEquals(401, statusOf(null, "key-source-2", body("WEB", "https://docs.example.com/a")));
        assertEquals(400, statusOf(TOKEN, null, body("WEB", "https://docs.example.com/a")));
    }

    @Test public void invalidTypeMapsToProblem415() throws Exception {
        assertEquals(415, statusOf(TOKEN, "key-source-3", body("GITHUB", "https://docs.example.com/a")));
        JsonNode problem = fetch(TOKEN, "key-source-4", body("GITHUB", "https://docs.example.com/a"));
        assertEquals("SOURCE_TYPE_FORBIDDEN", problem.path("code").asText());
        assertEquals(415, problem.path("status").asInt());
    }

    @Test public void loopbackTargetIsForbiddenAtTheBoundary() throws Exception {
        assertEquals(403, statusOf(TOKEN, "key-source-5", body("WEB", "http://127.0.0.1/x")));
        JsonNode problem = fetch(TOKEN, "key-source-6", body("WEB", "http://127.0.0.1/x"));
        assertEquals("SOURCE_URI_FORBIDDEN", problem.path("code").asText());
    }

    @Test public void getIsMethodNotAllowed() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/v2/sources/fetch")
                .openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        assertEquals(405, connection.getResponseCode());
        connection.disconnect();
    }

    @Test public void disabledServiceFailsClosedWithProblem() throws Exception {
        server.stop(0);
        httpExecutor.shutdownNow();
        start(settings(false));
        assertEquals(503, statusOf(TOKEN, "key-source-7", body("WEB", "https://docs.example.com/a")));
        JsonNode problem = fetch(TOKEN, "key-source-8", body("WEB", "https://docs.example.com/a"));
        assertEquals("FEATURE_DISABLED", problem.path("code").asText());
        assertEquals(503, problem.path("status").asInt());
        assertTrue(problem.path("message").asText().contains("not enabled"));
    }
}
