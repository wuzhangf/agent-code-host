package com.sage.business.code.ma001;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sage.business.code.bootstrap.BusinessServiceFactory;
import com.sage.business.code.bootstrap.ServiceConfig;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneConnections;
import com.sage.business.code.infrastructure.controlplane.SqliteStatementRecorder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;
import org.junit.runners.MethodSorters;

/**
 * MA00 boundary evidence for the model-catalog authority consolidation
 * (coordination: EnterpriseDevopsAI docs/tasks/MA-001). Each case boots the
 * REAL {@link ServiceConfig#load} + {@link BusinessServiceFactory#createGui}
 * composition against a deterministic loopback enterprise catalog endpoint
 * and a deterministic loopback model receiver — no mocked model port, no
 * file seed, no paid endpoint. The {@link SqliteStatementRecorder} wraps the
 * single {@link ControlPlaneConnections} acquisition seam so every real
 * {@code Statement}/{@code PreparedStatement} the service issues is
 * observed at the driver boundary.
 *
 * <p>Credential material is never asserted by value — the receivers record
 * only whether an Authorization header arrived ({@code hadAuthHeader}).</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ModelAuthorityBoundaryIT {

    // ---- enterprise plane identity (deterministic, secret-free) ----
    private static final String PROVIDER_ID = "msvc-mvtest";
    /** Second provider in the same release — MV09 epoch swap / MV16 ambiguity. */
    private static final String PROVIDER_ID_B = "msvc-mvtest-b";
    private static final String STABLE_MODEL_KEY = "code-primary";
    private static final String WIRE_MODEL_A = "wire-model-a";
    /** Published only as a decoy: the catalog carries it so the sqlite bypass resolves "successfully" to the wrong wire id. */
    private static final String TRAP_WIRE_MODEL = "trap-wire-x";
    private static final String PROFILE_ID = "mprf-mvtest";
    private static final String RELEASE_ID_A = "mrel-mvtest-a";
    /** The refresh-poisoning release the codec must reject (MV10). */
    private static final String RELEASE_ID_B = "mrel-mvtest-b";
    private static final String AUTHORITY_ID = "enterprise-model-control";
    private static final String SERVICE_TOKEN = "ma00-it-0123456789abcdef";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SqliteStatementRecorder RECORDER =
            new SqliteStatementRecorder();

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static String priorObservationMode;

    @BeforeClass
    public static void installRecorder() {
        priorObservationMode = System.getProperty("observation.mode");
        System.setProperty("observation.mode", "DISABLED");
        ControlPlaneConnections.installDecorator(RECORDER);
    }

    @AfterClass
    public static void removeRecorder() {
        ControlPlaneConnections.clearDecorator();
        if (priorObservationMode == null) {
            System.clearProperty("observation.mode");
        } else {
            System.setProperty("observation.mode", priorObservationMode);
        }
    }

    // ------------------------------------------------------------------
    // MV01 — fresh enterprise state: personal model tables must not be
    // created, read or written at all.
    // ------------------------------------------------------------------
    @Test
    public void mv01_freshEnterpriseBootTouchesNoModelTables() throws Exception {
        Harness h = bootEnterpriseService(false);
        try {
            // control: the service is really up and the catalog applied
            JsonNode catalog = api(h, "GET", "/api/v2/model-catalog", null);
            Assert.assertEquals(200, catalog.get("status").asInt());

            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            if (!modelStatements.isEmpty()) {
                StringBuilder dump = new StringBuilder(
                        "enterprise boot touched personal model tables "
                                + modelStatements.size() + " times:\n");
                for (SqliteStatementRecorder.Entry e : modelStatements) {
                    dump.append("  ").append(e.toString()).append('\n');
                }
                Assert.fail(dump.toString());
            }
            Assert.assertTrue("enterprise authority never got a catalog request",
                    h.enterprise.hits("/api/v1/model-runtime/catalog") > 0);
            Assert.assertTrue("authority saw no apply report after install",
                    h.enterprise.hits(
                            "/api/v1/model-runtime/catalog/applications") > 0);
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV02 — poisoned personal rows: the same key pointing at a different
    // paid endpoint must be unread, unwritten and unrouted.
    // ------------------------------------------------------------------
    @Test
    public void mv02_poisonedPersonalRowsAreUntouchedAndUnread() throws Exception {
        Harness h = bootEnterpriseService(true);
        try {
            JsonNode created = api(h, "POST", "/api/v2/sessions",
                    "{\"schemaVersion\":\"business-session/2.0\","
                            + "\"title\":\"MA00-MV02\"}");
            Assert.assertEquals("session create failed: " + created,
                    201, created.get("status").asInt());
            Assert.assertEquals("trap/personal endpoint received a model request",
                    0, h.trap.hits(null));
            // The poisoned rows must be byte-identical afterwards.
            Assert.assertEquals(h.poisonedRowsBefore, readPoisonedRows(h.stateDir));

            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            if (!modelStatements.isEmpty()) {
                StringBuilder dump = new StringBuilder(
                        "enterprise session creation issued model-table SQL:\n");
                for (SqliteStatementRecorder.Entry e : modelStatements) {
                    dump.append("  ").append(e.toString()).append('\n');
                }
                Assert.fail(dump.toString());
            }
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV03 — stable model key must resolve through the published snapshot,
    // never through the personal sqlite remote_model_id mapping.
    // ------------------------------------------------------------------
    @Test
    public void mv03_stableKeyResolvesThroughSnapshotNotSqlite() throws Exception {
        Harness h = bootEnterpriseService(true);
        try {
            // Boot-plane diagnostics first: a degraded catalog makes the
            // downstream assertions meaningless.
            com.sage.business.code.bootstrap.EffectiveModelContext ctx =
                    h.services.modelContext.current();
            Assert.assertNotNull("no effective model context", ctx);
            Assert.assertEquals("enterprise catalog did not assemble READY "
                            + "(catalogError=" + ctx.catalogError() + ", "
                            + "enterprise hits=" + h.enterprise.paths + ")",
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY, ctx.readiness());
            JsonNode created = api(h, "POST", "/api/v2/sessions",
                    "{\"schemaVersion\":\"business-session/2.0\","
                            + "\"title\":\"MA00-MV03\"}");
            Assert.assertEquals("session create failed: " + created,
                    201, created.get("status").asInt());
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);

            // A stale personal-era session pin. Every API surface that
            // could write modelAlias re-validates through the shared
            // resolver inside the session transaction; under today's
            // sqlite-bypass implementation that validation itself is the
            // violation (a nested inTransaction on the same connection
            // breaks the outer commit — STATE_STORE_ERROR 503). The row
            // shape a legacy personal-era session actually carried is
            // planted directly instead: the question under test is which
            // authority SEND-TIME resolution consults.
            seedStaleStableKeyPin(h.stateDir, sessionId);

            // The send-time window starts here: boot-time model-table SQL
            // is MV01's separate red assertion - this case discriminates
            // only which authority send-time key resolution consults.
            h.recorderWindowStart = RECORDER.entries().size();
            JsonNode message = api(h, "POST",
                    "/api/v2/sessions/" + sessionId + "/messages",
                    "{\"clientMessageId\":\"mv03-msg-1\","
                            + "\"content\":\"reply with the letter a\","
                            + "\"expectedSessionVersion\":" + version + "}");
            Assert.assertTrue("message POST returned " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);

            Assert.assertEquals("model receiver got no request",
                    1, h.model.hits("/v1/chat/completions"));
            String wireModel = h.model.lastModelId();
            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            StringBuilder dump = new StringBuilder();
            for (SqliteStatementRecorder.Entry e : modelStatements) {
                dump.append("  ").append(e.toString()).append('\n');
            }
            Assert.assertEquals(
                    "stable key '" + STABLE_MODEL_KEY
                            + "' must route to the snapshot's remoteModelId '"
                            + WIRE_MODEL_A + "', not the poisoned sqlite row"
                            + " (trap endpoint hits=" + h.trap.hits(null)
                            + ", model-table SQL issued during send-time"
                            + " resolution:\n" + dump + ")",
                    WIRE_MODEL_A, wireModel);
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));
            if (!modelStatements.isEmpty()) {
                Assert.fail("stable-key resolution consulted personal model"
                        + " tables instead of the snapshot:\n" + dump);
            }
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV10 - a load/decode/prepare failure on refresh keeps the previous
    // context fully intact (context, resolver, port, public catalog) and
    // files no APPLIED receipt for the rejected release (design T5.1).
    // ------------------------------------------------------------------
    @Test
    public void mv10_latePrepareFailureKeepsPreviousContext() throws Exception {
        Harness h = bootEnterpriseService(false);
        try {
            com.sage.business.code.bootstrap.EffectiveModelContext applied =
                    h.services.modelContext.current();
            Assert.assertNotNull("no effective model context", applied);
            Assert.assertEquals("enterprise catalog did not assemble READY",
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY, applied.readiness());
            com.sage.business.code.application.model.ModelSelectionResolver
                    appliedResolver = h.services.modelContext.resolver();
            JsonNode catalogBefore = api(h, "GET",
                    "/api/v2/model-catalog", null);
            Assert.assertEquals(200, catalogBefore.get("status").asInt());

            // The next poll serves an undecodable release B:
            // GATEWAY_IDENTITY is a credential kind this adapter honestly
            // cannot materialize - the codec must reject the release
            // rather than let the host patch around it.
            h.enterprise.swapCatalog(invalidCatalogJson(h.model.port()));

            Assert.assertFalse("a rejected release must not apply",
                    h.services.modelContext.refresh());
            Assert.assertSame(
                    "failed refresh must keep the previous context live",
                    applied, h.services.modelContext.current());
            Assert.assertEquals(
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY,
                    h.services.modelContext.current().readiness());
            Assert.assertSame("resolver must not be rebuilt off release B",
                    appliedResolver, h.services.modelContext.resolver());
            Assert.assertEquals("readiness fingerprint must stay release A",
                    applied.effectiveFingerprint(),
                    h.services.modelContext.current().effectiveFingerprint());

            // Ordinary chat still routes the applied release A.
            JsonNode created = createSession(h, "MA01-MV10");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode message = sendMessage(h, sessionId, version,
                    "mv10-msg-1");
            Assert.assertTrue("message POST returned " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);
            Assert.assertEquals("model receiver got no request",
                    1, h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("rejected release must not change routing",
                    WIRE_MODEL_A, h.model.lastModelId());
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));

            // The public catalog still projects the applied context A.
            JsonNode catalogAfter = api(h, "GET",
                    "/api/v2/model-catalog", null);
            Assert.assertEquals(200, catalogAfter.get("status").asInt());
            Assert.assertEquals("public catalog changed after failed apply",
                    catalogBefore.get("body"), catalogAfter.get("body"));

            // Release B was rejected by the codec, so no receipt -
            // APPLIED or APPLY_FAILED - may reference it; the only
            // filing on record is A's boot APPLIED.
            List<JsonNode> receipts = applicationReceipts(h);
            Assert.assertEquals("unexpected apply receipts filed",
                    1, receipts.size());
            Assert.assertEquals(RELEASE_ID_A,
                    receipts.get(0).path("releaseId").asText());
            Assert.assertEquals("APPLIED",
                    receipts.get(0).path("status").asText());
            for (JsonNode receipt : receipts) {
                Assert.assertNotEquals(
                        "receipt filed for the rejected release",
                        RELEASE_ID_B, receipt.path("releaseId").asText());
            }
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV15 - a receipt-endpoint failure does not un-publish the applied
    // context: the release stays live as REPORT_PENDING and the next
    // refresh resends exactly that receipt - no model calls, no
    // re-publish of an identical digest (design 4.3/T5).
    // ------------------------------------------------------------------
    @Test
    public void mv15_receiptFailureKeepsPublishedContextWithPendingReceipt()
            throws Exception {
        Harness h = bootEnterpriseService(false, 1);
        try {
            // Publish succeeded even though the receipt endpoint refused
            // the filing: the applied context is live and READY, and the
            // unfiled fact is parked as REPORT_PENDING.
            com.sage.business.code.bootstrap.EffectiveModelContext applied =
                    h.services.modelContext.current();
            Assert.assertNotNull("no effective model context", applied);
            Assert.assertEquals("enterprise catalog did not assemble READY",
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY, applied.readiness());
            Assert.assertTrue(
                    "receipt-endpoint failure must park REPORT_PENDING",
                    h.services.modelContext.hasPendingReceipt());
            Assert.assertNotNull("no pending receipt was retained",
                    h.services.modelContext.pendingReceipt());
            Assert.assertEquals(RELEASE_ID_A,
                    h.services.modelContext.pendingReceipt().getReleaseId());
            Assert.assertEquals("APPLIED",
                    h.services.modelContext.pendingReceipt()
                            .getStatus().name());
            Assert.assertEquals("boot must have attempted the filing once",
                    1, applicationReceipts(h).size());

            // A working chat pins the model-call count the retry must
            // not touch.
            JsonNode created = createSession(h, "MA01-MV15");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode message = sendMessage(h, sessionId, version,
                    "mv15-msg-1");
            Assert.assertTrue("message POST returned " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);
            int modelHits = h.model.hits("/v1/chat/completions");
            Assert.assertEquals(1, modelHits);

            // The receipt endpoint is healthy again: the refresh resends
            // ONLY the parked receipt before loading - one POST, zero
            // model calls, zero re-publishes of the identical digest.
            Assert.assertTrue("refresh with a pending receipt failed",
                    h.services.modelContext.refresh());
            Assert.assertFalse("receipt must flush on the next refresh",
                    h.services.modelContext.hasPendingReceipt());
            List<JsonNode> receipts = applicationReceipts(h);
            Assert.assertEquals(
                    "receipt retry must resend the same fact exactly once",
                    2, receipts.size());
            for (JsonNode receipt : receipts) {
                Assert.assertEquals(RELEASE_ID_A,
                        receipt.path("releaseId").asText());
                Assert.assertEquals("APPLIED",
                        receipt.path("status").asText());
            }
            Assert.assertSame(
                    "identical consumer digest must not re-publish",
                    applied, h.services.modelContext.current());
            Assert.assertEquals("receipt retry issued model calls",
                    modelHits, h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV25 (best-effort scope) - a process sysprop default pointing at a
    // different wire name must not override the applied snapshot's
    // default selection. Scoped capability-param plumbing beyond the
    // current ModelRequest is out of this slice's reach; the
    // env/sysprop-override half is the discriminator here.
    // ------------------------------------------------------------------
    @Test
    public void mv25_snapshotDefaultNotOverriddenBySystemProperty()
            throws Exception {
        String priorModelDefault = System.getProperty("model.default");
        String priorDefaultModel = System.getProperty("MODEL_DEFAULT_MODEL");
        System.setProperty("model.default", TRAP_WIRE_MODEL);
        System.setProperty("MODEL_DEFAULT_MODEL", TRAP_WIRE_MODEL);
        try {
            Harness h = bootEnterpriseService(false);
            try {
                // The install bridge legitimately rewrites model.default
                // during boot - poison it again so any send-time sysprop
                // read would land on the trap receiver. The applied
                // snapshot's defaultSelection must still win.
                System.setProperty("model.default", TRAP_WIRE_MODEL);
                com.sage.business.code.bootstrap.EffectiveModelContext ctx =
                        h.services.modelContext.current();
                Assert.assertNotNull("no effective model context", ctx);
                Assert.assertEquals(
                        com.sage.business.code.bootstrap
                                .EffectiveModelContext.Readiness.READY,
                        ctx.readiness());

                // No session pin: the host-default resolution path is
                // exactly where a global default could interfere.
                JsonNode created = createSession(h, "MA01-MV25");
                String sessionId = created.at("/body/sessionId").asText();
                long version = created.at("/body/sessionVersion")
                        .asLong(1L);
                JsonNode message = sendMessage(h, sessionId, version,
                        "mv25-msg-1");
                Assert.assertTrue("message POST returned " + message,
                        message.get("status").asInt() == 200
                                || message.get("status").asInt() == 202);
                Assert.assertEquals("model receiver got no request",
                        1, h.model.hits("/v1/chat/completions"));
                Assert.assertEquals(
                        "snapshot default must win over the sysprop default",
                        WIRE_MODEL_A, h.model.lastModelId());
                Assert.assertEquals("trap endpoint received a model request",
                        0, h.trap.hits(null));
            } finally {
                h.close();
            }
        } finally {
            restoreProperty("model.default", priorModelDefault);
            restoreProperty("MODEL_DEFAULT_MODEL", priorDefaultModel);
        }
    }

    // ------------------------------------------------------------------
    // MV06 - the bytes the real EAC governance publish path emits
    // (ModelRuntimeCatalogMv06FixtureTest drives publishRelease for
    // releases A and B through the genuine service/projection code)
    // flow through the adapter and reroute ordinary chat to B's
    // endpoint + default with no local file or DB edit.
    // ------------------------------------------------------------------
    @Test
    public void mv06_realProducerPublishAtoBReroutesChat()
            throws Exception {
        final Path fixtures = eacFixtures();
        final ReceiverStub modelB = new ReceiverStub();
        modelB.replyChatCompletions();
        Harness h = bootEnterpriseService(false, 0,
                new java.util.function.Function<ReceiverStub, String>() {
                    @Override public String apply(ReceiverStub model) {
                        try {
                            return servedFixture(fixtures,
                                    "mv06-release-a.json", model.port());
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                });
        try {
            com.sage.business.code.bootstrap.EffectiveModelContext
                    contextA = h.services.modelContext.current();
            Assert.assertNotNull("no effective model context", contextA);
            Assert.assertEquals("release A did not assemble READY",
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY, contextA.readiness());
            Assert.assertNotNull("context A lost the producer snapshot",
                    contextA.enterpriseSnapshot());
            String releaseIdA =
                    contextA.enterpriseSnapshot().getReleaseId();
            Assert.assertTrue("producer releaseId A expected, got "
                            + releaseIdA,
                    releaseIdA != null && releaseIdA.startsWith("mrel-"));
            Assert.assertEquals("producer generation A", 1L,
                    contextA.enterpriseSnapshot().getGeneration());
            String digestA = contextA.consumerSnapshotDigest();
            Assert.assertNotNull("consumerSnapshotDigest absent on A",
                    digestA);

            JsonNode created = createSession(h, "MA01-MV06-A");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode first = sendMessage(h, sessionId, version,
                    "mv06-msg-a1");
            Assert.assertTrue("first chat returned " + first,
                    first.get("status").asInt() == 200
                            || first.get("status").asInt() == 202);
            Assert.assertEquals("release A endpoint got no request",
                    1, h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("release A must serve its published"
                            + " remoteModelId",
                    "wire-model-a", h.model.lastModelId());

            // Governance publishes release B: new default model, new
            // provider endpoint. The host refresh pulls it - no local
            // model file or DB row is edited, and the recorder window
            // over the swap must stay empty.
            h.recorderWindowStart = RECORDER.entries().size();
            h.enterprise.swapCatalog(servedFixture(fixtures,
                    "mv06-release-b.json", modelB.port()));
            Assert.assertTrue("release B refresh failed",
                    h.services.modelContext.refresh());
            com.sage.business.code.bootstrap.EffectiveModelContext
                    contextB = h.services.modelContext.current();
            Assert.assertNotSame(
                    "release B publish did not swap the context",
                    contextA, contextB);
            Assert.assertNotNull("context B lost the producer snapshot",
                    contextB.enterpriseSnapshot());
            String releaseIdB =
                    contextB.enterpriseSnapshot().getReleaseId();
            Assert.assertTrue("producer releaseId B expected, got "
                            + releaseIdB,
                    releaseIdB != null && releaseIdB.startsWith("mrel-")
                            && !releaseIdB.equals(releaseIdA));
            Assert.assertEquals("producer generation B", 2L,
                    contextB.enterpriseSnapshot().getGeneration());
            Assert.assertFalse("consumer digest must change with the"
                            + " published projection",
                    digestA.equals(contextB.consumerSnapshotDigest()));

            JsonNode createdB = createSession(h, "MA01-MV06-B");
            String sessionIdB = createdB.at("/body/sessionId").asText();
            long versionB = createdB.at("/body/sessionVersion").asLong(1L);
            JsonNode second = sendMessage(h, sessionIdB, versionB,
                    "mv06-msg-b1");
            Assert.assertTrue("second chat returned " + second,
                    second.get("status").asInt() == 200
                            || second.get("status").asInt() == 202);
            Assert.assertEquals("release B endpoint got no request",
                    1, modelB.hits("/v1/chat/completions"));
            Assert.assertEquals("release B must serve its published"
                            + " remoteModelId",
                    "wire-model-b", modelB.lastModelId());
            Assert.assertEquals("release A endpoint received B traffic",
                    1, h.model.hits("/v1/chat/completions"));

            boolean appliedB = false;
            for (JsonNode receipt : applicationReceipts(h)) {
                if (releaseIdB.equals(receipt.path("releaseId").asText())
                        && "APPLIED".equals(
                                receipt.path("status").asText())) {
                    appliedB = true;
                }
            }
            Assert.assertTrue(
                    "no APPLIED receipt was filed for release B",
                    appliedB);
            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            StringBuilder dump = new StringBuilder();
            for (SqliteStatementRecorder.Entry e : modelStatements) {
                dump.append("  ").append(e.toString()).append('\n');
            }
            Assert.assertTrue("the A-to-B swap read or wrote personal"
                            + " model tables:\n" + dump,
                    modelStatements.isEmpty());
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));
        } finally {
            h.close();
            modelB.close();
        }
    }

    /**
     * The committed EAC producer fixtures ({@code
     * verification/model-authority/fixtures/}, generated by the real
     * {@code publishRelease} path in {@code
     * ModelRuntimeCatalogMv06FixtureTest}). Failing loudly when absent
     * keeps MV06 honest - a hand-written catalog here would reintroduce
     * the fixture gap this case exists to close.
     */
    private static Path eacFixtures() {
        Path moduleDir = Paths.get("").toAbsolutePath();
        Path candidate = moduleDir.getParent().getParent()
                .resolve("EnterpriseAgentCode")
                .resolve("verification")
                .resolve("model-authority")
                .resolve("fixtures");
        Assert.assertTrue("MV06 needs the real-producer fixtures - run "
                        + "ModelRuntimeCatalogMv06FixtureTest with "
                        + "-Dma001.fixtures.out=... first; missing "
                        + candidate,
                Files.isRegularFile(
                        candidate.resolve("mv06-release-a.json"))
                        && Files.isRegularFile(
                        candidate.resolve("mv06-release-b.json")));
        return candidate;
    }

    /**
     * Serves one real published projection with ONLY the transport
     * fields relabeled for loopback: {@code baseUrl} points at the test
     * receiver and the producer's {@code ENV_REF} credential becomes an
     * {@code INLINE_DEV} material (the process env cannot be populated
     * inside the surefire JVM). Release identity, fingerprints, modelKey
     * and capability projections are the producer's bytes verbatim.
     */
    private static String servedFixture(Path fixtures, String name,
                                        int modelPort)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        MAPPER.readTree(new String(
                                Files.readAllBytes(fixtures.resolve(name)),
                                StandardCharsets.UTF_8));
        JsonNode providers = root.path("providers");
        Assert.assertTrue("fixture " + name + " has no providers",
                providers.isArray() && providers.size() > 0);
        com.fasterxml.jackson.databind.node.ObjectNode provider =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        providers.get(0);
        provider.put("baseUrl",
                "http://127.0.0.1:" + modelPort + "/v1");
        com.fasterxml.jackson.databind.node.ObjectNode credential =
                provider.putObject("credential");
        credential.put("kind", "INLINE_DEV");
        credential.put("value", "mv06-loopback-material");
        credential.put("version", "ref-v1");
        return MAPPER.writeValueAsString(root);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private JsonNode createSession(Harness h, String title)
            throws IOException {
        JsonNode created = api(h, "POST", "/api/v2/sessions",
                "{\"schemaVersion\":\"business-session/2.0\","
                        + "\"title\":\"" + title + "\"}");
        Assert.assertEquals("session create failed: " + created,
                201, created.get("status").asInt());
        return created;
    }

    private JsonNode sendMessage(Harness h, String sessionId, long version,
                                 String clientMessageId) throws IOException {
        return api(h, "POST",
                "/api/v2/sessions/" + sessionId + "/messages",
                "{\"clientMessageId\":\"" + clientMessageId + "\","
                        + "\"content\":\"reply with the letter a\","
                        + "\"expectedSessionVersion\":" + version + "}");
    }

    /**
     * The receipt bodies the enterprise stub recorded on
     * {@code POST /api/v1/model-runtime/catalog/applications} - the
     * release id and status fields let a case tell an APPLIED for A
     * apart from a filing for the rejected release B.
     */
    private static List<JsonNode> applicationReceipts(Harness h) {
        List<JsonNode> out = new ArrayList<JsonNode>();
        List<String> paths = h.enterprise.paths;
        List<JsonNode> bodies = h.enterprise.bodies;
        for (int i = 0; i < paths.size() && i < bodies.size(); i++) {
            if (paths.get(i).startsWith(
                    "POST /api/v1/model-runtime/catalog/applications")
                    && bodies.get(i) != null) {
                out.add(bodies.get(i));
            }
        }
        return out;
    }

    /**
     * Release B as the codec sees it: structurally complete JSON whose
     * credential kind ({@code GATEWAY_IDENTITY}) the adapter does not
     * implement - a real decode-time rejection, not a malformed stub.
     */
    private static String invalidCatalogJson(int modelPort) {
        return "{"
                + "\"schemaVersion\":1,"
                + "\"authorityId\":\"" + AUTHORITY_ID + "\","
                + "\"releaseId\":\"" + RELEASE_ID_B + "\","
                + "\"generation\":42,"
                + "\"profileId\":\"" + PROFILE_ID + "\","
                + "\"profileRevisionNo\":5,"
                + "\"scopeDigest\":\"sha256:" + repeat('1', 64) + "\","
                + "\"projectionFingerprint\":\"sha256:"
                + repeat('b', 64) + "\","
                + "\"defaultSelection\":{\"providerId\":\"" + PROVIDER_ID
                + "\",\"modelKey\":\"" + STABLE_MODEL_KEY
                + "\",\"remoteModelId\":\"" + WIRE_MODEL_A + "\"},"
                + "\"providers\":[{"
                + "\"providerId\":\"" + PROVIDER_ID + "\","
                + "\"dialect\":\"OPENAI_CHAT_COMPLETIONS\","
                + "\"baseUrl\":\"http://127.0.0.1:" + modelPort + "/v1\","
                + "\"apiVersion\":null,"
                + "\"betaHeaders\":[],"
                + "\"profileRevision\":\"svc-rev:42\","
                + "\"dataPolicy\":{\"dataClassification\":null,"
                + "\"networkZone\":\"corp\"},"
                + "\"credential\":{\"kind\":\"GATEWAY_IDENTITY\"},"
                + "\"models\":[{"
                + "\"modelKey\":\"" + STABLE_MODEL_KEY + "\","
                + "\"remoteModelId\":\"" + WIRE_MODEL_A + "\","
                + "\"revision\":\"mrev:mcfg-1@42\","
                + "\"capabilities\":[\"NATIVE_TOOL_CALLING\"],"
                + "\"contextWindowTokens\":200000,"
                + "\"maxOutputTokens\":8192,"
                + "\"tiers\":{\"cost\":\"FREE\"}"
                + "}]}],"
                + "\"runtimeOptions\":{\"defaultPurpose\":null,"
                + "\"routePolicies\":[]},"
                + "\"readiness\":\"READY\","
                + "\"diagnostics\":[],"
                + "\"issuedAt\":\"2026-10-13T00:00:00Z\","
                + "\"validUntil\":null,"
                + "\"credentialVersion\":null"
                + "}";
    }

    // ------------------------------------------------------------------
    // MV17 — the non-GUI create() path must not silently install a file
    // catalog while claiming enterprise authority.
    // ------------------------------------------------------------------
    @Test
    public void mv17_managedCreateRefusesFileBypass() throws Exception {
        Path stateDir = temp.newFolder("state-mv17").toPath();
        Path workspace = temp.newFolder("ws-mv17").toPath();
        Path catalogFile = temp.newFile("trap-model.properties").toPath();
        Files.write(catalogFile, (
                "PROVIDER_1_ID=" + PROVIDER_ID + "\n"
                + "PROVIDER_1_DIALECT=OPENAI_CHAT_COMPLETIONS\n"
                + "PROVIDER_1_BASE_URL=http://127.0.0.1:1/v1\n"
                + "PROVIDER_1_API_KEY_ENV=MA00_NONEXISTENT_ENV\n"
                + "PROVIDER_1_MODEL_1_ID=" + TRAP_WIRE_MODEL + "\n"
                + "PROVIDER_1_MODEL_1_CAPABILITIES=NATIVE_TOOL_CALLING\n"
                + "MODEL_DEFAULT_MODEL=" + TRAP_WIRE_MODEL + "\n")
                .getBytes(StandardCharsets.UTF_8));
        ReceiverStub enterprise = new ReceiverStub();
        try {
            enterprise.catalog(catalogJson(1));
            Path configFile = writeServiceProperties(stateDir, workspace,
                    catalogFile, enterprise, true);
            ServiceConfig config = ServiceConfig.load(new String[] {
                    "--config", configFile.toString() });
            try {
                BusinessServiceFactory.create(config);
                Assert.fail("ENTERPRISE_HTTP with a model.config file must refuse "
                        + "the personal file authority — it silently installed it");
            } catch (RuntimeException expected) {
                String code = expected instanceof
                        com.sage.business.code.domain.BusinessException
                        ? ((com.sage.business.code.domain.BusinessException)
                                expected).getCode() : expected.getMessage();
                Assert.assertTrue("refusal must carry a typed MODEL_* code, got: "
                                + code,
                        code != null && code.contains("MODEL_"));
            }
        } finally {
            enterprise.close();
        }
    }

    // ------------------------------------------------------------------
    // MV17b — the same non-GUI path with NO model.config at all: under
    // ENTERPRISE_HTTP the absence of a file is not permission to walk
    // into personal/local assembly — the boot must stop with the same
    // typed source-conflict instead of a generic missing-file error or
    // a silent degraded catalog.
    // ------------------------------------------------------------------
    @Test
    public void mv17b_managedCreateRefusesMissingFileBypass()
            throws Exception {
        Path stateDir = temp.newFolder("state-mv17b").toPath();
        Path workspace = temp.newFolder("ws-mv17b").toPath();
        ReceiverStub enterprise = new ReceiverStub();
        try {
            enterprise.catalog(catalogJson(1));
            Path configFile = writeServiceProperties(stateDir, workspace,
                    null, enterprise, true);
            ServiceConfig config = ServiceConfig.load(new String[] {
                    "--config", configFile.toString() });
            try {
                BusinessServiceFactory.create(config);
                Assert.fail("ENTERPRISE_HTTP without a personal file must "
                        + "still fail closed on the non-GUI create path — "
                        + "it walked into local assembly");
            } catch (RuntimeException expected) {
                String code = expected instanceof
                        com.sage.business.code.domain.BusinessException
                        ? ((com.sage.business.code.domain.BusinessException)
                                expected).getCode() : expected.getMessage();
                Assert.assertTrue("refusal must carry a typed MODEL_* "
                                + "code, got: " + code,
                        code != null && code.contains("MODEL_"));
            }
        } finally {
            enterprise.close();
        }
    }

    // ------------------------------------------------------------------
    // MV08 — MA02 in-flight pinning invariant, proven end-to-end on the
    // real composition and the same real EAC producer fixtures MV06
    // serves: work captured under release A keeps routing to A's model
    // endpoint after B publishes; new chat follows B; and the persisted
    // ModelBinding rehydrates through the authority's release-addressed
    // read (GET .../catalog/releases/{id}) — never a silent substitute
    // of the latest release.
    // ------------------------------------------------------------------
    @Test
    public void mv08_capturedWorkStaysPinnedWhileNewWorkFollowsLatest()
            throws Exception {
        final Path fixtures = eacFixtures();
        final ReceiverStub modelB = new ReceiverStub();
        modelB.replyChatCompletions();
        Harness h = bootEnterpriseService(false, 0,
                new java.util.function.Function<ReceiverStub, String>() {
                    @Override public String apply(ReceiverStub model) {
                        try {
                            return servedFixture(fixtures,
                                    "mv06-release-a.json", model.port());
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                });
        try {
            com.sage.business.code.bootstrap.EffectiveModelContext
                    contextA = h.services.modelContext.current();
            Assert.assertNotNull("no effective model context", contextA);
            Assert.assertEquals("release A did not assemble READY",
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY, contextA.readiness());
            Assert.assertNotNull("context A lost the producer snapshot",
                    contextA.enterpriseSnapshot());
            final String releaseIdA =
                    contextA.enterpriseSnapshot().getReleaseId();
            Assert.assertTrue("producer releaseId A expected, got "
                            + releaseIdA,
                    releaseIdA != null && releaseIdA.startsWith("mrel-"));

            // Admission under release A: the tuple a run would pin.
            com.sage.business.code.application.model.ModelCapture
                    capture =
                    new com.sage.business.code.application.model
                            .ModelCapture(h.services.modelContext);
            com.sage.business.code.application.model.CapturedModelContext
                    captured = capture.capture();
            Assert.assertEquals(releaseIdA, captured.binding().releaseId);
            Assert.assertNotNull(
                    "binding carried no consumer snapshot digest",
                    captured.binding().consumerSnapshotDigest);
            Assert.assertEquals(contextA.consumerSnapshotDigest(),
                    captured.binding().consumerSnapshotDigest);

            // A model call through the captured port is a REAL model
            // request against release A's receiver.
            com.agent.framework.model.ModelRequest requestA =
                    com.agent.framework.model.ModelRequest.builder()
                            .model("wire-model-a")
                            .message(com.agent.framework.model
                                    .ModelMessage.userText(
                                            "reply with the letter a"))
                            .build();
            captured.port().generate(requestA);
            Assert.assertEquals(1, h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("wire-model-a", h.model.lastModelId());

            // Publish release B: different provider endpoint AND wire
            // id, so which receiver answers discriminates A from B.
            String releaseAJson = servedFixture(fixtures,
                    "mv06-release-a.json", h.model.port());
            h.enterprise.release(releaseIdA, releaseAJson);
            h.enterprise.swapCatalog(servedFixture(fixtures,
                    "mv06-release-b.json", modelB.port()));
            Assert.assertTrue("release B refresh failed",
                    h.services.modelContext.refresh());
            com.sage.business.code.bootstrap.EffectiveModelContext
                    contextB = h.services.modelContext.current();
            Assert.assertNotSame(contextA, contextB);
            Assert.assertNotNull(contextB.enterpriseSnapshot());
            Assert.assertFalse("producer published identical release ids",
                    releaseIdA.equals(contextB.enterpriseSnapshot()
                            .getReleaseId()));

            // NEW work follows the latest published release B — the
            // real HTTP chat surface, through the real capture seam.
            JsonNode created = createSession(h, "MA02-MV08-B");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode message = sendMessage(h, sessionId, version,
                    "mv08-msg-b1");
            Assert.assertTrue("message POST returned " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);
            Assert.assertEquals(
                    "new chat must route to release B's receiver",
                    1, modelB.hits("/v1/chat/completions"));
            Assert.assertEquals("wire-model-b", modelB.lastModelId());
            Assert.assertEquals("release A receiver took new work",
                    1, h.model.hits("/v1/chat/completions"));

            // IN-FLIGHT work stays pinned to the release it captured:
            // the same captured port still lands on A's receiver.
            captured.port().generate(requestA);
            Assert.assertEquals(
                    "captured context must stay pinned to release A",
                    2, h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("wire-model-a", h.model.lastModelId());
            Assert.assertEquals(1, modelB.hits("/v1/chat/completions"));
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));

            // Resume: the persisted binding's release is no longer live,
            // so rehydration re-loads release A through the real
            // release-addressed endpoint — and the rehydrated context
            // routes to A's receiver, not B's.
            com.sage.business.code.application.model.CapturedModelContext
                    resumed = capture.rehydrate(captured.binding());
            Assert.assertNotNull(resumed);
            Assert.assertTrue("a pinned reload must hit the "
                            + "release-addressed endpoint",
                    h.enterprise.hits("/releases/" + releaseIdA) > 0);
            Assert.assertNotNull(resumed.context().enterpriseSnapshot());
            Assert.assertEquals(releaseIdA,
                    resumed.context().enterpriseSnapshot().getReleaseId());
            Assert.assertNotSame(
                    "resume must not adopt the live release-B context",
                    contextB, resumed.context());
            resumed.port().generate(requestA);
            Assert.assertEquals(
                    "rehydrated release-A context must route to A",
                    3, h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("wire-model-a", h.model.lastModelId());
        } finally {
            h.close();
            modelB.close();
        }
    }

    // ==================================================================
    // MA04 — the enterprise synchronizer: published/consumer changes
    // reach the host automatically; failures and authorization loss
    // surface as typed admission results on the real HTTP surfaces.
    // ==================================================================

    /**
     * A capture seam bound to the SAME synchronizer the factory wired —
     * the factory-owned {@code ModelCapture} is not exposed on
     * GuiServices, so cases that need to hold a binding across a release
     * change construct an equivalent seam against the wired guard.
     */
    private static com.sage.business.code.application.model.ModelCapture
            guardedCapture(Harness h) {
        Assert.assertNotNull("enterprise synchronizer must be wired",
                h.services.catalogSynchronizer);
        com.sage.business.code.application.model.ModelCapture capture =
                new com.sage.business.code.application.model.ModelCapture(
                        h.services.modelContext);
        capture.admissionGuard(h.services.catalogSynchronizer);
        return capture;
    }

    private static com.agent.framework.model.ModelRequest chatRequest(
            String wireModel) {
        return com.agent.framework.model.ModelRequest.builder()
                .model(wireModel)
                .message(com.agent.framework.model.ModelMessage
                        .userText("reply with the letter a"))
                .build();
    }

    /**
     * A fresh session + its first message — the only send shape that
     * reaches {@code tasks.start}'s admission capture synchronously at
     * POST time (later messages on a busy session queue behind the
     * active run without re-checking admission).
     */
    private JsonNode sendFirstMessage(Harness h, String title,
                                      String clientMessageId)
            throws IOException {
        JsonNode created = createSession(h, title);
        String sessionId = created.at("/body/sessionId").asText();
        long version = created.at("/body/sessionVersion").asLong(1L);
        return sendMessage(h, sessionId, version, clientMessageId);
    }

    /** Bounded poll — asserts the condition, never a blind sleep. */
    private static void waitFor(String what,
            java.util.function.BooleanSupplier condition,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(40);
        }
        Assert.fail("timed out waiting for " + what);
    }

    /** An unauthenticated call — the authority endpoint must refuse it. */
    private JsonNode apiNoAuth(Harness h, String method, String path)
            throws IOException {
        URL url = new URL("http://127.0.0.1:" + h.port + path);
        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        int status = connection.getResponseCode();
        InputStream in = status < 400
                ? connection.getInputStream() : connection.getErrorStream();
        String text = in == null ? "" : new String(readAll(in),
                StandardCharsets.UTF_8);
        return MAPPER.createObjectNode()
                .put("status", status)
                .set("body", text.isEmpty()
                        ? MAPPER.createObjectNode() : MAPPER.readTree(text));
    }

    // ------------------------------------------------------------------
    // MV07 — an operator publish reaches the host through the poll loop
    // alone: no manual refresh, no personal API touch. The real scheduled
    // tick drives the real HTTP pull.
    // ------------------------------------------------------------------
    @Test
    public void mv07_synchronizerAutoInstallsPublishedRelease()
            throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n");
        try {
            Assert.assertNotNull("synchronizer must be armed",
                    h.services.catalogSynchronizer);
            com.sage.business.code.bootstrap.EffectiveModelContext
                    contextA = h.services.modelContext.current();
            Assert.assertEquals(RELEASE_ID_A,
                    contextA.enterpriseSnapshot().getReleaseId());

            // The operator publishes release B — the only signal is the
            // catalog head changing; nobody calls refresh().
            String releaseB = catalogJson(h.model.port(), RELEASE_ID_B, 42,
                    '2', 'c', "wire-model-b", "v1", true);
            h.enterprise.swapCatalog(releaseB);
            h.enterprise.release(RELEASE_ID_B, releaseB);

            waitFor("the poll loop to auto-install release B",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            com.sage.business.code.bootstrap
                                    .EffectiveModelContext current =
                                    h.services.modelContext.current();
                            return current != null
                                    && current.enterpriseSnapshot() != null
                                    && RELEASE_ID_B.equals(current
                                            .enterpriseSnapshot()
                                            .getReleaseId());
                        }
                    }, 10000);
            Assert.assertEquals("producer generation B", 42L,
                    h.services.modelContext.current()
                            .enterpriseSnapshot().getGeneration());

            // The apply is evidenced — an APPLIED receipt for B lands on
            // the applications endpoint without any manual trigger.
            waitFor("APPLIED receipt for release B",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            for (JsonNode receipt : applicationReceipts(h)) {
                                if (RELEASE_ID_B.equals(receipt
                                                .path("releaseId").asText())
                                        && "APPLIED".equals(receipt
                                                .path("status").asText())) {
                                    return true;
                                }
                            }
                            return false;
                        }
                    }, 5000);

            // New admitted work routes to B's wire identity.
            JsonNode created = createSession(h, "MA04-MV07");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode message = sendMessage(h, sessionId, version,
                    "mv07-msg-1");
            Assert.assertTrue("message POST returned " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);
            waitFor("release B model dispatch",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return "wire-model-b"
                                    .equals(h.model.lastModelId());
                        }
                    }, 5000);
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));
            Assert.assertTrue("the poll loop recorded real ticks",
                    h.services.catalogSynchronizer.status().polls >= 1);
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV11 — an authority outage closes NEW admission with a typed
    // failure (never a file/env fallback); after the freshness window the
    // refusal degrades to MODEL_CATALOG_STALE; a healthy tick reopens.
    // ------------------------------------------------------------------
    @Test
    public void mv11_catalogOutageClosesAdmissionThenStaleThenRecovers()
            throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n"
                        + "model.catalog.enterprise.sync.freshness-millis"
                        + "=1000\n");
        try {
            // Anchor the freshness window deterministically — the boot
            // apply seeds it at construction, and factory wiring between
            // construction and the first 250ms scheduled tick can exceed
            // the test's 1s window.
            h.services.catalogSynchronizer.tick();
            Assert.assertEquals("OPEN",
                    h.services.catalogSynchronizer.status().gateState);
            JsonNode healthy = sendFirstMessage(h, "MA04-MV11-A",
                    "mv11-msg-0");
            Assert.assertTrue("baseline send failed: " + healthy,
                    healthy.get("status").asInt() == 200
                            || healthy.get("status").asInt() == 202);
            waitFor("baseline model dispatch",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions") >= 1;
                        }
                    }, 5000);

            // The authority goes dark — a forced tick records the real
            // load failure; the running poll keeps failing behind it.
            h.enterprise.catalogUnavailable = true;
            h.services.catalogSynchronizer.tick();
            Assert.assertEquals("CLOSED",
                    h.services.catalogSynchronizer.status().gateState);

            // Inside the freshness window the admission failure is the
            // typed sync error — never a fall back to personal config.
            JsonNode refused = sendFirstMessage(h, "MA04-MV11-B",
                    "mv11-msg-1");
            Assert.assertEquals("admission during outage must be refused, "
                            + "got " + refused, 503,
                    refused.get("status").asInt());
            Assert.assertEquals("MODEL_CATALOG_UNAVAILABLE",
                    refused.at("/body/code").asText());
            Assert.assertEquals("no model call may ride the poisoned "
                            + "authority",
                    1, h.model.hits("/v1/chat/completions"));

            // Past the freshness window the gate reports staleness — the
            // host cannot silently keep using an unverified authority.
            Thread.sleep(1400);
            JsonNode stale = sendFirstMessage(h, "MA04-MV11-C",
                    "mv11-msg-2");
            Assert.assertEquals("expired freshness must answer typed "
                            + "STALE, got " + stale, 503,
                    stale.get("status").asInt());
            Assert.assertEquals("MODEL_CATALOG_STALE",
                    stale.at("/body/code").asText());
            Assert.assertEquals(1, h.model.hits("/v1/chat/completions"));

            // Recovery: the authority answers again; the next successful
            // tick reopens admission and a real chat completes.
            h.enterprise.catalogUnavailable = false;
            h.services.catalogSynchronizer.tick();
            Assert.assertEquals("OPEN",
                    h.services.catalogSynchronizer.status().gateState);
            JsonNode recovered = sendFirstMessage(h, "MA04-MV11-D",
                    "mv11-msg-3");
            Assert.assertTrue("post-recovery send failed: " + recovered,
                    recovered.get("status").asInt() == 200
                            || recovered.get("status").asInt() == 202);
            waitFor("post-recovery model dispatch",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions") >= 2;
                        }
                    }, 5000);
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV12 — the authority republishes the SAME release with a narrower
    // scope: the digest drift republishes the context and the binding
    // captured under the old scope cannot borrow the new projection.
    // ------------------------------------------------------------------
    @Test
    public void mv12_sameReleaseScopeDriftMismatchesCapturedBinding()
            throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n");
        try {
            h.enterprise.release(RELEASE_ID_A, catalogJson(h.model.port()));
            com.sage.business.code.application.model.ModelCapture capture =
                    guardedCapture(h);
            final com.sage.business.code.application.model
                    .CapturedModelContext captured = capture.capture();
            String digestBefore = captured.binding().consumerSnapshotDigest;
            Assert.assertNotNull(digestBefore);
            captured.port().generate(chatRequest(WIRE_MODEL_A));
            Assert.assertEquals(1, h.model.hits("/v1/chat/completions"));

            // Same releaseId, narrower scope (decoy model removed) — the
            // authority re-stamps the release in place.
            String republished = catalogJson(h.model.port(), RELEASE_ID_A,
                    41, '1', 'd', WIRE_MODEL_A, "v1", false);
            h.enterprise.swapCatalog(republished);
            h.enterprise.release(RELEASE_ID_A, republished);
            h.services.catalogSynchronizer.tick();

            com.sage.business.code.bootstrap.EffectiveModelContext now =
                    h.services.modelContext.current();
            Assert.assertEquals("same release must stay pinned",
                    RELEASE_ID_A, now.enterpriseSnapshot().getReleaseId());
            Assert.assertFalse("scope drift must move the consumer digest",
                    digestBefore.equals(
                            now.consumerSnapshotDigest()));

            try {
                captured.port().generate(chatRequest(WIRE_MODEL_A));
                Assert.fail("a binding captured under the old scope must "
                        + "not dispatch against the republished release");
            } catch (com.sage.business.code.domain.BusinessException e) {
                Assert.assertEquals("MODEL_BINDING_MISMATCH", e.getCode());
            }
            Assert.assertEquals("the refused dispatch must not reach the "
                            + "model endpoint",
                    1, h.model.hits("/v1/chat/completions"));

            // New admissions see the narrower scope and keep working.
            JsonNode created = createSession(h, "MA04-MV12");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode message = sendMessage(h, sessionId, version,
                    "mv12-msg-1");
            Assert.assertTrue("post-drift send failed: " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);
            waitFor("post-drift dispatch",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions") >= 2;
                        }
                    }, 5000);
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV13 — the authority rotates the provider credentialVersion in
    // place: the digest catches it, the release republishes, and the
    // binding pinned under v1 cannot silently dispatch on v2.
    // ------------------------------------------------------------------
    @Test
    public void mv13_credentialRotationMismatchesCapturedBinding()
            throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n");
        try {
            h.enterprise.release(RELEASE_ID_A, catalogJson(h.model.port()));
            com.sage.business.code.application.model.ModelCapture capture =
                    guardedCapture(h);
            final com.sage.business.code.application.model
                    .CapturedModelContext captured = capture.capture();
            Assert.assertEquals("v1",
                    captured.binding().credentialVersion);
            captured.port().generate(chatRequest(WIRE_MODEL_A));
            Assert.assertEquals(1, h.model.hits("/v1/chat/completions"));

            // In-place credential rotation: identical projection except
            // the credential version flips v1 -> v2.
            String rotated = catalogJson(h.model.port(), RELEASE_ID_A, 41,
                    '0', 'a', WIRE_MODEL_A, "v2", true);
            h.enterprise.swapCatalog(rotated);
            h.enterprise.release(RELEASE_ID_A, rotated);
            h.services.catalogSynchronizer.tick();

            Assert.assertFalse("credential rotation must move the "
                            + "consumer digest",
                    captured.binding().consumerSnapshotDigest.equals(
                            h.services.modelContext.current()
                                    .consumerSnapshotDigest()));
            try {
                captured.port().generate(chatRequest(WIRE_MODEL_A));
                Assert.fail("the v1 binding must not borrow rotated "
                        + "credentials");
            } catch (com.sage.business.code.domain.BusinessException e) {
                Assert.assertEquals("MODEL_BINDING_MISMATCH", e.getCode());
            }
            Assert.assertEquals(1, h.model.hits("/v1/chat/completions"));
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV14 — current is B while in-flight work pinned to A: withdrawing
    // A at the authority revokes its NEXT dispatch while release-B work
    // keeps flowing.
    // ------------------------------------------------------------------
    @Test
    public void mv14_withdrawnPinnedReleaseRevokesNextDispatch()
            throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n");
        try {
            String releaseAJson = catalogJson(h.model.port());
            h.enterprise.release(RELEASE_ID_A, releaseAJson);
            com.sage.business.code.application.model.ModelCapture capture =
                    guardedCapture(h);
            final com.sage.business.code.application.model
                    .CapturedModelContext capturedA = capture.capture();
            Assert.assertEquals(RELEASE_ID_A,
                    capturedA.binding().releaseId);
            capturedA.port().generate(chatRequest(WIRE_MODEL_A));
            Assert.assertEquals(1, h.model.hits("/v1/chat/completions"));

            // Governance publishes B — current moves, A stays authorized.
            String releaseB = catalogJson(h.model.port(), RELEASE_ID_B, 42,
                    '2', 'c', "wire-model-b", "v1", true);
            h.enterprise.swapCatalog(releaseB);
            h.enterprise.release(RELEASE_ID_B, releaseB);
            h.services.catalogSynchronizer.tick();
            Assert.assertEquals(RELEASE_ID_B,
                    h.services.modelContext.current()
                            .enterpriseSnapshot().getReleaseId());
            // The still-published A revalidates — the captured dispatch
            // keeps landing on A's wire identity.
            capturedA.port().generate(chatRequest(WIRE_MODEL_A));
            Assert.assertEquals(2, h.model.hits("/v1/chat/completions"));

            // Withdrawal: the pinned read answers 404 — the NEXT dispatch
            // on A is revoked typed; nothing reaches the model endpoint.
            h.enterprise.withdraw(RELEASE_ID_A);
            h.services.catalogSynchronizer.tick();
            try {
                capturedA.port().generate(chatRequest(WIRE_MODEL_A));
                Assert.fail("a withdrawn release must not dispatch again");
            } catch (com.sage.business.code.domain.BusinessException e) {
                Assert.assertEquals("MODEL_RELEASE_REVOKED", e.getCode());
            }
            Assert.assertEquals("the revoked dispatch must not reach the "
                            + "model endpoint",
                    2, h.model.hits("/v1/chat/completions"));

            // The live release is untouched by A's withdrawal.
            JsonNode created = createSession(h, "MA04-MV14");
            String sessionId = created.at("/body/sessionId").asText();
            long version = created.at("/body/sessionVersion").asLong(1L);
            JsonNode message = sendMessage(h, sessionId, version,
                    "mv14-msg-1");
            Assert.assertTrue("release-B send failed: " + message,
                    message.get("status").asInt() == 200
                            || message.get("status").asInt() == 202);
            waitFor("release-B dispatch after A's withdrawal",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions") >= 3;
                        }
                    }, 5000);
            Assert.assertEquals("wire-model-b", h.model.lastModelId());
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV14 (consumer leg) — the v2 envelope's consumer bindings are the
    // authority's authorization record: when our binding id leaves the
    // resolved set, admissions and dispatches revoke typed; when it
    // returns, the gate reopens.
    // ------------------------------------------------------------------
    @Test
    public void mv14b_v2ConsumerDeauthorizationRevokesAdmission()
            throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n");
        try {
            // The producer supports the v2 envelope and currently
            // authorizes consumer binding mcb-a.
            h.enterprise.v2Supported = true;
            h.enterprise.v2ConsumerBindingIds =
                    java.util.Arrays.asList("mcb-a");
            h.services.catalogSynchronizer.tick();
            Assert.assertEquals(Integer.valueOf(2),
                    h.services.catalogSynchronizer.status()
                            .negotiatedSchemaVersion);

            JsonNode admitted = sendFirstMessage(h, "MA04-MV14B-A",
                    "mv14b-msg-0");
            Assert.assertTrue("baseline send failed: " + admitted,
                    admitted.get("status").asInt() == 200
                            || admitted.get("status").asInt() == 202);
            waitFor("baseline model dispatch",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions") >= 1;
                        }
                    }, 5000);

            // The consumer binding is revoked at the authority — the
            // next envelope tick carries a resolved set without ours.
            h.enterprise.v2ConsumerBindingIds =
                    java.util.Arrays.asList("mcb-other");
            h.services.catalogSynchronizer.tick();
            Assert.assertTrue("the synchronizer must record consumer "
                            + "de-authorization",
                    h.services.catalogSynchronizer.status()
                            .consumerDeauthorized);
            JsonNode refused = sendFirstMessage(h, "MA04-MV14B-B",
                    "mv14b-msg-1");
            Assert.assertEquals("de-authorized admission must fail "
                            + "typed, got " + refused, 403,
                    refused.get("status").asInt());
            Assert.assertEquals("MODEL_AUTHORIZATION_REVOKED",
                    refused.at("/body/code").asText());
            Assert.assertEquals("a revoked admission must not reach the "
                            + "model endpoint",
                    1, h.model.hits("/v1/chat/completions"));

            // Re-authorization reopens the gate on the next tick.
            h.enterprise.v2ConsumerBindingIds =
                    java.util.Arrays.asList("mcb-a");
            h.services.catalogSynchronizer.tick();
            Assert.assertFalse(h.services.catalogSynchronizer.status()
                    .consumerDeauthorized);
            JsonNode restored = sendFirstMessage(h, "MA04-MV14B-C",
                    "mv14b-msg-2");
            Assert.assertTrue("re-authorized send failed: " + restored,
                    restored.get("status").asInt() == 200
                            || restored.get("status").asInt() == 202);
            waitFor("re-authorized dispatch",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions") >= 2;
                        }
                    }, 5000);
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV28 — GET /api/v2/model-catalog/authority reports the real
    // posture: source identity, applied release, sync state, negotiated
    // schema, receipt state — bearer-gated and secret-free.
    // ------------------------------------------------------------------
    @Test
    public void mv28_authorityStatusReportsSyncPosture() throws Exception {
        Harness h = bootEnterpriseService(false, 0, null,
                "model.catalog.enterprise.sync.enabled=true\n"
                        + "model.catalog.enterprise.sync.poll-millis=250\n"
                        + "model.catalog.enterprise.sync.jitter-millis=0\n");
        try {
            // Bearer-gated like every v2 surface.
            JsonNode anonymous = apiNoAuth(h, "GET",
                    "/api/v2/model-catalog/authority");
            Assert.assertEquals("the authority endpoint must require "
                            + "the bearer token", 401,
                    anonymous.get("status").asInt());

            // The v1-only stub refuses the version param: the probe's
            // fallback retry negotiates schemaVersion=1 honestly.
            h.services.catalogSynchronizer.tick();
            JsonNode status = api(h, "GET",
                    "/api/v2/model-catalog/authority", null);
            Assert.assertEquals("authority status failed: " + status,
                    200, status.get("status").asInt());
            JsonNode body = status.get("body");
            Assert.assertEquals("ENTERPRISE_HTTP",
                    body.at("/source").asText());
            Assert.assertEquals(RELEASE_ID_A,
                    body.at("/applied/releaseId").asText());
            Assert.assertEquals(41, body.at("/applied/generation").asInt());
            Assert.assertEquals(AUTHORITY_ID,
                    body.at("/applied/authorityId").asText());
            Assert.assertEquals("READY",
                    body.at("/applied/readiness").asText());
            Assert.assertTrue(body.at("/sync/enabled").asBoolean());
            Assert.assertEquals("OPEN", body.at("/sync/state").asText());
            Assert.assertEquals(1,
                    body.at("/sync/negotiatedSchemaVersion").asInt());
            Assert.assertEquals("RELEASE_WITHDRAWAL_ONLY",
                    body.at("/sync/revocationChannel").asText());
            Assert.assertEquals("APPLIED",
                    body.at("/sync/report/status").asText());
            Assert.assertFalse("enterprise catalog writes are read-only",
                    body.at("/capabilities/managedCatalogWrite")
                            .asBoolean());
            Assert.assertEquals(250,
                    body.at("/sync/pollMillis").asInt());
            Assert.assertTrue("the endpoint must not embed secret "
                            + "material",
                    !body.toString().contains("ma00-dev-only-material"));

            // A failing authority lands in lastError typed — the status
            // surface reports the failure instead of hiding it.
            h.enterprise.catalogUnavailable = true;
            h.services.catalogSynchronizer.tick();
            JsonNode degraded = api(h, "GET",
                    "/api/v2/model-catalog/authority", null);
            Assert.assertEquals(200, degraded.get("status").asInt());
            Assert.assertEquals("CLOSED",
                    degraded.at("/body/sync/state").asText());
            Assert.assertEquals("MODEL_CATALOG_UNAVAILABLE",
                    degraded.at("/body/sync/lastError/code").asText());
            Assert.assertEquals("UNAVAILABLE",
                    degraded.at("/body/sync/lastError/kind").asText());
            Assert.assertTrue(degraded.at("/body/sync/syncFailures")
                    .asLong() >= 1);
            Assert.assertEquals("the applied release stays reported "
                            + "honestly while degraded",
                    RELEASE_ID_A,
                    degraded.at("/body/applied/releaseId").asText());

            h.enterprise.catalogUnavailable = false;
            h.services.catalogSynchronizer.tick();
            JsonNode recovered = api(h, "GET",
                    "/api/v2/model-catalog/authority", null);
            Assert.assertEquals("OPEN",
                    recovered.at("/body/sync/state").asText());
            Assert.assertTrue("no typed error must survive recovery",
                    recovered.at("/body/sync/lastError/code").isNull()
                            || recovered.at("/body/sync/lastError/code")
                                    .asText().isEmpty());
        } finally {
            h.close();
        }
    }

    // ==================================================================
    // MA03 part 2 — managed model-catalog source seam: every write
    // surface bound to a non-LOCAL authority answers typed
    // MODEL_SOURCE_READ_ONLY; reads stay honest to the owning source.
    // ==================================================================

    // ------------------------------------------------------------------
    // MV04 — FILE_COMPAT: the file is the authority. The read surface
    // projects the file catalog with a content-addressed release
    // identity, every catalog write refuses MODEL_SOURCE_READ_ONLY, and
    // no personal model-table SQL is ever issued.
    // ------------------------------------------------------------------
    @Test
    public void mv04_fileCompatListsFileCatalogAndRefusesWrites()
            throws Exception {
        Path stateDir = Files.createDirectories(
                temp.getRoot().toPath().resolve(UUID.randomUUID()
                        .toString().substring(0, 8))).toAbsolutePath();
        Path workspace = Files.createDirectories(
                temp.getRoot().toPath().resolve("ws-"
                        + stateDir.getFileName())).toAbsolutePath();
        Path catalogFile = stateDir.resolve("model-providers.properties");
        Files.write(catalogFile, (
                "PROVIDER_1_ID=file-p\n"
                + "PROVIDER_1_DIALECT=OPENAI_CHAT_COMPLETIONS\n"
                + "PROVIDER_1_BASE_URL=http://127.0.0.1:1/v1\n"
                + "PROVIDER_1_API_KEY_ENV=MA00_ABSENT_ENV\n"
                + "PROVIDER_1_MODEL_1_ID=file-wire-a\n"
                + "PROVIDER_1_MODEL_1_CAPABILITIES=NATIVE_TOOL_CALLING\n"
                + "MODEL_DEFAULT_MODEL=file-wire-a\n")
                .getBytes(StandardCharsets.UTF_8));
        Path configFile = writeServiceProperties(stateDir, workspace,
                catalogFile, "model.catalog.source=FILE_COMPAT\n");
        Harness h = new Harness();
        h.stateDir = stateDir;
        h.recorderWindowStart = RECORDER.entries().size();
        try {
            h.config = ServiceConfig.load(new String[] {
                    "--config", configFile.toString() });
            h.services = BusinessServiceFactory.createGui(h.config);
            h.server = new BusinessHttpServer(
                    new InetSocketAddress("127.0.0.1", 0), SERVICE_TOKEN,
                    h.services.tasks, MAPPER, null, null, h.services.v2);
            h.server.start();
            h.port = serverPort(h.server);

            // The composed source IS the file authority: read-only,
            // content-addressed identity from the strict framework
            // source (releaseId = file-<sha256-prefix>, generation 1).
            Assert.assertEquals(com.sage.business.code.adapter.product
                            .EnterpriseCatalogSettings.Source.FILE_COMPAT,
                    h.services.modelCatalogSource.kind());
            Assert.assertFalse("FILE_COMPAT must be read-only",
                    h.services.modelCatalogSource.writable());
            Assert.assertNotNull("a clean file must produce a "
                            + "content-addressed snapshot; loadError="
                            + ((com.sage.business.code.bootstrap
                                    .FileManagedModelCatalogSource)
                                    h.services.modelCatalogSource).loadError(),
                    h.services.modelCatalogSource.snapshot());
            Assert.assertTrue("file release ids are content-addressed,"
                            + " got " + h.services.modelCatalogSource
                                    .sourceIdentity(),
                    h.services.modelCatalogSource.sourceIdentity()
                            .startsWith("file-"));

            // Read surface: the public catalog and the managed list
            // project the file, not an empty or sqlite view.
            JsonNode catalog = api(h, "GET", "/api/v2/model-catalog", null);
            Assert.assertEquals(200, catalog.get("status").asInt());
            boolean listed = false;
            for (JsonNode item : catalog.at("/body/items")) {
                if ("file-wire-a".equals(item.path("modelAlias").asText())) {
                    listed = true;
                }
            }
            Assert.assertTrue("the file catalog must list its model",
                    listed);
            JsonNode managed = api(h, "GET", "/api/v2/model-providers", null);
            Assert.assertEquals(200, managed.get("status").asInt());
            boolean managedListed = false;
            for (JsonNode provider : managed.at("/body/providers")) {
                if ("file-p".equals(provider.path("providerId").asText())) {
                    managedListed = true;
                }
            }
            Assert.assertTrue("the managed list must project the file",
                    managedListed);

            // Every catalog write refuses typed MODEL_SOURCE_READ_ONLY.
            JsonNode providerPut = api(h, "PUT",
                    "/api/v2/model-providers/file-p",
                    "{\"dialect\":\"OPENAI_CHAT_COMPLETIONS\","
                            + "\"baseUrl\":\"http://127.0.0.1:1/v1\","
                            + "\"credentialKind\":\"ENV\","
                            + "\"credentialEnvName\":\"X\"}");
            assertReadOnlyRefusal(providerPut, "provider upsert");
            JsonNode modelPut = api(h, "PUT",
                    "/api/v2/model-providers/file-p/models/file-wire-a",
                    "{}");
            assertReadOnlyRefusal(modelPut, "model upsert");
            // An empty JSON body rides the DELETE so the request carries
            // the mutation Idempotency-Key the handler requires.
            JsonNode providerDelete = api(h, "DELETE",
                    "/api/v2/model-providers/file-p", "{}");
            assertReadOnlyRefusal(providerDelete, "provider delete");
            JsonNode featureWrite = api(h, "PUT",
                    "/api/v2/environment/features/MODEL",
                    "{\"entries\":{\"defaultModel\":\"file-wire-a\"},"
                            + "\"expectedRevision\":0}");
            assertReadOnlyRefusal(featureWrite, "MODEL feature write");

            // A non-model feature write keeps working — the gate is
            // scoped to the catalog authority, not the feature plane.
            JsonNode webSearch = api(h, "PUT",
                    "/api/v2/environment/features/WEBSEARCH",
                    "{\"enabled\":true,\"expectedRevision\":0}");
            Assert.assertEquals("non-model feature write failed: "
                            + webSearch, 200,
                    webSearch.get("status").asInt());

            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            if (!modelStatements.isEmpty()) {
                StringBuilder dump = new StringBuilder(
                        "FILE_COMPAT issued model-table SQL:\n");
                for (SqliteStatementRecorder.Entry e : modelStatements) {
                    dump.append("  ").append(e.toString()).append('\n');
                }
                Assert.fail(dump.toString());
            }
            // The personal catalog slice does not exist in this store.
            Assert.assertFalse("model_provider must not exist under "
                            + "FILE_COMPAT",
                    tableExists(stateDir, "model_provider"));
            Assert.assertFalse("model_provider_model must not exist "
                            + "under FILE_COMPAT",
                    tableExists(stateDir, "model_provider_model"));
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV23 — ENTERPRISE_HTTP: the applied release is the authority.
    // Managed catalog writes and MODEL feature-row writes refuse typed
    // MODEL_SOURCE_READ_ONLY while non-model feature writes keep working.
    // ------------------------------------------------------------------
    @Test
    public void mv23_enterpriseModelWritesAreReadOnly() throws Exception {
        Harness h = bootEnterpriseService(false);
        try {
            com.sage.business.code.bootstrap.EffectiveModelContext ctx =
                    h.services.modelContext.current();
            Assert.assertNotNull("no effective model context", ctx);
            Assert.assertEquals("enterprise catalog did not assemble READY",
                    com.sage.business.code.bootstrap.EffectiveModelContext
                            .Readiness.READY, ctx.readiness());

            Assert.assertEquals(com.sage.business.code.adapter.product
                            .EnterpriseCatalogSettings.Source.ENTERPRISE_HTTP,
                    h.services.modelCatalogSource.kind());
            Assert.assertFalse("ENTERPRISE_HTTP must be read-only",
                    h.services.modelCatalogSource.writable());
            Assert.assertEquals("the applied release is the identity",
                    RELEASE_ID_A + "@41",
                    h.services.modelCatalogSource.sourceIdentity());

            JsonNode providerPut = api(h, "PUT",
                    "/api/v2/model-providers/" + PROVIDER_ID,
                    "{\"dialect\":\"OPENAI_CHAT_COMPLETIONS\","
                            + "\"baseUrl\":\"http://127.0.0.1:1/v1\","
                            + "\"credentialKind\":\"ENV\","
                            + "\"credentialEnvName\":\"X\"}");
            assertReadOnlyRefusal(providerPut, "provider upsert");
            JsonNode modelPut = api(h, "PUT",
                    "/api/v2/model-providers/" + PROVIDER_ID
                            + "/models/" + STABLE_MODEL_KEY,
                    "{}");
            assertReadOnlyRefusal(modelPut, "model upsert");
            JsonNode providerDelete = api(h, "DELETE",
                    "/api/v2/model-providers/" + PROVIDER_ID, "{}");
            assertReadOnlyRefusal(providerDelete, "provider delete");
            JsonNode modelDelete = api(h, "DELETE",
                    "/api/v2/model-providers/" + PROVIDER_ID
                            + "/models/" + STABLE_MODEL_KEY, "{}");
            assertReadOnlyRefusal(modelDelete, "model delete");
            JsonNode featureWrite = api(h, "PUT",
                    "/api/v2/environment/features/MODEL",
                    "{\"entries\":{\"defaultModel\":\""
                            + STABLE_MODEL_KEY + "\"},"
                            + "\"expectedRevision\":0}");
            assertReadOnlyRefusal(featureWrite, "MODEL feature write");

            // Non-model writes keep working under a managed source.
            JsonNode webSearch = api(h, "PUT",
                    "/api/v2/environment/features/WEBSEARCH",
                    "{\"enabled\":true,\"expectedRevision\":0}");
            Assert.assertEquals("non-model feature write failed: "
                            + webSearch, 200,
                    webSearch.get("status").asInt());

            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            if (!modelStatements.isEmpty()) {
                StringBuilder dump = new StringBuilder(
                        "refused writes still touched model tables:\n");
                for (SqliteStatementRecorder.Entry e : modelStatements) {
                    dump.append("  ").append(e.toString()).append('\n');
                }
                Assert.fail(dump.toString());
            }
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV05 — LOCAL first import: file -> DB import is one-shot. CRUD
    // writes land in the managed store; a deleted provider is NOT
    // resurrected by the file on the next boot; and the whole lifecycle
    // never needs or touches an enterprise endpoint.
    // ------------------------------------------------------------------
    @Test
    public void mv05_localImportCrudAndRequestSurviveRestart()
            throws Exception {
        Path stateDir = Files.createDirectories(
                temp.getRoot().toPath().resolve(UUID.randomUUID()
                        .toString().substring(0, 8))).toAbsolutePath();
        Path workspace = Files.createDirectories(
                temp.getRoot().toPath().resolve("ws-"
                        + stateDir.getFileName())).toAbsolutePath();
        initGitWorkspace(workspace);
        ReceiverStub model = new ReceiverStub();
        model.replyChatCompletions();
        ReceiverStub trap = new ReceiverStub();
        trap.replyChatCompletions();
        Path catalogFile = stateDir.resolve("model-providers.properties");
        Files.write(catalogFile, localCatalogProperties(model.port(),
                "local-p", "wire-local-a").getBytes(StandardCharsets.UTF_8));
        Path configFile = writeServiceProperties(stateDir, workspace,
                catalogFile, "model.catalog.source=LOCAL\n");
        Harness h = new Harness();
        h.stateDir = stateDir;
        h.model = model;
        h.trap = trap;
        // Deliberately no enterprise receiver: LOCAL never resolves one.
        try {
            startService(h, configFile);

            // The composed source is the LOCAL managed store.
            Assert.assertEquals(com.sage.business.code.adapter.product
                            .EnterpriseCatalogSettings.Source.LOCAL,
                    h.services.modelCatalogSource.kind());
            Assert.assertTrue("LOCAL must be writable",
                    h.services.modelCatalogSource.writable());

            // First-boot import: the file's provider row materialized in
            // the personal store and the one-shot marker is recorded.
            Assert.assertTrue("import produced no model_provider row",
                    providerRowExists(stateDir, "local-p"));
            Assert.assertTrue("no settings_import marker for MODEL",
                    importMarkerExists(stateDir, "MODEL"));

            // The read surfaces project the imported catalog.
            JsonNode providers = api(h, "GET", "/api/v2/model-providers",
                    null);
            Assert.assertEquals(200, providers.get("status").asInt());
            Assert.assertTrue("local-p must be listed",
                    providerListed(providers, "local-p"));

            // CRUD: rename through the managed surface, then add a
            // second provider — both real writes to the personal store.
            JsonNode rename = api(h, "PUT",
                    "/api/v2/model-providers/local-p",
                    "{\"dialect\":\"OPENAI_CHAT_COMPLETIONS\","
                            + "\"baseUrl\":\"http://127.0.0.1:"
                            + model.port() + "/v1\","
                            + "\"displayName\":\"MV05 Renamed\"}");
            Assert.assertEquals("provider rename failed: " + rename,
                    200, rename.get("status").asInt());
            // The real create flow: a provider lands DISABLED first (a
            // model-less provider can never pass merged-catalog
            // validation), gains its model, then enables.
            JsonNode createdProvider = api(h, "PUT",
                    "/api/v2/model-providers/local-b",
                    "{\"dialect\":\"OPENAI_CHAT_COMPLETIONS\","
                            + "\"baseUrl\":\"http://127.0.0.1:"
                            + model.port() + "/v1\","
                            + "\"credentialKind\":\"SECRET\","
                            + "\"apiKey\":\"ma00-dev-local-b\","
                            + "\"displayName\":\"MV05 Provider B\","
                            + "\"enabled\":false}");
            Assert.assertEquals("provider create failed: "
                            + createdProvider, 200,
                    createdProvider.get("status").asInt());
            JsonNode createdModel = api(h, "PUT",
                    "/api/v2/model-providers/local-b/models/wire-local-b",
                    "{\"remoteModelId\":\"wire-local-b\","
                            + "\"capabilities\":[\"NATIVE_TOOL_CALLING\"]}");
            Assert.assertEquals("model create failed: " + createdModel,
                    200, createdModel.get("status").asInt());
            JsonNode enabledProvider = api(h, "PUT",
                    "/api/v2/model-providers/local-b",
                    "{\"enabled\":true}");
            Assert.assertEquals("provider enable failed: "
                            + enabledProvider, 200,
                    enabledProvider.get("status").asInt());

            // A real request through the imported default lands on the
            // loopback receiver — never a lookup of personal sqlite at
            // dispatch time beyond the merged catalog.
            JsonNode chat = sendFirstMessage(h, "MA03-MV05", "mv05-msg-1");
            Assert.assertTrue("LOCAL chat failed: " + chat,
                    chat.get("status").asInt() == 200
                            || chat.get("status").asInt() == 202);
            waitFor("LOCAL dispatch", new java.util.function
                            .BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return model.hits("/v1/chat/completions") >= 1;
                        }
                    }, 8000);
            Assert.assertEquals("wire-local-a", model.lastModelId());

            // Delete the imported provider: replaceDefault keeps a
            // usable default on the CRUD-created provider.
            JsonNode deleted = api(h, "DELETE",
                    "/api/v2/model-providers/local-p"
                            + "?replaceDefault=wire-local-b", "{}");
            Assert.assertEquals("provider delete failed: " + deleted,
                    200, deleted.get("status").asInt());
            Assert.assertFalse("local-p must be gone from the list",
                    providerListed(api(h, "GET", "/api/v2/model-providers",
                            null), "local-p"));

            // Poison the seed file: re-add the deleted provider's file
            // block AND append a ghost provider the DB never imported —
            // a host that still consulted the file would resurrect both.
            Files.write(catalogFile, (localCatalogProperties(model.port(),
                    "local-p", "wire-local-a")
                    + "PROVIDER_2_ID=ghost-p\n"
                    + "PROVIDER_2_DIALECT=OPENAI_CHAT_COMPLETIONS\n"
                    + "PROVIDER_2_BASE_URL=http://127.0.0.1:"
                    + trap.port() + "/v1\n"
                    + "PROVIDER_2_API_KEY=ghost-material\n"
                    + "PROVIDER_2_MODEL_1_ID=trap-wire-x\n"
                    + "PROVIDER_2_MODEL_1_CAPABILITIES=NATIVE_TOOL_CALLING\n")
                    .getBytes(StandardCharsets.UTF_8));

            // Restart the service composition on the same state dir —
            // receivers stay alive because they are external endpoints.
            h.shutdownService();
            startService(h, configFile);

            JsonNode providersAfter = api(h, "GET",
                    "/api/v2/model-providers", null);
            Assert.assertEquals(200, providersAfter.get("status").asInt());
            Assert.assertFalse("deleted provider resurrected from file",
                    providerListed(providersAfter, "local-p"));
            Assert.assertFalse("never-imported ghost provider appeared",
                    providerListed(providersAfter, "ghost-p"));
            Assert.assertTrue("CRUD-created provider must survive",
                    providerListed(providersAfter, "local-b"));

            // Post-restart dispatch still flows through the DB catalog —
            // the CRUD-created default serves the request.
            JsonNode chatAfter = sendFirstMessage(h, "MA03-MV05-R",
                    "mv05-msg-2");
            Assert.assertTrue("post-restart LOCAL chat failed: "
                            + chatAfter,
                    chatAfter.get("status").asInt() == 200
                            || chatAfter.get("status").asInt() == 202);
            waitFor("post-restart dispatch", new java.util.function
                            .BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return model.hits("/v1/chat/completions") >= 2;
                        }
                    }, 8000);
            Assert.assertEquals("wire-local-b", model.lastModelId());
            Assert.assertEquals("trap endpoint received a model request",
                    0, trap.hits(null));
        } finally {
            h.close();
        }
    }

    // ------------------------------------------------------------------
    // MV09 — NEXT_SAFE_REQUEST: a desired-settings patch submitted while
    // a run is in flight stays PENDING, never mutates the in-flight
    // call, and the NEXT semantic request installs the complete epoch —
    // wire model, descriptor and endpoint all from provider B.
    // ------------------------------------------------------------------
    @Test
    public void mv09_nextSafeRequestInstallsEpochOnBoundary()
            throws Exception {
        ReceiverStub modelB = new ReceiverStub();
        modelB.replyChatCompletions();
        Harness h = bootEnterpriseService(false, 0,
                new java.util.function.Function<ReceiverStub, String>() {
                    @Override public String apply(ReceiverStub model) {
                        return catalogJsonTwoProviders(model.port(),
                                modelB.port(), RELEASE_ID_A);
                    }
                });
        try {
            JsonNode created = createSession(h, "MA04-MV09");
            final String sessionId = created.at("/body/sessionId")
                    .asText();
            final long version = created.at("/body/sessionVersion")
                    .asLong(1L);

            // Read the durable revision before any turn — the session
            // keyed lock makes every settings call impossible while the
            // gated turn below is in flight, which is itself the
            // product's guarantee that a desired write can never mutate
            // the in-flight call.
            JsonNode settings = api(h, "GET",
                    "/api/v2/sessions/" + sessionId + "/model-settings",
                    null);
            Assert.assertEquals(200, settings.get("status").asInt());
            final long revision = settings.at("/body/settingsRevision")
                    .asLong();

            // The v2 message POST answers only after the first model
            // turn completes — so the in-flight window requires the send
            // on a worker thread while the gated call is outstanding.
            h.model.gating = true;
            java.util.concurrent.ExecutorService sender =
                    java.util.concurrent.Executors
                            .newSingleThreadExecutor();
            java.util.concurrent.Future<JsonNode> first =
                    sender.submit(new java.util.concurrent
                            .Callable<JsonNode>() {
                        @Override public JsonNode call()
                                throws Exception {
                            return sendMessage(h, sessionId, version,
                                    "mv09-msg-1");
                        }
                    });
            waitFor("in-flight model call on provider A",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions")
                                    >= 1;
                        }
                    }, 8000);
            Assert.assertEquals(WIRE_MODEL_A, h.model.lastModelId());

            // Submit the NEXT_SAFE_REQUEST desired-state write while the
            // turn is provably in flight. The session keyed lock means
            // this PATCH cannot even be evaluated until the turn ends —
            // the strongest possible form of "never mutates the
            // in-flight call" (no state transition, PENDING or
            // otherwise, is observable mid-turn over public HTTP).
            java.util.concurrent.ExecutorService patcher =
                    java.util.concurrent.Executors
                            .newSingleThreadExecutor();
            java.util.concurrent.Future<JsonNode> patchCall =
                    patcher.submit(new java.util.concurrent
                            .Callable<JsonNode>() {
                        @Override public JsonNode call()
                                throws Exception {
                            // The public catalog names entries by wire
                            // id — the desired pin is provider B's wire
                            // alias.
                            return api(h, "PATCH",
                                    "/api/v2/sessions/" + sessionId
                                            + "/model-settings",
                                    "{\"expectedSettingsRevision\":"
                                            + revision + ","
                                            + "\"applyPolicy\":"
                                            + "\"NEXT_SAFE_REQUEST\","
                                            + "\"settings\":{"
                                            + "\"providerId\":\""
                                            + PROVIDER_ID_B
                                            + "\",\"modelAlias\":"
                                            + "\"wire-model-b\"}}");
                        }
                    });
            // Give the PATCH its full chance to complete mid-flight: it
            // must not — the turn's keyed lock excludes it.
            boolean finishedMidFlight = false;
            long pollUntil = System.currentTimeMillis() + 800L;
            while (System.currentTimeMillis() < pollUntil) {
                if (patchCall.isDone()) { finishedMidFlight = true; break; }
                try { Thread.sleep(50L); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            Assert.assertFalse("a NEXT_SAFE_REQUEST write was evaluated "
                            + "while a model turn was in flight",
                    finishedMidFlight);
            // Mid-flight must not swap: provider B has seen nothing and
            // A's wire id is still what the live call asked for.
            Assert.assertEquals("the in-flight call leaked onto "
                            + "provider B",
                    0, modelB.hits("/v1/chat/completions"));

            h.model.openGate();
            JsonNode firstResponse;
            try {
                firstResponse = first.get(30,
                        java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ee) {
                throw new AssertionError("gated first send failed", ee);
            } finally {
                sender.shutdownNow();
            }
            Assert.assertTrue("first send failed: " + firstResponse,
                    firstResponse.get("status").asInt() == 200
                            || firstResponse.get("status").asInt() == 202);
            // Ordinary chat on a no-lane session completes inline —
            // SUCCEEDED with runId null; the send's return already means
            // the turn (and the session keyed lock) is finished.
            Assert.assertEquals("SUCCEEDED",
                    firstResponse.at("/body/status").asText());

            // The queued write is evaluated once the turn's lock frees.
            // READY is deterministic here: ordinary chat never creates a
            // RunRecord, so no active run exists at evaluation. (The
            // PENDING variant requires a workspace-bound run, which a
            // no-managed-lane host cannot produce — and the lock makes
            // mid-turn evaluation impossible regardless.)
            JsonNode patch;
            try {
                patch = patchCall.get(30,
                        java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ee) {
                throw new AssertionError("queued settings patch failed",
                        ee);
            } finally {
                patcher.shutdownNow();
            }
            Assert.assertEquals("settings patch failed: " + patch,
                    200, patch.get("status").asInt());
            Assert.assertEquals("accepted write must be pending-install",
                    "READY", patch.at("/body/transitionState").asText());

            // The next semantic request installs the epoch — the wire id
            // AND the endpoint move to provider B's published selection.
            JsonNode second = sendMessage(h, sessionId,
                    patch.at("/body/sessionVersion").asLong(0L),
                    "mv09-msg-2");
            Assert.assertTrue("second send failed: " + second,
                    second.get("status").asInt() == 200
                            || second.get("status").asInt() == 202);
            waitFor("epoch-installed call on provider B",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return modelB.hits("/v1/chat/completions")
                                    >= 1;
                        }
                    }, 8000);
            Assert.assertEquals("the epoch must serve provider B's "
                            + "wire id, not A's",
                    "wire-model-b", modelB.lastModelId());
            Assert.assertTrue("provider B's credential must ride the "
                            + "call", modelB.authSeen.get(
                            modelB.authSeen.size() - 1).booleanValue());

            // The installed settings are durable on the session view —
            // READY carries desired=effective. (APPLIED is stamped only
            // by the run-path epoch ack, which the run-free ordinary
            // chat lane never invokes.)
            JsonNode after = api(h, "GET",
                    "/api/v2/sessions/" + sessionId + "/model-settings",
                    null);
            Assert.assertEquals(200, after.get("status").asInt());
            Assert.assertEquals("READY",
                    after.at("/body/transitionState").asText());
            Assert.assertEquals(PROVIDER_ID_B,
                    after.at("/body/effective/providerId").asText());
            Assert.assertEquals("wire-model-b",
                    after.at("/body/effective/modelAlias").asText());
            Assert.assertEquals("provider A received post-swap traffic",
                    1, h.model.hits("/v1/chat/completions"));
        } finally {
            h.model.openGate();
            h.close();
            modelB.close();
        }
    }

    // ------------------------------------------------------------------
    // MV16 — explicit unknown pins and ambiguous aliases refuse typed
    // at every entry surface: session patch, session create, and the
    // stage-host admission modelBinding pin. Nothing falls back to a
    // default or a best-effort route, and no refused request reaches
    // the model endpoint.
    // ------------------------------------------------------------------
    @Test
    public void mv16_unknownPinAndAmbiguousAliasRefuseTyped()
            throws Exception {
        String priorTrust = System.getProperty("acs.stage.trust.mode");
        System.setProperty("acs.stage.trust.mode", "dev");
        ReceiverStub modelB = new ReceiverStub();
        modelB.replyChatCompletions();
        try {
            Harness h = bootEnterpriseService(false, 0,
                    new java.util.function.Function<ReceiverStub,
                            String>() {
                        @Override public String apply(
                                ReceiverStub model) {
                            return catalogJsonTwoProviders(model.port(),
                                    modelB.port(), RELEASE_ID_A);
                        }
                    });
            try {
                JsonNode created = createSession(h, "MA04-MV16");
                String sessionId = created.at("/body/sessionId")
                        .asText();
                JsonNode settings = api(h, "GET",
                        "/api/v2/sessions/" + sessionId
                                + "/model-settings", null);
                long revision = settings.at("/body/settingsRevision")
                        .asLong();

                // (1) Unknown bare alias -> typed refusal.
                JsonNode ghostAlias = api(h, "PATCH",
                        "/api/v2/sessions/" + sessionId
                                + "/model-settings",
                        "{\"expectedSettingsRevision\":" + revision + ","
                                + "\"settings\":{\"modelAlias\":"
                                + "\"ghost-model-key\"}}");
                Assert.assertEquals("unknown alias must refuse, got "
                                + ghostAlias, 409,
                        ghostAlias.get("status").asInt());
                Assert.assertTrue("typed MODEL_* refusal expected, got "
                                + ghostAlias,
                        ghostAlias.at("/body/code").asText()
                                .startsWith("MODEL_"));

                // (2) Bare wire alias owned by BOTH providers ->
                // ambiguous; the resolver must refuse rather than pick
                // the first owner.
                JsonNode ambiguous = api(h, "PATCH",
                        "/api/v2/sessions/" + sessionId
                                + "/model-settings",
                        "{\"expectedSettingsRevision\":" + revision + ","
                                + "\"settings\":{\"modelAlias\":\""
                                + SHARED_WIRE + "\"}}");
                Assert.assertEquals("ambiguous bare alias must refuse, "
                                + "got " + ambiguous, 409,
                        ambiguous.get("status").asInt());
                Assert.assertEquals("MODEL_SELECTION_AMBIGUOUS",
                        ambiguous.at("/body/code").asText());

                // (3) Explicit unknown provider pin -> typed refusal.
                JsonNode ghostProvider = api(h, "PATCH",
                        "/api/v2/sessions/" + sessionId
                                + "/model-settings",
                        "{\"expectedSettingsRevision\":" + revision + ","
                                + "\"settings\":{\"providerId\":\""
                                + "ghost-provider"
                                + "\",\"modelAlias\":\"" + STABLE_MODEL_KEY
                                + "\"}}");
                Assert.assertEquals("unknown provider pin must refuse, "
                                + "got " + ghostProvider, 409,
                        ghostProvider.get("status").asInt());
                Assert.assertTrue(ghostProvider.at("/body/code").asText()
                        .startsWith("MODEL_"));

                // (4) Same pin at session create -> refused, no session.
                JsonNode createRefused = api(h, "POST",
                        "/api/v2/sessions",
                        "{\"schemaVersion\":\"business-session/2.0\","
                                + "\"title\":\"MA04-MV16-create\","
                                + "\"modelSettings\":{\"providerId\":"
                                + "\"ghost-provider\",\"modelAlias\":\""
                                + STABLE_MODEL_KEY + "\"}}");
                Assert.assertEquals("create with an unknown pin must "
                                + "refuse, got " + createRefused, 409,
                        createRefused.get("status").asInt());
                Assert.assertTrue(createRefused.at("/body/code").asText()
                        .startsWith("MODEL_"));

                // (5) Stage admission: an unverifiable modelBinding pin
                // fails MODEL_BINDING_UNSUPPORTED before any binding row.
                int hitsBefore = h.model.hits("/v1/chat/completions")
                        + modelB.hits("/v1/chat/completions");
                JsonNode pinned = api(h, "POST",
                        "/api/v2/design-stage-operations",
                        stageStartBody("mv16-refuse", "ghost-provider:"
                                + "ghost-key"));
                Assert.assertEquals("an unverifiable pin must refuse "
                                + "422, got " + pinned, 422,
                        pinned.get("status").asInt());
                Assert.assertEquals("MODEL_BINDING_UNSUPPORTED",
                        pinned.at("/body/code").asText());
                JsonNode lookup = api(h, "GET",
                        "/api/v2/design-stage-operations/by-request/"
                                + "mv16-refuse", null);
                Assert.assertEquals(200, lookup.get("status").asInt());
                Assert.assertEquals("a refused admission must leave no "
                                + "binding",
                        "NOT_FOUND_AUTHORITATIVE",
                        lookup.at("/body/lookupState").asText());

                // (6) Positive control: a resolvable pin is admitted and
                // the persisted aggregate carries the enterprise
                // binding fact.
                JsonNode admitted = api(h, "POST",
                        "/api/v2/design-stage-operations",
                        stageStartBody("mv16-admit",
                                PROVIDER_ID + ":" + STABLE_MODEL_KEY));
                Assert.assertEquals("a resolvable pin must admit, got "
                                + admitted, 202,
                        admitted.get("status").asInt());
                JsonNode aggregate = stageAggregate(h,
                        "mv16-admit");
                Assert.assertNotNull("no persisted stage aggregate for "
                                + "the admitted start", aggregate);
                JsonNode binding = aggregate.path("modelBinding");
                Assert.assertEquals("ENTERPRISE_HTTP",
                        binding.path("source").asText());
                Assert.assertEquals(RELEASE_ID_A,
                        binding.path("releaseId").asText());
                Assert.assertEquals(PROVIDER_ID,
                        binding.path("providerId").asText());

                // No refused request dispatched a model call; the design
                // intake is the only dispatch the admitted leg makes.
                waitFor("design intake dispatch", new java.util.function
                                .BooleanSupplier() {
                            @Override public boolean getAsBoolean() {
                                return h.model.hits("/v1/chat/completions")
                                        + modelB.hits(
                                        "/v1/chat/completions")
                                        > 0;
                            }
                        }, 8000);
                Assert.assertEquals(WIRE_MODEL_A, h.model.lastModelId());
                // The admitted leg provably CAN dispatch (hits grew past
                // the refused-window snapshot) — so every refusal above
                // stands as a door the dispatcher could have used but
                // did not.
                Assert.assertTrue("the admitted leg never reached a "
                                + "model endpoint", h.model.hits(null)
                        + modelB.hits(null) > hitsBefore);
            } finally {
                h.close();
            }
        } finally {
            restoreProperty("acs.stage.trust.mode", priorTrust);
            modelB.close();
        }
    }

    // ------------------------------------------------------------------
    // MV18 — five distinct consumer entries all dispatch through the
    // real applied source: ordinary chat (session run), CODE (v1
    // task/run), PLAN (Spec planner -> PlanAgent forced tool call),
    // DESIGN (stage-host DESIGN leaf), ACT (spec convert-to-act ->
    // task run). Each leg lands binding/source facts, not a shared
    // "a model call happened" proxy.
    // ------------------------------------------------------------------
    @Test
    public void mv18_fiveConsumersDispatchThroughRealSource()
            throws Exception {
        String priorTrust = System.getProperty("acs.stage.trust.mode");
        String priorPlan = System.getProperty(
                "business.spec.product-plan.enabled");
        System.setProperty("acs.stage.trust.mode", "dev");
        System.setProperty("business.spec.product-plan.enabled", "true");
        try {
            Harness h = bootEnterpriseService(false);
            try {
                String releaseAJson = catalogJson(h.model.port());
                h.enterprise.release(RELEASE_ID_A, releaseAJson);
                // ---- CHAT: ordinary session message -> run ----
                JsonNode chat = sendFirstMessage(h, "MA05-MV18-CHAT",
                        "mv18-chat-1");
                Assert.assertTrue("chat send failed: " + chat,
                        chat.get("status").asInt() == 200
                                || chat.get("status").asInt() == 202);
                waitFor("chat model dispatch", hitsAtLeast(h, 1),
                        8000);
                // Ordinary chat is run-free on this no-lane host — the
                // durable-fact surface is the dispatch itself: it went
                // to the published endpoint, on A's wire id, carrying
                // A's credential.
                Assert.assertEquals(WIRE_MODEL_A,
                        h.model.lastModelId());
                Assert.assertTrue("chat rode no credential",
                        h.model.authSeen.get(
                                h.model.authSeen.size() - 1)
                                .booleanValue());
                Assert.assertEquals("chat leaked to the trap endpoint",
                        0, h.trap.hits(null));

                // ---- CODE: v1 task contract -> approved run ----
                JsonNode task = api(h, "POST", "/api/v1/tasks",
                        "{\"schemaVersion\":\"business-code-task/0.1\","
                                + "\"workspace\":\"matest\","
                                + "\"mode\":\"INSPECT\","
                                + "\"goal\":\"inspect the workspace\","
                                + "\"acceptanceCriteria\":[{\"id\":"
                                + "\"AC-1\",\"given\":\"a bound run\","
                                + "\"when\":\"it admits\",\"then\":"
                                + "[\"binding stamped\"],\"observable\":"
                                + "\"run record\"}],\"verification\":{\"level\":\"NONE\"},\"limits\":{\"maxModelTurns\":4,\"maxEditAttempts\":1}}");
                Assert.assertEquals("task create failed: " + task,
                        201, task.get("status").asInt());
                String taskId = task.at("/body/taskId").asText();
                int revision = task.at("/body/revision").asInt();
                String fingerprint = task.at("/body/fingerprint")
                        .asText();
                JsonNode approved = api(h, "POST",
                        "/api/v1/tasks/" + taskId + "/approvals",
                        "{\"revision\":" + revision
                                + ",\"fingerprint\":\"" + fingerprint
                                + "\"}");
                Assert.assertEquals("task approve failed: " + approved,
                        200, approved.get("status").asInt());
                JsonNode codeRun = api(h, "POST",
                        "/api/v1/tasks/" + taskId + "/runs",
                        "{\"revision\":" + revision
                                + ",\"fingerprint\":\"" + fingerprint
                                + "\"}");
                Assert.assertEquals("run start failed: " + codeRun,
                        202, codeRun.get("status").asInt());
                assertEnterpriseBinding(codeRun.at("/body/modelBinding"));
                final String codeRunId = codeRun.at("/body/runId")
                        .asText();
                try {
                    waitFor("CODE model dispatch", hitsAtLeast(h, 2),
                            25000);
                } catch (AssertionError ae) {
                    JsonNode v = api(h, "GET", "/api/v2/runs/"
                            + codeRunId, null);
                    throw new AssertionError("CODE run view: " + v,
                            ae);
                }
                // The spec below pins the workspace baseline at create;
                // a still-finalizing INSPECT run can drop scratch files
                // that drift the fingerprint and trip SPEC_STALE at
                // approve — wait for the run to go terminal first.
                waitFor("CODE run terminal", runTerminal(h, codeRunId),
                        15000);

                // ---- PLAN: SPEC session -> spec planner model call ----
                JsonNode specSession = api(h, "POST", "/api/v2/sessions",
                        "{\"schemaVersion\":\"business-session/2.0\","
                                + "\"title\":\"MA05-MV18-PLAN\","
                                + "\"workspaceAlias\":\"matest\","
                                + "\"interactionMode\":\"SPEC\"}");
                Assert.assertEquals("SPEC session create failed: "
                                + specSession, 201,
                        specSession.get("status").asInt());
                String specSessionId = specSession.at("/body/sessionId")
                        .asText();
                // The synthesized plan's goal must equal the request
                // goal or plan validation refuses it.
                h.model.planGoal = "plan a small change";
                JsonNode spec = api(h, "POST",
                        "/api/v2/sessions/" + specSessionId + "/specs",
                        "{\"goal\":\"plan a small change\","
                                + "\"workspaceAlias\":\"matest\"}");
                Assert.assertEquals("spec create failed: " + spec,
                        201, spec.get("status").asInt());
                String specId = spec.at("/body/specId").asText();
                Assert.assertFalse(spec.at("/body/proposalId").asText()
                        .isEmpty());
                waitFor("PLAN model dispatch", hitsAtLeast(h, 3), 8000);
                Assert.assertEquals("the plan call must ride the "
                                + "applied release's wire id",
                        WIRE_MODEL_A, h.model.lastModelId());

                // ---- ACT: spec approve -> convert-to-act -> run ----
                JsonNode specView = api(h, "GET",
                        "/api/v2/specs/" + specId, null);
                Assert.assertEquals(200, specView.get("status").asInt());
                JsonNode decide = api(h, "POST",
                        "/api/v2/specs/" + specId
                                + "/approvals/mv18-decision/decisions",
                        "{\"expectedRevision\":1,\"decision\":\"APPROVE\","
                                + "\"contractFingerprint\":\""
                                + specView.at("/body/contractFingerprint")
                                .asText()
                                + "\",\"policyFingerprint\":\""
                                + specView.at("/body/policyFingerprint")
                                .asText()
                                + "\",\"workspaceBaselineFingerprint\":\""
                                + specView.at(
                                "/body/workspaceBaselineFingerprint")
                                .asText()
                                + "\",\"planFingerprint\":\""
                                + specView.at("/body/planFingerprint")
                                .asText() + "\"}");
                Assert.assertEquals("spec approve failed: " + decide
                                + " | workspace drift: "
                                + workspaceDrift(h), 200,
                        decide.get("status").asInt());
                JsonNode act = api(h, "POST",
                        "/api/v2/specs/" + specId + "/convert-to-act",
                        "{}");
                Assert.assertEquals("convert-to-act failed: " + act,
                        201, act.get("status").asInt());
                String actTaskId = act.at("/body/taskId").asText();
                String actFingerprint = act.at("/body/taskFingerprint")
                        .asText();
                JsonNode actApproved = api(h, "POST",
                        "/api/v1/tasks/" + actTaskId + "/approvals",
                        "{\"revision\":1,\"fingerprint\":\""
                                + actFingerprint + "\"}");
                Assert.assertEquals("ACT task approve failed: "
                                + actApproved, 200,
                        actApproved.get("status").asInt());
                JsonNode actRun = api(h, "POST",
                        "/api/v1/tasks/" + actTaskId + "/runs",
                        "{\"revision\":1,\"fingerprint\":\""
                                + actFingerprint + "\"}");
                Assert.assertEquals("ACT run start failed: " + actRun,
                        202, actRun.get("status").asInt());
                assertEnterpriseBinding(actRun.at("/body/modelBinding"));
                waitFor("ACT model dispatch", hitsAtLeast(h, 4), 8000);

                // ---- DESIGN: stage-host DESIGN binding -> intake ----
                // Runs last: a stage operation may write into the
                // workspace, which would drift the approved spec's
                // recorded baseline (SPEC_STALE) if it preceded the
                // approve/convert above.
                JsonNode design = api(h, "POST",
                        "/api/v2/design-stage-operations",
                        stageStartBody("mv18-design", null));
                Assert.assertEquals("design stage start failed: "
                                + design, 202,
                        design.get("status").asInt());
                waitFor("DESIGN model dispatch", hitsAtLeast(h, 5),
                        15000);
                JsonNode designAggregate = stageAggregate(h,
                        "mv18-design");
                Assert.assertNotNull("no stage aggregate persisted",
                        designAggregate);
                assertEnterpriseBinding(
                        designAggregate.path("modelBinding"));

                // Every leg proved the real source: all model traffic
                // went to the applied release's endpoint, on its wire
                // id, and nothing touched personal model tables.
                Assert.assertEquals(WIRE_MODEL_A, h.model.lastModelId());
                Assert.assertEquals("trap endpoint received a model "
                                + "request", 0, h.trap.hits(null));
                List<SqliteStatementRecorder.Entry> modelStatements =
                        modelTableStatements(h);
                if (!modelStatements.isEmpty()) {
                    StringBuilder dump = new StringBuilder(
                            "consumer dispatches read/wrote personal "
                                    + "model tables:\n");
                    for (SqliteStatementRecorder.Entry e
                            : modelStatements) {
                        dump.append("  ").append(e.toString())
                                .append('\n');
                    }
                    Assert.fail(dump.toString());
                }
            } finally {
                h.close();
            }
        } finally {
            restoreProperty("acs.stage.trust.mode", priorTrust);
            restoreProperty("business.spec.product-plan.enabled",
                    priorPlan);
        }
    }

    // ------------------------------------------------------------------
    // MV20 — checkpoint recovery: paused runs carry a persisted binding;
    // after a real service restart the RESUME rehydrates through the
    // release-addressed authority read (fixed mapping — the published
    // current release is never substituted). Withdrawing the release
    // first makes the same run stop typed instead of continuing on a
    // newer or default release.
    // ------------------------------------------------------------------
    @Test
    public void mv20_checkpointResumeRehydratesPinnedOrStopsTyped()
            throws Exception {
        final ReceiverStub modelB = new ReceiverStub();
        modelB.replyChatCompletions();
        // A run holds a WorkspaceFileLock for its whole flight — two
        // runs on one workspace can never both reach their model call,
        // so each gets its own workspace. Plain (non-Git) dirs keep
        // LOCAL_CHECKOUT honored instead of normalizing to
        // ISOLATED_WORKTREE, which in-place resumability needs.
        Path workspaceA = Files.createDirectories(
                temp.getRoot().toPath().resolve("ws-a-"
                        + UUID.randomUUID().toString().substring(0, 8)))
                .toAbsolutePath();
        Path workspaceB = Files.createDirectories(
                temp.getRoot().toPath().resolve("ws-b-"
                        + UUID.randomUUID().toString().substring(0, 8)))
                .toAbsolutePath();
        Harness h = bootEnterpriseService(false, 0, null,
                "workspace.matest-a=" + workspaceA.toString()
                        .replace("\\", "\\\\") + "\n"
                        + "workspace.matest-b=" + workspaceB.toString()
                        .replace("\\", "\\\\") + "\n");
        try {
            // The pinned read must be servable — the release-addressed
            // endpoint carries A until the test withdraws it.
            h.enterprise.release(RELEASE_ID_A, catalogJson(h.model.port()));
            h.model.gating = true;
            // Ordinary chat on a no-lane session completes inline with
            // no RunRecord — checkpoint/resume qualification needs real
            // runs, so MV20 drives two INSPECT task runs instead.
            final String runIdA = startResumableTaskRun(h, "mv20-a",
                    "matest-a");
            final String runIdB = startResumableTaskRun(h, "mv20-b",
                    "matest-b");
            Assert.assertFalse("task run carried no runId",
                    runIdA.isEmpty());
            Assert.assertFalse("task run carried no runId",
                    runIdB.isEmpty());
            waitFor("both runs in flight", new java.util.function
                            .BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions")
                                    >= 2;
                        }
                    }, 8000);

            // Persisted binding facts at pause time — the checkpoint's
            // authority tuple.
            JsonNode runA = api(h, "GET", "/api/v1/runs/" + runIdA,
                    null);
            JsonNode bindingA = runA.at("/body/modelBinding");
            assertEnterpriseBinding(bindingA);
            Assert.assertEquals(RELEASE_ID_A,
                    bindingA.path("releaseId").asText());

            // Human-pause both runs at the next safe boundary.
            JsonNode pauseA = api(h, "POST",
                    "/api/v2/runs/" + runIdA + "/controls",
                    "{\"intent\":\"PAUSE\"}");
            Assert.assertEquals("pause A refused: " + pauseA, 200,
                    pauseA.get("status").asInt());
            JsonNode pauseB = api(h, "POST",
                    "/api/v2/runs/" + runIdB + "/controls",
                    "{\"intent\":\"PAUSE\"}");
            Assert.assertEquals("pause B refused: " + pauseB, 200,
                    pauseB.get("status").asInt());
            h.model.openGate();
            waitFor("run A paused with checkpoint", runPaused(h,
                    runIdA), 15000);
            waitFor("run B paused with checkpoint", runPaused(h,
                    runIdB), 15000);

            // Restart: receivers stay alive (external endpoints), the
            // service composition and its in-memory capture map are
            // rebuilt — every later rehydrate must go through the
            // release-addressed enterprise read.
            Path configFile = h.stateDir.resolve("service.properties");
            h.shutdownService();
            // Governance moves current to B; A stays published.
            String releaseB = catalogJson(modelB.port(), RELEASE_ID_B,
                    42, '2', 'c', "wire-model-b", "v1", true);
            h.enterprise.swapCatalog(releaseB);
            h.enterprise.release(RELEASE_ID_B, releaseB);
            startService(h, configFile);


            // Positive leg: A still published -> RESUME rehydrates the
            // persisted pin through GET .../releases/A, then continues —
            // never onto the now-current release B.
            JsonNode resumeA = api(h, "POST",
                    "/api/v2/runs/" + runIdA + "/controls",
                    "{\"intent\":\"RESUME\"}");
            Assert.assertEquals("resume A refused: " + resumeA, 200,
                    resumeA.get("status").asInt());
            waitFor("pinned release re-fetch at resume",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.enterprise.hits("/releases/"
                                    + RELEASE_ID_A) > 0;
                        }
                    }, 8000);
            // The INSPECT run's report synthesis was its single model
            // turn — pause landed after it, so resume may dispatch no
            // further call. The authority fact is the rehydrated pin
            // (release-A re-fetch above) plus terminal completion still
            // on A's binding with zero fallback onto B.
            waitFor("resumed run completes on A's pin",
                    runTerminal(h, runIdA), 15000);
            Assert.assertEquals("the resumed call must stay on A's "
                            + "wire id", WIRE_MODEL_A,
                    h.model.lastModelId());
            // Baseline for the withdrawn leg: run A's resumed dispatch
            // counts as legitimate traffic on the pinned endpoint.
            final int hitsAfterResumeA =
                    h.model.hits("/v1/chat/completions");
            Assert.assertEquals("the now-current release B must not "
                            + "absorb pinned work",
                    0, modelB.hits("/v1/chat/completions"));
            JsonNode viewA = api(h, "GET",
                    "/api/v2/runs/" + runIdA, null);
            Assert.assertEquals("the resumed pinned run must reach "
                            + "terminal completion: " + viewA,
                    "COMPLETED",
                    viewA.at("/body/state").asText());
            Assert.assertNotSame("resume under a live pin must not "
                            + "stop typed", "MODEL_SNAPSHOT_UNAVAILABLE",
                    viewA.at("/body/failureCode").asText());

            // Negative leg: withdraw A, then resume B — the pinned read
            // now 404s and the run parks typed, dispatching nothing.
            h.enterprise.withdraw(RELEASE_ID_A);
            JsonNode resumeB = api(h, "POST",
                    "/api/v2/runs/" + runIdB + "/controls",
                    "{\"intent\":\"RESUME\"}");
            Assert.assertEquals("resume B refused: " + resumeB, 200,
                    resumeB.get("status").asInt());
            waitFor("withdrawn resume parks typed",
                    new java.util.function.BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            try {
                                JsonNode v = api(h, "GET",
                                        "/api/v2/runs/" + runIdB, null);
                                return "NEEDS_ATTENTION".equals(
                                        v.at("/body/state").asText());
                            } catch (IOException e) {
                                return false;
                            }
                        }
                    }, 15000);
            JsonNode viewB = api(h, "GET", "/api/v2/runs/" + runIdB,
                    null);
            Assert.assertEquals("MODEL_SNAPSHOT_UNAVAILABLE",
                    viewB.at("/body/failureCode").asText());
            Assert.assertEquals("the refused resume dispatched a model "
                            + "call", hitsAfterResumeA,
                    h.model.hits("/v1/chat/completions"));
            Assert.assertEquals("current release B absorbed withdrawn "
                            + "pinned work",
                    0, modelB.hits("/v1/chat/completions"));
        } finally {
            h.model.openGate();
            h.close();
            modelB.close();
        }
    }

    // ------------------------------------------------------------------
    // MV21 — state stamps refuse cross-source reads: a binding minted
    // under LOCAL stamps source=LOCAL and can never rehydrate on an
    // ENTERPRISE-stamped host; the enterprise host over a LOCAL-era
    // state dir issues zero personal-table SQL while its first bind
    // stamps source=ENTERPRISE_HTTP.
    // ------------------------------------------------------------------
    @Test
    public void mv21_localStampedBindingRefusedOnEnterpriseHost()
            throws Exception {
        // Phase 1 — a real LOCAL boot: import + a live capture that
        // stamps source=LOCAL into a persisted-shaped binding.
        Path stateDir = Files.createDirectories(
                temp.getRoot().toPath().resolve(UUID.randomUUID()
                        .toString().substring(0, 8))).toAbsolutePath();
        Path workspace = Files.createDirectories(
                temp.getRoot().toPath().resolve("ws-"
                        + stateDir.getFileName())).toAbsolutePath();
        ReceiverStub model = new ReceiverStub();
        model.replyChatCompletions();
        Path catalogFile = stateDir.resolve("model-providers.properties");
        Files.write(catalogFile, localCatalogProperties(model.port(),
                "local-p", "wire-local-a").getBytes(StandardCharsets.UTF_8));
        Path localConfig = writeServiceProperties(stateDir, workspace,
                catalogFile, "model.catalog.source=LOCAL\n");
        Harness h = new Harness();
        h.stateDir = stateDir;
        h.model = model;
        h.trap = new ReceiverStub();
        h.trap.replyChatCompletions();
        Map<String, Object> localBindingMap;
        List<String> personalRowsBefore;
        try {
            startService(h, localConfig);
            com.sage.business.code.application.model.ModelCapture
                    capture = new com.sage.business.code.application.model
                            .ModelCapture(h.services.modelContext);
            com.sage.business.code.application.model.ModelBinding
                    localBinding = capture.capture().binding();
            Assert.assertEquals("LOCAL", localBinding.source);
            localBindingMap = localBinding.toMap();
            Assert.assertNotNull(localBindingMap);
            personalRowsBefore = readPersonalRows(stateDir);
            Assert.assertFalse("LOCAL import produced no personal rows",
                    personalRowsBefore.isEmpty());
        } finally {
            h.shutdownService();
        }

        // Phase 2 — repoint the SAME state dir at ENTERPRISE_HTTP: the
        // real cross-source posture change. The LOCAL-stamped binding is
        // refused typed, personal rows stay byte-identical, and the
        // host's own first bind reads no personal tables.
        h.enterprise = new ReceiverStub();
        h.enterprise.catalog(catalogJson(h.model.port()));
        Path enterpriseConfig = writeServiceProperties(stateDir,
                workspace, null, h.enterprise, true);
        try {
            startService(h, enterpriseConfig);
            Assert.assertEquals(com.sage.business.code.adapter.product
                            .EnterpriseCatalogSettings.Source
                            .ENTERPRISE_HTTP,
                    h.services.modelCatalogSource.kind());
            // Deliberately NOT resetting the recorder window: the whole
            // enterprise boot + runtime on a LOCAL-migrated state dir is
            // the asserted window — any personal-table touch anywhere in
            // it is a boundary violation.

            // The honest composition seam for this boot shape: the
            // factory's ModelCapture carries an admission guard only
            // when the enterprise synchronizer is wired — this harness
            // boots sync.disabled, so the binding-refusal contract under
            // test is exactly what the wired capture does.
            com.sage.business.code.application.model.ModelCapture
                    capture = new com.sage.business.code.application.model
                            .ModelCapture(h.services.modelContext);
            try {
                capture.rehydrate(com.sage.business.code.application
                        .model.ModelBinding.fromMap(localBindingMap));
                Assert.fail("a LOCAL-stamped binding must not rehydrate "
                        + "on an ENTERPRISE-stamped host");
            } catch (com.sage.business.code.domain.BusinessException e) {
                Assert.assertEquals("MODEL_SNAPSHOT_UNAVAILABLE",
                        e.getCode());
            }
            Assert.assertEquals("the refusal pulled the release read",
                    0, h.enterprise.hits("/releases/"));

            // A legal first bind on the old state: a session chat
            // admits + dispatches through the enterprise source — and
            // the whole window never reads or writes personal model
            // tables. (The no-lane chat lane is run-free — a direct
            // completion with no RunRecord — so the durable binding
            // fact is read on a real task run below, admitted under the
            // same captured context.)
            JsonNode chat = sendFirstMessage(h, "MA05-MV21",
                    "mv21-msg-1");
            Assert.assertTrue("enterprise chat on old state failed: "
                            + chat,
                    chat.get("status").asInt() == 200
                            || chat.get("status").asInt() == 202);
            waitFor("enterprise dispatch", new java.util.function
                            .BooleanSupplier() {
                        @Override public boolean getAsBoolean() {
                            return h.model.hits("/v1/chat/completions")
                                    >= 1;
                        }
                    }, 8000);

            JsonNode task = api(h, "POST", "/api/v1/tasks",
                    "{\"schemaVersion\":\"business-code-task/0.1\","
                            + "\"workspace\":\"matest\","
                            + "\"mode\":\"INSPECT\","
                            + "\"goal\":\"inspect the workspace\","
                            + "\"acceptanceCriteria\":[{\"id\":\"AC-1\","
                            + "\"given\":\"a bound run\",\"when\":\"it "
                            + "admits\",\"then\":[\"binding stamped\"],"
                            + "\"observable\":\"run record\"}],\"verification\":{\"level\":\"NONE\"},\"limits\":{\"maxModelTurns\":4,\"maxEditAttempts\":1}}");
            Assert.assertEquals("task create failed: " + task,
                    201, task.get("status").asInt());
            String taskId = task.at("/body/taskId").asText();
            int revision = task.at("/body/revision").asInt();
            String fingerprint = task.at("/body/fingerprint").asText();
            JsonNode approved = api(h, "POST",
                    "/api/v1/tasks/" + taskId + "/approvals",
                    "{\"revision\":" + revision
                            + ",\"fingerprint\":\"" + fingerprint
                            + "\"}");
            Assert.assertEquals("task approve failed: " + approved,
                    200, approved.get("status").asInt());
            JsonNode run = api(h, "POST",
                    "/api/v1/tasks/" + taskId + "/runs",
                    "{\"revision\":" + revision
                            + ",\"fingerprint\":\"" + fingerprint
                            + "\"}");
            Assert.assertEquals("run start failed: " + run, 202,
                    run.get("status").asInt());
            assertEnterpriseBinding(run.at("/body/modelBinding"));
            Assert.assertEquals(RELEASE_ID_A,
                    run.at("/body/modelBinding/releaseId").asText());

            List<SqliteStatementRecorder.Entry> modelStatements =
                    modelTableStatements(h);
            StringBuilder dump = new StringBuilder();
            for (SqliteStatementRecorder.Entry e : modelStatements) {
                dump.append("  ").append(e.toString()).append('\n');
            }
            Assert.assertTrue("the cross-source boot issued "
                            + "personal-table SQL:\n" + dump,
                    modelStatements.isEmpty());
            Assert.assertEquals("the LOCAL-era personal rows were "
                            + "rewritten", personalRowsBefore,
                    readPersonalRows(stateDir));
            Assert.assertEquals("trap endpoint received a model request",
                    0, h.trap.hits(null));
        } finally {
            h.close();
        }
    }

    /** Bounded supplier: run view reaches a terminal v2 state. */
    private java.util.function.BooleanSupplier runTerminal(
            final Harness h, final String runId) {
        return new java.util.function.BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                try {
                    JsonNode view = api(h, "GET",
                            "/api/v2/runs/" + runId, null);
                    String state = view.at("/body/state").asText();
                    return "COMPLETED".equals(state)
                            || "FAILED".equals(state)
                            || "CANCELLED".equals(state)
                            || "NEEDS_ATTENTION".equals(state);
                } catch (IOException e) {
                    return false;
                }
            }
        };
    }

    /** Bounded supplier: {@code hits("/v1/chat/completions") >= n}. */
    private java.util.function.BooleanSupplier hitsAtLeast(
            final Harness h, final int n) {
        return new java.util.function.BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                return h.model.hits("/v1/chat/completions") >= n;
            }
        };
    }

    /**
     * Bounded supplier: the run view reports the human-paused source
     * status with a checkpoint. (The v2 {@code state} enum has no
     * PAUSED_FOR_HUMAN mapping — it projects UNKNOWN; the raw
     * {@code sourceStatus} is the honest signal.)
     */
    private java.util.function.BooleanSupplier runPaused(
            final Harness h, final String runId) {
        return new java.util.function.BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                try {
                    JsonNode view = api(h, "GET",
                            "/api/v2/runs/" + runId, null);
                    return "PAUSED_FOR_HUMAN".equals(
                            view.at("/body/sourceStatus").asText())
                            && view.at("/body/checkpointAvailable")
                            .asBoolean();
                } catch (IOException e) {
                    return false;
                }
            }
        };
    }

    /**
     * Turns the harness workspace into a real (empty-committed) Git
     * repository so INSPECT task runs satisfy READ_ONLY_SNAPSHOT /
     * ISOLATED_WORKTREE eligibility. Git is a product dependency — the
     * service itself shells {@code git -C} for workspace capture.
     */
    private static void initGitWorkspace(Path workspace)
            throws IOException {
        runGit(workspace, "init", "-q");
        runGit(workspace, "-c", "user.email=ma001@test",
                "-c", "user.name=ma001", "commit", "-q",
                "--allow-empty", "-m", "init");
    }

    private static void runGit(Path workspace, String... args)
            throws IOException {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(workspace.toString());
        for (String a : args) command.add(a);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process proc = builder.start();
            int exit = proc.waitFor();
            Assert.assertEquals("git " + Arrays.toString(args)
                            + " failed", 0, exit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git init interrupted", e);
        }
    }

    /**
     * Creates + approves + starts a real MODIFY task run through the
     * v1 contract — the pausable/resumable run vehicle for MV20
     * (ordinary chat is run-free on a no-lane host, and INSPECT maps to
     * READ_ONLY_SNAPSHOT which cannot resume in place). Returns the
     * admitted runId.
     */
    private String startResumableTaskRun(Harness h, String tag,
                                       String workspaceAlias)
            throws IOException {
        String goal = "inspect " + tag;
        // MODIFY+LOCAL_CHECKOUT executes in the real workspace root, so
        // the pause checkpoint's workspace matches the task's at
        // resume. INSPECT always runs READ_ONLY_SNAPSHOT — its
        // checkpoint binds the materialized snapshot and
        // CodeContinuationAuthorization refuses it as a different
        // workspace (RESUME_INCOMPATIBLE), structurally unresumable.
        JsonNode task = api(h, "POST", "/api/v1/tasks",
                "{\"schemaVersion\":\"business-code-task/0.1\","
                        + "\"workspace\":\"" + workspaceAlias + "\","
                        + "\"workspaceMode\":\"LOCAL_CHECKOUT\","
                        + "\"permissionMode\":\"FULL_ACCESS\","
                        + "\"mode\":\"MODIFY\","
                        + "\"goal\":\"" + goal + "\","
                        + "\"acceptanceCriteria\":[{\"id\":\"AC-1\","
                        + "\"given\":\"a run\",\"when\":\"it starts\","
                        + "\"then\":[\"reported\"],\"observable\":\"summary\"}],"
                        + "\"writeScope\":{\"allow\":[\"docs\"],"
                        + "\"forbid\":[]},"
                        + "\"verification\":{\"level\":\"COMPILE\"},"
                        + "\"limits\":{\"maxModelTurns\":4,\"maxEditAttempts\":1}}");
        Assert.assertEquals("task create failed: " + task, 201,
                task.get("status").asInt());
        String taskId = task.at("/body/taskId").asText();
        int revision = task.at("/body/revision").asInt();
        String fingerprint = task.at("/body/fingerprint").asText();
        JsonNode approved = api(h, "POST",
                "/api/v1/tasks/" + taskId + "/approvals",
                "{\"revision\":" + revision
                        + ",\"fingerprint\":\"" + fingerprint
                        + "\"}");
        Assert.assertEquals("task approve failed: " + approved, 200,
                approved.get("status").asInt());
        JsonNode started = api(h, "POST",
                "/api/v1/tasks/" + taskId + "/runs",
                "{\"revision\":" + revision
                        + ",\"fingerprint\":\"" + fingerprint
                        + "\"}");
        Assert.assertTrue("run start failed: " + started,
                started.get("status").asInt() == 200
                        || started.get("status").asInt() == 202);
        return started.at("/body/runId").asText();
    }

    /** The MV16/MV18 DESIGN-stage start body in the v1 contract. */
    private static String stageStartBody(String requestId,
                                         String modelBindingRef) {
        return "{"
                + "\"schemaVersion\":\"acs-stage-host.v1\","
                + "\"adapterRevision\":\"acs-stage-host.draft-1\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"effectId\":\"effect-" + requestId + "\","
                + "\"requestDigest\":\"sha256:" + repeat('5', 64)
                + "\","
                + "\"identity\":{"
                + "\"organizationId\":\"org-mv\","
                + "\"projectId\":\"proj-mv\","
                + "\"stageBindingId\":\"bind-" + requestId + "\","
                + "\"stageEpoch\":\"epoch-1\","
                + "\"stageKind\":\"DESIGN\","
                + "\"remoteOperationKey\":\"remote-" + requestId
                + "\"},"
                + "\"permitRef\":\"dev-permit-" + requestId + "\","
                + "\"providerExtension\":{"
                + "\"schema\":\"design-session-request/v2\","
                + "\"requirementText\":\"design a small change\""
                + "}"
                + (modelBindingRef == null ? ""
                        : ",\"modelBinding\":{\"ref\":\""
                        + modelBindingRef + "\"}")
                + "}";
    }

    /**
     * Reads the persisted stage aggregate for a started request — the
     * durable binding fact surface MV16/MV18 assert on. Scans
     * {@code stage-host/bindings/<scope>/<binding>/aggregate.json}.
     */
    private static JsonNode stageAggregate(Harness h, String requestId)
            throws IOException {
        Path bindings = h.stateDir.resolve("stage-host").resolve(
                "bindings");
        if (!Files.isDirectory(bindings)) return null;
        List<Path> found = new ArrayList<Path>();
        java.util.stream.Stream<Path> scopes = Files.list(bindings);
        try {
            java.util.Iterator<Path> it = scopes.iterator();
            while (it.hasNext()) {
                Path scope = it.next();
                if (!Files.isDirectory(scope)) continue;
                java.util.stream.Stream<Path> keys = Files.list(scope);
                try {
                    java.util.Iterator<Path> kit = keys.iterator();
                    while (kit.hasNext()) {
                        Path file = kit.next().resolve("aggregate.json");
                        if (Files.isRegularFile(file)) found.add(file);
                    }
                } finally {
                    keys.close();
                }
            }
        } finally {
            scopes.close();
        }
        for (Path file : found) {
            JsonNode aggregate = MAPPER.readTree(new String(
                    Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (requestId.equals(aggregate.path("startRequestId")
                    .asText())) {
                return aggregate;
            }
        }
        return null;
    }

    /** Asserts the persisted binding names the real enterprise release. */
    private static void assertEnterpriseBinding(JsonNode binding) {
        Assert.assertTrue("binding missing or empty: " + binding,
                binding != null && binding.isObject()
                        && !binding.path("source").asText().isEmpty());
        Assert.assertEquals("ENTERPRISE_HTTP",
                binding.path("source").asText());
        Assert.assertEquals(RELEASE_ID_A,
                binding.path("releaseId").asText());
        Assert.assertEquals(PROVIDER_ID,
                binding.path("providerId").asText());
    }

    /** A LOCAL-era personal row snapshot for byte-compare (MV21). */
    private static List<String> readPersonalRows(Path stateDir)
            throws Exception {
        Path db = stateDir.resolve("gui-sessions.db");
        if (!Files.isRegularFile(db)) return Collections.emptyList();
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toString());
        List<String> rows = new ArrayList<String>();
        try {
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(
                    "SELECT provider_id,dialect,base_url,credential_kind,"
                            + "credential_ref,enabled,source,revision "
                            + "FROM model_provider ORDER BY provider_id");
            while (rs.next()) {
                rows.add("p|" + rs.getString(1) + "|" + rs.getString(2)
                        + "|" + rs.getString(3) + "|" + rs.getString(4)
                        + "|" + rs.getString(5) + "|" + rs.getInt(6)
                        + "|" + rs.getString(7) + "|" + rs.getInt(8));
            }
            rs.close();
            rs = statement.executeQuery(
                    "SELECT provider_id,model_id,remote_model_id,enabled,"
                            + "revision FROM model_provider_model "
                            + "ORDER BY provider_id,model_id");
            while (rs.next()) {
                rows.add("m|" + rs.getString(1) + "|" + rs.getString(2)
                        + "|" + rs.getString(3) + "|" + rs.getInt(4)
                        + "|" + rs.getInt(5));
            }
            rs.close();
            statement.close();
        } finally {
            connection.close();
        }
        return rows;
    }

    private static boolean providerRowExists(Path stateDir,
            String providerId) throws Exception {
        Path db = stateDir.resolve("gui-sessions.db");
        if (!Files.isRegularFile(db)) return false;
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toString());
        try {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM model_provider WHERE provider_id=?");
            try {
                statement.setString(1, providerId);
                ResultSet rows = statement.executeQuery();
                try {
                    return rows.next();
                } finally {
                    rows.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private static boolean importMarkerExists(Path stateDir,
            String featureId) throws Exception {
        Path db = stateDir.resolve("gui-sessions.db");
        if (!Files.isRegularFile(db)) return false;
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toString());
        try {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM settings_import WHERE feature_id=?");
            try {
                statement.setString(1, featureId);
                ResultSet rows = statement.executeQuery();
                try {
                    return rows.next();
                } finally {
                    rows.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    private static boolean providerListed(JsonNode providers,
            String providerId) {
        for (JsonNode provider : providers.at("/body/providers")) {
            if (providerId.equals(provider.path("providerId").asText())) {
                return true;
            }
        }
        return false;
    }

    /** A refused write answers HTTP 409 + the typed read-only code. */
    private static void assertReadOnlyRefusal(JsonNode response,
                                              String operation) {
        Assert.assertEquals(operation + " must be refused, got "
                        + response, 409, response.get("status").asInt());
        Assert.assertEquals(operation + " must carry the typed code",
                "MODEL_SOURCE_READ_ONLY",
                response.at("/body/code").asText());
    }

    /** True when {@code gui-sessions.db} carries the named table. */
    private static boolean tableExists(Path stateDir, String table)
            throws Exception {
        Path db = stateDir.resolve("gui-sessions.db");
        if (!Files.isRegularFile(db)) return false;
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
        try {
            PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type='table' "
                            + "AND name=?");
            try {
                statement.setString(1, table);
                ResultSet rows = statement.executeQuery();
                try {
                    return rows.next();
                } finally {
                    rows.close();
                }
            } finally {
                statement.close();
            }
        } finally {
            connection.close();
        }
    }

    /**
     * Shared service.properties writer whose catalog-source block is
     * supplied verbatim — FILE_COMPAT needs no enterprise endpoint, so
     * this overload takes raw lines instead of a receiver.
     */
    private Path writeServiceProperties(Path stateDir, Path workspace,
                                        Path catalogFile,
                                        String sourceBlock)
            throws IOException {
        Path file = stateDir.resolve("service.properties");
        StringBuilder out = new StringBuilder();
        out.append("server.host=127.0.0.1\n");
        out.append("server.port=").append(freePort()).append('\n');
        out.append("auth.token=").append(SERVICE_TOKEN).append('\n');
        out.append("state.dir=").append(stateDir.toString()
                .replace("\\", "\\\\")).append('\n');
        out.append("workspace.matest=").append(workspace.toString()
                .replace("\\", "\\\\")).append('\n');
        out.append("logging.dir=").append(stateDir.resolve("logs")
                .toString().replace("\\", "\\\\")).append('\n');
        out.append("agent.profiles.directory=").append(stateDir
                .resolve("agents").toString().replace("\\", "\\\\")).append('\n');
        if (catalogFile != null) {
            out.append("model.config=").append(catalogFile.toString()
                    .replace("\\", "\\\\")).append('\n');
        }
        out.append(sourceBlock);
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    // ==================================================================
    // harness
    // ==================================================================

    private Harness bootEnterpriseService(boolean poisonedDb) throws Exception {
        return bootEnterpriseService(poisonedDb, 0);
    }

    /**
     * @param applicationFailures number of {@code POST .../applications}
     *        receipts the enterprise stub answers 500 to before turning
     *        healthy (MV15 report-endpoint fault, set before boot so the
     *        first APPLIED filing already fails).
     */
    private Harness bootEnterpriseService(boolean poisonedDb,
                                          int applicationFailures)
            throws Exception {
        return bootEnterpriseService(poisonedDb, applicationFailures, null);
    }

    /**
     * @param catalogFor computes the enterprise catalog body from the
     *        freshly bound model receiver; {@code null} serves the
     *        hand-written {@link #catalogJson}. MV06 supplies the real
     *        EAC publish-path bytes instead.
     */
    private Harness bootEnterpriseService(boolean poisonedDb,
                                          int applicationFailures,
                                          java.util.function.Function
                                                  <ReceiverStub, String>
                                                  catalogFor)
            throws Exception {
        return bootEnterpriseService(poisonedDb, applicationFailures,
                catalogFor, null);
    }

    /**
     * @param extraEnterpriseConfig MA04 opt-in: extra
     *        {@code model.catalog.enterprise.*} lines appended after the
     *        default {@code sync.enabled=false}; when non-null the shared
     *        enterprise block still carries the default-off flag first so
     *        a case re-enables explicitly.
     */
    private Harness bootEnterpriseService(boolean poisonedDb,
                                          int applicationFailures,
                                          java.util.function.Function
                                                  <ReceiverStub, String>
                                                  catalogFor,
                                          String extraEnterpriseConfig)
            throws Exception {
        Path stateDir = Files.createDirectories(
                temp.getRoot().toPath().resolve(UUID.randomUUID()
                        .toString().substring(0, 8))).toAbsolutePath();
        Path workspace = Files.createDirectories(
                temp.getRoot().toPath().resolve("ws-"
                        + stateDir.getFileName())).toAbsolutePath();
        initGitWorkspace(workspace);
        Harness h = new Harness();
        h.stateDir = stateDir;
        h.model = new ReceiverStub();
        h.model.replyChatCompletions();
        h.trap = new ReceiverStub();
        h.trap.replyChatCompletions();
        h.enterprise = new ReceiverStub();
        h.enterprise.applicationFailures.set(applicationFailures);
        h.enterprise.catalog(catalogFor == null
                ? catalogJson(h.model.port())
                : catalogFor.apply(h.model));
        if (poisonedDb) {
            writePoisonedDatabase(stateDir, h.trap.port());
            h.poisonedRowsBefore = readPoisonedRows(stateDir);
        }
        Path configFile = extraEnterpriseConfig == null
                ? writeServiceProperties(stateDir, workspace, null,
                        h.enterprise, true)
                : writeServiceProperties(stateDir, workspace, null,
                        h.enterprise, extraEnterpriseConfig);
        startService(h, configFile);
        return h;
    }

    /**
     * One real boot of the full service composition over an existing
     * service.properties — shared by the first boot and the in-JVM
     * restart used by MV05/MV20/MV21. The recorder window opens BEFORE
     * {@code ServiceConfig.load} so the boot's own statements land in
     * the asserted window.
     */
    private void startService(Harness h, Path configFile) throws Exception {
        h.recorderWindowStart = RECORDER.entries().size();
        h.config = ServiceConfig.load(new String[] {
                "--config", configFile.toString() });
        h.services = BusinessServiceFactory.createGui(h.config);
        h.server = new BusinessHttpServer(
                new InetSocketAddress("127.0.0.1", 0), SERVICE_TOKEN,
                h.services.tasks, MAPPER, null, null, h.services.v2);
        h.server.start();
        h.port = serverPort(h.server);
    }

    /** BusinessHttpServer does not expose its bound address; reflect the wrapped HttpServer. */
    private static int serverPort(BusinessHttpServer server) throws Exception {
        java.lang.reflect.Field field =
                BusinessHttpServer.class.getDeclaredField("server");
        field.setAccessible(true);
        return ((HttpServer) field.get(server)).getAddress().getPort();
    }

    private static final class Harness implements AutoCloseable {
        Path stateDir;
        ServiceConfig config;
        BusinessServiceFactory.GuiServices services;
        BusinessHttpServer server;
        ReceiverStub enterprise;
        ReceiverStub model;
        ReceiverStub trap;
        int port;
        int recorderWindowStart;
        List<String> poisonedRowsBefore = Collections.emptyList();

        /**
         * Stops the service composition WITHOUT touching the loopback
         * receivers — the in-JVM restart seam for MV05/MV20/MV21. The
         * owner lock is an OS file lock held for the process lifetime;
         * a second {@code ServiceConfig.load} on the same state dir is
         * refused until it is released here.
         */
        void shutdownService() {
            if (services != null && services.catalogSynchronizer != null) {
                services.catalogSynchronizer.close();
            }
            if (server != null) {
                server.close();
                server = null;
            }
            // tasks.close() drains runs, persists queued state and
            // closes the control-plane database through the owned
            // journal — the same path BusinessAgentServiceMain takes.
            if (services != null && services.tasks != null) {
                try { services.tasks.close(); } catch (Exception ignored) { }
            }
            if (config != null && config.getOwnerLock() != null) {
                config.getOwnerLock().close();
            }
        }

        @Override public void close() {
            shutdownService();
            if (enterprise != null) enterprise.close();
            if (model != null) model.close();
            if (trap != null) trap.close();
        }
    }

    /** Diagnoses SPEC_STALE: dumps HEAD + dirty porcelain entries of the matest workspace. */
    private static String workspaceDrift(Harness h) {
        try {
            java.util.Properties props = new java.util.Properties();
            java.io.FileInputStream in = new java.io.FileInputStream(
                    h.stateDir.resolve("service.properties").toFile());
            props.load(in);
            in.close();
            Path ws = new java.io.File(
                    props.getProperty("workspace.matest")).toPath();
            ProcessBuilder pb = new ProcessBuilder("git", "-C",
                    ws.toString(), "status", "--porcelain=v1",
                    "--untracked-files=all");
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            java.io.ByteArrayOutputStream buf =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = pr.getInputStream().read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            pr.waitFor();
            ProcessBuilder hb = new ProcessBuilder("git", "-C",
                    ws.toString(), "rev-parse", "HEAD");
            hb.redirectErrorStream(true);
            Process hp = hb.start();
            java.io.ByteArrayOutputStream hbuf =
                    new java.io.ByteArrayOutputStream();
            while ((n = hp.getInputStream().read(chunk)) >= 0) {
                hbuf.write(chunk, 0, n);
            }
            hp.waitFor();
            return "HEAD=" + hbuf.toString("UTF-8").trim()
                    + " dirty=[" + buf.toString("UTF-8").trim() + "]";
        } catch (Exception e) {
            return "<drift probe failed: " + e + ">";
        }
    }

    /** Model-table statements recorded only inside this harness's window. */
    private static List<SqliteStatementRecorder.Entry> modelTableStatements(
            Harness h) {
        List<SqliteStatementRecorder.Entry> all = RECORDER.entries();
        List<SqliteStatementRecorder.Entry> out =
                new ArrayList<SqliteStatementRecorder.Entry>();
        for (int i = Math.min(h.recorderWindowStart, all.size());
                i < all.size(); i++) {
            SqliteStatementRecorder.Entry e = all.get(i);
            if (e.sql != null && SqliteStatementRecorder.MODEL_TABLES
                    .matcher(e.sql.toLowerCase(
                            java.util.Locale.ROOT)).find()) {
                out.add(e);
            }
        }
        return out;
    }

    private JsonNode api(Harness h, String method, String path, String json)
            throws IOException {
        // HttpURLConnection refuses PATCH outright — send it over a raw
        // socket exactly like GuiHttpTest does.
        if ("PATCH".equals(method)) {
            return patchApi(h, path, json);
        }
        URL url = new URL("http://127.0.0.1:" + h.port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization",
                "Bearer " + SERVICE_TOKEN);
        if (json != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Idempotency-Key",
                    "ma00-" + UUID.randomUUID());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = connection.getOutputStream();
            try { out.write(body); } finally { out.close(); }
        }
        int status = connection.getResponseCode();
        InputStream in = status < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        String text = in == null ? "" : new String(readAll(in),
                StandardCharsets.UTF_8);
        return MAPPER.createObjectNode()
                .put("status", status)
                .set("body", text.isEmpty()
                        ? MAPPER.createObjectNode() : MAPPER.readTree(text));
    }

    /**
     * A real PATCH request over a raw socket — the same request surface
     * as {@link #api} but shaped for a method HttpURLConnection cannot
     * express. The business server always answers a fixed-length body,
     * so reading to connection close yields status + JSON.
     */
    private JsonNode patchApi(Harness h, String path, String json)
            throws IOException {
        byte[] body = json == null
                ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        java.net.Socket socket = new java.net.Socket("127.0.0.1", h.port);
        try {
            socket.setSoTimeout(30000);
            String head = "PATCH " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + h.port + "\r\n"
                    + "Authorization: Bearer " + SERVICE_TOKEN + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Idempotency-Key: ma00-" + UUID.randomUUID() + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
            String raw = new String(readAll(socket.getInputStream()),
                    StandardCharsets.UTF_8);
            int firstSpace = raw.indexOf(' ');
            int status = Integer.parseInt(raw.substring(firstSpace + 1,
                    raw.indexOf(' ', firstSpace + 1)));
            int bodyAt = raw.indexOf("\r\n\r\n");
            String text = bodyAt < 0 ? "" : raw.substring(bodyAt + 4);
            return MAPPER.createObjectNode()
                    .put("status", status)
                    .set("body", text.isEmpty()
                            ? MAPPER.createObjectNode()
                            : MAPPER.readTree(text));
        } finally {
            socket.close();
        }
    }

    private Path writeServiceProperties(Path stateDir, Path workspace,
                                        Path catalogFile,
                                        ReceiverStub enterprise,
                                        boolean enterpriseSource)
            throws IOException {
        Path file = stateDir.resolve("service.properties");
        StringBuilder out = new StringBuilder();
        out.append("server.host=127.0.0.1\n");
        out.append("server.port=").append(freePort()).append('\n');
        out.append("auth.token=").append(SERVICE_TOKEN).append('\n');
        out.append("state.dir=").append(stateDir.toString()
                .replace("\\", "\\\\")).append('\n');
        out.append("workspace.matest=").append(workspace.toString()
                .replace("\\", "\\\\")).append('\n');
        out.append("logging.dir=").append(stateDir.resolve("logs")
                .toString().replace("\\", "\\\\")).append('\n');
        out.append("agent.profiles.directory=").append(stateDir
                .resolve("agents").toString().replace("\\", "\\\\")).append('\n');
        if (catalogFile != null) {
            out.append("model.config=").append(catalogFile.toString()
                    .replace("\\", "\\\\")).append('\n');
        }
        if (enterpriseSource) {
            out.append("model.catalog.source=ENTERPRISE_HTTP\n");
            out.append("model.catalog.enterprise.endpoint=http://127.0.0.1:")
                    .append(enterprise.port()).append('\n');
            out.append("model.catalog.enterprise.profile-id=")
                    .append(PROFILE_ID).append('\n');
            out.append("model.catalog.enterprise.token-env=")
                    .append("MA00_ABSENT_TOKEN_ENV").append('\n');
            // MA04: the MA01-03 cases exercise the manual refresh seam —
            // the poll loop stays OFF so a scheduled tick can never race
            // their exact hit/receipt assertions. MA04 cases opt in via
            // bootEnterpriseService(..., extraEnterpriseConfig).
            out.append("model.catalog.enterprise.sync.enabled=false\n");
        }
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * MA04 variant: same enterprise block plus caller-supplied
     * {@code model.catalog.enterprise.*} lines appended after the
     * default-off sync flag (later lines win for a duplicate key).
     */
    private Path writeServiceProperties(Path stateDir, Path workspace,
                                        Path catalogFile,
                                        ReceiverStub enterprise,
                                        String extraEnterpriseConfig)
            throws IOException {
        Path file = stateDir.resolve("service.properties");
        StringBuilder out = new StringBuilder();
        out.append("server.host=127.0.0.1\n");
        out.append("server.port=").append(freePort()).append('\n');
        out.append("auth.token=").append(SERVICE_TOKEN).append('\n');
        out.append("state.dir=").append(stateDir.toString()
                .replace("\\", "\\\\")).append('\n');
        out.append("workspace.matest=").append(workspace.toString()
                .replace("\\", "\\\\")).append('\n');
        out.append("logging.dir=").append(stateDir.resolve("logs")
                .toString().replace("\\", "\\\\")).append('\n');
        out.append("agent.profiles.directory=").append(stateDir
                .resolve("agents").toString().replace("\\", "\\\\")).append('\n');
        if (catalogFile != null) {
            out.append("model.config=").append(catalogFile.toString()
                    .replace("\\", "\\\\")).append('\n');
        }
        out.append("model.catalog.source=ENTERPRISE_HTTP\n");
        out.append("model.catalog.enterprise.endpoint=http://127.0.0.1:")
                .append(enterprise.port()).append('\n');
        out.append("model.catalog.enterprise.profile-id=")
                .append(PROFILE_ID).append('\n');
        out.append("model.catalog.enterprise.token-env=")
                .append("MA00_ABSENT_TOKEN_ENV").append('\n');
        if (extraEnterpriseConfig != null) {
            out.append(extraEnterpriseConfig);
        }
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String catalogJson(int modelPort) {
        return catalogJson(modelPort, RELEASE_ID_A, 41, '0', 'a',
                WIRE_MODEL_A, "v1", true);
    }

    /**
     * Parameterized catalog body — the same projection shape
     * {@link #catalogJson(int)} emits, with release identity, digests,
     * wire-model id, credential version and the decoy model selectable
     * so MA04 cases can express publish-B / same-release-drift
     * scenarios without rewriting the schema.
     */
    private static String catalogJson(int modelPort, String releaseId,
            long generation, char scopeChar, char fingerprintChar,
            String wireModel, String credentialVersion,
            boolean includeDecoy) {
        StringBuilder out = new StringBuilder();
        out.append("{")
                .append("\"schemaVersion\":1,")
                .append("\"authorityId\":\"").append(AUTHORITY_ID)
                .append("\",")
                .append("\"releaseId\":\"").append(releaseId)
                .append("\",")
                .append("\"generation\":").append(generation).append(',')
                .append("\"profileId\":\"").append(PROFILE_ID)
                .append("\",")
                .append("\"profileRevisionNo\":4,")
                .append("\"scopeDigest\":\"sha256:")
                .append(repeat(scopeChar, 64)).append("\",")
                .append("\"projectionFingerprint\":\"sha256:")
                .append(repeat(fingerprintChar, 64)).append("\",")
                .append("\"defaultSelection\":{\"providerId\":\"")
                .append(PROVIDER_ID)
                .append("\",\"modelKey\":\"").append(STABLE_MODEL_KEY)
                .append("\",\"remoteModelId\":\"").append(wireModel)
                .append("\"},")
                .append("\"providers\":[{")
                .append("\"providerId\":\"").append(PROVIDER_ID)
                .append("\",")
                .append("\"dialect\":\"OPENAI_CHAT_COMPLETIONS\",")
                .append("\"baseUrl\":\"http://127.0.0.1:")
                .append(modelPort).append("/v1\",")
                .append("\"apiVersion\":null,")
                .append("\"betaHeaders\":[],")
                .append("\"profileRevision\":\"svc-rev:")
                .append(generation).append("\",")
                .append("\"dataPolicy\":{\"dataClassification\":null,")
                .append("\"networkZone\":\"corp\"},")
                .append("\"credential\":{\"kind\":\"INLINE_DEV\",")
                .append("\"value\":\"ma00-dev-only-material\",")
                .append("\"version\":\"").append(credentialVersion)
                .append("\"},")
                .append("\"models\":[{")
                .append("\"modelKey\":\"").append(STABLE_MODEL_KEY)
                .append("\",")
                .append("\"remoteModelId\":\"").append(wireModel)
                .append("\",")
                .append("\"revision\":\"mrev:mcfg-1@")
                .append(generation).append("\",")
                .append("\"capabilities\":[\"NATIVE_TOOL_CALLING\"],")
                .append("\"contextWindowTokens\":200000,")
                .append("\"maxOutputTokens\":8192,")
                .append("\"tiers\":{\"cost\":\"ECONOMY\"}")
                .append('}');
        if (includeDecoy) {
            out.append(",{\"modelKey\":\"decoy-secondary\",")
                    .append("\"remoteModelId\":\"").append(TRAP_WIRE_MODEL)
                    .append("\",")
                    .append("\"revision\":\"mrev:mcfg-2@")
                    .append(generation).append("\",")
                    .append("\"capabilities\":[\"NATIVE_TOOL_CALLING\"],")
                    .append("\"contextWindowTokens\":200000,")
                    .append("\"maxOutputTokens\":8192,")
                    .append("\"tiers\":{\"cost\":\"ECONOMY\"}")
                    .append('}');
        }
        out.append("]}],")
                .append("\"runtimeOptions\":{\"defaultPurpose\":null,")
                .append("\"routePolicies\":[]},")
                .append("\"readiness\":\"READY\",")
                .append("\"diagnostics\":[],")
                .append("\"issuedAt\":\"2026-10-13T00:00:00Z\",")
                .append("\"validUntil\":null,")
                .append("\"credentialVersion\":null")
                .append('}');
        return out.toString();
    }

    /**
     * Two-provider catalog in one release (MV09/MV16): provider A
     * ({@link #PROVIDER_ID}) owns {@link #STABLE_MODEL_KEY} on
     * {@code wireModelA} at {@code portA}; provider B
     * ({@link #PROVIDER_ID_B}) declares the SAME stable key on
     * {@code wireModelB} at {@code portB} — a bare
     * {@code code-primary} alias is genuinely ambiguous while
     * {@code providerId:code-primary} pins resolve distinctly.
     * defaultSelection stays pinned to A so unpinned sends keep working.
     */
    private static String catalogJsonTwoProviders(int portA, int portB,
                                                  String releaseId) {
        StringBuilder out = new StringBuilder();
        out.append("{")
                .append("\"schemaVersion\":1,")
                .append("\"authorityId\":\"").append(AUTHORITY_ID)
                .append("\",")
                .append("\"releaseId\":\"").append(releaseId)
                .append("\",")
                .append("\"generation\":41,")
                .append("\"profileId\":\"").append(PROFILE_ID)
                .append("\",")
                .append("\"profileRevisionNo\":4,")
                .append("\"scopeDigest\":\"sha256:")
                .append(repeat('3', 64)).append("\",")
                .append("\"projectionFingerprint\":\"sha256:")
                .append(repeat('e', 64)).append("\",")
                .append("\"defaultSelection\":{\"providerId\":\"")
                .append(PROVIDER_ID)
                .append("\",\"modelKey\":\"").append(STABLE_MODEL_KEY)
                .append("\",\"remoteModelId\":\"").append(WIRE_MODEL_A)
                .append("\"},")
                .append("\"providers\":[")
                .append(providerJson(PROVIDER_ID, portA, WIRE_MODEL_A,
                        "v1", 41))
                .append(',')
                .append(providerJson(PROVIDER_ID_B, portB, "wire-model-b",
                        "v1-b", 41))
                .append("],")
                .append("\"runtimeOptions\":{\"defaultPurpose\":null,")
                .append("\"routePolicies\":[]},")
                .append("\"readiness\":\"READY\",")
                .append("\"diagnostics\":[],")
                .append("\"issuedAt\":\"2026-10-13T00:00:00Z\",")
                .append("\"validUntil\":null,")
                .append("\"credentialVersion\":null")
                .append('}');
        return out.toString();
    }

    /**
     * Wire model id BOTH mv16 providers declare — a bare
     * {@code shared-wire-x} alias is genuinely ambiguous across owners.
     */
    private static final String SHARED_WIRE = "shared-wire-x";

    /**
     * One provider block in the {@link #catalogJson} projection shape.
     * Besides the provider-private {@code wireModel} under
     * {@link #STABLE_MODEL_KEY}, every provider also declares the shared
     * {@link #SHARED_WIRE} remote id under its own modelKey so a bare
     * shared alias has two distinct owners.
     */
    private static String providerJson(String providerId, int port,
            String wireModel, String credentialVersion, long generation) {
        return "{\"providerId\":\"" + providerId + "\","
                + "\"dialect\":\"OPENAI_CHAT_COMPLETIONS\","
                + "\"baseUrl\":\"http://127.0.0.1:" + port + "/v1\","
                + "\"apiVersion\":null,"
                + "\"betaHeaders\":[],"
                + "\"profileRevision\":\"svc-rev:" + generation + "\","
                + "\"dataPolicy\":{\"dataClassification\":null,"
                + "\"networkZone\":\"corp\"},"
                + "\"credential\":{\"kind\":\"INLINE_DEV\","
                + "\"value\":\"ma00-dev-only-material\","
                + "\"version\":\"" + credentialVersion + "\"},"
                + "\"models\":[{"
                + "\"modelKey\":\"" + STABLE_MODEL_KEY + "\","
                + "\"remoteModelId\":\"" + wireModel + "\","
                + "\"revision\":\"mrev:mcfg-1@" + generation + "\","
                + "\"capabilities\":[\"NATIVE_TOOL_CALLING\"],"
                + "\"contextWindowTokens\":200000,"
                + "\"maxOutputTokens\":8192,"
                + "\"tiers\":{\"cost\":\"ECONOMY\"}"
                + "},{"
                + "\"modelKey\":\"shared-"
                + providerId.substring(providerId.length() - 1) + "\","
                + "\"remoteModelId\":\"" + SHARED_WIRE + "\","
                + "\"revision\":\"mrev:mcfg-2@" + generation + "\","
                + "\"capabilities\":[\"NATIVE_TOOL_CALLING\"],"
                + "\"contextWindowTokens\":200000,"
                + "\"maxOutputTokens\":8192,"
                + "\"tiers\":{\"cost\":\"ECONOMY\"}"
                + "}]}";
    }

    /**
     * The LOCAL personal catalog file (MV05): one provider pointing at
     * the loopback model receiver with an inline dev credential — the
     * import migrates it to a SECRET slot the same way a real operator
     * file does. {@code MODEL_DEFAULT_MODEL} seeds the default.
     */
    private static String localCatalogProperties(int modelPort,
                                                 String providerId,
                                                 String wireModel) {
        return "PROVIDER_1_ID=" + providerId + "\n"
                + "PROVIDER_1_DIALECT=OPENAI_CHAT_COMPLETIONS\n"
                + "PROVIDER_1_BASE_URL=http://127.0.0.1:" + modelPort
                + "/v1\n"
                + "PROVIDER_1_API_KEY=ma00-dev-local-material\n"
                + "PROVIDER_1_MODEL_1_ID=" + wireModel + "\n"
                + "PROVIDER_1_MODEL_1_CAPABILITIES=NATIVE_TOOL_CALLING\n"
                + "MODEL_DEFAULT_MODEL=" + wireModel + "\n";
    }

    // ---- poisoned personal database ----------------------------------

    /**
     * The poisoned state is a REAL post-migration personal store: the
     * scenario is a host that ran the personal file+DB pipeline and was
     * later repointed at {@code ENTERPRISE_HTTP}. Opening the production
     * {@link com.sage.business.code.infrastructure.controlplane
     * .ControlPlaneDatabase} once runs V001–V030 and builds the exact
     * schema a real host carries — a hand-written DDL subset drifted from
     * post-V030 reality and made the store a fixture no host could have.
     * The migration DDL lands in the recorder ahead of the harness window
     * (window starts after this call returns), so it is never asserted.
     */
    private static void writePoisonedDatabase(Path stateDir, int trapPort)
            throws Exception {
        com.sage.business.code.infrastructure.controlplane
                .ControlPlaneDatabase database =
                new com.sage.business.code.infrastructure.controlplane
                        .ControlPlaneDatabase(stateDir);
        database.close();
        Path db = stateDir.resolve("gui-sessions.db");
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toString());
        try {
            long now = System.currentTimeMillis();
            // The same provider id, a DIFFERENT (trap) base_url and a
            // poisoned stable-key→wire mapping. If any enterprise path
            // consults these rows the request lands on the trap receiver
            // or on TRAP_WIRE_MODEL.
            PreparedStatement provider = connection.prepareStatement(
                    "INSERT INTO model_provider(provider_id,dialect,base_url,"
                            + "credential_kind,credential_ref,display_name,"
                            + "enabled,source,revision,created_at,updated_at,"
                            + "api_version,beta_headers) "
                            + "VALUES(?,?,?,?,?,?,1,'DATABASE',1,?,?,?,?)");
            try {
                provider.setString(1, PROVIDER_ID);
                provider.setString(2, "OPENAI_CHAT_COMPLETIONS");
                provider.setString(3, "http://127.0.0.1:" + trapPort + "/v1");
                provider.setString(4, "ENV");
                provider.setString(5, "MA00_PAID_TRAP_ENV");
                provider.setString(6, "poisoned personal row");
                provider.setLong(7, now);
                provider.setLong(8, now);
                provider.setNull(9, java.sql.Types.VARCHAR);
                provider.setNull(10, java.sql.Types.VARCHAR);
                provider.executeUpdate();
            } finally { provider.close(); }
            PreparedStatement model = connection.prepareStatement(
                    "INSERT INTO model_provider_model(provider_id,model_id,"
                            + "enabled,revision,created_at,updated_at,"
                            + "remote_model_id) VALUES(?,?,1,1,?,?,?)");
            try {
                model.setString(1, PROVIDER_ID);
                model.setString(2, STABLE_MODEL_KEY);
                model.setLong(3, now);
                model.setLong(4, now);
                model.setString(5, TRAP_WIRE_MODEL);
                model.executeUpdate();
            } finally { model.close(); }
        } finally {
            connection.close();
        }
    }

    /**
     * Plants the durable row shape a legacy personal-era session carried:
     * {@code model_alias} holds the {@code providerId:modelKey} stable
     * composite verbatim (that is what applyModelSettings persisted while
     * the DB-managed catalog owned the namespace). Written through plain
     * JDBC — outside the recorder seam — because it is fixture state, not
     * service behavior: the host that stored this pin ran before the
     * ENTERPRISE_HTTP reconfiguration under test.
     */
    private static void seedStaleStableKeyPin(Path stateDir, String sessionId)
            throws Exception {
        Path db = stateDir.resolve("gui-sessions.db");
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toString());
        try {
            PreparedStatement pin = connection.prepareStatement(
                    "UPDATE sessions SET model_alias=?,model_settings_json=? "
                            + "WHERE id=?");
            try {
                pin.setString(1, PROVIDER_ID + ":" + STABLE_MODEL_KEY);
                pin.setString(2, "{\"modelAlias\":\"" + PROVIDER_ID + ":"
                        + STABLE_MODEL_KEY + "\"}");
                pin.setString(3, sessionId);
                if (pin.executeUpdate() != 1) {
                    throw new IllegalStateException(
                            "session row not found for stale pin seed: "
                                    + sessionId);
                }
            } finally { pin.close(); }
        } finally {
            connection.close();
        }
    }

    private static List<String> readPoisonedRows(Path stateDir)
            throws Exception {
        Path db = stateDir.resolve("gui-sessions.db");
        if (!Files.isRegularFile(db)) return Collections.emptyList();
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toString());
        List<String> rows = new ArrayList<String>();
        try {
            Statement statement = connection.createStatement();
            ResultSet rs = statement.executeQuery(
                    "SELECT provider_id,dialect,base_url,credential_kind,"
                            + "credential_ref,enabled,source,revision "
                            + "FROM model_provider ORDER BY provider_id");
            while (rs.next()) {
                rows.add("p|" + rs.getString(1) + "|" + rs.getString(2)
                        + "|" + rs.getString(3) + "|" + rs.getString(4)
                        + "|" + rs.getString(5) + "|" + rs.getInt(6)
                        + "|" + rs.getString(7) + "|" + rs.getInt(8));
            }
            rs.close();
            rs = statement.executeQuery(
                    "SELECT provider_id,model_id,remote_model_id,enabled,"
                            + "revision FROM model_provider_model "
                            + "ORDER BY provider_id,model_id");
            while (rs.next()) {
                rows.add("m|" + rs.getString(1) + "|" + rs.getString(2)
                        + "|" + rs.getString(3) + "|" + rs.getInt(4)
                        + "|" + rs.getInt(5));
            }
            rs.close();
            statement.close();
        } finally {
            connection.close();
        }
        return rows;
    }

    // ---- loopback receivers -------------------------------------------

    private static final class ReceiverStub implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> bodies = new CopyOnWriteArrayList<JsonNode>();
        final List<String> paths = new CopyOnWriteArrayList<String>();
        final List<Boolean> authSeen = new CopyOnWriteArrayList<Boolean>();
        /**
         * Remaining 500 responses the applications endpoint owes -
         * MV15's report-endpoint fault. Read-and-decremented per POST
         * so a single failure does not cascade into a second refresh.
         */
        final java.util.concurrent.atomic.AtomicInteger applicationFailures =
                new java.util.concurrent.atomic.AtomicInteger();
        /**
         * MA04 MV11: while {@code true} the catalog GET answers 503 —
         * a transient authority outage the synchronizer must surface as
         * a typed admission failure, not a fallback.
         */
        volatile boolean catalogUnavailable;
        /**
         * MA04: this stub only understands {@code catalogSchemaVersion=2}
         * when {@code true}; otherwise a parameterized request answers
         * the producer's typed 400 so the probe falls back to a v1 read.
         */
        volatile boolean v2Supported;
        /** v2 {@code consumer.consumerBindingIds} the head envelope reports. */
        volatile List<String> v2ConsumerBindingIds = Collections.emptyList();
        /** v2 {@code consumer.consumerBindingId} (first id when unset). */
        volatile String v2ConsumerBindingId;
        /** v2 {@code consumer.validUntil} ISO instant, or null. */
        volatile String v2ConsumerValidUntil;
        private volatile String catalogBody;
        /**
         * Release-addressed bodies for
         * {@code GET /api/v1/model-runtime/catalog/releases/{id}} — the
         * MA02 pinned-resume read (MV08). Keyed by release id; a miss
         * answers 404 exactly like a withdrawn release.
         */
        private final java.util.Map<String, String> releaseBodies =
                new java.util.concurrent.ConcurrentHashMap<String, String>();

        ReceiverStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            // Each exchange needs its own thread: MV09/MV20 gate a model
            // call mid-flight and must still record (and gate) a second
            // in-flight request — the default dispatcher thread would
            // serialize handlers and drop the concurrency the tests are
            // built on.
            server.setExecutor(java.util.concurrent.Executors
                    .newCachedThreadPool());
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        /** Enterprise plane: catalog GET + apply-report POST. */
        void catalog(String body) {
            this.catalogBody = body;
            server.createContext("/api/v1/model-runtime/catalog",
                    new com.sun.net.httpserver.HttpHandler() {
                        @Override public void handle(HttpExchange x)
                                throws IOException {
                            record(x);
                            if ("POST".equals(x.getRequestMethod())) {
                                if (applicationFailures.get() > 0
                                        && applicationFailures
                                                .decrementAndGet() >= 0) {
                                    respond(x, 500, "{}");
                                    return;
                                }
                                respond(x, 200, "{}");
                                return;
                            }
                            if (catalogUnavailable) {
                                respond(x, 503,
                                        "{\"error\":\"catalog endpoint "
                                                + "unavailable\"}");
                                return;
                            }
                            String requestPath =
                                    x.getRequestURI().getPath();
                            int marker = requestPath.indexOf("/releases/");
                            if (marker >= 0) {
                                String id = requestPath.substring(
                                        marker + "/releases/".length());
                                String body = releaseBodies.get(id);
                                if (body == null) {
                                    respond(x, 404,
                                            "{\"error\":\"release "
                                                    + id
                                                    + " is unavailable\"}");
                                    return;
                                }
                                respond(x, 200, body);
                                return;
                            }
                            String query = x.getRequestURI().getRawQuery();
                            boolean wantsV2 = query != null
                                    && query.contains(
                                            "catalogSchemaVersion=2");
                            if (wantsV2 && !v2Supported) {
                                // The producer's typed refusal — the
                                // probe retries unparameterized and
                                // records negotiated schemaVersion=1.
                                respond(x, 400,
                                        "{\"error\":{\"code\":"
                                                + "\"MODEL_CATALOG_SCHEMA_"
                                                + "UNSUPPORTED\",\"message\":"
                                                + "\"catalogSchemaVersion 2 "
                                                + "is not supported\"}}");
                                return;
                            }
                            if (wantsV2) {
                                respond(x, 200,
                                        v2Envelope(catalogBody));
                                return;
                            }
                            respond(x, 200, catalogBody);
                        }
                    });
        }

        /** Swaps the projection the next catalog GET serves (MV10). */
        void swapCatalog(String body) {
            this.catalogBody = body;
        }

        /**
         * Registers the body a pinned
         * {@code GET .../catalog/releases/{releaseId}} serves (MV08) —
         * the enterprise plane's release-addressed read the persisted
         * {@code ModelBinding} resume path re-fetches through.
         */
        void release(String releaseId, String body) {
            releaseBodies.put(releaseId, body);
        }

        /**
         * MA04 MV14: withdraws a pinned release — the next
         * {@code GET .../releases/{id}} answers 404 exactly like a
         * revoked projection at the authority.
         */
        void withdraw(String releaseId) {
            releaseBodies.remove(releaseId);
        }

        /**
         * The v2 head envelope for the current projection: the same
         * identity/scope/fingerprint bytes with {@code schemaVersion=2}
         * and the {@code consumer} authorization block the v1 schema
         * cannot express (design §4.1 — the producer stamps exactly these
         * fields when {@code catalogSchemaVersion=2} is negotiated).
         */
        private String v2Envelope(String base) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode root =
                        (com.fasterxml.jackson.databind.node.ObjectNode)
                                MAPPER.readTree(base);
                root.put("schemaVersion", 2);
                com.fasterxml.jackson.databind.node.ObjectNode consumer =
                        root.putObject("consumer");
                List<String> ids = v2ConsumerBindingIds;
                com.fasterxml.jackson.databind.node.ArrayNode array =
                        consumer.putArray("consumerBindingIds");
                for (String id : ids) {
                    array.add(id);
                }
                String primary = v2ConsumerBindingId != null
                        ? v2ConsumerBindingId
                        : (ids.isEmpty() ? null : ids.get(0));
                if (primary != null) {
                    consumer.put("consumerBindingId", primary);
                }
                if (v2ConsumerValidUntil != null) {
                    consumer.put("validUntil", v2ConsumerValidUntil);
                }
                return MAPPER.writeValueAsString(root);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "v2 envelope transform failed", failure);
            }
        }

        /**
         * MV09/MV20 in-flight seam: while {@code gating} is set each
         * chat-completion request records normally but blocks on {@link
         * #gate} before answering, so the caller holds the model call
         * mid-flight exactly like a slow upstream. {@link #openGate()}
         * unblocks every queued request permanently.
         */
        volatile boolean gating;
        /**
         * MV18 plan leg: the goal the emit_code_plan stub echoes back.
         * Plan validation refuses a synthesized plan whose goal differs
         * from the request goal, so the emitting test sets this to the
         * spec goal it POSTs. {@code null} keeps the generic default.
         */
        volatile String planGoal;
        final java.util.concurrent.Semaphore gate =
                new java.util.concurrent.Semaphore(0);

        void openGate() {
            gating = false;
            gate.release(64);
        }

        /** Model plane: canned OpenAI chat completion echoing the model id. */
        void replyChatCompletions() {
            server.createContext("/v1/chat/completions",
                    new com.sun.net.httpserver.HttpHandler() {
                        @Override public void handle(HttpExchange x)
                                throws IOException {
                            record(x);
                            JsonNode request = lastBody();
                            String model = request != null
                                    && request.get("model") != null
                                    ? request.get("model").asText() : "unknown";
                            if (gating) {
                                try {
                                    gate.acquire();
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    respond(x, 500, "{}");
                                    return;
                                }
                            }
                            // MV18 plan leg: a request that carries the
                            // emit_code_plan(_v2) forced tool gets the
                            // real tool_calls reply the PlanSynthesizer
                            // contract consumes — a canned text answer
                            // would parse-fail and hide the dispatch.
                            String raw = request == null
                                    ? "" : request.toString();
                            if (raw.contains("emit_code_plan")) {
                                String tool = raw.contains(
                                        "emit_code_plan_v2")
                                        ? "emit_code_plan_v2"
                                        : "emit_code_plan";
                                respond(x, 200, planToolResponse(model,
                                        tool,
                                        planGoal == null
                                                ? "ma00 plan goal"
                                                : planGoal));
                                return;
                            }
                            respond(x, 200,
                                    "{\"id\":\"ma00-resp\",\"model\":\""
                                            + model + "\","
                                            + "\"choices\":[{\"index\":0,"
                                            + "\"message\":{\"role\":\"assistant\","
                                            + "\"content\":\"MA00 canned reply\"},"
                                            + "\"finish_reason\":\"stop\"}],"
                                            + "\"usage\":{\"prompt_tokens\":1,"
                                            + "\"completion_tokens\":1,"
                                            + "\"total_tokens\":2}}");
                        }
                    });
        }

        /**
         * The minimal valid {@code emit_code_plan} arguments the plan
         * contract accepts (goal/summary/steps with id+title+description),
         * delivered as a real function-call payload.
         */
        private static String planToolResponse(String model, String tool,
                String goal) {
            String arguments = "{\"goal\":\"" + goal + "\","
                    + "\"summary\":\"ma00 plan summary\","
                    + "\"steps\":[{\"id\":\"s1\",\"title\":\"step one\","
                    + "\"description\":\"do the requested change\"}]}";
            StringBuilder escaped = new StringBuilder();
            for (int i = 0; i < arguments.length(); i++) {
                char c = arguments.charAt(i);
                if (c == '\\' || c == '"') escaped.append('\\');
                escaped.append(c);
            }
            return "{\"id\":\"ma00-plan\",\"model\":\"" + model + "\","
                    + "\"choices\":[{\"index\":0,\"message\":{"
                    + "\"role\":\"assistant\",\"content\":null,"
                    + "\"tool_calls\":[{\"id\":\"call_ma00_plan\","
                    + "\"type\":\"function\",\"function\":{\"name\":\""
                    + tool + "\",\"arguments\":\"" + escaped + "\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,"
                    + "\"completion_tokens\":1,\"total_tokens\":2}}";
        }

        private void record(HttpExchange x) {
            paths.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
            authSeen.add(Boolean.valueOf(
                    x.getRequestHeaders().getFirst("Authorization") != null));
            try {
                bodies.add(x.getRequestBody() == null
                        ? null : MAPPER.readTree(
                                new String(readAll(x.getRequestBody()),
                                        StandardCharsets.UTF_8)));
            } catch (Exception bad) {
                bodies.add(null);
            }
        }

        private JsonNode lastBody() {
            return bodies.isEmpty() ? null : bodies.get(bodies.size() - 1);
        }

        int hits(String pathPrefix) {
            if (pathPrefix == null) return paths.size();
            int count = 0;
            for (String p : paths) {
                if (p.contains(pathPrefix)) count++;
            }
            return count;
        }

        String lastModelId() {
            for (int i = bodies.size() - 1; i >= 0; i--) {
                JsonNode body = bodies.get(i);
                if (body != null && body.get("model") != null) {
                    return body.get("model").asText();
                }
            }
            return null;
        }

        private void respond(HttpExchange x, int status, String json)
                throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type",
                    "application/json; charset=utf-8");
            x.sendResponseHeaders(status, bytes.length);
            OutputStream out = x.getResponseBody();
            out.write(bytes);
            out.close();
        }

        @Override public void close() {
            server.stop(0);
        }
    }

    private static int freePort() throws IOException {
        java.net.ServerSocket probe = new java.net.ServerSocket(0,
                50, java.net.InetAddress.getByName("127.0.0.1"));
        try {
            return probe.getLocalPort();
        } finally {
            probe.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String repeat(char c, int n) {
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) out.append(c);
        return out.toString();
    }
}
