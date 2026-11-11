package com.sage.business.code.adapter.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.agent.product.design.v2.api.CallerScope;
import com.agent.product.design.v2.api.CommandContext;
import com.agent.product.design.v2.api.DesignSessionRequest;
import com.agent.product.design.v2.api.DeliveryRef;
import com.agent.product.design.v2.api.SessionInput;
import com.agent.product.design.v2.api.SessionSnapshot;
import com.agent.product.design.v2.session.DefaultDesignSessionService;
import com.agent.product.design.v2.spi.Clock;
import com.agent.product.design.v2.spi.Ids;
import com.agent.product.design.v2.spi.SessionEffect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.producthost.designv2.CasArtifactPort;
import com.sage.business.code.producthost.designv2.CoordinatorSessionEffectPort;
import com.sage.business.code.producthost.designv2.FileDesignDocumentPort;
import com.sage.business.code.producthost.designv2.FileDesignSessionStore;
import com.sage.business.code.producthost.designv2.NoModelExecutionHost;
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
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Black-box source-delivery HTTP coverage over the real Product session
 * service and filesystem ports. The source is sealed before the HTTP target
 * create so a test double cannot make a foreign or mutable document look
 * authorized.
 */
public final class DesignSourceDeliveryHttpTest {

    private static final String TOKEN = "design-source-http-token";
    private static final CallerScope OWNER = new CallerScope(
            "org-a", "alice", "project-a", "instance-a");
    private static final CallerScope FOREIGN = new CallerScope(
            "org-a", "bob", "project-b", "instance-b");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private FileDesignSessionStore store;
    private FileDesignDocumentPort documents;
    private CasArtifactPort artifacts;
    private DefaultDesignSessionService service;
    private String sourceSessionId;
    private DeliveryRef sourceDelivery;
    private String sourceDocumentDigest;
    private String foreignRef;
    private String foreignDigest;

    @Before
    public void setUp() throws Exception {
        Path root = folder.newFolder("design-source-state").toPath();
        store = new FileDesignSessionStore(root);
        documents = new FileDesignDocumentPort(root);
        artifacts = new CasArtifactPort(root);
        byte[] foreignBytes = "foreign project secret".getBytes(
                StandardCharsets.UTF_8);
        foreignRef = artifacts.store(foreignBytes, "text/plain");
        foreignDigest = "sha256:" + sha256(foreignBytes);
        Clock clock = new Clock.System();
        Ids ids = new Ids.Uuids();
        service = new DefaultDesignSessionService(store, documents,
                new NoModelExecutionHost(), null, clock, ids);

        byte[] requirement = "original authorized requirement".getBytes(
                StandardCharsets.UTF_8);
        String requirementRef = artifacts.store(requirement, "text/plain");
        String requirementDigest = "sha256:" + sha256(requirement);
        SessionSnapshot created = service.create(
                DesignSessionRequest.builder("project-a")
                        .title("sealed source")
                        .requirement(requirementRef, requirementDigest)
                        .build(),
                new CommandContext("source-create", OWNER, null));
        sourceSessionId = created.getSessionId();
        String sourceText = "# Source design\nsealed bytes\n";
        sourceDocumentDigest = "sha256:" + sha256(
                sourceText.getBytes(StandardCharsets.UTF_8));
        service.send(sourceSessionId,
                SessionInput.documentRevision("design.md", sourceText, 0L),
                new CommandContext("source-document", OWNER, null));

        CoordinatorSessionEffectPort effects =
                new CoordinatorSessionEffectPort(store, documents, artifacts,
                        null, clock, ids);
        Map<String, Object> proposal = new LinkedHashMap<String, Object>();
        proposal.put("conclusion", "IMPLEMENTATION_READY");
        proposal.put("documents", Collections.singletonList("design.md"));
        proposal.put("primaryDocument", "design.md");
        effects.apply(sourceSessionId, "source-run", "source-delivery",
                Collections.singletonList(SessionEffect.of(
                        SessionEffect.Kind.DELIVERY_PROPOSED, proposal)));
        sourceDelivery = service.get(sourceSessionId, OWNER)
                .getDeliveries().get(0);

        DesignSessionApiHandler handler = new DesignSessionApiHandler(
                mapper, TOKEN, service, store, documents, artifacts);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiDispatcher(mapper,
                Collections.<V2RouteGroup>singletonList(handler)));
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void sourceDeliveryPostImportsExactSeedAndReplaysByCommand() throws Exception {
        ObjectNode request = sourceRequest("project-a", sourceDelivery.getDigest());
        Response first = post("/api/v2/design/sessions", request,
                "target-create", OWNER);
        assertEquals(202, first.status);
        JsonNode created = mapper.readTree(first.body);
        String targetSessionId = created.path("sessionId").asText();
        assertTrue(targetSessionId.length() > 0);
        JsonNode imported = created.path("documents").get(0);
        assertEquals("design.md", imported.path("path").asText());
        assertEquals("design-session:" + sourceSessionId
                + "/delivery:" + sourceDelivery.getDeliveryId(),
                imported.path("sourceRef").asText());
        assertEquals("1", imported.path("sourceVersion").asText());
        assertEquals(sourceDocumentDigest,
                imported.path("sourceDigest").asText());

        Response replay = post("/api/v2/design/sessions", request,
                "target-create", OWNER);
        assertEquals(202, replay.status);
        assertEquals(targetSessionId,
                mapper.readTree(replay.body).path("sessionId").asText());
        SessionSnapshot durable = service.get(targetSessionId, OWNER);
        assertEquals(1, durable.getDocuments().size());
        assertEquals(1L, durable.getDocuments().get(0).getVersion());
        assertEquals("# Source design\nsealed bytes\n",
                new String(documents.read(targetSessionId, "design.md", 1L),
                        StandardCharsets.UTF_8));
    }

    @Test
    public void derivedTargetDeliveryExportsUpstreamAndPreservesSourceBytes()
            throws Exception {
        ObjectNode request = sourceRequest("project-a",
                sourceDelivery.getDigest());
        Response created = post("/api/v2/design/sessions", request,
                "target-seal-create", OWNER);
        assertEquals(202, created.status);
        String targetSessionId = mapper.readTree(created.body)
                .path("sessionId").asText();

        CoordinatorSessionEffectPort effects =
                new CoordinatorSessionEffectPort(store, documents, artifacts,
                        null, new Clock.System(), new Ids.Uuids());
        Map<String, Object> proposal = new LinkedHashMap<String, Object>();
        proposal.put("conclusion", "IMPLEMENTATION_READY");
        proposal.put("documents", Collections.singletonList("design.md"));
        proposal.put("primaryDocument", "design.md");
        effects.apply(targetSessionId, "target-seal-run", "target-seal",
                Collections.singletonList(SessionEffect.of(
                        SessionEffect.Kind.DELIVERY_PROPOSED, proposal)));

        DeliveryRef targetDelivery = service.get(targetSessionId, OWNER)
                .getDeliveries().get(0);
        Response deliveryResponse = get("/api/v2/design/sessions/"
                + targetSessionId + "/deliveries/"
                + targetDelivery.getDeliveryId(), OWNER);
        assertEquals(200, deliveryResponse.status);
        JsonNode deliveryView = mapper.readTree(deliveryResponse.body);
        assertEquals(targetDelivery.getManifestRef(),
                deliveryView.path("manifestRef").asText());
        assertEquals(targetDelivery.getDigest(),
                deliveryView.path("digest").asText());

        JsonNode manifest = mapper.readTree(artifacts.read(
                targetDelivery.getManifestRef(), null));
        JsonNode upstream = manifest.path("upstreamSources").get(0);
        assertEquals("design-session:" + sourceSessionId + "/delivery:"
                        + sourceDelivery.getDeliveryId(),
                upstream.path("sourceRef").asText());
        assertEquals("1", upstream.path("sourceVersion").asText());
        assertEquals(sourceDocumentDigest,
                upstream.path("sourceDigest").asText());
        assertEquals("SEALED_DELIVERY; upstream approval and authorization "
                        + "are not inherited", upstream.path("summary").asText());
        assertEquals(sourceDocumentDigest,
                "sha256:" + manifest.path("documents").get(0)
                        .path("sha256").asText());
        assertEquals("# Source design\nsealed bytes\n",
                new String(documents.read(sourceSessionId, "design.md", 1L),
                        StandardCharsets.UTF_8));
        assertEquals("# Source design\nsealed bytes\n",
                new String(documents.read(targetSessionId, "design.md", 1L),
                        StandardCharsets.UTF_8));
    }

    @Test
    public void sourceDeliveryRejectsForeignProjectAndTamperedDigest() throws Exception {
        ObjectNode foreignProject = sourceRequest("project-b",
                sourceDelivery.getDigest());
        Response projectMismatch = post("/api/v2/design/sessions",
                foreignProject, "foreign-project", OWNER);
        assertEquals(403, projectMismatch.status);
        assertEquals("SCOPE_DENIED",
                mapper.readTree(projectMismatch.body).path("code").asText());

        ObjectNode tampered = sourceRequest("project-a", "sha256:" + repeat('0', 64));
        Response digestMismatch = post("/api/v2/design/sessions", tampered,
                "tampered-digest", OWNER);
        assertEquals(400, digestMismatch.status);
        assertEquals("INPUT_INVALID",
                mapper.readTree(digestMismatch.body).path("code").asText());
    }

    @Test
    public void scopedCreateRejectsBareKnownCasRequirementRef() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("projectRef", "project-a");
        request.put("requirementRef", foreignRef);
        request.put("requirementDigest", foreignDigest);

        Response response = post("/api/v2/design/sessions", request,
                "bare-requirement", OWNER);
        assertScopeDenied(response);
    }

    @Test
    public void scopedCreateRejectsBareKnownCasMaterials() throws Exception {
        ObjectNode request = sourceRequest("project-a",
                sourceDelivery.getDigest());
        request.remove("sourceDelivery");
        request.putArray("materials").addObject()
                .put("ref", foreignRef)
                .put("digest", foreignDigest)
                .put("mediaType", "text/plain");

        Response response = post("/api/v2/design/sessions", request,
                "bare-material", OWNER);
        assertScopeDenied(response);
    }

    @Test
    public void scopedSourceDeliveryRejectsExtraKnownCasMaterial() throws Exception {
        ObjectNode request = sourceRequest("project-a",
                sourceDelivery.getDigest());
        request.putArray("materials").addObject()
                .put("ref", foreignRef)
                .put("digest", foreignDigest)
                .put("mediaType", "text/plain");

        Response response = post("/api/v2/design/sessions", request,
                "source-plus-foreign-material", OWNER);
        assertScopeDenied(response);
    }

    @Test
    public void scopedMaterialInputRejectsBareKnownCasReference() throws Exception {
        ObjectNode create = mapper.createObjectNode()
                .put("projectRef", "project-a")
                .put("requirementText", "new requirement");
        Response created = post("/api/v2/design/sessions", create,
                "material-input-create", OWNER);
        assertEquals(202, created.status);
        String sessionId = mapper.readTree(created.body)
                .path("sessionId").asText();

        ObjectNode input = mapper.createObjectNode().put("kind", "MATERIAL");
        input.putArray("materialRefs").addObject()
                .put("ref", foreignRef)
                .put("digest", foreignDigest)
                .put("mediaType", "text/plain");
        Response response = post("/api/v2/design/sessions/" + sessionId
                + "/inputs", input, "material-input-foreign", OWNER);
        assertScopeDenied(response);
    }

    @Test
    public void sourceDocumentReadRejectsForeignCallerScope() throws Exception {
        Response allowed = get("/api/v2/design/sessions/" + sourceSessionId
                + "/documents/design.md", OWNER);
        assertEquals(200, allowed.status);
        assertEquals("# Source design\nsealed bytes\n",
                mapper.readTree(allowed.body).path("content").asText());

        Response denied = get("/api/v2/design/sessions/" + sourceSessionId
                + "/documents/design.md", FOREIGN);
        assertEquals(403, denied.status);
        assertEquals("SCOPE_DENIED",
                mapper.readTree(denied.body).path("code").asText());
    }

    @Test
    public void scopedHistoricalAndEventReadsRejectForeignCaller() throws Exception {
        String base = "/api/v2/design/sessions/" + sourceSessionId;
        for (String suffix : new String[]{
                "/events",
                "/documents/design.md/versions",
                "/documents/design.md/versions/1/content",
                "/deliveries/" + sourceDelivery.getDeliveryId()}) {
            assertScopeDenied(get(base + suffix, FOREIGN));
        }
    }

    private void assertScopeDenied(Response response) throws Exception {
        assertEquals(403, response.status);
        assertEquals("SCOPE_DENIED",
                mapper.readTree(response.body).path("code").asText());
    }

    private ObjectNode sourceRequest(String project, String digest) {
        ObjectNode request = mapper.createObjectNode();
        request.put("projectRef", project);
        request.put("requirementText", "revise the sealed source");
        request.put("title", "continuation");
        ObjectNode source = request.putObject("sourceDelivery");
        source.put("sessionId", sourceSessionId);
        source.put("deliveryId", sourceDelivery.getDeliveryId());
        source.put("digest", digest);
        source.putArray("selectedPaths").add("design.md");
        return request;
    }

    private Response post(String path, ObjectNode body, String idempotency,
                          CallerScope scope) throws Exception {
        return call("POST", path, body.toString(), idempotency, scope);
    }

    private Response get(String path, CallerScope scope) throws Exception {
        return call("GET", path, null, null, scope);
    }

    private Response call(String method, String path, String body,
                          String idempotency, CallerScope scope) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getAddress().getPort() + path)
                .openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        connection.setRequestProperty("X-Caller-Organization",
                scope.getOrganizationId());
        connection.setRequestProperty("X-Caller-Principal",
                scope.getPrincipalId());
        connection.setRequestProperty("X-Caller-Project", scope.getProjectId());
        connection.setRequestProperty("X-Caller-Instance", scope.getInstanceId());
        if (idempotency != null) {
            connection.setRequestProperty("Idempotency-Key", idempotency);
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        return new Response(status, read(stream));
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        try (InputStream input = stream) {
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
