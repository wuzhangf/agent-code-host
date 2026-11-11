package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.mcp.McpServerSettings;
import com.sage.business.code.application.mcp.McpSourceService;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * EW10.3 HTTP wiring for the read-only MCP source routes: bearer +
 * Idempotency-Key enforcement, problem mapping and the mcpReadOnlySource
 * fail-closed gate, served through the same dispatcher the launcher wires.
 */
public class McpApiHandlerTest {
    private static final String TOKEN = "mcp-api-contract-bearer";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private String base;

    /** Hermetic fake session: READY status, one listed resource, one text read. */
    private static final class StubSession implements McpSourceService.Session {
        @Override public void start() { }

        @Override public boolean isAlive() { return true; }

        @Override public com.fasterxml.jackson.databind.JsonNode call(String method,
                com.fasterxml.jackson.databind.node.ObjectNode params) {
            ObjectMapper om = new ObjectMapper();
            if ("resources/list".equals(method)) {
                com.fasterxml.jackson.databind.node.ArrayNode resources = om.createArrayNode();
                resources.addObject().put("uri", "file:///docs/a.md").put("mimeType", "text/markdown");
                return om.createObjectNode().set("resources", resources);
            }
            if ("resources/read".equals(method)) {
                com.fasterxml.jackson.databind.node.ArrayNode contents = om.createArrayNode();
                contents.addObject().put("uri", params.path("uri").asText())
                        .put("mimeType", "text/markdown").put("text", "# guide");
                return om.createObjectNode().set("contents", contents);
            }
            throw new IllegalStateException("unexpected " + method);
        }

        @Override public void close() { }
    }

    private static McpSourceService service(boolean enabled) {
        Properties properties = new Properties();
        if (enabled) properties.setProperty("mcp.readonly.enabled", "true");
        properties.setProperty("mcp.server.docs.command", "java");
        ObjectMapper om = new ObjectMapper();
        return new McpSourceService(McpServerSettings.fromProperties(properties), om,
                new McpSourceService.SessionFactory() {
                    @Override public McpSourceService.Session create(McpServerSettings.ServerEntry entry) {
                        return new StubSession();
                    }
                });
    }

    @Before public void setUp() throws Exception {
        start(true);
    }

    private void start(boolean enabled) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper, java.util.Arrays.<V2RouteGroup>asList(
                new McpApiHandler(mapper, TOKEN, service(enabled)))));
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void tearDown() {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    private JsonNode call(String method, String path, String token, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
            connection.setDoOutput(true);
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
        JsonNode json = mapper.readTree(buffer.toString("UTF-8"));
        // The status code is asserted separately; return the parsed body.
        return json;
    }

    private int statusOf(String method, String path, String token, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
            connection.setDoOutput(true);
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    @Test public void serversAndResourcesAndReadAreServed() throws Exception {
        assertEquals(200, statusOf("GET", "/api/v2/mcp/servers", TOKEN, null, null));
        JsonNode servers = call("GET", "/api/v2/mcp/servers", TOKEN, null, null);
        assertTrue(servers.isArray());
        assertEquals("docs", servers.get(0).path("serverId").asText());
        assertEquals("READY", servers.get(0).path("status").asText());

        assertEquals(200, statusOf("GET", "/api/v2/mcp/servers/docs/resources", TOKEN, null, null));
        JsonNode resources = call("GET", "/api/v2/mcp/servers/docs/resources", TOKEN, null, null);
        assertEquals("file:///docs/a.md", resources.get(0).path("uri").asText());

        assertEquals(200, statusOf("POST", "/api/v2/mcp/servers/docs/resources/read",
                TOKEN, "key-mcp-1", "{\"uri\":\"file:///docs/a.md\"}"));
        JsonNode read = call("POST", "/api/v2/mcp/servers/docs/resources/read",
                TOKEN, "key-mcp-1", "{\"uri\":\"file:///docs/a.md\"}");
        assertEquals(Boolean.TRUE, read.path("untrustedContent").asBoolean());
        assertEquals("docs", read.path("serverId").asText());
        assertEquals("# guide", read.path("contentText").asText());
    }

    @Test public void authIdempotencyAndMethodAreEnforced() throws Exception {
        assertEquals(401, statusOf("GET", "/api/v2/mcp/servers", null, null, null));
        assertEquals(400, statusOf("POST", "/api/v2/mcp/servers/docs/resources/read",
                TOKEN, null, "{\"uri\":\"file:///docs/a.md\"}"));
        assertEquals(405, statusOf("GET", "/api/v2/mcp/servers/docs/resources/read",
                TOKEN, "key-mcp-2", null));
    }

    @Test public void unknownServerMapsToProblem404() throws Exception {
        assertEquals(404, statusOf("GET", "/api/v2/mcp/servers/missing/resources", TOKEN, null, null));
        JsonNode problem = call("GET", "/api/v2/mcp/servers/missing/resources", TOKEN, null, null);
        assertEquals("MCP_SERVER_UNKNOWN", problem.path("code").asText());
    }

    @Test public void disabledFlagFailsClosedWithProblem() throws Exception {
        server.stop(0);
        httpExecutor.shutdownNow();
        start(false);
        assertEquals(503, statusOf("GET", "/api/v2/mcp/servers", TOKEN, null, null));
        JsonNode problem = call("GET", "/api/v2/mcp/servers", TOKEN, null, null);
        assertEquals("FEATURE_DISABLED", problem.path("code").asText());
        assertEquals(503, problem.path("status").asInt());
    }
}
