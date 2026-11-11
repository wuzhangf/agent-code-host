package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * WU01 G10 route-contract closure (2026-09-29 design section 10.1): the
 * public paths declared by the source OpenAPI — /api/v2/extension-test-
 * executions and /api/v2/executions/{executionId}/extension-invocations —
 * are now served by the real handlers, and the former private paths remain
 * recognized as deprecated aliases delegating to the same implementation.
 * Without an assembled plane both members answer 503 EXT_HOST_NOT_WIRED;
 * neither falls through to 404 anymore.
 */
public class McpWu00RouteContractReproTest {
    private static final String TOKEN = "wu00-route-bearer";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private String base;

    @Before public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Legacy read-only MCP surface + extension evidence routes with no
        // assembled plane: recognized-but-unwired paths answer 503
        // EXT_HOST_NOT_WIRED, unrecognized paths fall through to 404.
        Properties properties = new Properties();
        properties.setProperty("mcp.readonly.enabled", "true");
        properties.setProperty("mcp.server.docs.command", "java");
        // The read-only session factory is never invoked on the paths this
        // repro exercises; a throwing stub keeps the handler constructible.
        com.sage.business.code.application.mcp.McpSourceService.SessionFactory neverUsed =
                entry -> { throw new IllegalStateException("not exercised"); };
        McpApiHandler mcp = new McpApiHandler(mapper, TOKEN,
                new com.sage.business.code.application.mcp.McpSourceService(
                        com.sage.business.code.application.mcp.McpServerSettings
                                .fromProperties(properties),
                        mapper, neverUsed));
        ExtensionApiHandler extensions = new ExtensionApiHandler(mapper, TOKEN, null);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper,
                java.util.Arrays.<V2RouteGroup>asList(mcp, extensions)));
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void tearDown() {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    private int statusOf(String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (body != null) {
            connection.setRequestProperty("Idempotency-Key", "wu00-key");
            connection.setDoOutput(true);
            java.io.OutputStream out = connection.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        return status;
    }

    private JsonNode problemOf(String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (body != null) {
            connection.setRequestProperty("Idempotency-Key", "wu00-key");
            connection.setDoOutput(true);
            java.io.OutputStream out = connection.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = connection.getResponseCode();
        InputStream stream = status < 400 ? connection.getInputStream()
                : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            stream.close();
        }
        connection.disconnect();
        return mapper.readTree(buffer.toString("UTF-8"));
    }

    /** The source OpenAPI itself declares the two public paths. */
    @Test public void openapiDeclaresPublicExtensionPaths() throws Exception {
        InputStream yaml = getClass().getResourceAsStream(
                "/openapi/business-codeagent-v2.yaml");
        assertNotNull("source OpenAPI must be on the runtime test classpath", yaml);
        java.io.ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = yaml.read(chunk)) >= 0) buffer.write(chunk, 0, read);
        yaml.close();
        String text = buffer.toString("UTF-8");
        assertTrue("OpenAPI must declare /api/v2/extension-test-executions",
                text.contains("/api/v2/extension-test-executions:"));
        assertTrue("OpenAPI must declare /api/v2/executions/{executionId}/extension-invocations",
                text.contains("/api/v2/executions/{executionId}/extension-invocations:"));
    }

    /**
     * WU01: the OpenAPI-declared debug-execution path and the deprecated
     * private alias are BOTH served by the same handler seam — recognized
     * (503 EXT_HOST_NOT_WIRED without a plane), never a 404 fall-through.
     */
    @Test public void declaredTestExecutionPathIsServedLikeTheAlias() throws Exception {
        String body = "{\"extensions\":{\"mcpServers\":[{\"serverId\":\"s\","
                + "\"toolIds\":[\"t\"]}]}}";
        int declared = statusOf("POST", "/api/v2/extension-test-executions", body);
        int alias = statusOf("POST", "/api/v2/extensions/test", body);
        assertEquals("public path is recognized (503 unwired)", 503, declared);
        assertEquals("deprecated alias delegates to the same handler", 503, alias);
        assertEquals("EXT_HOST_NOT_WIRED", problemOf("POST",
                "/api/v2/extension-test-executions", body).path("code").asText());
        assertEquals("EXT_HOST_NOT_WIRED", problemOf("POST",
                "/api/v2/extensions/test", body).path("code").asText());
    }

    /**
     * WU01: the OpenAPI-declared invocation-evidence path and the deprecated
     * private alias are BOTH recognized (503 EXT_HOST_NOT_WIRED without a
     * plane); neither 404s.
     */
    @Test public void declaredInvocationListPathIsServedLikeTheAlias() throws Exception {
        int declared = statusOf("GET",
                "/api/v2/executions/exec-1/extension-invocations", null);
        int alias = statusOf("GET",
                "/api/v2/executions/exec-1/extensions/invocations", null);
        assertEquals("public path is recognized (503 unwired)", 503, declared);
        assertEquals("deprecated alias delegates to the same handler", 503, alias);
        assertEquals("EXT_HOST_NOT_WIRED", problemOf("GET",
                "/api/v2/executions/exec-1/extension-invocations", null)
                .path("code").asText());
        assertEquals("EXT_HOST_NOT_WIRED", problemOf("GET",
                "/api/v2/executions/exec-1/extensions/invocations", null)
                .path("code").asText());
    }
}