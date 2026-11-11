package com.sage.business.code.bootstrap;

import java.util.Properties;
import java.lang.reflect.Field;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sage.business.code.application.client.ClientRegistrationResult;
import com.sage.business.code.application.client.ClientSessionRegistry;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import com.sage.business.code.adapter.http.stream.LongRequestAdmission;
import org.junit.Assert;
import org.junit.Test;

public class MultiClientSettingsTest {
    @Test
    public void sharedStrictRejectsLegacyMutationEvenForRegisteredClient() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("clients.mode", "SHARED_STRICT");
        MultiClientSettings settings = MultiClientSettings.fromProperties(properties);
        ClientSessionRegistry registry = new ClientSessionRegistry("store", "boot");
        ClientRegistrationResult registered = registry.register(
                com.sage.business.code.adapter.http.ClientSessionApiHandler.LOCAL_OWNER_PRINCIPAL,
                new ClientSessionRegistry.RegistrationRequest(
                        UUID.randomUUID().toString(), "hash", UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(), "ELECTRON", Arrays.asList("v2"), "test"));
        BusinessHttpServer server = new BusinessHttpServer(
                new InetSocketAddress("127.0.0.1", 0), "server-token-012345", null,
                new ObjectMapper(), null, null, null, null, registry, true, settings);
        server.start();
        try {
            int port = serverPort(server);
            Assert.assertEquals(428, request(port, null));
            Assert.assertEquals(428, request(port, registered.getClientSessionToken()));
        } finally {
            server.close();
        }
    }

    private static int request(int port, String clientToken) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/api/v1/tasks").openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer server-token-012345");
        if (clientToken != null) connection.setRequestProperty(
                com.sage.business.code.adapter.http.ClientSessionApiHandler.CLIENT_TOKEN_HEADER,
                clientToken);
        connection.setDoOutput(true);
        connection.getOutputStream().close();
        return connection.getResponseCode();
    }

    private static int serverPort(BusinessHttpServer server) throws Exception {
        Field field = BusinessHttpServer.class.getDeclaredField("server");
        field.setAccessible(true);
        return ((com.sun.net.httpserver.HttpServer) field.get(server)).getAddress().getPort();
    }

    @Test
    public void defaultsRemainLegacyCompatibleAndExposeFiniteLimits() {
        MultiClientSettings settings = MultiClientSettings.defaults();
        Assert.assertEquals(MultiClientSettings.Mode.LEGACY_COMPAT, settings.getMode());
        Assert.assertEquals(Integer.valueOf(16), settings.capabilityLimits().get("maxActive"));
        Assert.assertEquals(24, settings.getMaxStreamTotal());
        Assert.assertEquals(16, settings.getMaxControlStreams());
        Assert.assertEquals(8, settings.getMaxTerminalStreams());
        Assert.assertEquals(8, settings.getMaxLongPoll());
        Assert.assertEquals(48, settings.getHttpWorkers());
        Assert.assertEquals(64, settings.getHttpQueueCapacity());
        Assert.assertEquals(16, settings.getShortRequestReserve());
        Assert.assertEquals(Integer.valueOf(48), settings.capabilityLimits().get("httpWorkers"));
        Assert.assertEquals(256, settings.getMaxStreamQueueEvents());
        Assert.assertEquals(2 * 1024 * 1024, settings.getMaxStreamQueueBytes());
    }

    @Test
    public void parsesAndValidatesExplicitSharedLimits() {
        Properties properties = new Properties();
        properties.setProperty("clients.mode", "SHARED_STRICT");
        properties.setProperty("clients.max-active", "8");
        properties.setProperty("clients.ttl-ms", "60000");
        properties.setProperty("clients.heartbeat-ms", "10000");
        properties.setProperty("streams.max-total", "12");
        properties.setProperty("streams.max-control", "5");
        properties.setProperty("streams.max-terminal", "4");
        properties.setProperty("streams.max-long-poll", "3");
        properties.setProperty("http.workers", "24");
        properties.setProperty("http.queue-capacity", "7");
        properties.setProperty("http.short-request-reserve", "9");
        properties.setProperty("streams.max-queue-events", "128");
        properties.setProperty("streams.max-queue-bytes", "131072");
        MultiClientSettings settings = MultiClientSettings.fromProperties(properties);
        Assert.assertEquals(MultiClientSettings.Mode.SHARED_STRICT, settings.getMode());
        Assert.assertEquals(8, settings.getMaxActive());
        Assert.assertEquals(60000L, settings.getTtlMillis());
        Assert.assertEquals(12, settings.getMaxStreamTotal());
        Assert.assertEquals(5, settings.getMaxControlStreams());
        Assert.assertEquals(4, settings.getMaxTerminalStreams());
        Assert.assertEquals(3, settings.getMaxLongPoll());
        Assert.assertEquals(24, settings.getHttpWorkers());
        Assert.assertEquals(7, settings.getHttpQueueCapacity());
        Assert.assertEquals(9, settings.getShortRequestReserve());
        Assert.assertEquals(128, settings.getMaxStreamQueueEvents());
        Assert.assertEquals(131072, settings.getMaxStreamQueueBytes());
    }

    @Test
    public void rejectsHeartbeatThatCannotRefreshBeforeExpiry() {
        Properties properties = new Properties();
        properties.setProperty("clients.ttl-ms", "10000");
        properties.setProperty("clients.heartbeat-ms", "5000");
        try {
            MultiClientSettings.fromProperties(properties);
            Assert.fail("expected invalid heartbeat interval");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("heartbeat"));
        }
    }

    @Test
    public void rejectsStreamKindLimitAboveSharedTotal() {
        Properties properties = new Properties();
        properties.setProperty("streams.max-total", "4");
        properties.setProperty("streams.max-control", "5");
        try {
            MultiClientSettings.fromProperties(properties);
            Assert.fail("expected invalid stream limit");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("streams.max-control"));
        }
    }

    @Test
    public void rejectsHttpWorkersWithoutShortRequestCapacity() {
        Properties properties = new Properties();
        properties.setProperty("http.workers", "31");
        properties.setProperty("http.short-request-reserve", "16");
        try {
            MultiClientSettings.fromProperties(properties);
            Assert.fail("expected insufficient HTTP worker capacity");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("http worker capacity"));
        }
    }

    @Test
    public void appliesConfiguredHttpWorkerAndQueueCapacityToServer() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("http.workers", "20");
        properties.setProperty("http.queue-capacity", "11");
        properties.setProperty("http.short-request-reserve", "16");
        properties.setProperty("streams.max-total", "2");
        properties.setProperty("streams.max-control", "1");
        properties.setProperty("streams.max-terminal", "1");
        properties.setProperty("streams.max-long-poll", "1");
        MultiClientSettings settings = MultiClientSettings.fromProperties(properties);
        BusinessHttpServer server = new BusinessHttpServer(
                new java.net.InetSocketAddress("127.0.0.1", 0),
                "1234567890123456", null, new ObjectMapper(), null, settings);
        try {
            Field field = BusinessHttpServer.class.getDeclaredField("httpExecutor");
            field.setAccessible(true);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(server);
            Assert.assertEquals(20, executor.getCorePoolSize());
            Assert.assertEquals(20, executor.getMaximumPoolSize());
            Assert.assertEquals(11, executor.getQueue().remainingCapacity());
        } finally {
            server.close();
        }
    }

    @Test
    public void rejectsSseKindReservationsThatExceedSseTotal() {
        Properties properties = new Properties();
        properties.setProperty("streams.max-total", "20");
        properties.setProperty("streams.max-control", "16");
        properties.setProperty("streams.max-terminal", "8");
        try {
            MultiClientSettings.fromProperties(properties);
            Assert.fail("expected stream kind capacity failure");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("streams.max-control"));
        }
    }

    @Test
    public void configuredStreamLimitsMatchAdmissionConstructorContract() {
        Properties properties = new Properties();
        properties.setProperty("streams.max-total", "3");
        properties.setProperty("streams.max-control", "2");
        properties.setProperty("streams.max-terminal", "1");
        properties.setProperty("streams.max-long-poll", "1");
        MultiClientSettings settings = MultiClientSettings.fromProperties(properties);

        LongRequestAdmission admission = new LongRequestAdmission(
                settings.getMaxStreamTotal(), settings.getMaxControlStreams(),
                settings.getMaxTerminalStreams(), settings.getMaxLongPoll());
        Assert.assertEquals(3, admission.availableTotal());
        Assert.assertEquals(Integer.valueOf(2),
                admission.available().get(LongRequestAdmission.Kind.CONTROL_STREAM));
        Assert.assertEquals(Integer.valueOf(1),
                admission.available().get(LongRequestAdmission.Kind.TERMINAL_STREAM));
        Assert.assertEquals(Integer.valueOf(1),
                admission.available().get(LongRequestAdmission.Kind.LONG_POLL));
    }
}
