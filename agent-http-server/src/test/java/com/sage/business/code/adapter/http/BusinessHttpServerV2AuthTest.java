package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.application.event.LiveEventHub;
import com.sage.business.code.contract.v2.event.BusinessEventEnvelope;
import com.sage.business.code.contract.v2.event.EventQueryResponse;
import com.sage.business.code.infrastructure.event.EventJournal;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/** Black-box authentication tests for the real BusinessHttpServer boundary. */
public class BusinessHttpServerV2AuthTest {
    private static final String TOKEN = "business-http-v2-auth-token";
    private final ObjectMapper mapper = new ObjectMapper();
    private BusinessHttpServer server;

    @After
    public void tearDown() {
        if (server != null) server.close();
    }

    @Test
    public void v2BoundaryRejectsMissingAndWrongBearerBeforeDispatch() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        start(new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                calls.incrementAndGet();
                byte[] body = "{\"ok\":true}".getBytes("UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().close();
            }
        });

        Assert.assertEquals(401, request("/api/v2/test", null));
        Assert.assertEquals(401, request("/api/v2/test", "wrong-token"));
        Assert.assertEquals(0, calls.get());
        Assert.assertEquals(200, request("/api/v2/test", TOKEN));
        Assert.assertEquals(1, calls.get());
        Assert.assertEquals(401, request("/worker-host/v1/manifest", null));
        Assert.assertEquals(401, request("/worker-host/v1/manifest?ticket=ignored", null));
        Assert.assertEquals(401, request("/worker-host/v1/manifest", "wrong-token"));
        Assert.assertEquals(1, calls.get());
        Assert.assertEquals(200, request("/worker-host/v1/manifest", TOKEN));
        Assert.assertEquals(2, calls.get());
    }

    @Test
    public void streamTicketsAreDelegatedOnlyOnTheTwoExplicitSsePaths() throws Exception {
        start(new V2ApiHandler(new EmptyJournal(), new LiveEventHub(), mapper, TOKEN, null));

        String ticket = issueTicket();
        Assert.assertFalse(ticket.isEmpty());
        HttpURLConnection valid = open("/api/v2/events?ticket=" + ticket, "GET");
        Assert.assertEquals(200, valid.getResponseCode());
        valid.disconnect();

        String sessionTicket = issueTicket();
        HttpURLConnection validSession = open(
                "/api/v2/sessions/session-1/events?ticket=" + sessionTicket, "GET");
        Assert.assertEquals(200, validSession.getResponseCode());
        validSession.disconnect();

        HttpURLConnection invalid = open("/api/v2/sessions/session-1/events?ticket=invalid", "GET");
        Assert.assertEquals(401, invalid.getResponseCode());
        Assert.assertTrue(mapper.readTree(invalid.getErrorStream()).path("message").asText()
                .contains("valid one-time stream ticket"));
        invalid.disconnect();
    }

    @Test
    public void nonSseV2RouteWithTicketStillRequiresBearer() throws Exception {
        start(new V2ApiHandler(new EmptyJournal(), new LiveEventHub(), mapper, TOKEN, null));
        Assert.assertEquals(401, request("/api/v2/events/journal?ticket=not-a-route-ticket", null));
    }

    private void start(HttpHandler v2) throws Exception {
        server = new BusinessHttpServer(new InetSocketAddress("127.0.0.1", 0), TOKEN,
                null, mapper, null, null, v2);
        server.start();
    }

    private int request(String path, String bearer) throws Exception {
        HttpURLConnection connection = open(path, "GET");
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        int status = connection.getResponseCode();
        if (status >= 400 && connection.getErrorStream() != null) connection.getErrorStream().close();
        connection.disconnect();
        return status;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        int port = serverPort();
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        return connection;
    }

    private String issueTicket() throws Exception {
        HttpURLConnection issue = open("/api/v2/stream-tickets", "POST");
        issue.setRequestProperty("Authorization", "Bearer " + TOKEN);
        issue.setDoOutput(true);
        issue.getOutputStream().close();
        Assert.assertEquals(201, issue.getResponseCode());
        JsonNode issued = mapper.readTree(issue.getInputStream());
        issue.disconnect();
        return issued.path("ticket").asText();
    }

    private int serverPort() throws Exception {
        java.lang.reflect.Field field = BusinessHttpServer.class.getDeclaredField("server");
        field.setAccessible(true);
        return ((com.sun.net.httpserver.HttpServer) field.get(server)).getAddress().getPort();
    }

    private static final class EmptyJournal implements EventJournal {
        @Override public BusinessEventEnvelope append(BusinessEventEnvelope event) { return event; }
        @Override public BusinessEventEnvelope findByEventId(String eventId) { return null; }
        @Override public EventQueryResponse readAfter(long afterCursor, int limit) {
            return page();
        }
        @Override public EventQueryResponse readStreamAfter(String streamType, String streamId,
                long afterCursor, int limit) { return page(); }
        @Override public long lastStreamSequence(String streamType, String streamId) { return 0L; }
        private static EventQueryResponse page() {
            EventQueryResponse response = new EventQueryResponse();
            response.setNextCursor(0L);
            response.setHighWaterMark(0L);
            response.setRetentionFloor(0L);
            return response;
        }
    }
}
