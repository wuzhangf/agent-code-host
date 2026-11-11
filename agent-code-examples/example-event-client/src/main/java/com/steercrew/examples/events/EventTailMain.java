package com.steercrew.examples.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tails the durable v2 event stream — the same feed the desktop client
 * renders from. Point it at any running host:
 *
 *   java -jar example-event-client-*-standalone.jar \
 *       http://127.0.0.1:8769 <auth.token> [cursor]
 *
 * Prints each event frame; Ctrl-C to stop. This is the raw material for
 * community dashboards, CLI monitors, and alternative UIs.
 */
public final class EventTailMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventTailMain() {
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0].replaceAll("/$", "")
                : "http://127.0.0.1:8766";
        String token = args.length > 1 ? args[1] : "";
        String cursor = args.length > 2 ? args[2] : "0";
        while (true) {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    base + "/api/v2/events?cursor=" + cursor + "&waitMillis=30000")
                    .openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setReadTimeout(45000);
            if (conn.getResponseCode() != 200) {
                System.err.println("events -> HTTP " + conn.getResponseCode());
                Thread.sleep(3000);
                continue;
            }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith(":")) continue;
                    JsonNode frame = MAPPER.readTree(
                            line.startsWith("data:") ? line.substring(5).trim() : line);
                    JsonNode next = frame.path("cursor");
                    if (next.isNumber() || next.isTextual()) {
                        cursor = next.asText();
                    }
                    System.out.println(frame.toString());
                }
            }
        }
    }
}
