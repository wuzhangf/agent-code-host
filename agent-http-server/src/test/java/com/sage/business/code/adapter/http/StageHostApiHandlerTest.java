package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.stagehost.HostAggregate;
import com.sage.business.code.application.stagehost.StageHostCas;
import com.sage.business.code.application.stagehost.StageHostService;
import com.sage.business.code.application.stagehost.StageHostStore;
import com.sage.business.code.application.stagehost.StageProductLeaf;
import com.sage.business.code.application.stagehost.StageTrust;
import com.sage.business.code.contract.v2.stagehost.HostEffectRequest;
import com.sage.business.code.contract.v2.stagehost.HostSealRequest;
import com.sage.business.code.contract.v2.stagehost.HostStageContract;
import com.sage.business.code.contract.v2.stagehost.HostStartRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 05 §5.1/§5.3 HTTP wiring for the worker-facing stage-host API:
 * bearer enforcement, Idempotency-Key on every mutation, typed problem
 * codes, and an honest capability gap when a product leaf is absent.
 */
public class StageHostApiHandlerTest {

    private static final String TOKEN = "stage-host-contract-bearer";
    private static final String IMPL = "/api/v2/implementation-operations";
    private static final String DESIGN = "/api/v2/design-stage-operations";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private String base;
    private StageHostService service;

    /** Minimal IMPLEMENT leaf — binds one product id, honest version. */
    private static final class FakeLeaf implements StageProductLeaf {
        private final StageHostCas cas;
        FakeLeaf(StageHostCas cas) { this.cas = cas; }
        public String stageKind() { return "IMPLEMENT"; }

        public LeafCapabilities capabilities() {
            LeafCapabilities c = new LeafCapabilities();
            c.inputs = c.decisions = c.controls = c.drive = true;
            c.events = c.quiescence = c.seal = c.currentness = true;
            return c;
        }

        public LeafStartResult start(HostAggregate b,
                HostStartRequest r) {
            LeafStartResult s = new LeafStartResult();
            s.productOperationId = "intake-api-1";
            s.bindings.put("primary", "intake-api-1");
            s.versions.put("primary", "1");
            return s;
        }

        public LeafObservation observe(HostAggregate b) {
            LeafObservation o = new LeafObservation();
            o.queryState = HostStageContract.QueryState.OK;
            o.productPhase = "RUNNING";
            o.productState = "RUNNING";
            o.productVersion = "1";
            o.bindings.put("primary", "intake-api-1");
            return o;
        }

        public LeafEffectResult input(HostAggregate b,
                HostEffectRequest r, byte[] body) {
            LeafEffectResult res = new LeafEffectResult();
            res.outcome = HostStageContract.EffectOutcome.APPLIED;
            res.receiptRef = "product:receipt:api";
            res.appliedProductVersion = "2";
            return res;
        }

        public LeafEffectResult decide(HostAggregate b,
                HostEffectRequest r, byte[] body) {
            return input(b, r, body);
        }

        public LeafEffectResult control(HostAggregate b,
                HostEffectRequest r, byte[] body) {
            return input(b, r, body);
        }

        public List<LeafWorkItem> workItems(HostAggregate b) {
            return Collections.emptyList();
        }

        public List<LeafEvent> pollEvents(HostAggregate b, String cursor,
                int limit) {
            return Collections.emptyList();
        }

        public List<LeafWriter> writers(HostAggregate b) {
            LeafWriter w = new LeafWriter();
            w.writerId = "api-driver";
            w.kind = "PRODUCT_DRIVER";
            w.state = "STOPPED";
            w.observedAt = "2026-01-01T00:00:00Z";
            return Collections.singletonList(w);
        }

        public LeafSeal seal(HostAggregate b, HostSealRequest r) {
            LeafSeal s = new LeafSeal();
            s.state = "SEALED";
            s.semanticOutcome = "API_SEALED";
            s.productRef = "intake-api-1";
            s.productVersion = "2";
            s.artifacts = Collections.emptyList();
            return s;
        }

        public LeafCurrentness currentness(HostAggregate b, String purpose,
                String objectRef, String objectVersion, String objectDigest,
                String observationRequestId) {
            LeafCurrentness c = new LeafCurrentness();
            c.current = HostStageContract.Currentness.TRUE;
            c.queryState = HostStageContract.QueryState.OK;
            c.checkedAt = "2026-01-01T00:00:00Z";
            return c;
        }

        public LeafStepResult step(HostAggregate b, String stepKey,
                HostDriveBudget budget) {
            LeafStepResult r = new LeafStepResult();
            r.state = "RESOLVED";
            if (!b.getProductBindings().containsKey("primary")) {
                r.bindings.put("primary", "intake-api-1");
                r.appliedProductVersion = "1";
                HostStartRequest original = com.sage.business.code.application.stagehost.StageStartBodies.read(cas, b);
                if (original.getReleaseInputRef() != null) {
                    r.bindings.put("readInputSha", StageHostCas.sha256Hex(cas.read(original.getReleaseInputRef())));
                    r.bindings.put("readProfileSha", StageHostCas.sha256Hex(cas.read(original.getDeploymentProfileRef())));
                }
            }
            return r;
        }

        public void reconcile(HostAggregate b) { }
    }

    @Before public void setUp() throws Exception {
        Path root = tmp.newFolder("stagehost").toPath();
        StageHostStore store = new StageHostStore(root);
        StageHostCas cas = new StageHostCas(root);
        Map<String, StageProductLeaf> leaves =
                new LinkedHashMap<String, StageProductLeaf>();
        leaves.put("IMPLEMENT", new FakeLeaf(cas));
        // NOTE: no DESIGN leaf registered — DESIGN requests exercise the
        // honest UNSUPPORTED_CAPABILITY surface.
        service = new StageHostService(store, cas,
                leaves, new StageTrust.DevPermit(), "api-test-1");
        List<V2RouteGroup> groups = Arrays.<V2RouteGroup>asList(
                new StageHostApiHandler(mapper, TOKEN, service));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper, groups));
        httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After public void tearDown() {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
    }

    // ------------------------------------------------------------ http

    private JsonNode call(String method, String path, String token,
            String idemKey, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL(base + path).openConnection();
        c.setRequestMethod(method);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (idemKey != null) {
            c.setRequestProperty("Idempotency-Key", idemKey);
        }
        if (body != null) {
            c.setDoOutput(true);
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
        }
        int status = c.getResponseCode();
        InputStream in = status < 400
                ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) buf.write(chunk, 0, n);
            in.close();
        }
        c.disconnect();
        String text = buf.toString("UTF-8");
        JsonNode node = text.isEmpty()
                ? mapper.createObjectNode() : mapper.readTree(text);
        return ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                .put("__status", status);
    }

    private static String startBody(String requestId, String stageKind) {
        return "{"
                + "\"schemaVersion\":\"" + HostStageContract.SCHEMA_VERSION + "\","
                + "\"adapterRevision\":\"" + HostStageContract.ADAPTER_REVISION + "\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"effectId\":\"eff-" + requestId + "\","
                + "\"requestDigest\":\"sha256:req-" + requestId + "\","
                + "\"effectContentDigest\":\"sha256:eff-" + requestId + "\","
                + "\"permitRef\":\"permit-1\","
                + "\"identity\":{"
                +   "\"organizationId\":\"org-1\","
                +   "\"projectId\":\"proj-1\","
                +   "\"stageBindingId\":\"sb-1\","
                +   "\"stageEpoch\":\"1\","
                +   "\"stageKind\":\"" + stageKind + "\","
                +   "\"stageSeq\":\"1\","
                +   "\"remoteOperationKey\":\"remote-" + requestId + "\""
                + "}}";
    }

    // ------------------------------------------------------------ tests
    private JsonNode inputAsset(byte[] bytes,String digest,String origin,String token)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/api/v2/release-operations/input-assets").openConnection();
        c.setRequestMethod("POST");if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
        if(digest!=null)c.setRequestProperty("If-Content-SHA256",digest);
        if(origin!=null)c.setRequestProperty("X-Origin-Artifact-Ref",origin);
        c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        int status=c.getResponseCode();try(InputStream in=status<400?c.getInputStream():c.getErrorStream()){
            return ((com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(in)).put("__status",status);
        }finally{c.disconnect();}
    }
    @Test public void inputAssetsReachStoredStartReaderWithoutRewritingBytes()throws Exception {
        // The wrapper's business digest deliberately differs from its raw CAS digest.
        byte[] bytes=" {\n\"profileId\":\"p\",\"version\":\"1\",\"digest\":\"logical-core\",\"core\":{}} \n".getBytes(StandardCharsets.UTF_8);
        String sha=StageHostCas.sha256Hex(bytes), origin="org-1/"+sha;
        JsonNode pin=inputAsset(bytes,"sha256:"+sha,origin,TOKEN);
        assertEquals(201,pin.path("__status").asInt());assertEquals(bytes.length,pin.path("bytes").asInt());
        assertEquals(origin,pin.path("originRef").asText());assertEquals("cas:sha256:"+sha,pin.path("ref").asText());
        assertEquals(pin.path("ref"),inputAsset(bytes,"sha256:"+sha,"cas:sha256:"+sha,TOKEN).path("ref"));
        com.fasterxml.jackson.databind.node.ObjectNode body=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(startBody("asset-start","IMPLEMENT"));
        body.put("releaseInputRef",pin.path("ref").asText());body.put("releaseInputDigest",sha);
        body.put("deploymentProfileRef",pin.path("ref").asText());body.put("deploymentProfileDigest",sha);
        JsonNode started=call("POST",IMPL,TOKEN,"asset-start",body.toString());
        assertEquals(202,started.path("__status").asInt());
        assertEquals(sha,started.path("productBindings").path("readInputSha").asText());
        assertEquals(sha,started.path("productBindings").path("readProfileSha").asText());
    }
    @Test public void inputAssetsRejectUnauthenticatedMissingWrongAndCrossDigestPins()throws Exception {
        byte[] bytes="{}".getBytes(StandardCharsets.UTF_8);String sha=StageHostCas.sha256Hex(bytes),wrong=String.join("",Collections.nCopies(64,"a"));
        assertEquals(401,inputAsset(bytes,"sha256:"+sha,"org/"+sha,null).path("__status").asInt());
        assertEquals(422,inputAsset(bytes,null,"org/"+sha,TOKEN).path("__status").asInt());
        assertEquals(422,inputAsset(bytes,"sha256:"+wrong,"org/"+sha,TOKEN).path("__status").asInt());
        assertEquals(422,inputAsset(bytes,"sha256:"+sha,"org/"+wrong,TOKEN).path("__status").asInt());
        assertEquals(422,inputAsset(bytes,"sha256:"+sha,null,TOKEN).path("__status").asInt());
        byte[] oversized=new byte[1048577];
        assertEquals(413,inputAsset(oversized,"sha256:"+StageHostCas.sha256Hex(oversized),"org/"+StageHostCas.sha256Hex(oversized),TOKEN).path("__status").asInt());
    }
    private JsonNode publish(String operationId,byte[] bytes,String digest)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+IMPL+"/"+operationId+"/bodies").openConnection();c.setRequestMethod("POST");c.setRequestProperty("Authorization","Bearer "+TOKEN);c.setRequestProperty("Idempotency-Key","body-"+StageHostCas.sha256Hex(bytes));c.setRequestProperty("If-Content-SHA256",digest);c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(bytes);}int status=c.getResponseCode();InputStream in=status<400?c.getInputStream():c.getErrorStream();JsonNode result=mapper.readTree(in);in.close();c.disconnect();return ((com.fasterxml.jackson.databind.node.ObjectNode)result).put("__status",status);
    }
    @Test public void bodyPublicationRoundTripsExactBytesAndScopesRead()throws Exception {
        String op=call("POST",IMPL,TOKEN,"body-start",startBody("body-start","IMPLEMENT")).path("hostOperationId").asText();
        byte[] bytes=" {\n \"value\" : \"untouched\" } \n".getBytes(StandardCharsets.UTF_8);String digest=StageHostCas.sha256Hex(bytes);
        JsonNode pin=publish(op,bytes,"sha256:"+digest);assertEquals(201,pin.path("__status").asInt());assertEquals("cas:sha256:"+digest,pin.path("ref").asText());
        HttpURLConnection c=(HttpURLConnection)new URL(base+IMPL+"/"+op+"/bodies/"+digest).openConnection();c.setRequestProperty("Authorization","Bearer "+TOKEN);assertEquals(200,c.getResponseCode());ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[]b=new byte[1024];int n;while((n=in.read(b))>=0)out.write(b,0,n);}org.junit.Assert.assertArrayEquals(bytes,out.toByteArray());c.disconnect();
        String other=call("POST",IMPL,TOKEN,"other-start",startBody("other-start","IMPLEMENT").replace("sb-1","sb-2")).path("hostOperationId").asText();
        assertEquals(404,call("GET",IMPL+"/"+other+"/bodies/"+digest,TOKEN,null,null).path("__status").asInt());
        assertEquals(pin.path("ref"),publish(op,bytes,"sha256:"+digest).path("ref"));
    }
    @Test public void bodyPublicationRejectsDigestAbsentOperationAndOversize()throws Exception {
        String op=call("POST",IMPL,TOKEN,"body-invalid",startBody("body-invalid","IMPLEMENT")).path("hostOperationId").asText();byte[]bytes="{}".getBytes(StandardCharsets.UTF_8);
        assertEquals(422,publish(op,bytes,"sha256:"+String.join("",Collections.nCopies(64,"a"))).path("__status").asInt());
        assertEquals(404,publish("hop-absent",bytes,"sha256:"+StageHostCas.sha256Hex(bytes)).path("__status").asInt());
        byte[]large=new byte[(int)com.sage.business.code.contract.input.InputLimits.CURRENT.maxRequestBodyBytes()+1];
        try{service.publishBody(op,large,"sha256:unused");org.junit.Assert.fail();}catch(com.sage.business.code.domain.BusinessException expected){assertTrue(expected.getMessage().contains("request limit"));}
    }

    @Test public void mutationRequiresBearer() throws Exception {
        JsonNode p = call("POST", IMPL, null, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        assertEquals(401, p.path("__status").asInt());
    }

    @Test public void mutationRequiresIdempotencyKey() throws Exception {
        JsonNode p = call("POST", IMPL, TOKEN, null,
                startBody("req-1", "IMPLEMENT"));
        assertEquals(400, p.path("__status").asInt());
    }

    @Test public void startBindsProductAndReturnsOperation()
            throws Exception {
        JsonNode r = call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        assertEquals(202, r.path("__status").asInt());
        assertTrue(r.path("hostOperationId").asText().startsWith("hop-"));
        assertEquals("intake-api-1",
                r.path("productBindings").path("primary").asText());
    }

    @Test public void startReplayReturnsSameOperation() throws Exception {
        JsonNode a = call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        JsonNode b = call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        assertEquals(a.path("hostOperationId").asText(),
                b.path("hostOperationId").asText());
    }

    @Test public void byRequestFindsOperation() throws Exception {
        call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        JsonNode r = call("GET",
                IMPL + "/by-request/req-1", TOKEN, null, null);
        assertEquals(200, r.path("__status").asInt());
        assertEquals(HostStageContract.LookupState.FOUND,
                r.path("lookupState").asText());
    }

    @Test public void byRequestDigestMismatchIsConflict() throws Exception {
        call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        JsonNode r = call("GET",
                IMPL + "/by-request/req-1?requestDigest=sha256:X",
                TOKEN, null, null);
        assertEquals(HostStageContract.LookupState.CONFLICT,
                r.path("lookupState").asText());
    }

    @Test public void operationViewIsHonest() throws Exception {
        JsonNode st = call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        String opId = st.path("hostOperationId").asText();
        JsonNode v = call("GET", IMPL + "/" + opId, TOKEN, null, null);
        assertEquals(200, v.path("__status").asInt());
        assertEquals(HostStageContract.QueryState.OK,
                v.path("queryState").asText());
    }

    @Test public void inputAppliesAndIsReplayable() throws Exception {
        JsonNode st = call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        String opId = st.path("hostOperationId").asText();
        String body = "{"
                + "\"schemaVersion\":\"" + HostStageContract.SCHEMA_VERSION + "\","
                + "\"adapterRevision\":\"" + HostStageContract.ADAPTER_REVISION + "\","
                + "\"requestId\":\"req-in-1\","
                + "\"effectId\":\"eff-in-1\","
                + "\"requestDigest\":\"sha256:in-1\","
                + "\"action\":\"CONTINUE\","
                + "\"permitRef\":\"permit-1\"}";
        JsonNode r1 = call("POST", IMPL + "/" + opId + "/inputs",
                TOKEN, "idem-in-1", body);
        assertEquals(202, r1.path("__status").asInt());
        assertEquals(HostStageContract.EffectOutcome.APPLIED,
                r1.path("outcome").asText());
        JsonNode r2 = call("POST", IMPL + "/" + opId + "/inputs",
                TOKEN, "idem-in-1", body);
        assertEquals(r1.path("outcome").asText(),
                r2.path("outcome").asText());
    }

    @Test public void capabilitiesProbeReportsBoundLeaf()
            throws Exception {
        JsonNode r = call("GET", IMPL + "/capabilities/IMPLEMENT",
                TOKEN, null, null);
        assertEquals(200, r.path("__status").asInt());
        assertTrue(r.path("drive").asBoolean());
        assertTrue(r.path("seal").asBoolean());
        assertTrue(r.path("currentness").asBoolean());
    }

    @Test public void capabilitiesProbeUnboundKindIs404()
            throws Exception {
        // REVIEW has no leaf in this fixture — the worker must see a
        // clean "not bound", never a guessed advertisement.
        JsonNode p = call("GET", IMPL + "/capabilities/REVIEW",
                TOKEN, null, null);
        assertEquals(404, p.path("__status").asInt());
        assertEquals(HostStageContract.Reasons.UNSUPPORTED_CAPABILITY,
                p.path("code").asText());
    }

    @Test public void capabilitiesProbeRequiresBearer() throws Exception {
        JsonNode p = call("GET", IMPL + "/capabilities/IMPLEMENT",
                null, null, null);
        assertEquals(401, p.path("__status").asInt());
    }

    @Test public void absentDesignLeafIsCapabilityGap() throws Exception {
        // No DESIGN leaf registered — honest 503 UNSUPPORTED_CAPABILITY.
        JsonNode p = call("POST", DESIGN, TOKEN, "idem-1",
                startBody("req-d1", "DESIGN"));
        assertEquals(503, p.path("__status").asInt());
        assertEquals(HostStageContract.Reasons.UNSUPPORTED_CAPABILITY,
                p.path("code").asText());
    }

    @Test public void getOnMutationRouteIsMethodNotAllowed()
            throws Exception {
        JsonNode p = call("GET", IMPL, TOKEN, null, null);
        assertEquals(405, p.path("__status").asInt());
    }

    @Test public void unknownOperationIsLookupMiss() throws Exception {
        JsonNode p = call("GET", IMPL + "/hop-does-not-exist",
                TOKEN, null, null);
        assertEquals(404, p.path("__status").asInt());
        assertEquals(HostStageContract.Reasons.LOOKUP_MISS,
                p.path("code").asText());
    }

    @Test public void malformedBodyIsInvalidRequest() throws Exception {
        JsonNode p = call("POST", IMPL, TOKEN, "idem-1", "{not-json");
        assertEquals(400, p.path("__status").asInt());
        assertEquals("INVALID_REQUEST", p.path("code").asText());
    }

    @Test public void eventsAndQuiescenceAreReads() throws Exception {
        JsonNode st = call("POST", IMPL, TOKEN, "idem-1",
                startBody("req-1", "IMPLEMENT"));
        String opId = st.path("hostOperationId").asText();
        assertEquals(200, call("GET", IMPL + "/" + opId + "/events",
                TOKEN, null, null).path("__status").asInt());
        JsonNode q = call("GET", IMPL + "/" + opId + "/quiescence",
                TOKEN, null, null);
        assertEquals(200, q.path("__status").asInt());
        assertFalse(q.path("state").asText().isEmpty());
    }
}
