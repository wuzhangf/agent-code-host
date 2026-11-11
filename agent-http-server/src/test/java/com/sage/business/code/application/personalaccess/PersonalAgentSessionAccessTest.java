package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Session-create and auxiliary-deliver ledgers: durable admission under the
 *  original key, exact proven session append, and recover-in-place — a lost
 *  reply is never recovered by substituting a new key. */
public class PersonalAgentSessionAccessTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Path root; private ControlPlaneDatabase db;
    private PersonalAccessStore store; private PersonalAccessService auth;
    private FakeUpstream upstream; private PersonalAgentSessionAccess access;
    private String token, did;

    /** Loopback-ACS fake: create dedups on the source idempotency key and
     *  boundary requests dedup on their requestId, like the real ledger. */
    private final class FakeUpstream implements PersonalAgentSessionAccess.Upstream {
        final Map<String, ObjectNode> sessions = new LinkedHashMap<String, ObjectNode>();
        final Map<String, ObjectNode> boundaries = new LinkedHashMap<String, ObjectNode>();
        final Map<String, JsonNode> gets = new HashMap<String, JsonNode>();
        final Map<String, ObjectNode> uploads = new LinkedHashMap<String, ObjectNode>();
        final Map<String, byte[]> chunkBytes = new HashMap<String, byte[]>();
        final List<String> calls = new ArrayList<String>();
        final AtomicInteger createCalls = new AtomicInteger(), boundaryPosts = new AtomicInteger(), uploadPosts = new AtomicInteger();
        final Deque<IOException> failures = new ArrayDeque<IOException>();
        int createStatus = 201, boundaryStatus = 202;

        public PersonalAgentSessionAccess.Reply call(PersonalAccessService.Device device, String method,
                String path, JsonNode body, String key) throws IOException {
            calls.add(method + " " + path);
            if (!failures.isEmpty()) throw failures.poll();
            if ("POST".equals(method) && "/api/v2/sessions".equals(path)) {
                createCalls.incrementAndGet();
                ObjectNode prior = sessions.get(key);
                if (prior != null) return new PersonalAgentSessionAccess.Reply(201, prior);
                if (createStatus != 201) return new PersonalAgentSessionAccess.Reply(createStatus, error("VALIDATION_FAILED"));
                ObjectNode created = ((ObjectNode) body).deepCopy();
                created.put("sessionId", "session-created-" + sessions.size());
                sessions.put(key, created);
                return new PersonalAgentSessionAccess.Reply(201, created);
            }
            if ("POST".equals(method) && path.matches("/api/v2/runs/[^/]+/boundary-requests")) {
                boundaryPosts.incrementAndGet();
                String rid = path.split("/")[4], bid = body.path("requestId").asText();
                ObjectNode prior = boundaries.get(rid + "/" + bid);
                if (prior != null) return new PersonalAgentSessionAccess.Reply(202, prior);
                if (boundaryStatus != 202)
                    return new PersonalAgentSessionAccess.Reply(boundaryStatus, error("EXECUTION_EPOCH_CONFLICT"));
                ObjectNode receipt = mapper.createObjectNode()
                        .put("schemaVersion", "run-boundary-receipt/1").put("requestId", bid)
                        .put("runId", rid).put("kind", body.path("kind").asText())
                        .put("state", "PENDING").put("sequence", 1);
                boundaries.put(rid + "/" + bid, receipt);
                return new PersonalAgentSessionAccess.Reply(202, receipt);
            }
            if ("GET".equals(method) && path.matches("/api/v2/runs/[^/]+/boundary-requests/[^/]+")) {
                String[] seg = path.split("/");
                ObjectNode found = boundaries.get(seg[4] + "/" + seg[6]);
                return found == null
                        ? new PersonalAgentSessionAccess.Reply(404, error("BOUNDARY_INPUT_NOT_FOUND"))
                        : new PersonalAgentSessionAccess.Reply(200, found);
            }
            if (path.matches("/api/v2/projects/[^/]+/material-uploads")) {
                if ("POST".equals(method)) {
                    uploadPosts.incrementAndGet();
                    String sourceKey = body.path("requestKey").asText();
                    ObjectNode prior = uploads.get("req:" + sourceKey);
                    if (prior != null) return new PersonalAgentSessionAccess.Reply(202, prior);
                    ObjectNode upload = mapper.createObjectNode()
                            .put("uploadKey", "mat-" + uploads.size()).put("requestKey", sourceKey)
                            .put("state", "OPEN").put("nextOffset", 0)
                            .put("displayName", body.path("displayName").asText())
                            .put("mediaType", body.path("mediaType").asText())
                            .put("sizeBytes", body.path("sizeBytes").asLong())
                            .put("sha256", body.path("sha256").asText())
                            .set("target", body.path("target"));
                    uploads.put("req:" + sourceKey, upload);
                    uploads.put("key:" + upload.path("uploadKey").asText(), upload);
                    return new PersonalAgentSessionAccess.Reply(202, upload);
                }
                return new PersonalAgentSessionAccess.Reply(404, error("NOT_FOUND"));
            }
            if (path.matches("/api/v2/projects/[^/]+/material-uploads/[^/]+(/chunks/[0-9]+|/seal)?")) {
                String[] seg = path.split("/");
                String uploadKey = seg[6];
                ObjectNode upload = uploads.get("key:" + uploadKey);
                if (upload == null) return new PersonalAgentSessionAccess.Reply(404, error("NOT_FOUND"));
                if ("GET".equals(method) && seg.length == 7) return new PersonalAgentSessionAccess.Reply(200, upload);
                if ("PUT".equals(method) && seg.length == 9 && "chunks".equals(seg[7])) {
                    String sourceKey = body.path("requestKey").asText();
                    ObjectNode prior = uploads.get("req:" + sourceKey);
                    if (prior != null) return new PersonalAgentSessionAccess.Reply(202, prior);
                    ObjectNode updated = upload.deepCopy().put("nextOffset", Long.parseLong(seg[8]) + 1);
                    uploads.put("req:" + sourceKey, updated);
                    uploads.put("key:" + uploadKey, updated);
                    return new PersonalAgentSessionAccess.Reply(202, updated);
                }
                if ("POST".equals(method) && seg.length == 8 && "seal".equals(seg[7])) {
                    String sourceKey = body.path("requestKey").asText();
                    ObjectNode prior = uploads.get("req:" + sourceKey);
                    if (prior != null) return new PersonalAgentSessionAccess.Reply(202, prior);
                    ObjectNode sealed = upload.deepCopy().put("state", "SEALED").put("materialRef", "mat-ref-" + uploadKey);
                    uploads.put("req:" + sourceKey, sealed);
                    uploads.put("key:" + uploadKey, sealed);
                    return new PersonalAgentSessionAccess.Reply(202, sealed);
                }
                if ("DELETE".equals(method) && seg.length == 7) {
                    ObjectNode cancelled = upload.deepCopy().put("state", "CANCELLED");
                    uploads.put("key:" + uploadKey, cancelled);
                    return new PersonalAgentSessionAccess.Reply(202, cancelled);
                }
                return new PersonalAgentSessionAccess.Reply(404, error("NOT_FOUND"));
            }
            JsonNode canned = gets.get(method + " " + path);
            return canned == null
                    ? new PersonalAgentSessionAccess.Reply(404, error("NOT_FOUND"))
                    : new PersonalAgentSessionAccess.Reply(200, canned);
        }

        /** Raw-byte lane mirroring the real chunk contract: digest query
         *  required, sha256 verified, same-offset rewrite conflicts. */
        public PersonalAgentSessionAccess.Reply callBytes(PersonalAccessService.Device device, String method,
                String path, byte[] bytes, String key) throws IOException {
            calls.add(method + " " + path);
            if (!failures.isEmpty()) throw failures.poll();
            String route = path, digest = null;
            int q = path.indexOf('?');
            if (q >= 0) {
                route = path.substring(0, q);
                for (String pair : path.substring(q + 1).split("&"))
                    if (pair.startsWith("digest=")) digest = pair.substring("digest=".length());
            }
            if ("PUT".equals(method) && route.matches("/api/v2/projects/[^/]+/material-uploads/[^/]+/chunks/[0-9]+")) {
                String[] seg = route.split("/");
                ObjectNode upload = uploads.get("key:" + seg[6]);
                if (upload == null) return new PersonalAgentSessionAccess.Reply(404, error("NOT_FOUND"));
                if (digest == null || !digest.matches("[0-9a-fA-F]{64}"))
                    return new PersonalAgentSessionAccess.Reply(400, error("MATERIAL_CHUNK_DIGEST_REQUIRED"));
                if (bytes == null || bytes.length < 1)
                    return new PersonalAgentSessionAccess.Reply(422, error("MATERIAL_CHUNK_TOO_LARGE"));
                if (!PersonalAccessService.hashBytes(bytes).equalsIgnoreCase(digest))
                    return new PersonalAgentSessionAccess.Reply(409, error("MATERIAL_CHUNK_DIGEST_MISMATCH"));
                long offset = Long.parseLong(seg[8]);
                byte[] prior = chunkBytes.put(seg[6] + "/" + offset, bytes);
                if (prior != null && !Arrays.equals(prior, bytes))
                    return new PersonalAgentSessionAccess.Reply(409, error("MATERIAL_CHUNK_OFFSET"));
                if (offset < upload.path("nextOffset").asLong()) return new PersonalAgentSessionAccess.Reply(200, upload);
                ObjectNode updated = upload.deepCopy().put("nextOffset", offset + bytes.length);
                uploads.put("key:" + seg[6], updated);
                return new PersonalAgentSessionAccess.Reply(200, updated);
            }
            return new PersonalAgentSessionAccess.Reply(404, error("NOT_FOUND"));
        }
    }

    private ObjectNode error(String code) { return mapper.createObjectNode().put("code", code); }

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("pac-agent-");
        db = new ControlPlaneDatabase(root, false);
        store = new PersonalAccessStore(db, mapper);
        auth = new PersonalAccessService(store, mapper, new byte[32], "store-test", "https://localhost:9443");
        String iid = UUID.randomUUID().toString(), rid = UUID.randomUUID().toString(), poll = UUID.randomUUID().toString();
        ObjectNode invite = auth.invitation(iid, Arrays.asList("session-parent"),
                new HashSet<String>(Arrays.asList("OBSERVE")), "cert");
        auth.request(mapper.createObjectNode().put("inviteId", iid)
                .put("inviteSecret", invite.path("inviteSecret").asText()).put("pairingRequestId", rid)
                .put("pollingSecretHash", PersonalAccessService.hash(poll))
                .put("installationId", UUID.randomUUID().toString()).put("label", "phone"));
        auth.decide(rid, true);
        ObjectNode credential = auth.redeem(rid, poll, "redeem");
        token = credential.path("accessToken").asText();
        did = credential.path("deviceGrantId").asText();
        upstream = new FakeUpstream();
        access = new PersonalAgentSessionAccess(store, auth, mapper, upstream);
    }

    @After public void cleanup() throws Exception {
        db.close();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    private void denied(String code, ThrowingCall call) {
        try { call.run(); fail("Expected " + code); }
        catch (BusinessException e) { assertEquals(code, e.getCode()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private interface ThrowingCall { void run() throws Exception; }

    private void scope(String requestId, String revision, List<PersonalResourceScope.Resource> resources, String... actions) {
        auth.updateScopeResources(did, requestId, revision, resources,
                java.util.Collections.<PersonalResourceScope.LaunchScope>emptyList(),
                new TreeSet<String>(Arrays.asList(actions)));
    }

    private ObjectNode createBody(String key) {
        return mapper.createObjectNode().put("requestKey", key).put("title", "mobile session");
    }

    private long revision() { return auth.authenticate(token).revision; }

    private void deliverFixtures(String turnState) {
        upstream.gets.put("GET /api/v2/sessions/aux-one", mapper.createObjectNode()
                .put("sessionId", "aux-one").put("sessionPurpose", "AUXILIARY_READ_ONLY")
                .put("parentSessionId", "session-parent"));
        upstream.gets.put("GET /api/v2/sessions/aux-one/auxiliary-turns/turn-one", mapper.createObjectNode()
                .put("turnId", "turn-one").put("sessionId", "aux-one")
                .put("parentSessionId", "session-parent").put("state", turnState)
                .put("answer", "verified guidance"));
        upstream.gets.put("GET /api/v2/sessions/session-parent", mapper.createObjectNode()
                .put("sessionId", "session-parent").put("activeRunId", "run-one"));
        upstream.gets.put("GET /api/v2/runs/run-one", mapper.createObjectNode()
                .put("runId", "run-one").put("sessionId", "session-parent"));
        upstream.gets.put("GET /api/v2/runs/run-one/execution-segments", mapper.createObjectNode()
                .set("current", mapper.createObjectNode().put("executionEpoch", "3")));
    }

    @Test public void createNeedsCreateGrantAndAppendsOnlyTheProvenSession() throws Exception {
        denied("DEVICE_SCOPE_DENIED", () -> access.createSession(token, createBody("c0"), "c0"));
        scope("create-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "CREATE");
        PersonalAgentSessionAccess.Result result = access.createSession(token, createBody("c1"), "c1");
        assertEquals(200, result.status);
        assertEquals("OBSERVED", result.body.path("state").asText());
        assertEquals("session-created-0", result.body.path("sessionId").asText());
        // Only the created session became visible; unrelated sessions stay denied.
        assertNotNull(auth.authorize(token, "session-created-0", "OBSERVE"));
        denied("DEVICE_SCOPE_DENIED", () -> auth.authorize(token, "session-other", "OBSERVE"));
        denied("DEVICE_SCOPE_DENIED", () -> auth.authorize(token, "session-created-0", "CONTROL"));
        // Exact replay is one durable fact; no second upstream create.
        PersonalAgentSessionAccess.Result replay = access.createSession(token, createBody("c1"), "c1");
        assertEquals("OBSERVED", replay.body.path("state").asText());
        assertEquals("session-created-0", replay.body.path("sessionId").asText());
        assertEquals(1, upstream.createCalls.get());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",
                () -> access.createSession(token, createBody("c1").put("title", "changed"), "c1"));
    }

    @Test public void createOriginIsServerInjectedAndClientOriginRejected() throws Exception {
        scope("create-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "CREATE");
        denied("INVALID_REQUEST", () -> access.createSession(token,
                createBody("c2").put("origin", mapper.createObjectNode().put("clientType", "FORGED")), "c2"));
        PersonalAgentSessionAccess.Result result = access.createSession(token, createBody("c3"), "c3");
        assertEquals("OBSERVED", result.body.path("state").asText());
        JsonNode wire = upstream.sessions.values().iterator().next().path("origin");
        assertEquals("MOBILE", wire.path("clientType").asText());
        assertEquals("phone", wire.path("deviceDisplayName").asText());
    }

    @Test public void lostCreateReplyRecoversThroughTheOriginalKey() throws Exception {
        scope("create-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "CREATE");
        upstream.failures.add(new IOException("reply lost"));
        PersonalAgentSessionAccess.Result lost = access.createSession(token, createBody("c4"), "c4");
        assertEquals("UNKNOWN", lost.body.path("state").asText());
        assertEquals("LOCAL_REPLY_UNCONFIRMED", lost.body.path("code").asText());
        // Recovery replays the identical wire under the same namespaced key; the
        // upstream ledger dedups it into the same session instead of a second one.
        PersonalAgentSessionAccess.Result recovered = access.sessionRequest(token, "c4");
        assertEquals("OBSERVED", recovered.body.path("state").asText());
        assertEquals("session-created-0", recovered.body.path("sessionId").asText());
        assertNotNull(auth.authorize(token, "session-created-0", "OBSERVE"));
        denied("NOT_FOUND", () -> access.sessionRequest(token, "never-admitted"));
    }

    @Test public void rejectedCreateIsTerminalAndNeverRedispatched() throws Exception {
        scope("create-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "CREATE");
        upstream.createStatus = 422;
        PersonalAgentSessionAccess.Result rejected = access.createSession(token, createBody("c5"), "c5");
        assertEquals("REJECTED", rejected.body.path("state").asText());
        upstream.createStatus = 201;
        PersonalAgentSessionAccess.Result again = access.sessionRequest(token, "c5");
        assertEquals("REJECTED", again.body.path("state").asText());
        assertEquals(1, upstream.createCalls.get());
    }

    @Test public void deliverProjectsAuxAnswerIntoOneContextAdviceInput() throws Exception {
        deliverFixtures("SUCCEEDED");
        scope("deliver-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT");
        PersonalAgentSessionAccess.Result result = access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d1"), "d1");
        assertEquals("OBSERVED", result.body.path("state").asText());
        assertTrue(result.body.path("boundaryRequestId").asText().startsWith("mobile-"));
        ObjectNode receipt = upstream.boundaries.values().iterator().next();
        assertEquals("run-one", receipt.path("runId").asText());
        assertEquals("CONTEXT_ADVICE", receipt.path("kind").asText());
        // Replay reads the original key in place; no second boundary input.
        PersonalAgentSessionAccess.Result replay = access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d1"), "d1");
        assertEquals("OBSERVED", replay.body.path("state").asText());
        assertEquals(1, upstream.boundaryPosts.get());
    }

    @Test public void deliverRefusesNonTerminalOrChangedAnswersAndForeignSessions() throws Exception {
        deliverFixtures("RUNNING");
        scope("deliver-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT");
        denied("AUXILIARY_ANSWER_NOT_TERMINAL", () -> access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d2"), "d2"));
        deliverFixtures("SUCCEEDED");
        denied("AUXILIARY_ANSWER_CHANGED", () -> access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d3").put("expectedAnswerRevision", "0".repeat(64)), "d3"));
        // A main session cannot be used as the aux handle.
        upstream.gets.put("GET /api/v2/sessions/main-one", mapper.createObjectNode()
                .put("sessionId", "main-one").put("sessionPurpose", "MAIN"));
        denied("DEVICE_SCOPE_DENIED", () -> access.deliver(token, "main-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d4"), "d4"));
        // Without INTERACT on the parent nothing is admitted.
        scope("observer-scope", "2",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE");
        denied("DEVICE_SCOPE_DENIED", () -> access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d5"), "d5"));
        assertEquals(0, upstream.boundaryPosts.get());
    }

    @Test public void clientKeysArePerOperationAndCrossKindReuseConflicts() throws Exception {
        deliverFixtures("SUCCEEDED");
        scope("both-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT", "CREATE");
        access.createSession(token, createBody("shared"), "shared");
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH", () -> access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "shared"), "shared"));
        access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d6"), "d6");
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",
                () -> access.createSession(token, createBody("d6"), "d6"));
    }

    @Test public void rejectedDeliverIsTerminalAndInFlightKeysAreNotDoubled() throws Exception {
        deliverFixtures("SUCCEEDED");
        scope("deliver-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT");
        access.deliver(token, "aux-one", "turn-one", mapper.createObjectNode().put("requestKey", "d7"), "d7");
        PersonalAgentSessionAccess.Result again = access.sessionRequest(token, "d7");
        assertEquals("OBSERVED", again.body.path("state").asText());
        assertEquals(1, upstream.boundaryPosts.get());
        // A rejected upstream admission is recovered without a redispatch.
        upstream.boundaryStatus = 409;
        PersonalAgentSessionAccess.Result conflicted = access.deliver(token, "aux-one", "turn-one",
                mapper.createObjectNode().put("requestKey", "d8"), "d8");
        assertEquals("REJECTED", conflicted.body.path("state").asText());
        upstream.boundaryStatus = 202;
        PersonalAgentSessionAccess.Result stayed = access.sessionRequest(token, "d8");
        assertEquals("REJECTED", stayed.body.path("state").asText());
        assertEquals(2, upstream.boundaryPosts.get());
    }

    // ===== acs-material ledger: project material-uploads profile =====

    private ObjectNode uploadBody(String key, String targetField, String targetValue) {
        ObjectNode body = mapper.createObjectNode().put("requestKey", key)
                .put("displayName", "note.txt").put("mediaType", "text/plain")
                .put("sizeBytes", 16).put("sha256", "a".repeat(64));
        body.putObject("target").put(targetField, targetValue);
        return body;
    }
    private ObjectNode chunkBody(String key, String data) {
        byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return mapper.createObjectNode().put("requestKey", key)
                .put("chunkDigest", PersonalAccessService.hashBytes(bytes))
                .put("dataBase64", java.util.Base64.getEncoder().encodeToString(bytes));
    }
    private String[] tail(String... parts) { return parts; }

    @Test public void acsMaterialUploadLifecycleUsesDurableLedger() throws Exception {
        scope("material-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT");
        PersonalAgentSessionAccess.Result created = access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("m1", "sessionId", "session-parent"), "m1");
        assertEquals("OBSERVED", created.body.path("state").asText());
        assertEquals("MATERIAL_UPLOAD_CREATE", created.body.path("operation").asText());
        assertEquals("session-parent", created.body.path("sessionId").asText());
        String uploadKey = created.body.path("source").path("uploadKey").asText();
        assertEquals("mat-0", uploadKey);
        // Exact replay resolves in place; the upstream ledger dedups the key so
        // the identical replayed wire still yields one upstream admission.
        PersonalAgentSessionAccess.Result replay = access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("m1", "sessionId", "session-parent"), "m1");
        assertEquals("OBSERVED", replay.body.path("state").asText());
        assertEquals("mat-0", replay.body.path("source").path("uploadKey").asText());
        assertEquals(1, upstream.uploads.values().stream().map(u -> u.path("uploadKey").asText()).distinct().count());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH", () -> access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("m1", "sessionId", "session-parent").put("displayName", "other.txt"), "m1"));
        // Chunks verify the declared digest before dispatch.
        denied("MATERIAL_CHUNK_DIGEST_MISMATCH", () -> access.materialUpload(token, "project-one",
                tail(uploadKey, "chunks", "0"), "PUT",
                chunkBody("c-bad", "data").put("chunkDigest", "f".repeat(64)), "c-bad"));
        PersonalAgentSessionAccess.Result chunk = access.materialUpload(token, "project-one",
                tail(uploadKey, "chunks", "0"), "PUT", chunkBody("c1", "chunk-zero"), "c1");
        assertEquals("OBSERVED", chunk.body.path("state").asText());
        assertEquals(10, chunk.body.path("source").path("nextOffset").asLong());
        PersonalAgentSessionAccess.Result sealed = access.materialUpload(token, "project-one",
                tail(uploadKey, "seal"), "POST",
                mapper.createObjectNode().put("requestKey", "s1").put("sha256", "a".repeat(64)), "s1");
        assertEquals("SEALED", sealed.body.path("source").path("state").asText());
        PersonalAgentSessionAccess.Result read = access.materialUpload(token, "project-one",
                tail(uploadKey), "GET", null, null);
        assertEquals(200, read.status);
        assertEquals(uploadKey, read.body.path("uploadKey").asText());
        assertEquals("SEALED", read.body.path("state").asText());
        PersonalAgentSessionAccess.Result deleted = access.materialUpload(token, "project-one",
                tail(uploadKey), "DELETE", null, "del-1");
        assertEquals("MATERIAL_UPLOAD_CANCEL", deleted.body.path("operation").asText());
        assertEquals("CANCELLED", deleted.body.path("source").path("state").asText());
    }

    @Test public void acsMaterialLostReplyRecoversOriginalWireUnderOriginalKey() throws Exception {
        scope("material-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT");
        upstream.failures.add(new IOException("reply lost"));
        PersonalAgentSessionAccess.Result lost = access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("m2", "sessionId", "session-parent"), "m2");
        assertEquals("UNKNOWN", lost.body.path("state").asText());
        // Recovery replays the identical stored wire under the original namespaced
        // key; the upstream dedup keeps it a single admission.
        PersonalAgentSessionAccess.Result recovered = access.sessionRequest(token, "m2");
        assertEquals("OBSERVED", recovered.body.path("state").asText());
        assertEquals("mat-0", recovered.body.path("source").path("uploadKey").asText());
        denied("NOT_FOUND", () -> access.sessionRequest(token, "never-admitted"));
    }

    @Test public void acsMaterialUploadEnforcesBindingWhitelistAndGrants() throws Exception {
        scope("material-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT");
        // Unknown fields and key mismatches never reach upstream.
        denied("INVALID_REQUEST", () -> access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("w1", "sessionId", "session-parent").put("url", "http://x"), "w1"));
        denied("IDEMPOTENCY_KEY_MISMATCH", () -> access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("w2", "sessionId", "session-parent"), "w2-other"));
        // A session outside the device scope cannot be bound.
        denied("DEVICE_SCOPE_DENIED", () -> access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("w3", "sessionId", "session-other"), "w3"));
        // createKey binds a CREATE grant, not an existing session.
        denied("DEVICE_SCOPE_DENIED", () -> access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("w4", "createKey", "future-task"), "w4"));
        assertEquals(0, upstream.uploadPosts.get());
        scope("create-scope", "2",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "INTERACT", "CREATE");
        PersonalAgentSessionAccess.Result created = access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("w5", "createKey", "future-task"), "w5");
        assertEquals("OBSERVED", created.body.path("state").asText());
        // A target carrying neither an authorized session nor a createKey is refused.
        ObjectNode unbound = uploadBody("w6", "taskId", "placeholder");
        unbound.putObject("target").putNull("taskId");
        denied("LOCAL_BINDING_MISMATCH", () -> access.materialUpload(token, "project-one",
                tail(), "POST", unbound, "w6"));
        // Cross-kind key reuse conflicts instead of shadowing another ledger.
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH", () -> access.materialUpload(token, "project-one",
                tail(), "POST", uploadBody("w5", "sessionId", "session-parent"), "w5"));
    }

    @Test public void dispatchStartedAtIsTheDurableDispatchStampAndSurvivesRecovery() throws Exception {
        scope("dispatch-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-test", "session-parent")),
                "OBSERVE", "CREATE");
        PersonalAgentSessionAccess.Result result = access.createSession(token, createBody("t1"), "t1");
        // The ledger stamped the real dispatch moment; no client or clock guess.
        assertTrue(result.body.path("dispatchStartedAt").isIntegralNumber());
        long stamped = result.body.path("dispatchStartedAt").asLong();
        assertTrue(stamped > 0 && stamped <= System.currentTimeMillis());
        // Replay recovers the identical durable stamp rather than a fresh time.
        PersonalAgentSessionAccess.Result replay = access.sessionRequest(token, "t1");
        assertEquals(stamped, replay.body.path("dispatchStartedAt").asLong());
    }
}
