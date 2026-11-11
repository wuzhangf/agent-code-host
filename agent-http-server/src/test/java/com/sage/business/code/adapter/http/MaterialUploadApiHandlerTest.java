package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.sage.business.code.application.material.MaterialUploadService;
import com.sage.business.code.application.personalaccess.PersonalAccessService;
import com.sage.business.code.application.personalaccess.PersonalAccessStore;
import com.sage.business.code.application.personalaccess.PersonalAgentSessionAccess;
import com.sage.business.code.application.personalaccess.PersonalResourceScope;
import com.sage.business.code.application.session.SessionRecordV2;
import com.sage.business.code.contract.v2.session.SessionMessageRequest;
import com.sage.business.code.contract.v2.session.SessionMessageResponse;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialUploadApiHandlerTest {
    private static final String BEARER = "material-handler-token-01";
    private final ObjectMapper mapper = new ObjectMapper();
    private Path root;
    private Path workspace;
    private ControlPlaneDatabase database;
    private MaterialUploadService service;
    private HttpServer server;
    private String base;
    private final Map<String, SessionRecordV2> sessions = new HashMap<String, SessionRecordV2>();
    private PersonalAccessService auth;
    private PersonalAgentSessionAccess access;
    private String pacToken;
    private String pacDid;

    private final PersonalAgentSessionAccess.Upstream upstream =
            new PersonalAgentSessionAccess.Upstream() {
        @Override public PersonalAgentSessionAccess.Reply call(
                PersonalAccessService.Device device, String method, String path,
                JsonNode body, String key) throws java.io.IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + BEARER);
            if (key != null) c.setRequestProperty("Idempotency-Key", key);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(mapper.writeValueAsBytes(body));
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] raw = in == null ? new byte[0] : readAll(in);
            c.disconnect();
            return new PersonalAgentSessionAccess.Reply(status,
                    raw.length == 0 ? null : mapper.readTree(raw));
        }
        @Override public PersonalAgentSessionAccess.Reply callBytes(
                PersonalAccessService.Device device, String method, String path,
                byte[] body, String key) throws java.io.IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + BEARER);
            if (key != null) c.setRequestProperty("Idempotency-Key", key);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setFixedLengthStreamingMode(body.length);
                c.getOutputStream().write(body);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] raw = in == null ? new byte[0] : readAll(in);
            c.disconnect();
            return new PersonalAgentSessionAccess.Reply(status,
                    raw.length == 0 ? null : mapper.readTree(raw));
        }
    };

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("mat-api-");
        workspace = Files.createDirectories(root.resolve("ws-one"));
        Map<String, String> aliases = new HashMap<String, String>();
        aliases.put("project-one", workspace.toString());
        database = new ControlPlaneDatabase(root.resolve("state"), false);
        service = new MaterialUploadService(database, new WorkspaceRegistry(aliases),
                sessions::get, null, mapper);
        session("s1", "project-one");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new V2ApiDispatcher(mapper,
                Arrays.<V2RouteGroup>asList(
                        new MaterialUploadApiHandler(mapper, BEARER, service))));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        PersonalAccessStore store = new PersonalAccessStore(database, mapper);
        auth = new PersonalAccessService(store, mapper, new byte[32], "store-test",
                "https://localhost:9443");
        String iid = UUID.randomUUID().toString(), rid = UUID.randomUUID().toString(),
                poll = UUID.randomUUID().toString();
        ObjectNode invite = auth.invitation(iid, Arrays.asList("s1"),
                new HashSet<String>(Arrays.asList("OBSERVE")), "cert");
        auth.request(mapper.createObjectNode().put("inviteId", iid)
                .put("inviteSecret", invite.path("inviteSecret").asText())
                .put("pairingRequestId", rid)
                .put("pollingSecretHash", PersonalAccessService.hash(poll))
                .put("installationId", UUID.randomUUID().toString()).put("label", "phone"));
        auth.decide(rid, true);
        ObjectNode credential = auth.redeem(rid, poll, "redeem");
        pacToken = credential.path("accessToken").asText();
        pacDid = credential.path("deviceGrantId").asText();
        access = new PersonalAgentSessionAccess(store, auth, mapper, upstream);
        grantSession("s1", "OBSERVE", "INTERACT");
    }

    @After public void cleanup() throws Exception {
        if (server != null) server.stop(0);
        if (database != null) database.close();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(
                    p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    private void grantSession(String sessionId, String... actions) {
        auth.updateScopeResources(pacDid, UUID.randomUUID().toString(),
                Long.toString(auth.authenticate(pacToken).revision),
                Arrays.asList(new PersonalResourceScope.Resource(
                        "ACS_SESSION", "store-test", sessionId)),
                java.util.Collections.<PersonalResourceScope.LaunchScope>emptyList(),
                new TreeSet<String>(Arrays.asList(actions)));
    }

    private void session(String sessionId, String alias) {
        SessionRecordV2 s = new SessionRecordV2();
        s.sessionId = sessionId; s.workspaceAlias = alias; s.status = "ACTIVE";
        sessions.put(sessionId, s);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static final class HttpReply { int status; JsonNode body; }

    private HttpReply call(String method, String path, byte[] raw, boolean authorized,
            String idempotencyKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        if (authorized) c.setRequestProperty("Authorization", "Bearer " + BEARER);
        if (idempotencyKey != null) c.setRequestProperty("Idempotency-Key", idempotencyKey);
        if (raw != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(raw);
        }
        HttpReply reply = new HttpReply();
        reply.status = c.getResponseCode();
        InputStream in = reply.status >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] body = in == null ? new byte[0] : readAll(in);
        c.disconnect();
        reply.body = body.length == 0 ? null : mapper.readTree(body);
        return reply;
    }

    private HttpReply json(String method, String path, JsonNode body) throws Exception {
        return call(method, path, body == null ? null : mapper.writeValueAsBytes(body),
                true, null);
    }

    private ObjectNode createBody(String key, String mediaType, byte[] bytes) {
        ObjectNode body = mapper.createObjectNode().put("requestKey", key)
                .put("displayName", "note.txt").put("mediaType", mediaType)
                .put("sizeBytes", bytes.length).put("sha256", sha256(bytes));
        body.putObject("target").put("sessionId", "s1");
        return body;
    }

    private String problemCode(HttpReply reply) {
        return reply.body == null ? null : reply.body.path("code").asText();
    }

    private void denied(String code, ThrowingCall call) {
        try { call.run(); fail("Expected " + code); }
        catch (BusinessException e) { assertEquals(code, e.getCode()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private interface ThrowingCall { void run() throws Exception; }

    @Test public void sixRoutesEnforceBearerWhitelistsAndRawChunks() throws Exception {
        byte[] bytes = "raw-chunk-bytes".getBytes("UTF-8");
        String project = "/api/v2/projects/project-one/material-uploads";

        HttpReply deniedAuth = call("GET", project, null, false, null);
        assertEquals(401, deniedAuth.status);

        byte[] all = "AA==raw-chunk-bytes".getBytes("UTF-8");
        HttpReply created = json("POST", project, createBody("r1", "text/plain", all));
        assertEquals(200, created.status);
        assertEquals("SELECTED", created.body.path("state").asText());
        String key = created.body.path("uploadKey").asText();

        ObjectNode extra = createBody("r2", "text/plain", bytes).put("foreign", 1);
        HttpReply rejectedField = json("POST", project, extra);
        assertEquals(400, rejectedField.status);
        assertEquals("INVALID_REQUEST", problemCode(rejectedField));

        ObjectNode foreignTarget = createBody("r3", "text/plain", bytes);
        foreignTarget.putObject("target").put("foreignId", "x");
        HttpReply rejectedTarget = json("POST", project, foreignTarget);
        assertEquals(400, rejectedTarget.status);
        assertEquals("INVALID_REQUEST", problemCode(rejectedTarget));

        HttpReply list = json("GET", project, null);
        assertEquals(200, list.status);
        assertEquals(1, list.body.path("items").size());
        assertEquals(key, list.body.path("items").get(0).path("uploadKey").asText());

        HttpReply get = json("GET", project + "/" + key, null);
        assertEquals(200, get.status);
        assertEquals(key, get.body.path("uploadKey").asText());

        HttpReply noDigest = call("PUT", project + "/" + key + "/chunks/0", bytes, true, null);
        assertEquals(400, noDigest.status);
        assertEquals("MATERIAL_CHUNK_DIGEST_REQUIRED", problemCode(noDigest));

        HttpReply badOffset = call("PUT", project + "/" + key + "/chunks/abc",
                bytes, true, null);
        assertEquals(409, badOffset.status);
        assertEquals("MATERIAL_CHUNK_OFFSET", problemCode(badOffset));

        byte[] opaque = "AA==".getBytes("UTF-8");
        HttpReply chunk = call("PUT", project + "/" + key + "/chunks/0?digest="
                + sha256(opaque), opaque, true, null);
        assertEquals(200, chunk.status);
        assertEquals(4, chunk.body.path("nextOffset").asInt());

        HttpReply chunkRest = call("PUT", project + "/" + key + "/chunks/4?digest="
                + sha256(bytes), bytes, true, null);
        assertEquals(200, chunkRest.status);
        assertEquals(all.length, chunkRest.body.path("nextOffset").asInt());

        ObjectNode sealExtra = mapper.createObjectNode().put("requestKey", "seal-1")
                .put("sha256", sha256(all)).put("foreign", 1);
        HttpReply rejectedSeal = json("POST", project + "/" + key + "/seal", sealExtra);
        assertEquals(400, rejectedSeal.status);
        assertEquals("INVALID_REQUEST", problemCode(rejectedSeal));

        HttpReply sealed = json("POST", project + "/" + key + "/seal",
                mapper.createObjectNode().put("requestKey", "seal-1")
                        .put("sha256", sha256(all)));
        assertEquals(200, sealed.status);
        assertEquals("SEALED", sealed.body.path("state").asText());
        assertTrue(sealed.body.path("materialRef").asText().startsWith("matref-"));

        HttpReply wrongMethod = json("PUT", project, null);
        assertEquals(405, wrongMethod.status);
        HttpReply sealedGet = json("GET", project + "/" + key + "/seal", null);
        assertEquals(405, sealedGet.status);
        HttpReply unknown = json("GET", project + "/" + key + "/peek", null);
        assertEquals(404, unknown.status);
        assertEquals("INVALID_REQUEST", problemCode(unknown));
        HttpReply otherTail = json("GET", "/api/v2/projects/project-one/other", null);
        assertEquals(404, otherTail.status);
        assertEquals("INVALID_REQUEST", problemCode(otherTail));
        HttpReply missing = json("GET", project + "/mat-missing", null);
        assertEquals(404, missing.status);
        assertEquals("MATERIAL_NOT_FOUND", problemCode(missing));
        HttpReply foreignProject = json("GET", "/api/v2/projects/project-two/material-uploads", null);
        assertEquals(404, foreignProject.status);
        assertEquals("PROJECT_NOT_FOUND", problemCode(foreignProject));

        HttpReply deleted = json("DELETE", project + "/" + key, null);
        assertEquals(200, deleted.status);
        assertEquals("CANCELLED", deleted.body.path("state").asText());
    }

    @Test public void materialsWireContractAndReceiptShape() throws Exception {
        String sha = "a".repeat(64);
        SessionMessageRequest request = mapper.readValue(
                "{\"clientMessageId\":\"m1\",\"content\":\"go\",\"materials\":"
                        + "[{\"materialRef\":\"matref-1\",\"sha256\":\"" + sha
                        + "\",\"role\":\"IMAGE\"}]}",
                SessionMessageRequest.class);
        assertEquals(1, request.getMaterials().size());
        assertEquals("matref-1", request.getMaterials().get(0).getMaterialRef());
        assertEquals(sha, request.getMaterials().get(0).getSha256());
        assertEquals("IMAGE", request.getMaterials().get(0).getRole());
        try {
            mapper.readValue("{\"clientMessageId\":\"m2\",\"materials\":"
                            + "[{\"materialRef\":\"matref-1\",\"sha256\":\"" + sha
                            + "\",\"role\":\"IMAGE\",\"dataBase64\":\"AA==\"}]}",
                    SessionMessageRequest.class);
            fail("materials[] must reject unknown fields");
        } catch (UnrecognizedPropertyException expected) { }

        List<SessionMessageRequest.MaterialRef> badRole =
                new ArrayList<SessionMessageRequest.MaterialRef>();
        SessionMessageRequest.MaterialRef video = new SessionMessageRequest.MaterialRef();
        video.setMaterialRef("matref-x"); video.setSha256(sha); video.setRole("VIDEO");
        badRole.add(video);
        denied("MATERIALS_INVALID", () -> service.bind("s1", "project-one", badRole, "cm-role"));

        SessionMessageResponse.MaterialReceipt receipt =
                new SessionMessageResponse.MaterialReceipt();
        receipt.setMaterialRef("matref-1");
        receipt.setUploadKey("mat-1");
        receipt.setSha256(sha);
        receipt.setRole("IMAGE");
        receipt.setAttachmentId("att-1");
        JsonNode wire = mapper.readTree(mapper.writeValueAsString(receipt));
        assertEquals("matref-1", wire.path("materialRef").asText());
        assertEquals("mat-1", wire.path("uploadKey").asText());
        assertEquals(sha, wire.path("sha256").asText());
        for (String forbidden : Arrays.asList("bytes", "data", "dataBase64", "content"))
            assertFalse("receipt leaked " + forbidden, wire.has(forbidden));
    }

    private ObjectNode pacCreate(String key) {
        ObjectNode body = mapper.createObjectNode().put("requestKey", key)
                .put("displayName", "note.txt").put("mediaType", "text/plain")
                .put("sizeBytes", 8).put("sha256", sha256("pac-data".getBytes()));
        body.putObject("target").put("sessionId", "s1");
        return body;
    }

    @Test public void pacCreateReadAndCancelReachRealHandler() throws Exception {
        PersonalAgentSessionAccess.Result created = access.materialUpload(pacToken,
                "project-one", new String[0], "POST", pacCreate("m1"), "m1");
        assertEquals(200, created.status);
        assertEquals("OBSERVED", created.body.path("state").asText());
        String uploadKey = created.body.path("source").path("uploadKey").asText();
        assertTrue(uploadKey.startsWith("mat-"));
        assertEquals("SELECTED", created.body.path("source").path("state").asText());
        assertTrue("dispatchStartedAt must be a ledger fact",
                created.body.path("dispatchStartedAt").asLong() > 0);

        PersonalAgentSessionAccess.Result read = access.materialUpload(pacToken,
                "project-one", new String[]{uploadKey}, "GET", null, null);
        assertEquals(200, read.status);
        assertEquals("SELECTED", read.body.path("state").asText());
        assertEquals(uploadKey, read.body.path("uploadKey").asText());

        PersonalAgentSessionAccess.Result cancelled = access.materialUpload(pacToken,
                "project-one", new String[]{uploadKey}, "DELETE", null, "del-1");
        assertEquals(200, cancelled.status);
        assertEquals("OBSERVED", cancelled.body.path("state").asText());
        assertEquals("CANCELLED", cancelled.body.path("source").path("state").asText());
        assertEquals("CANCELLED", service.get("project-one", uploadKey).path("state").asText());
    }

    @Test public void pacChunkAndSealReachRawBytesContract() throws Exception {
        PersonalAgentSessionAccess.Result created = access.materialUpload(pacToken,
                "project-one", new String[0], "POST", pacCreate("m2"), "m2");
        assertEquals("OBSERVED", created.body.path("state").asText());
        String uploadKey = created.body.path("source").path("uploadKey").asText();

        byte[] bytes = "pac-data".getBytes("UTF-8");
        ObjectNode chunk = mapper.createObjectNode().put("requestKey", "c1")
                .put("chunkDigest", PersonalAccessService.hashBytes(bytes))
                .put("dataBase64",
                        java.util.Base64.getEncoder().encodeToString(bytes));
        PersonalAgentSessionAccess.Result uploaded = access.materialUpload(pacToken,
                "project-one", new String[]{uploadKey, "chunks", "0"}, "PUT", chunk, "c1");
        assertEquals("PAC relay must deliver chunk bytes upstream: " + uploaded.body,
                "OBSERVED", uploaded.body.path("state").asText());

        PersonalAgentSessionAccess.Result sealed = access.materialUpload(pacToken,
                "project-one", new String[]{uploadKey, "seal"}, "POST",
                mapper.createObjectNode().put("requestKey", "s1")
                        .put("sha256", sha256(bytes)), "s1");
        assertEquals("OBSERVED", sealed.body.path("state").asText());
        assertEquals("SEALED", sealed.body.path("source").path("state").asText());
        assertTrue(sealed.body.path("source").path("materialRef").asText()
                .startsWith("matref-"));
    }
}
