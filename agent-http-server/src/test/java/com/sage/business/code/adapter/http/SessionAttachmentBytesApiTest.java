package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.BusinessCodeExecutor;
import com.sage.business.code.application.TaskApplicationService;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.application.gui.GuiTaskOrchestrator;
import com.sage.business.code.application.session.ImageFixtures;
import com.sage.business.code.application.session.SessionAttachment;
import com.sage.business.code.application.session.SessionService;
import com.sage.business.code.bootstrap.BusinessServiceFactory;
import com.sage.business.code.contract.input.InputLimits;
import com.sage.business.code.domain.BusinessExecutionResult;
import com.sage.business.code.domain.BusinessRunContext;
import com.sage.business.code.domain.RunStatus;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sage.business.code.infrastructure.gui.GuiSessionStore;
import com.sage.business.code.infrastructure.input.LocalInputAssetStore;
import com.sage.business.code.infrastructure.session.SqliteSessionRepository;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * 多模态设计 §7.8（WU-10）：图片 bytes 端点 HTTP 契约——归属校验先于
 * blob 读取、真实 image MIME、Content-Length、no-store/nosniff；与
 * 统一 body 上限一致的 413。
 */
public class SessionAttachmentBytesApiTest {
    private static final String TOKEN = "attachment-bytes-contract-bearer";
    private Path root, workspace, state;
    private ObjectMapper mapper;
    private ControlPlaneDatabase database;
    private HttpServer server;
    private java.util.concurrent.ExecutorService httpExecutor;
    private TaskApplicationService app;
    private GuiSessionStore store;
    private GuiTaskOrchestrator gui;
    private SessionService sessions;
    private LocalInputAssetStore assets;

    @Before public void setUp() throws Exception {
        System.setProperty("code.agent.imageInput.enabled", "true");
        Path base = Paths.get("target", "attachment-bytes-api-test").toAbsolutePath();
        Files.createDirectories(base);
        root = Files.createTempDirectory(base, "case-");
        workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.write(workspace.resolve("README.md"), Collections.singletonList("fixture"));
        git("init"); git("config", "user.email", "test@example.invalid");
        git("config", "user.name", "test"); git("add", "."); git("commit", "-m", "initial");
        state = root.resolve("state"); Files.createDirectories(state);
        mapper = BusinessServiceFactory.mapper();
        database = new ControlPlaneDatabase(state);
        SqliteEventJournal journal = new SqliteEventJournal(database, mapper);
        BusinessEventPublisher publisher = new BusinessEventPublisher(journal);
        LiveEventHub hub = new LiveEventHub();
        WorkspaceRegistry registry = new WorkspaceRegistry(
                Collections.singletonMap("sample", workspace.toString()));
        BusinessCodeExecutor fake = new BusinessCodeExecutor() {
            public BusinessExecutionResult execute(com.sage.business.code.domain.ApprovedBusinessTask t,
                    BusinessRunContext c) {
                return new BusinessExecutionResult(RunStatus.SUCCEEDED,
                        "product-" + c.getRunId(), "已完成", Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Collections.singletonMap("result", "PASSED"), null, null, null);
            }
        };
        app = new TaskApplicationService(new AtomicJsonStore(state, mapper),
                new com.sage.business.code.application.TaskCanonicalizer(registry, mapper),
                registry, fake, mapper, root.resolve("locks"));
        store = new GuiSessionStore(state, mapper);
        gui = new GuiTaskOrchestrator(store, app, registry);
        app.addRunStateListener(gui);
        assets = new LocalInputAssetStore(state.resolve("input-assets"), mapper);
        sessions = new SessionService(new SqliteSessionRepository(database, mapper),
                registry, mapper, publisher, new SessionService.MessageGateway() {
                    @Override public Map<String, Object> sendMessage(String sessionId,
                            String clientMessageId, String content, String idempotencyKey,
                            List<SessionAttachment> attachments) {
                        return gui.sendMessage(sessionId, clientMessageId, content,
                                idempotencyKey, attachments);
                    }
                });
        sessions.inputAssets(assets);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2", new V2ApiHandler(journal, hub, mapper, TOKEN, null, sessions));
        httpExecutor = java.util.concurrent.Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.start();
    }

    @After public void tearDown() throws Exception {
        System.clearProperty("code.agent.imageInput.enabled");
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
        if (app != null) app.close();
        if (store != null) store.close();
        if (database != null) database.close();
        delete(root);
    }

    @Test public void imageBytesEndpointServesOwnedAttachmentWithSafeHeaders() throws Exception {
        byte[] image = ImageFixtures.png(28, 14);
        String sessionId = createSession("create-1");
        JsonNode accepted = sendMessage(sessionId, "img-api", "看图", image, "image/png", "key-1");
        String messageId = accepted.path("messageId").asText();
        String attachmentId = accepted.path("attachments").path(0).path("attachmentId").asText();

        HttpURLConnection get = call("GET", "/api/v2/sessions/" + sessionId + "/messages/"
                + messageId + "/attachments/" + attachmentId + "/bytes", null, null);
        Assert.assertEquals(200, get.getResponseCode());
        Assert.assertEquals("image/png", get.getHeaderField("Content-Type"));
        Assert.assertEquals("private, no-store", get.getHeaderField("Cache-Control"));
        Assert.assertEquals("nosniff", get.getHeaderField("X-Content-Type-Options"));
        Assert.assertEquals(String.valueOf(image.length), get.getHeaderField("Content-Length"));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        copy(get.getInputStream(), body);
        Assert.assertArrayEquals(image, body.toByteArray());

        // 未知附件 → 404 problem；文本端点对图片 → 400 problem。
        HttpURLConnection missing = call("GET", "/api/v2/sessions/" + sessionId + "/messages/"
                + messageId + "/attachments/att-none/bytes", null, null);
        Assert.assertEquals(404, missing.getResponseCode());
        Assert.assertEquals("ATTACHMENT_NOT_FOUND",
                mapper.readTree(missing.getErrorStream()).path("code").asText());
        HttpURLConnection wrongEndpoint = call("GET", "/api/v2/sessions/" + sessionId
                + "/messages/" + messageId + "/attachments/" + attachmentId + "/content",
                null, null);
        Assert.assertEquals(400, wrongEndpoint.getResponseCode());
        Assert.assertEquals("INVALID_REQUEST",
                mapper.readTree(wrongEndpoint.getErrorStream()).path("code").asText());
    }

    @Test public void crossSessionBytesReadIsAUniform404() throws Exception {
        byte[] image = ImageFixtures.jpeg(30, 20);
        String owner = createSession("create-2");
        String other = createSession("create-2b");
        JsonNode accepted = sendMessage(owner, "img-x", "看图", image, "image/jpeg", "key-2");
        String messageId = accepted.path("messageId").asText();
        String attachmentId = accepted.path("attachments").path(0).path("attachmentId").asText();
        HttpURLConnection cross = call("GET", "/api/v2/sessions/" + other + "/messages/"
                + messageId + "/attachments/" + attachmentId + "/bytes", null, null);
        Assert.assertEquals(404, cross.getResponseCode());
        // 统一 MESSAGE_NOT_FOUND：不泄露目标消息/附件是否属于其他会话。
        Assert.assertEquals("MESSAGE_NOT_FOUND",
                mapper.readTree(cross.getErrorStream()).path("code").asText());
        // 无 token → 401。
        HttpURLConnection anonymous = (HttpURLConnection) new URL("http://127.0.0.1:"
                + server.getAddress().getPort() + "/api/v2/sessions/" + owner + "/messages/"
                + messageId + "/attachments/" + attachmentId + "/bytes").openConnection();
        anonymous.setRequestMethod("GET");
        Assert.assertEquals(401, anonymous.getResponseCode());
    }

    @Test public void oversizedBodyIsRejectedBeforeFullDecode() throws Exception {
        String sessionId = createSession("create-3");
        // body 上限 = ceil(8MiB/3)*4 + 1 MiB + 64 KiB；恰好超限 1 字节。
        long limit = InputLimits.CURRENT.maxRequestBodyBytes();
        String prefix = "{\"clientMessageId\":\"big-1\",\"content\":\"x\","
                + "\"expectedSessionVersion\":1,\"attachments\":[{"
                + "\"clientAttachmentId\":\"a\",\"name\":\"a.png\",\"mediaType\":\"image/png\","
                + "\"dataBase64\":\"";
        String suffix = "\"}]}";
        long fillerLength = limit + 1 - prefix.length() - suffix.length();
        StringBuilder body = new StringBuilder((int) (prefix.length() + fillerLength
                + suffix.length()));
        body.append(prefix);
        char[] filler = new char[8192];
        java.util.Arrays.fill(filler, 'A');
        for (long written = 0; written < fillerLength; written += filler.length) {
            int chunk = (int) Math.min(filler.length, fillerLength - written);
            body.append(filler, 0, chunk);
        }
        body.append(suffix);
        Assert.assertEquals(limit + 1, body.length());
        HttpURLConnection post = call("POST", "/api/v2/sessions/" + sessionId + "/messages",
                "key-big", body.toString());
        Assert.assertEquals(413, post.getResponseCode());
        JsonNode problem = mapper.readTree(post.getErrorStream());
        Assert.assertEquals("INVALID_REQUEST", problem.path("code").asText());
        Assert.assertTrue(problem.path("message").asText().contains("Request body exceeds"));
        // 超限请求不产生消息/任务。
        Assert.assertEquals(0, store.listMessages(sessionId).size());
    }

    /** readLimited 直接契约：超限在读满前停止（不完整解码）。 */
    @Test public void readLimitedStopsAtTheUnifiedCap() throws Exception {
        long limit = InputLimits.CURRENT.maxRequestBodyBytes();
        final java.io.InputStream stream = new java.io.InputStream() {
            long remaining = limit + 1024;
            @Override public int read() {
                return remaining-- > 0 ? 'x' : -1;
            }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) return -1;
                int chunk = (int) Math.min(length, remaining);
                remaining -= chunk;
                java.util.Arrays.fill(buffer, offset, offset + chunk, (byte) 'x');
                return chunk;
            }
        };
        try {
            TestSupport.readBodyLimited(stream);
            Assert.fail("body above the unified limit must be rejected");
        } catch (com.sage.business.code.domain.BusinessException expected) {
            Assert.assertEquals(413, expected.getHttpStatus());
            Assert.assertEquals("INVALID_REQUEST", expected.getCode());
        }
    }

    /** 包内缝隙：访问 protected static readLimited（与生产同一段代码）。 */
    static final class TestSupport extends V2HttpSupport {
        TestSupport() { super(new ObjectMapper(), "unused"); }

        static byte[] readBodyLimited(java.io.InputStream input) throws IOException {
            return readLimited(input);
        }
    }

    // ---- helpers ----

    private String createSession(String key) throws Exception {
        HttpURLConnection create = call("POST", "/api/v2/sessions", key,
                "{\"workspaceAlias\":\"sample\",\"interactionMode\":\"ACT\","
                        + "\"executionMode\":\"INSPECT\",\"autonomyLevel\":\"OFF\","
                        + "\"verificationLevel\":\"NONE\",\"writeAllow\":[],\"writeForbid\":[]}");
        Assert.assertEquals(201, create.getResponseCode());
        return mapper.readTree(create.getInputStream()).path("sessionId").asText();
    }

    private JsonNode sendMessage(String sessionId, String clientMessageId, String content,
            byte[] image, String mediaType, String key) throws Exception {
        String body = "{\"clientMessageId\":\"" + clientMessageId + "\",\"content\":\""
                + content + "\",\"expectedSessionVersion\":1,\"attachments\":[{"
                + "\"clientAttachmentId\":\"att-" + clientMessageId + "\",\"name\":\"shot.bin\","
                + "\"mediaType\":\"" + mediaType + "\",\"dataBase64\":\""
                + Base64.getEncoder().encodeToString(image) + "\"}]}";
        HttpURLConnection post = call("POST", "/api/v2/sessions/" + sessionId + "/messages",
                key, body);
        Assert.assertEquals(202, post.getResponseCode());
        return mapper.readTree(post.getInputStream());
    }

    private HttpURLConnection call(String method, String path, String key, String body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getAddress().getPort() + path).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        if (key != null) connection.setRequestProperty("Idempotency-Key", key);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = connection.getOutputStream(); out.write(bytes); out.close();
        }
        return connection;
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git"); command.add("-C"); command.add(workspace.toString());
        Collections.addAll(command, args); Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(process.getInputStream(), output); copy(process.getErrorStream(), output);
        if (process.waitFor() != 0) throw new IOException(output.toString("UTF-8"));
    }

    private static void delete(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                List<Path> paths = new ArrayList<Path>();
                Files.walk(path).forEach(paths::add);
                Collections.reverse(paths);
                for (Path p : paths) Files.deleteIfExists(p);
                return;
            } catch (java.nio.file.FileSystemException locked) {
                try { Thread.sleep(200L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }
    }
}
