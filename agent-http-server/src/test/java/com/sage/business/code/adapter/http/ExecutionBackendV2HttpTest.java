package com.sage.business.code.adapter.http;

import com.agent.product.code.sandbox.BackendAvailability;
import com.agent.product.code.sandbox.ExecutionBackendCatalogSnapshot;
import com.agent.product.code.sandbox.ExecutionBackendDescriptor;
import com.agent.product.code.sandbox.ExecutionEnvironmentInspectRequest;
import com.agent.product.code.sandbox.ExecutionEnvironmentInspectResult;
import com.agent.product.code.sandbox.IsolationLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.adapter.product.SandboxExecutionSettings;
import com.sage.business.code.application.ExecutionBackendDiscoveryCoordinator;
import com.sage.business.code.application.ExecutionEnvironmentService;
import com.sage.business.code.application.SandboxApprovalCoordinator;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for the v2 backend status/refresh contract. */
public class ExecutionBackendV2HttpTest {
    private static final String TOKEN = "token-0123456789abcdef";

    @Rule public final TemporaryFolder state = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();
    private BusinessHttpServer server;
    private String baseUrl;

    @Before public void setUp() throws Exception {
        SandboxExecutionSettings settings = SandboxExecutionSettings.builder()
                .mode(SandboxExecutionSettings.MODE_AUTO)
                .imageReference("registry.example/img@sha256:" + hex64())
                .discoveryTtlMillis(600000L)
                .build();
        ExecutionBackendDiscoveryCoordinator discovery =
                new ExecutionBackendDiscoveryCoordinator(settings, new ExecutionBackendDiscoveryCoordinator.Probe() {
                    @Override public ExecutionEnvironmentInspectResult call() {
                        long now = System.currentTimeMillis();
                        ExecutionBackendDescriptor backend = new ExecutionBackendDescriptor(
                                "docker", "OCI_DOCKER", BackendAvailability.READY,
                                IsolationLevel.CONTAINER_RESTRICTED, "27.0", null, null,
                                "default", null, null, null, "linux", "amd64",
                                settings.getImageReference(), "sha256:" + hex64(), "fp",
                                null, null, now, now + 600000L);
                        ExecutionBackendCatalogSnapshot catalog =
                                new ExecutionBackendCatalogSnapshot(now, now + 600000L,
                                        Collections.singletonList(backend));
                        return ExecutionEnvironmentInspectResult.ofCatalog(catalog,
                                ExecutionEnvironmentInspectRequest.builder()
                                        .imageReference(settings.getImageReference())
                                        .stateRoot(state.getRoot()).build(), 1L);
                    }
                });
        ExecutionEnvironmentService environment = new ExecutionEnvironmentService(
                settings, discovery,
                new SandboxApprovalCoordinator(
                        new AtomicJsonStore(state.getRoot().toPath(), mapper), mapper));
        V2ApiDispatcher dispatcher = new V2ApiDispatcher(mapper,
                Collections.<V2RouteGroup>singletonList(
                        new ExecutionBackendApiHandler(mapper, TOKEN, environment)));
        server = new BusinessHttpServer(new InetSocketAddress("127.0.0.1", 0),
                TOKEN, null, mapper, null, null, dispatcher, environment);
        server.start();
        java.lang.reflect.Field field = BusinessHttpServer.class.getDeclaredField("server");
        field.setAccessible(true);
        com.sun.net.httpserver.HttpServer httpServer =
                (com.sun.net.httpserver.HttpServer) field.get(server);
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    @After public void tearDown() {
        if (server != null) server.close();
    }

    @Test public void readsCachedStateAndRefreshesThroughV2() throws Exception {
        Response before = exchange("GET", "/api/v2/execution-backends", null, null);
        assertEquals(200, before.status);
        assertEquals("NEVER_PROBED", mapper.readTree(before.body)
                .path("reasonCode").asText());

        Response refresh = exchange("POST", "/api/v2/execution-backends/refresh",
                "", "refresh-1");
        assertEquals(202, refresh.status);
        JsonNode body = mapper.readTree(refresh.body);
        assertEquals(1L, body.path("generation").asLong());
        assertEquals("READY", body.path("backends").get(0)
                .path("availability").asText());

        Response cached = exchange("GET", "/api/v2/execution-backends", null, null,
                body.path("catalogFingerprint").asText());
        assertEquals(304, cached.status);
    }

    @Test public void refreshRequiresIdempotencyKeyAndBearer() throws Exception {
        Response missingKey = exchange("POST", "/api/v2/execution-backends/refresh", "", null);
        assertEquals(400, missingKey.status);

        HttpURLConnection connection = (HttpURLConnection) new URL(
                baseUrl + "/api/v2/execution-backends").openConnection();
        assertEquals(401, connection.getResponseCode());
        connection.disconnect();
    }

    private Response exchange(String method, String path, String body,
            String idempotencyKey) throws Exception {
        return exchange(method, path, body, idempotencyKey, null);
    }

    private Response exchange(String method, String path, String body,
            String idempotencyKey, String ifNoneMatch) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path)
                .openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (idempotencyKey != null) {
            connection.setRequestProperty("Idempotency-Key", idempotencyKey);
        }
        if (ifNoneMatch != null) {
            connection.setRequestProperty("If-None-Match", ifNoneMatch);
        }
        connection.setDoOutput(body != null);
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json");
            OutputStream out = connection.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();
        }
        int status = connection.getResponseCode();
        InputStream in = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buffer = new byte[1024];
            for (int read; (read = in.read(buffer)) >= 0;) bytes.write(buffer, 0, read);
            in.close();
        }
        String responseBody = new String(bytes.toByteArray(), "UTF-8");
        connection.disconnect();
        return new Response(status, responseBody);
    }

    private static String hex64() {
        StringBuilder value = new StringBuilder(64);
        for (int i = 0; i < 64; i++) value.append('a');
        return value.toString();
    }

    private static final class Response {
        final int status;
        final String body;
        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
