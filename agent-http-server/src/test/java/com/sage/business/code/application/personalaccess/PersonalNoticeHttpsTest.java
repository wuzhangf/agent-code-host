package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.bootstrap.PersonalNoticeSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real TLS -> persistent notice pipeline. The ntfy publisher is faked; the upstream ACS source is a controlled stub. */
public class PersonalNoticeHttpsTest {
    PersonalLocalTaskAccessTest f; PersonalAccessServer server; HttpServer upstream; SSLContext tls;
    PersonalNoticeService notices; PersonalNoticeRepository noticeStore; X509Certificate certificate;
    List<String[]> published = Collections.synchronizedList(new ArrayList<String[]>());
    volatile ObjectNode approvals, messages, summary, run;

    @Before public void setup() throws Exception {
        f = new PersonalLocalTaskAccessTest(); f.setup();
        PersonalAccessService.Device device = f.auth.authenticate(f.token);
        f.auth.updateScopeResources(f.did, "notice-scope", Long.toString(device.revision),
                Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION", "acs-store", "session-one")),
                Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend, "project-one", "pi")),
                new TreeSet<String>(Arrays.asList("OBSERVE", "LAUNCH", "CONTROL", "INTERACT", "APPROVE")));
        approvals = page(); messages = page(); messages.put("sessionId", "session-one");
        summary = f.json.createObjectNode().put("activeRunId", ""); run = null;
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", this::stub); upstream.start();
        String password = UUID.randomUUID().toString(); Path file = f.root.resolve("fixture-only-tls.p12");
        String keytool = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1", "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", password,
                "-keypass", password, "-noprompt").redirectErrorStream(true)
                .redirectOutput(f.root.resolve("fixture-only-keytool.log").toFile()).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(file)) { keys.load(input, password.toCharArray()); }
        KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(keys, password.toCharArray());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        tls = SSLContext.getInstance("TLS");
        tls.init(keyManager.getKeyManagers(), trust.getTrustManagers(), new SecureRandom());
        certificate = (X509Certificate) keys.getCertificate("fixture");
        noticeStore = new PersonalNoticeRepository(f.records);
        PersonalNoticeService.Publisher publisher = new PersonalNoticeService.Publisher() {
            @Override public NtfyNoticeAdapter.Result publish(String topicRef, String clickUrl, String tag) {
                published.add(new String[]{topicRef, clickUrl, tag});
                return NtfyNoticeAdapter.Result.accepted(200, System.currentTimeMillis());
            }
        };
        notices = new PersonalNoticeService(settings(), noticeStore, publisher,
                PersonalNoticeService.ACL_UNSUPPORTED, java.time.Clock.systemUTC(),
                new java.util.function.Supplier<NtfyNoticeAdapter.SubscriptionAuth>() {
                    @Override public NtfyNoticeAdapter.SubscriptionAuth get() {
                        return NtfyNoticeAdapter.SubscriptionAuth.basic("reader", "read-secret");
                    }
                });
        server = new PersonalAccessServer(new InetSocketAddress("127.0.0.1", 0), tls, certificate, f.auth,
                new ClientSessionRegistry("acs-store", "notice-test-boot"), f.json,
                new URI("http://127.0.0.1:" + upstream.getAddress().getPort()), "owner-token").notices(notices);
        server.start();
    }

    @After public void close() throws Exception {
        if (server != null) server.close();
        if (upstream != null) upstream.stop(0);
        if (f != null) f.cleanup();
    }

    private ObjectNode page() { ObjectNode out = f.json.createObjectNode(); out.putArray("items"); return out; }

    private static PersonalNoticeSettings settings() {
        Properties p = new Properties();
        p.setProperty("personal.notice.enabled", "true");
        p.setProperty("personal.notice.server", "https://ntfy.example");
        return PersonalNoticeSettings.from(p);
    }

    void stub(HttpExchange x) {
        try {
            String path = x.getRequestURI().getPath();
            JsonNode out; int status = 200;
            if (path.endsWith("/approvals")) out = approvals;
            else if (path.endsWith("/messages")) out = messages;
            else if (path.endsWith("/summary")) out = summary;
            else if (path.startsWith("/api/v2/runs/") && run != null) out = run;
            else { out = f.json.createObjectNode().put("code", "NOT_FOUND"); status = 404; }
            byte[] b = f.json.writeValueAsBytes(out);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, b.length);
            x.getResponseBody().write(b);
        } catch (Exception ignored) { } finally { x.close(); }
    }

    void pendingApproval(String id, String version) {
        ObjectNode page = page();
        page.putArray("items").addObject().put("approvalId", id).put("sessionId", "session-one")
                .put("requiresUserAction", true).put("decisionStatus", "PENDING")
                .put("requestVersion", version).put("publicSummary", "approve fixture");
        approvals = page;
    }

    JsonNode call(String method, String path, JsonNode body, String token, int expected) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL("https://127.0.0.1:" + server.port() + path).openConnection();
        c.setSSLSocketFactory(tls.getSocketFactory()); c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setConnectTimeout(5000); c.setReadTimeout(10000);
        try {
            if (body != null) {
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(f.json.writeValueAsBytes(body)); }
            }
            int status = c.getResponseCode();
            if (status == 204) { assertEquals(expected, status); return null; }
            JsonNode result = f.json.readTree(status >= 400 ? c.getErrorStream() : c.getInputStream());
            assertEquals(result.toString(), expected, status);
            return result;
        } finally { c.disconnect(); }
    }

    String secondDevice() throws Exception {
        String poll = "poll-2", request = "pairing-2";
        ObjectNode invite = f.auth.invitation("invite-2", Arrays.asList("session-one"),
                new TreeSet<String>(Arrays.asList("OBSERVE")), "cert");
        f.auth.request(f.json.createObjectNode().put("inviteId", "invite-2")
                .put("inviteSecret", invite.path("inviteSecret").asText()).put("pairingRequestId", request)
                .put("installationId", "other").put("label", "tablet")
                .put("pollingSecretHash", PersonalAccessService.hash(poll)));
        f.auth.decide(request, true);
        return f.auth.redeem(request, poll, "redeem-2").path("accessToken").asText();
    }

    @Test public void capabilitiesAndSubscriptionLifecycleAreDeviceScoped() throws Exception {
        JsonNode host = call("GET", "/personal/v1/host", null, f.token, 200);
        assertTrue(host.path("capabilities").path("notifications").asBoolean());
        PersonalAccessServer plain = new PersonalAccessServer(new InetSocketAddress("127.0.0.1", 0), tls,
                certificate, f.auth, new ClientSessionRegistry("acs-store", "plain-boot"), f.json,
                new URI("http://127.0.0.1:" + upstream.getAddress().getPort()), "owner-token");
        try {
            plain.start();
            HttpsURLConnection c = (HttpsURLConnection) new URL("https://127.0.0.1:" + plain.port() + "/personal/v1/host").openConnection();
            c.setSSLSocketFactory(tls.getSocketFactory());
            c.setRequestProperty("Authorization", "Bearer " + f.token);
            JsonNode view = f.json.readTree(c.getInputStream());
            assertFalse(view.path("capabilities").path("notifications").asBoolean());
            c.disconnect();
        } finally { plain.close(); }

        JsonNode sub = call("GET", "/personal/v1/notifications/subscription", null, f.token, 200);
        assertEquals("https://ntfy.example", sub.path("server").asText());
        assertTrue(sub.path("topic").asText().startsWith("pac-"));
        assertTrue(sub.path("clickBase").asText().startsWith("agentcontrol://inbox?host="));
        assertEquals("basic", sub.path("auth").path("type").asText());
        assertEquals("reader", sub.path("auth").path("username").asText());
        assertEquals("read-secret", sub.path("auth").path("password").asText());
        // Provisioning is stable per device and isolated across devices.
        assertEquals(sub.path("topic").asText(),
                call("GET", "/personal/v1/notifications/subscription", null, f.token, 200).path("topic").asText());
        String other = secondDevice();
        JsonNode foreign = call("GET", "/personal/v1/notifications/subscription", null, other, 200);
        assertNotEquals(sub.path("topic").asText(), foreign.path("topic").asText());
    }

    @Test public void preferencesRoundTripAndRequestIdReplay() throws Exception {
        JsonNode view = call("GET", "/personal/v1/notifications/preferences", null, f.token, 200);
        assertFalse(view.path("enabled").asBoolean());
        assertFalse(view.path("route").path("configured").asBoolean());
        call("PUT", "/personal/v1/notifications/preferences",
                f.json.createObjectNode().put("requestId", "r-pre").put("enabled", true), f.token, 409);
        call("GET", "/personal/v1/notifications/subscription", null, f.token, 200);
        ObjectNode body = f.json.createObjectNode().put("requestId", "r1").put("enabled", true)
                .put("quietStart", "23:00").put("quietEnd", "07:00");
        JsonNode updated = call("PUT", "/personal/v1/notifications/preferences", body, f.token, 200);
        assertTrue(updated.path("enabled").asBoolean());
        assertTrue(updated.path("route").path("enabled").asBoolean());
        assertEquals("23:00", updated.path("quietStart").asText());
        assertEquals(updated.toString(),
                call("PUT", "/personal/v1/notifications/preferences", body, f.token, 200).toString());
        JsonNode conflict = call("PUT", "/personal/v1/notifications/preferences",
                f.json.createObjectNode().put("requestId", "r1").put("enabled", false), f.token, 409);
        assertEquals("REQUEST_ID_REUSE", conflict.path("code").asText());
    }

    @Test public void testNoticeFlowsThroughRealPipelineAndReplays() throws Exception {
        call("GET", "/personal/v1/notifications/subscription", null, f.token, 200);
        call("PUT", "/personal/v1/notifications/preferences",
                f.json.createObjectNode().put("requestId", "on").put("enabled", true), f.token, 200);
        ObjectNode req = f.json.createObjectNode().put("requestId", "t1");
        JsonNode first = call("POST", "/personal/v1/notifications/test", req, f.token, 200);
        assertEquals("QUEUED", first.path("state").asText());
        server.noticePass();
        assertEquals(1, published.size());
        String[] sent = published.get(0);
        assertEquals("test", sent[2]);
        assertTrue(sent[1].startsWith("agentcontrol://inbox?host="));
        assertTrue(sent[1].contains("&notice="));
        JsonNode replay = call("POST", "/personal/v1/notifications/test", req, f.token, 200);
        assertEquals(first.path("deliveryId").asText(), replay.path("deliveryId").asText());
        assertEquals("ACCEPTED", replay.path("state").asText());
        assertEquals(1, published.size());
    }

    @Test public void inboxCommitFeedsNoticeAndRefResolvesOpenThenResolved() throws Exception {
        // An item scanned before any route exists must still notify once a route is enabled (reconcile).
        pendingApproval("a1", "1");
        server.inboxPass();
        assertTrue(published.isEmpty());
        call("GET", "/personal/v1/notifications/subscription", null, f.token, 200);
        call("PUT", "/personal/v1/notifications/preferences",
                f.json.createObjectNode().put("requestId", "on").put("enabled", true), f.token, 200);
        server.noticePass();
        assertEquals(1, published.size());
        String[] sent = published.get(0);
        assertEquals("needs_action", sent[2]);
        String ref = sent[1].substring(sent[1].indexOf("&notice=") + 8);
        JsonNode open = call("GET", "/personal/v1/notifications/refs/" + ref, null, f.token, 200);
        assertEquals("OPEN", open.path("state").asText());
        assertEquals("NEEDS_INPUT", open.path("kind").asText());
        assertTrue(open.path("itemId").isTextual());
        // Once the source reports the approval handled, the same ref resolves to current truth.
        approvals = page();
        server.inboxPass();
        JsonNode done = call("GET", "/personal/v1/notifications/refs/" + ref, null, f.token, 200);
        assertEquals("RESOLVED", done.path("state").asText());
    }

    @Test public void refsExpireAndStayInvisibleToOtherDevices() throws Exception {
        call("GET", "/personal/v1/notifications/subscription", null, f.token, 200);
        call("PUT", "/personal/v1/notifications/preferences",
                f.json.createObjectNode().put("requestId", "on").put("enabled", true), f.token, 200);
        JsonNode first = call("POST", "/personal/v1/notifications/test",
                f.json.createObjectNode().put("requestId", "t-ref"), f.token, 200);
        PersonalNoticeService.Delivery stored = noticeStore.get(PersonalNoticeService.DELIVERY_KIND,
                first.path("deliveryId").asText(), PersonalNoticeService.Delivery.class);
        String other = secondDevice();
        call("GET", "/personal/v1/notifications/refs/" + stored.ref, null, other, 404);
        call("GET", "/personal/v1/notifications/refs/AAAAAAAAAAAAAAAAAAAAAA", null, f.token, 404);
        // A ref past its expiry reports EXPIRED to its owner.
        PersonalNoticeService.NoticeRef expired = new PersonalNoticeService.NoticeRef();
        expired.ref = "bbbbbbbbbbbbbbbbbbbbbb"; expired.deviceId = f.did; expired.itemId = "item-gone";
        expired.kind = "NEEDS_INPUT"; expired.createdAt = System.currentTimeMillis() - 60000;
        expired.expiresAt = System.currentTimeMillis() - 1000;
        noticeStore.put(PersonalNoticeService.REF_KIND, expired.ref, expired);
        JsonNode view = call("GET", "/personal/v1/notifications/refs/" + expired.ref, null, f.token, 200);
        assertEquals("EXPIRED", view.path("state").asText());
    }

    @Test public void revokeDisablesRouteAndStopsFutureEnqueue() throws Exception {
        call("GET", "/personal/v1/notifications/subscription", null, f.token, 200);
        call("PUT", "/personal/v1/notifications/preferences",
                f.json.createObjectNode().put("requestId", "on").put("enabled", true), f.token, 200);
        call("DELETE", "/personal/v1/device", null, f.token, 204);
        PersonalNoticeService.Route route = notices.routeInfo(f.did);
        assertNotNull(route);
        assertFalse(route.enabled); assertTrue(route.paused);
        assertEquals("DEVICE_REVOKED", route.pausedReason);
        assertTrue(route.cleanupPending);
        assertNull(notices.enqueue(f.did, "item-1", "rev-1"));
    }
}
