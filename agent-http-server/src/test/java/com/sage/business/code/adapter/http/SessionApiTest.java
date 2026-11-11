package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.application.session.SessionService;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sage.business.code.infrastructure.session.SqliteSessionRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** WP4 HTTP contract: attach, optimistic mutation, idempotency and fork. */
public class SessionApiTest {
    private static final String TOKEN = "session-api-contract-bearer";
    private Path state;
    private Path workspace;
    private ControlPlaneDatabase database;
    private HttpServer server;
    private java.util.concurrent.ExecutorService httpExecutor;
    private ObjectMapper mapper;

    @Before public void setUp() throws Exception {
        Path root = Paths.get("target", "session-api-test").toAbsolutePath();
        Files.createDirectories(root);
        state = Files.createTempDirectory(root, "case-");
        workspace = Files.createTempDirectory("session-api-workspace-");
        Files.write(workspace.resolve("README.md"), Collections.singletonList("fixture"));
        git("init"); git("config", "user.email", "test@example.invalid");
        git("config", "user.name", "test"); git("add", "."); git("commit", "-m", "initial");
        mapper = new ObjectMapper();
        database = new ControlPlaneDatabase(state);
        SqliteEventJournal journal = new SqliteEventJournal(database, mapper);
        BusinessEventPublisher publisher = new BusinessEventPublisher(journal);
        LiveEventHub hub = new LiveEventHub();
        publisher.addListener(new java.util.function.Consumer<com.sage.business.code.contract.v2.event.BusinessEventEnvelope>() {
            @Override public void accept(com.sage.business.code.contract.v2.event.BusinessEventEnvelope event) {
                hub.publish(event);
            }
        });
        SessionService sessions = new SessionService(new SqliteSessionRepository(database, mapper),
                new WorkspaceRegistry(Collections.singletonMap("sample", workspace.toString())),
                mapper, publisher, null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiHandler(journal, hub, mapper, TOKEN, null, sessions));
        httpExecutor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
    }

    @After public void tearDown() throws Exception {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
        if (database != null) database.close();
        delete(workspace); delete(state);
    }

    @Test public void createAttachPatchAndForkUseTheV2Contract() throws Exception {
        String createBody = "{\"workspaceAlias\":\"sample\",\"executionMode\":\"INSPECT\","
                + "\"verificationLevel\":\"NONE\",\"writeAllow\":[],\"writeForbid\":[]}";
        HttpURLConnection create = call("POST", "/api/v2/sessions", "create-api", createBody);
        Assert.assertEquals(201, create.getResponseCode());
        JsonNode created = mapper.readTree(create.getInputStream());
        String sessionId = created.path("sessionId").asText();
        Assert.assertEquals(1L, created.path("sessionVersion").asLong());

        HttpURLConnection repeated = call("POST", "/api/v2/sessions", "create-api", createBody);
        Assert.assertEquals(201, repeated.getResponseCode());
        Assert.assertEquals(sessionId, mapper.readTree(repeated.getInputStream()).path("sessionId").asText());

        RawResponse patch = rawPatch("/api/v2/sessions/" + sessionId, "patch-api",
                "{\"expectedSessionVersion\":1,\"title\":\"renamed\"}");
        Assert.assertEquals(200, patch.status);
        Assert.assertEquals(2L, mapper.readTree(patch.body).path("sessionVersion").asLong());

        RawResponse stale = rawPatch("/api/v2/sessions/" + sessionId, "patch-stale",
                "{\"expectedSessionVersion\":1,\"title\":\"wrong\"}");
        Assert.assertEquals(409, stale.status);
        Assert.assertEquals("SESSION_VERSION_CONFLICT", mapper.readTree(stale.body).path("code").asText());

        HttpURLConnection fork = call("POST", "/api/v2/sessions/" + sessionId + "/forks", "fork-api",
                "{\"expectedSessionVersion\":2,\"copyPolicy\":\"MESSAGES_ONLY\"}");
        Assert.assertEquals(201, fork.getResponseCode());
        JsonNode child = mapper.readTree(fork.getInputStream());
        Assert.assertNotEquals(sessionId, child.path("sessionId").asText());
        Assert.assertEquals(sessionId, child.path("parentSessionId").asText());
        Assert.assertEquals("INSPECT", child.path("executionMode").asText());
    }

    private HttpURLConnection call(String method, String path, String key, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getAddress().getPort() + path).openConnection();
        connection.setRequestMethod(method); connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = connection.getOutputStream(); out.write(bytes); out.close();
        }
        return connection;
    }

    private RawResponse rawPatch(String path, String key, String body) throws Exception {
        Socket socket = new Socket("127.0.0.1", server.getAddress().getPort());
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(("PATCH " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Authorization: Bearer " + TOKEN + "\r\nIdempotency-Key: " + key + "\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes); output.flush();
            InputStream input = socket.getInputStream();
            String statusLine = readLine(input);
            int status = Integer.parseInt(statusLine.substring(statusLine.indexOf(' ') + 1,
                    statusLine.indexOf(' ') + 4));
            int length = 0; String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            byte[] response = new byte[length]; int offset = 0;
            while (offset < length) { int read = input.read(response, offset, length - offset); if (read < 0) break; offset += read; }
            return new RawResponse(status, new String(response, StandardCharsets.UTF_8));
        } finally { socket.close(); }
    }

    private static String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder(); int c;
        while ((c = input.read()) >= 0) { if (c == '\n') break; if (c != '\r') line.append((char) c); }
        return line.toString();
    }

    private static final class RawResponse {
        final int status; final String body;
        RawResponse(int status, String body) { this.status = status; this.body = body; }
    }

    private void git(String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<String>();
        command.add("git"); command.add("-C"); command.add(workspace.toString());
        Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(process.getInputStream(), output); copy(process.getErrorStream(), output);
        if (process.waitFor() != 0) throw new IOException(output.toString("UTF-8"));
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[1024];
        for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
    }

    private static void delete(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach(value -> {
            try { Files.deleteIfExists(value); } catch (IOException ignored) { }
        });
    }
}
