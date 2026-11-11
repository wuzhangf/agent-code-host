package com.sage.business.code.adapter.http;

import com.agent.product.code.sandbox.BackendAvailability;
import com.agent.product.code.sandbox.ExecutionBackendDescriptor;
import com.agent.product.code.sandbox.ExecutionBackendCatalogSnapshot;
import com.agent.product.code.sandbox.ExecutionEnvironmentInspectResult;
import com.agent.product.code.sandbox.IsolationLevel;
import com.agent.product.code.sandbox.SandboxCommandApprovalHandler;
import com.agent.product.code.sandbox.SandboxCommandApprovalRequest;
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
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Design 22.3: HTTP safe projection of the backend catalog, refresh
 * idempotency semantics, and the sandbox approval bridge endpoints.
 * The endpoints under test never delegate to TaskApplicationService, so a
 * null app keeps this test free of the git fixture machinery.
 */
public class ExecutionEnvironmentHttpTest {
    private static final String TOKEN = "token-0123456789abcdef";

    @Rule public final TemporaryFolder state = new TemporaryFolder();

    private BusinessHttpServer server;
    private ExecutorService httpExecutor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger probeCalls = new AtomicInteger();
    private SandboxApprovalCoordinator approvals;

    private String baseUrl;

    @Before public void setUp() throws Exception {
        SandboxExecutionSettings settings = SandboxExecutionSettings.builder()
                .mode(SandboxExecutionSettings.MODE_AUTO)
                .imageReference("registry.example/img@sha256:" + hex64())
                .discoveryTtlMillis(600000L)
                .build();
        approvals = new SandboxApprovalCoordinator(
                new AtomicJsonStore(state.getRoot().toPath(), mapper), mapper);
        ExecutionBackendDiscoveryCoordinator discovery =
                new ExecutionBackendDiscoveryCoordinator(
                        settings, probe(), new java.util.function.LongSupplier() {
                            public long getAsLong() { return System.currentTimeMillis(); }
                        });
        ExecutionEnvironmentService sandboxService =
                new ExecutionEnvironmentService(settings, discovery, approvals);
        server = new BusinessHttpServer(
                new java.net.InetSocketAddress("127.0.0.1", 0), TOKEN,
                null, mapper, null, null, null, sandboxService);
        server.start();
        baseUrl = "http://127.0.0.1:" + serverPort(server);
        httpExecutor = Executors.newCachedThreadPool();
    }

    @After public void tearDown() throws Exception {
        if (server != null) server.close();
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    private static int serverPort(BusinessHttpServer target) throws Exception {
        java.lang.reflect.Field field =
                BusinessHttpServer.class.getDeclaredField("server");
        field.setAccessible(true);
        return ((com.sun.net.httpserver.HttpServer) field.get(target))
                .getAddress().getPort();
    }

    /** Deterministic READY docker backend probe carrying unsafe host detail. */
    private ExecutionBackendDiscoveryCoordinator.Probe probe() {
        return new ExecutionBackendDiscoveryCoordinator.Probe() {
            public ExecutionEnvironmentInspectResult call() {
                probeCalls.incrementAndGet();
                long now = System.currentTimeMillis();
                ExecutionBackendDescriptor descriptor = new ExecutionBackendDescriptor(
                        "docker", "OCI_DOCKER", BackendAvailability.READY,
                        IsolationLevel.CONTAINER_RESTRICTED,
                        "27.0", null, null, "default",
                        "tcp://127.0.0.1:2375", "C:\\docker-socket", null,
                        "linux", "amd64",
                        "registry.example/img@sha256:" + hex64(),
                        "sha256:" + hex64(), "fp", null, null,
                        now, now + 600000L);
                ExecutionBackendCatalogSnapshot catalog =
                        new ExecutionBackendCatalogSnapshot(
                                now, now + 600000L,
                                Collections.singletonList(descriptor));
                return ExecutionEnvironmentInspectResult.ofCatalog(
                        catalog,
                        com.agent.product.code.sandbox.ExecutionEnvironmentInspectRequest
                                .builder()
                                .imageReference("registry.example/img@sha256:" + hex64())
                                .stateRoot(state.getRoot())
                                .build(),
                        12L);
            }
        };
    }

    private static String hex64() {
        StringBuilder out = new StringBuilder(64);
        for (int i = 0; i < 64; i++) out.append('a');
        return out.toString();
    }

    private static final class Response {
        final int status;
        final String etag;
        final String body;
        Response(int status, String etag, String body) {
            this.status = status; this.etag = etag; this.body = body;
        }
    }

    private Response exchange(String method, String path, String body,
            String idempotencyKey, String ifNoneMatch) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(baseUrl + path).openConnection();
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
        String etag = connection.getHeaderField("ETag");
        InputStream stream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] chunk = new byte[4096];
            for (int n; (n = stream.read(chunk)) >= 0; ) buffer.write(chunk, 0, n);
            stream.close();
        }
        connection.disconnect();
        return new Response(status, etag, new String(buffer.toByteArray(), "UTF-8"));
    }

    @Test public void statusIsASafeProjectionWithEtagCaching() throws Exception {
        Response first = exchange("GET", "/api/v1/execution-backends", null, null, null);
        assertEquals(200, first.status);
        JsonNode body = mapper.readTree(first.body);
        assertEquals(ExecutionBackendCatalogSnapshot.SCHEMA_VERSION,
                body.path("snapshotVersion").asText());
        assertTrue(body.path("snapshotId").asText().startsWith("gen-0"));
        assertEquals(0, body.path("generation").asLong());
        assertEquals("NEVER_PROBED", body.path("reasonCode").asText());
        Response refresh = exchange("POST", "/api/v1/execution-backends/refresh",
                null, "idem-1", null);
        assertEquals(202, refresh.status);
        // safe projection: host endpoints and socket paths must not leak
        assertFalse(refresh.body.contains("tcp://"));
        assertFalse(refresh.body.contains("docker-socket"));
        JsonNode refreshed = mapper.readTree(refresh.body);
        assertEquals(1, refreshed.path("generation").asLong());
        assertEquals(1, refreshed.path("backends").size());
        assertEquals("READY", refreshed.path("backends").get(0).path("availability").asText());
        assertEquals("CONTAINER_RESTRICTED",
                refreshed.path("backends").get(0).path("isolationLevel").asText());

        Response cached = exchange("GET", "/api/v1/execution-backends", null, null,
                refreshed.path("catalogFingerprint").asText());
        assertEquals(304, cached.status);
        assertEquals("", cached.body);
    }

    @Test public void refreshRequiresIdempotencyKeyAndProbesOnce() throws Exception {
        Response missing = exchange("POST", "/api/v1/execution-backends/refresh",
                null, null, null);
        assertEquals(400, missing.status);
        assertTrue(missing.body.contains("INVALID_REQUEST"));

        Response withKey = exchange("POST", "/api/v1/execution-backends/refresh",
                null, "idem-key-1", null);
        assertEquals(202, withKey.status);
        // the projection stays stable and idempotent for repeat reads
        Response again = exchange("GET", "/api/v1/execution-backends", null, null, null);
        assertEquals(200, again.status);
        assertEquals(1, mapper.readTree(again.body).path("generation").asLong());
        assertEquals(1, probeCalls.get());
    }

    @Test public void unauthenticatedRequestsAreRejected() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(baseUrl + "/api/v1/execution-backends")
                        .openConnection();
        assertEquals(401, connection.getResponseCode());
        connection.disconnect();
    }

    @Test public void disabledSandboxFailsClosed() throws Exception {
        BusinessHttpServer disabled = new BusinessHttpServer(
                new java.net.InetSocketAddress("127.0.0.1", 0), TOKEN,
                null, mapper, null, null, null, null);
        try {
            disabled.start();
            String url = "http://127.0.0.1:" + serverPort(disabled)
                    + "/api/v1/execution-backends";
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
            assertEquals(404, connection.getResponseCode());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream stream = connection.getErrorStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = stream.read(chunk)) >= 0; ) buffer.write(chunk, 0, n);
            String body = new String(buffer.toByteArray(), "UTF-8");
            assertTrue(body.contains("SANDBOX_DISABLED"));
            connection.disconnect();
        } finally {
            disabled.close();
        }
    }

    @Test public void sandboxApprovalBridgeProjectsPendingAndDecides() throws Exception {
        // no pending approval yet
        Response none = exchange("GET", "/api/v1/runs/biz-run-1/sandbox-approvals/pending",
                null, null, null);
        assertEquals(404, none.status);
        assertTrue(none.body.contains("SANDBOX_APPROVAL_NOT_PENDING"));

        // park a durable pending request on the SAME coordinator instance the
        // HTTP server serves (production registers via the Product bridge)
        final SandboxCommandApprovalHandler handler =
                approvals.handlerFor("biz-run-1");
        final SandboxCommandApprovalRequest request = request(
                "appr-http-1", System.currentTimeMillis() + 60000L);
        httpExecutor.submit(new Runnable() {
            public void run() {
                handler.awaitDecision(request,
                        System.currentTimeMillis() + 10000L);
            }
        });
        long deadline = System.currentTimeMillis() + 5000L;
        while (approvals.pending("biz-run-1") == null
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertNotNull(approvals.pending("biz-run-1"));

        Response pending = exchange("GET", "/api/v1/runs/biz-run-1/sandbox-approvals/pending",
                null, null, null);
        assertEquals(200, pending.status);
        JsonNode body = mapper.readTree(pending.body);
        assertEquals("appr-http-1", body.path("approvalId").asText());
        assertEquals(12, body.path("scriptByteLength").asInt());
        assertEquals("CONTAINER_RESTRICTED", body.path("isolationLevel").asText());
        // the redacted preview passes through; the host endpoint identity
        // behind the backend must never cross the wire
        assertEquals("mvn -q ... (redacted)",
                body.path("scriptRedactedPreview").asText());
        assertEquals(64, body.path("scriptSha256").asText().length());
        assertFalse(pending.body.contains("endpointhash"));
        assertFalse(pending.body.contains("daemonhash"));

        // decide with a wrong fingerprint must fail closed
        Response wrong = exchange("POST", "/api/v1/runs/biz-run-1/sandbox-approvals/appr-http-1",
                "{\"fingerprint\":\"" + hex64() + "\",\"decision\":\"APPROVE_ONCE\"}",
                "idem-2", null);
        assertEquals(409, wrong.status);
        assertTrue(wrong.body.contains("SANDBOX_APPROVAL_FINGERPRINT_MISMATCH"));

        // the correct fingerprint releases the waiter
        Response decision = exchange("POST",
                "/api/v1/runs/biz-run-1/sandbox-approvals/appr-http-1",
                "{\"fingerprint\":\"" + request.getFingerprint()
                        + "\",\"decision\":\"APPROVE_ONCE\"}",
                "idem-3", null);
        assertEquals(200, decision.status);
        assertTrue(decision.body.contains("ACCEPTED"));

        // after the decision nothing is pending anymore
        Response closed = exchange("GET", "/api/v1/runs/biz-run-1/sandbox-approvals/pending",
                null, null, null);
        assertEquals(404, closed.status);
    }

    private SandboxCommandApprovalRequest request(
            String approvalId, long expiresAtMillis) {
        return new SandboxCommandApprovalRequest(
                approvalId, "run-1", "inv-1",
                hex64(), 12, "mvn -q ... (redacted)", "/bin/sh",
                "module-a", 120000L,
                "OCI_DOCKER", "docker", "default",
                "endpointhash", "daemonhash", "sha256:" + hex64(),
                "CONTAINER_RESTRICTED", "profilefp", "mountfp", "NONE",
                2147483648L, 2.0d, 256L,
                Arrays.asList("HOME", "PATH"), "NONE", 0,
                "baselinefp", "sess-1", "stagingfp",
                System.currentTimeMillis() - 1000L, expiresAtMillis);
    }
}
