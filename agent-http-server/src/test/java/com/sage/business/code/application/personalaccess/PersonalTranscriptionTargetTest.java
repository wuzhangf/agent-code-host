package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Transcription target is exactly one branch: an authorized ACS session id
 *  XOR a native {backendStoreId,taskId}; authorization follows the recorded
 *  target, never the caller's claim. */
public class PersonalTranscriptionTargetTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Path root; private ControlPlaneDatabase db;
    private PersonalAccessStore store; private PersonalAccessService auth;
    private PersonalTranscriptionService service;
    private String token, did;
    private final Fake provider = new Fake();

    static class Fake implements PersonalTranscriptionService.Provider {
        final AtomicInteger calls = new AtomicInteger();
        public String binding() { return "test-offline"; }
        public String transcribe(String id, byte[] pcm, String lang, BooleanSupplier cancelled) throws Exception {
            calls.incrementAndGet(); return "native transcript";
        }
        public void close() { }
    }

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("pac-voice-target-");
        db = new ControlPlaneDatabase(root, false);
        store = new PersonalAccessStore(db, mapper);
        auth = new PersonalAccessService(store, mapper, new byte[32], "store-voice", "https://localhost:9443");
        String iid = UUID.randomUUID().toString(), rid = UUID.randomUUID().toString(), poll = UUID.randomUUID().toString();
        ObjectNode invite = auth.invitation(iid, Arrays.asList("session-one"),
                new HashSet<String>(Arrays.asList("OBSERVE", "INTERACT")), "cert");
        auth.request(mapper.createObjectNode().put("inviteId", iid)
                .put("inviteSecret", invite.path("inviteSecret").asText()).put("pairingRequestId", rid)
                .put("pollingSecretHash", PersonalAccessService.hash(poll))
                .put("installationId", UUID.randomUUID().toString()).put("label", "phone"));
        auth.decide(rid, true);
        ObjectNode credential = auth.redeem(rid, poll, "redeem");
        token = credential.path("accessToken").asText();
        did = credential.path("deviceGrantId").asText();
        // Replace the scope with both targets; the update is the whole resource list.
        auth.updateScopeResources(did, "native-scope", "1",
                Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL", "native-store", "native-task"),
                        new PersonalResourceScope.Resource("ACS_SESSION", "store-voice", "session-one")),
                java.util.Collections.<PersonalResourceScope.LaunchScope>emptyList(),
                new TreeSet<String>(Arrays.asList("OBSERVE", "INTERACT")));
        service = new PersonalTranscriptionService(store, auth, mapper, provider);
    }

    @After public void cleanup() throws Exception {
        service.close(); db.close();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    static byte[] wav() {
        ByteBuffer b = ByteBuffer.allocate(6444).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(6436).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(6400);
        return b.array();
    }

    private void denied(String code, Runnable call) {
        try { call.run(); fail("Expected " + code); }
        catch (BusinessException e) { assertEquals(code, e.getCode()); }
    }

    private ObjectNode completed(String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        ObjectNode result;
        do {
            result = service.get(token, key);
            if (!Arrays.asList("ACCEPTED", "DISPATCHED").contains(result.path("state").asText())) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("request not completed");
    }

    @Test public void targetIsExclusiveXorOfSessionAndNativeTask() throws Exception {
        byte[] wav = wav(); String hash = PersonalTranscriptionService.digest(wav);
        denied("INVALID_REQUEST", () -> service.submit(token, "both", "session-one",
                "native-store", "native-task", hash, wav, "auto"));
        denied("INVALID_REQUEST", () -> service.submit(token, "neither", null,
                null, null, hash, wav, "auto"));
        denied("INVALID_REQUEST", () -> service.submit(token, "half", null,
                "native-store", null, hash, wav, "auto"));
        assertEquals(0, provider.calls.get());
    }

    @Test public void nativeTargetAuthorizesAgainstLocalTaskAndProjectsItsRef() throws Exception {
        byte[] wav = wav(); String hash = PersonalTranscriptionService.digest(wav);
        denied("DEVICE_SCOPE_DENIED", () -> service.submit(token, "wrong-store", null,
                "other-store", "native-task", hash, wav, "auto"));
        denied("DEVICE_SCOPE_DENIED", () -> service.submit(token, "wrong-task", null,
                "native-store", "other-task", hash, wav, "auto"));
        ObjectNode accepted = service.submit(token, "native-one", null,
                "native-store", "native-task", hash, wav, "auto");
        assertEquals("ACCEPTED", accepted.path("state").asText());
        ObjectNode done = completed("native-one");
        assertEquals("SUCCEEDED", done.path("state").asText());
        assertEquals("native transcript", done.path("text").asText());
        assertFalse(done.has("sessionId"));
        assertEquals("native-store", done.path("target").path("storeId").asText());
        assertEquals("native-task", done.path("target").path("taskId").asText());
        assertEquals(1, provider.calls.get());
    }

    @Test public void requestKeyBindsTheExactTargetFingerprint() throws Exception {
        byte[] wav = wav(); String hash = PersonalTranscriptionService.digest(wav);
        service.submit(token, "same-key", "session-one", null, null, hash, wav, "auto");
        completed("same-key");
        // Same key with a different target kind is a payload mismatch, not a replay.
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH", () -> service.submit(token, "same-key", null,
                "native-store", "native-task", hash, wav, "auto"));
        byte[] different = wav(); different[44] = 1;
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH", () -> service.submit(token, "same-key", "session-one",
                null, null, PersonalTranscriptionService.digest(different), different, "auto"));
    }

    @Test public void readBackFollowsTheRecordedTargetBranch() throws Exception {
        byte[] wav = wav(); String hash = PersonalTranscriptionService.digest(wav);
        service.submit(token, "scoped", null, "native-store", "native-task", hash, wav, "auto");
        completed("scoped");
        // Drop the native resource; the recorded target keeps controlling visibility.
        auth.updateScopeResources(did, "drop-native", "2",
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "store-voice", "session-one")),
                java.util.Collections.<PersonalResourceScope.LaunchScope>emptyList(),
                new TreeSet<String>(Arrays.asList("OBSERVE", "INTERACT")));
        denied("DEVICE_SCOPE_DENIED", () -> service.get(token, "scoped"));
        denied("DEVICE_SCOPE_DENIED", () -> service.cancel(token, "scoped"));
    }
}
