package com.steercrew.examples.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Decorator over the stock /api/v2 handler: owns /api/v2/example/* and
 * delegates everything else to the engine's dispatcher. This is the
 * sanctioned way to add endpoints without forking the engine.
 */
public final class ExampleEndpoints implements HttpHandler {

    private final HttpHandler delegate;
    private final ObjectMapper mapper;

    public ExampleEndpoints(HttpHandler delegate, ObjectMapper mapper) {
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/api/v2/example/")) {
            serve(exchange, path.substring("/api/v2/example".length()));
            return;
        }
        delegate.handle(exchange);
    }

    private void serve(HttpExchange exchange, String tail) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        if ("GET".equals(exchange.getRequestMethod()) && "/ping".equals(tail)) {
            body.put("pong", true)
                .put("servedBy", "example-custom-endpoint");
        } else {
            body.put("code", "NOT_FOUND")
                .put("message", "unknown example endpoint: " + tail);
            respond(exchange, 404, body);
            return;
        }
        respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
