package com.steercrew.examples.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal MCP stdio server exposing one tool ("echo"). Register it with a
 * running engine over the governed MCP surface:
 *
 *   POST /api/v2/mcp/servers  (see README for the registration payload)
 *
 * The engine spawns this process, speaks JSON-RPC over stdin/stdout, and
 * the agent gains the "echo" tool.
 */
public final class EchoMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EchoMcpServer() {
    }

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = System.out;
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            JsonNode request = MAPPER.readTree(line);
            String method = request.path("method").asText("");
            JsonNode id = request.get("id");
            if (id == null || id.isNull()) {
                continue; // notification: no response
            }
            ObjectNode result = MAPPER.createObjectNode();
            switch (method) {
                case "initialize":
                    result.putObject("protocolVersion").put("value", "2024-11-05");
                    ObjectNode caps = result.putObject("capabilities");
                    caps.putObject("tools");
                    ObjectNode info = result.putObject("serverInfo");
                    info.put("name", "echo-mcp");
                    info.put("version", "0.1.0");
                    break;
                case "tools/list":
                    ObjectNode tool = MAPPER.createObjectNode();
                    tool.put("name", "echo");
                    tool.put("description", "Echoes the input text back.");
                    ObjectNode schema = tool.putObject("inputSchema");
                    schema.put("type", "object");
                    schema.putObject("properties").putObject("text").put("type", "string");
                    schema.putArray("required").add("text");
                    result.putArray("tools").add(tool);
                    break;
                case "tools/call":
                    String text = request.path("params").path("arguments")
                            .path("text").asText("");
                    ObjectNode content = MAPPER.createObjectNode();
                    content.put("type", "text");
                    content.put("text", "echo: " + text);
                    result.putArray("content").add(content);
                    break;
                case "ping":
                    break; // empty result
                default:
                    reply(out, id, error(-32601, "method not found: " + method));
                    continue;
            }
            reply(out, id, result);
        }
    }

    private static ObjectNode error(int code, String message) {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        return err;
    }

    private static void reply(PrintStream out, JsonNode id, JsonNode payload) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.set("id", id);
        if (payload.isObject() && payload.has("code")) {
            frame.set("error", payload);
        } else {
            frame.set("result", payload);
        }
        out.println(frame.toString());
        out.flush();
    }
}
