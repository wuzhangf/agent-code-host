package com.sage.business.code.adapter.http;

import com.agent.product.code.terminal.CodeTerminalService;
import com.agent.product.code.terminal.TerminalApprovalDecision;
import com.agent.product.code.terminal.TerminalExecutionOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.client.ClientRegistrationResult;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.application.terminal.TerminalApplicationService;
import com.sage.business.code.contract.v2.terminal.TerminalControlAcquireRequest;
import com.sage.business.code.contract.v2.terminal.TerminalInputRequest;
import com.sage.business.code.contract.v2.terminal.TerminalSessionCreateRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** HTTP identity and fenced terminal mutation contract. */
public class TerminalApiHandlerTest {
    private static final String BEARER = "terminal-handler-test-bearer";

    @Test
    public void handlerDerivesHolderFromAuthenticatedClientTokenAndIgnoresControllerBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TerminalExecutionOptions options = TerminalExecutionOptions.builder()
                .enabled(true).allowedExecutable("fixture")
                .approvalHandler((request, deadline) -> new TerminalApprovalDecision(
                        request.getApprovalId(), request.getFingerprint(),
                        TerminalApprovalDecision.Decision.APPROVE_USER_ONLY))
                .build();
        CodeTerminalService product = com.agent.product.code.terminal.TerminalTestKit.fake(".", options);
        TerminalApplicationService terminals = new TerminalApplicationService("handler-service", product);
        ClientSessionRegistry sessions = new ClientSessionRegistry("store", "boot", 60_000L, 4, 8, 4, 4);
        String clientId=UUID.randomUUID().toString();
        String processInstanceId=UUID.randomUUID().toString();
        ClientRegistrationResult client = sessions.register(ClientSessionApiHandler.LOCAL_OWNER_PRINCIPAL,
                new ClientSessionRegistry.RegistrationRequest(UUID.randomUUID().toString(), "hash-a", clientId,
                        processInstanceId, "ELECTRON", Arrays.asList("v2"), "test"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        List<V2RouteGroup> groups = Collections.<V2RouteGroup>singletonList(
                new TerminalApiHandler(mapper, BEARER, terminals));
        server.createContext("/api/v2", new V2ApiDispatcher(mapper, groups, sessions, true));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            TerminalSessionCreateRequest create = new TerminalSessionCreateRequest();
            create.setWorkspaceExecutionId("workspace-http");
            create.setExecutable("fixture");
            create.setInputPolicy("BOUNDED_TEXT");
            Response created = request("POST", base + "/api/v2/sessions/business-http/terminal-sessions",
                    BEARER, client.getClientSessionToken(), "create-http", mapper.writeValueAsBytes(create));
            assertEquals(202, created.status);
            String id = mapper.readTree(created.body()).path("terminalSessionId").asText();

            TerminalControlAcquireRequest acquire = new TerminalControlAcquireRequest();
            acquire.setOperationId("acquire-http");
            acquire.setExpectedControlVersion(0L);
            Response held = request("POST", base + "/api/v2/terminal-sessions/" + id + "/control/acquire",
                    BEARER, client.getClientSessionToken(), "acquire-http", mapper.writeValueAsBytes(acquire));
            assertEquals(200, held.status);
            com.fasterxml.jackson.databind.JsonNode lease = mapper.readTree(held.body());

            TerminalInputRequest input = new TerminalInputRequest();
            input.setOperationId("input-http");
            input.setExpectedStateVersion(lease.path("terminalStateVersion").asLong());
            input.setExpectedInputVersion(0L);
            input.setLeaseId(lease.path("leaseId").asText());
            input.setControlEpoch(lease.path("controlEpoch").asLong());
            input.setController("AGENT");
            input.setText("accepted only because the client lease is valid");
            Response applied = request("POST", base + "/api/v2/terminal-sessions/" + id + "/input",
                    BEARER, client.getClientSessionToken(), "input-http", mapper.writeValueAsBytes(input));
            assertEquals(202, applied.status);
            assertEquals("APPLIED", mapper.readTree(applied.body()).path("delivery").asText());

            TerminalControlAcquireRequest missingHolder = new TerminalControlAcquireRequest();
            missingHolder.setOperationId("acquire-no-client");
            missingHolder.setExpectedControlVersion(heldControlVersion(lease));
            Response rejected = request("POST", base + "/api/v2/terminal-sessions/" + id + "/control/acquire",
                    BEARER, null, "acquire-no-client", mapper.writeValueAsBytes(missingHolder));
            assertEquals(428, rejected.status);
            assertTrue(rejected.body().contains("CLIENT_REGISTRATION_REQUIRED"));
        } finally {
            server.stop(0);
            executor.shutdownNow();
            terminals.close();
        }
    }

    private static long heldControlVersion(com.fasterxml.jackson.databind.JsonNode lease) {
        return lease.path("controlVersion").asLong();
    }

    private static Response request(String method, String url, String bearer, String clientToken,
                                    String key, byte[] body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (clientToken != null) connection.setRequestProperty(ClientSessionApiHandler.CLIENT_TOKEN_HEADER, clientToken);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        connection.setDoOutput(body != null);
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (stream != null) {
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
        }
        return new Response(status, output.toByteArray());
    }

    private static final class Response {
        final int status; final byte[] raw;
        Response(int status, byte[] raw) { this.status = status; this.raw = raw; }
        String body() { return new String(raw, java.nio.charset.StandardCharsets.UTF_8); }
    }
}
