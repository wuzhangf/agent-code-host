package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.hook.HookSettings;
import com.sage.business.code.application.hook.HookService;
import com.sage.business.code.application.skill.SkillCatalogService;
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
 * EW10.5/EW10.6 HTTP wiring for the skill catalog and typed hook routes:
 * bearer + Idempotency-Key enforcement, problem mapping and the
 * skillsCatalog / hooks fail-closed gates.
 */
public class ExtensionApiHandlerTest {
    private static final String TOKEN = "extension-api-contract-bearer";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private String base;

    @Before public void setUp() throws Exception {
        start(true, true);
    }

    private void start(boolean skillsEnabled, boolean hooksEnabled) throws Exception {
        Properties hookProperties = new Properties();
        if (hooksEnabled) hookProperties.setProperty("hook.enabled", "true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        SkillCatalogService skills = new SkillCatalogService(
                new com.sage.business.code.infrastructure.AtomicJsonStore(
                        java.nio.file.Files.createTempDirectory("skill-api"), mapper), null,
                skillsEnabled);
        HookService hooks = new HookService(HookSettings.fromProperties(hookProperties), mapper);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper, java.util.Arrays.<V2RouteGroup>asList(
                new SkillApiHandler(mapper, TOKEN, skills),
                new HookApiHandler(mapper, TOKEN, hooks))));
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
        return mapper.readTree(buffer.toString("UTF-8"));
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

    private static final String REGISTER_BODY =
            "{\"name\":\"error-codes\",\"version\":\"1.0.0\",\"source\":\"user\","
            + "\"scope\":\"PROJECT\",\"projectAlias\":\"sample\","
            + "\"instructions\":\"Map error codes first.\",\"permissions\":[\"CONTEXT_INJECTION\"]}";

    @Test public void skillRegisterListAndStatusWork() throws Exception {
        assertEquals(201, statusOf("POST", "/api/v2/skills", TOKEN, "key-skill-1", REGISTER_BODY));
        JsonNode created = call("POST", "/api/v2/skills", TOKEN, "key-skill-1", REGISTER_BODY);
        String skillId = created.path("skillId").asText();
        assertTrue(skillId.startsWith("skill-"));
        assertEquals("ACTIVE", created.path("status").asText());

        assertEquals(200, statusOf("GET", "/api/v2/skills", TOKEN, null, null));
        JsonNode list = call("GET", "/api/v2/skills", TOKEN, null, null);
        assertEquals(1, list.size());

        assertEquals(200, statusOf("POST", "/api/v2/skills/" + skillId + "/status",
                TOKEN, "key-skill-2",
                "{\"status\":\"DISABLED\",\"expectedRevision\":1}"));
        JsonNode disabled = call("POST", "/api/v2/skills/" + skillId + "/status",
                TOKEN, "key-skill-2", "{\"status\":\"DISABLED\",\"expectedRevision\":1}");
        assertEquals("DISABLED", disabled.path("status").asText());
        assertEquals(2, disabled.path("revision").asLong());

        // Stale revision → typed conflict.
        assertEquals(409, statusOf("POST", "/api/v2/skills/" + skillId + "/status",
                TOKEN, "key-skill-3", "{\"status\":\"ACTIVE\",\"expectedRevision\":1}"));
        JsonNode conflict = call("POST", "/api/v2/skills/" + skillId + "/status",
                TOKEN, "key-skill-3", "{\"status\":\"ACTIVE\",\"expectedRevision\":1}");
        assertEquals("SKILL_REVISION_CONFLICT", conflict.path("code").asText());
    }

    @Test public void skillAuthAndIdempotencyAreEnforced() throws Exception {
        assertEquals(401, statusOf("POST", "/api/v2/skills", null, "key-skill-4", REGISTER_BODY));
        assertEquals(400, statusOf("POST", "/api/v2/skills", TOKEN, null, REGISTER_BODY));
        assertEquals(405, statusOf("DELETE", "/api/v2/skills", TOKEN, "key-skill-5", null));
    }

    @Test public void hooksRoutesServeHealthProjections() throws Exception {
        assertEquals(200, statusOf("GET", "/api/v2/hooks", TOKEN, null, null));
        JsonNode hooks = call("GET", "/api/v2/hooks", TOKEN, null, null);
        assertTrue(hooks.isArray());
        assertEquals(0, hooks.size());
        assertEquals(200, statusOf("GET", "/api/v2/hooks/executions", TOKEN, null, null));
        JsonNode executions = call("GET", "/api/v2/hooks/executions", TOKEN, null, null);
        assertEquals(Boolean.FALSE, executions.path("truncated").asBoolean());
    }

    @Test public void disabledFlagsFailClosedWithProblem() throws Exception {
        server.stop(0);
        httpExecutor.shutdownNow();
        start(false, false);
        assertEquals(503, statusOf("POST", "/api/v2/skills", TOKEN, "key-skill-6", REGISTER_BODY));
        JsonNode skillProblem = call("POST", "/api/v2/skills", TOKEN, "key-skill-6", REGISTER_BODY);
        assertEquals("FEATURE_DISABLED", skillProblem.path("code").asText());
        assertEquals(503, skillProblem.path("status").asInt());
        assertEquals(503, statusOf("GET", "/api/v2/hooks", TOKEN, null, null));
        JsonNode hookProblem = call("GET", "/api/v2/hooks", TOKEN, null, null);
        assertEquals("FEATURE_DISABLED", hookProblem.path("code").asText());
    }
}
