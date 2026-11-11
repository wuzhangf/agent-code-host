package com.steercrew.examples.embedded;

import com.sage.business.code.bootstrap.BusinessServiceFactory;
import com.sage.business.code.bootstrap.BusinessServiceFactory.GuiServices;
import com.sage.business.code.bootstrap.RuntimeProvenance;
import com.sage.business.code.bootstrap.ServiceConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Drops BusinessHttpServer entirely: mounts the engine's v2 handler on a
 * bare JDK HttpServer under your own auth scheme (an X-Api-Key header
 * here — OAuth, mTLS, or an upstream gateway work the same way), plus a
 * custom context the stock server does not serve. Spring MVC / Netty /
 * embedded-in-another-app follow the same pattern: keep the HttpHandler,
 * replace the server. Note the v2 handler enforces its own bearer check internally — adapt at the edge (header translation), don't try to strip it.
 *
 *   java -jar example-embedded-server-*-standalone.jar --config service.properties
 *   curl -H "X-Api-Key: <auth.token>" http://127.0.0.1:8769/api/v2/capabilities
 */
public final class EmbeddedServerMain {

    private EmbeddedServerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("business.runtime.provenance.snapshotId", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.snapshotId",
                    "embedded-" + UUID.randomUUID());
        }
        if (System.getProperty("business.runtime.provenance.schemaVersion", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.schemaVersion",
                    RuntimeProvenance.SCHEMA_VERSION);
        }
        ServiceConfig config = ServiceConfig.load(args);
        String host = config.getHost();
        int port = config.getPort();
        String token = config.getToken();
        GuiServices services = BusinessServiceFactory.createGui(config);

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/v2", new ApiKeyGate(services.v2, token));
        server.createContext("/health/live", exchange -> {
            byte[] bytes = "{\"status\":\"LIVE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/custom-ui", exchange -> {
            byte[] bytes = "<html><body>Community UI surface</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        System.out.println("embedded host on " + host + ":" + port
                + " — v2 under X-Api-Key auth, /custom-ui open");
    }

    /**
     * Edge auth adaptation: clients present X-Api-Key; the engine's v2
     * handler enforces bearer auth internally, so translate the header
     * before delegating. The v2 auth contract is not swappable — wrap it.
     */
    private static final class ApiKeyGate implements HttpHandler {
        private final HttpHandler delegate;
        private final String expected;

        private ApiKeyGate(HttpHandler delegate, String expected) {
            this.delegate = delegate;
            this.expected = expected;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String key = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (expected.isEmpty() || !expected.equals(key)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            HttpExchange wrapped = new HeaderInjectingExchange(
                    exchange, "Authorization", "Bearer " + key);
            try {
                delegate.handle(wrapped);
            } catch (Throwable t) {
                t.printStackTrace();
                String msg = String.valueOf(t.getMessage()).replace('\"', '\'');
                byte[] b = ("{\"error\":\"" + t.getClass().getSimpleName()
                        + ": " + msg + "\"}").getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, b.length);
                    exchange.getResponseBody().write(b);
                } catch (IOException ignored) {
                }
            } finally {
                exchange.close();
            }
        }
    }

}
