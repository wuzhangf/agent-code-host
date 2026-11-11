# agent-code-examples

Runnable, forkable examples for extending the public host layer of the
agent-code stack ([agent-http-server](https://github.com/wuzhangf/agent-http-server)).
The engine (`com.steercrew.agtcode:*`) is consumed as binaries from
Maven Central — everything here is the layer you own.

Each module builds a standalone fat jar the desktop can attach to:

    mvn package
    java -jar example-custom-endpoint/target/*-standalone.jar --config service.properties

`service.properties` is the same file the stock launcher consumes
(see `agent-http-server/config/service.local.example.properties`).

## example-custom-endpoint

Adds `GET /api/v2/example/ping` alongside the full stock v2 surface by
wrapping the engine's `HttpHandler` — the pattern for webhooks, admin
endpoints, or integrations without touching the dispatcher.

## example-custom-host

The minimal composition root: load config, call
`BusinessServiceFactory.createGui`, bind `BusinessHttpServer`. Fork this
file when you want a different launcher, extra contexts, or your own
service wiring.

## example-mcp-tool

A minimal MCP stdio server (JSON-RPC `initialize` / `tools/list` /
`tools/call`) exposing one `echo` tool. Build the jar, then register the
server with a running engine:

    POST /api/v2/mcp/servers
    {
      "transport": "STDIO",
      "command": "java",
      "args": ["-jar", "/path/to/example-mcp-tool-*-standalone.jar"]
    }

The engine spawns the process; agents gain the `echo` tool through the
governed MCP surface.

## example-embedded-server

Drops `BusinessHttpServer` entirely: mounts the engine's v2
`HttpHandler` on a bare JDK `HttpServer` with a different edge auth
scheme (`X-Api-Key` → `Authorization: Bearer` translation via an
`HttpExchange` wrapper) plus an open `/custom-ui` context. The pattern
for Spring MVC / Netty / embedding the agent into another app.

Two constraints discovered while building it:

- The v2 handler enforces bearer auth **internally** — adapt at the
  edge (header translation), don't try to strip it.
- `HttpExchange.getRequestHeaders()` is unmodifiable on modern JDKs —
  inject headers via a delegating `HttpExchange` (`HeaderInjectingExchange`),
  not `headers.set()`.

## example-event-client

A tail of the durable `/api/v2/events` stream — the same feed the
desktop renders — for building dashboards, CLI monitors, or alternative
UIs against any running host.

## example-providers

Configuration only — no code. `model-providers.multi.example.properties`
shows a second OpenAI-compatible provider (Gemini compat shown; works
identically for OpenRouter, DeepSeek, Groq, Together...). Provider
entries ride the existing `OPENAI_CHAT_COMPLETIONS` dialect.
