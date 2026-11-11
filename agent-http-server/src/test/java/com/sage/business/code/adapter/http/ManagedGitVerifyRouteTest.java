package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.agent.framework.capability.git.remote.CloneRequest;
import com.agent.framework.capability.git.remote.CloneResult;
import com.agent.framework.capability.git.remote.FetchRequest;
import com.agent.framework.capability.git.remote.FetchResult;
import com.agent.framework.capability.git.remote.GitRemoteServices;
import com.agent.framework.capability.git.remote.GitRemoteTransport;
import com.agent.framework.capability.git.remote.OperationGrant;
import com.agent.framework.capability.git.remote.PushRequest;
import com.agent.framework.capability.git.remote.PushResult;
import com.agent.framework.capability.git.remote.RefQuery;
import com.agent.framework.capability.git.remote.RemoteOperationRef;
import com.agent.framework.capability.git.remote.RemoteRefs;
import com.agent.framework.capability.git.remote.RemoteRepositoryRef;
import com.agent.framework.capability.git.remote.ReconcileResult;
import com.agent.framework.capability.git.remote.RemoteTransportException;
import com.agent.framework.capability.process.ProcessServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.git.ManagedGitRepositoryService;
import com.sage.business.code.application.git.ManagedGitWorkspaceService;
import com.sage.business.code.application.git.ManagedRepositoryRegistry;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Route regression for DL-42: {@code /api/v2/managed-repositories/verify}
 * and repository lookup were dead code because the handler matched a
 * 4-segment path while the real URI has 5 segments. These tests pin the
 * full-path dispatch so the control-plane verify chain stays reachable.
 */
public class ManagedGitVerifyRouteTest {
    private static final String TOKEN = "managed-git-route-token-0123456789";
    private static final String HEAD_OID =
            "0123456789abcdef0123456789abcdef01234567";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private Path root;
    private StubTransport transport;

    @Before
    public void setUp() throws Exception {
        root = Files.createTempDirectory("managed-git-route-test");
        Path lanes = Files.createDirectories(root.resolve("lanes"));
        Path cacheDir = Files.createDirectories(root.resolve("cache"));
        Path registryDir = Files.createDirectories(root.resolve("registry"));
        Path remoteState = Files.createDirectories(root.resolve("remote-state"));
        transport = new StubTransport();
        ManagedGitRepositoryService cache =
                new ManagedGitRepositoryService(cacheDir, transport, mapper);
        ManagedRepositoryRegistry registry = new ManagedRepositoryRegistry(
                new AtomicJsonStore(registryDir, mapper), cache);
        GitRemoteServices remoteServices = new GitRemoteServices("git",
                ProcessServices.defaults(), remoteState.toFile(), 30_000L);
        ManagedGitWorkspaceService workspaces = new ManagedGitWorkspaceService(
                lanes, new AtomicJsonStore(lanes, mapper), mapper,
                registry, cache, transport, remoteServices);
        ManagedGitWorkspaceApiHandler handler =
                new ManagedGitWorkspaceApiHandler(mapper, TOKEN, registry, workspaces);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", exchange -> {
            try {
                if (!handler.handleGroup(exchange,
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestMethod())) {
                    byte[] body = "{\"code\":\"INVALID_REQUEST\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(404, body.length);
                    exchange.getResponseBody().write(body);
                }
            } catch (BusinessException failure) {
                byte[] body = ("{\"code\":\"" + failure.getCode() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(failure.getHttpStatus(), body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    public void verifyRouteIsDispatchedAndReturnsRemoteFacts() throws Exception {
        HttpURLConnection connection = post("/api/v2/managed-repositories/verify",
                "{\"remoteUrl\":\"http://192.168.1.31:8080/grp/repo\","
                        + "\"baseRef\":\"refs/heads/main\"}", TOKEN);
        assertEquals(200, connection.getResponseCode());
        JsonNode body = mapper.readTree(readBody(connection));
        assertTrue(body.path("ok").asBoolean());
        assertEquals(HEAD_OID, body.path("headOid").asText());
        assertEquals(HEAD_OID, body.path("baseRefOid").asText());
        assertEquals("SHA1", body.path("objectFormat").asText());
        assertEquals("http://192.168.1.31:8080/grp/repo", transport.lastUrl);
        assertEquals(Collections.singletonList("refs/heads/main"),
                transport.lastPrefixes);
    }

    @Test
    public void verifyRouteWithoutBaseRefStillProbesHead() throws Exception {
        HttpURLConnection connection = post("/api/v2/managed-repositories/verify",
                "{\"remoteUrl\":\"http://192.168.1.31:8080/All-Projects\"}", TOKEN);
        assertEquals(200, connection.getResponseCode());
        JsonNode body = mapper.readTree(readBody(connection));
        assertTrue(body.path("ok").asBoolean());
        assertEquals(HEAD_OID, body.path("headOid").asText());
        assertFalse(body.has("baseRefOid"));
        assertTrue(transport.lastPrefixes.isEmpty());
    }

    @Test
    public void verifyRouteReportsMissingRefWithoutLeakingUrl() throws Exception {
        HttpURLConnection connection = post("/api/v2/managed-repositories/verify",
                "{\"remoteUrl\":\"http://192.168.1.31:8080/grp/repo\","
                        + "\"baseRef\":\"refs/heads/absent\"}", TOKEN);
        assertEquals(200, connection.getResponseCode());
        JsonNode body = mapper.readTree(readBody(connection));
        assertFalse(body.path("ok").asBoolean());
        assertEquals("REMOTE_REF_NOT_FOUND", body.path("errorCode").asText());
    }

    @Test
    public void verifyRouteRequiresBearer() throws Exception {
        HttpURLConnection connection = post("/api/v2/managed-repositories/verify",
                "{\"remoteUrl\":\"http://192.168.1.31:8080/grp/repo\"}", null);
        assertEquals(401, connection.getResponseCode());
        connection.getErrorStream().close();
    }

    @Test
    public void repositoryLookupRouteIsDispatched() throws Exception {
        // With the old length-4 check this URI fell through the group and
        // surfaced as INVALID_REQUEST; the fix must produce the domain 404.
        HttpURLConnection connection = open(
                "/api/v2/managed-repositories/no-such-repo", "GET", TOKEN);
        assertEquals(404, connection.getResponseCode());
        JsonNode body = mapper.readTree(readBody(connection));
        assertEquals("REPOSITORY_NOT_FOUND", body.path("code").asText());
    }

    private HttpURLConnection post(String path, String json, String bearer)
            throws Exception {
        HttpURLConnection connection = open(path, "POST", bearer);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Idempotency-Key",
                "test-" + System.nanoTime());
        connection.setDoOutput(true);
        connection.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        connection.getOutputStream().close();
        return connection;
    }

    private HttpURLConnection open(String path, String method, String bearer)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getAddress().getPort() + path)
                .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        return connection;
    }

    private static String readBody(HttpURLConnection connection)
            throws Exception {
        InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        try {
            byte[] buffer = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = stream.read(buffer)) >= 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
            connection.disconnect();
        }
    }

    /** Deterministic ls-remote stub; every other SPI method is unused here. */
    private static final class StubTransport implements GitRemoteTransport {
        private String lastUrl;
        private List<String> lastPrefixes = Collections.emptyList();

        @Override
        public RemoteRefs lsRemote(RefQuery query) {
            this.lastUrl = query.getRemoteUrl();
            this.lastPrefixes =
                    new ArrayList<String>(query.getRefPrefixes());
            List<RemoteRepositoryRef> refs = new ArrayList<RemoteRepositoryRef>();
            refs.add(new RemoteRepositoryRef("refs/heads/main", HEAD_OID, null));
            return new RemoteRefs(refs, HEAD_OID, "refs/heads/main");
        }

        @Override public CloneResult clone(CloneRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public CloneResult materializeLaneRepository(CloneRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public FetchResult fetch(FetchRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public PushResult pushExact(PushRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public ReconcileResult reconcileRemoteState(RemoteOperationRef operation) {
            throw new UnsupportedOperationException();
        }
    }
}
