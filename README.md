# agent-code-host

Public host surface for the **agent-code** engine — one repository holding the
three pieces that used to live in separate repos. The engine itself ships as
compiled binaries from Maven Central (`com.steercrew.agtcode:*`); everything
here is the layer you own and can fork.

## Modules

| Module | What it is |
|---|---|
| [`agent-http-server`](agent-http-server) | Plain-Java HTTP layer for the runtime: `BusinessHttpServer`, v1/v2 dispatch, stream admission, loopback+bearer auth, standalone launchers and host assembly. Published artifact other hosts consume. |
| [`agent-code-boot`](agent-code-boot) | Spring Boot host skeleton — embeds `agent-code-runtime` + `agent-http-server`, Spring owns config/lifecycle/packaging. |
| [`agent-code-examples`](agent-code-examples) | Runnable, forkable examples (custom endpoint, custom host, embedded server, event client, MCP tool, provider config). |

## Build

```bash
mvn validate          # reactor sanity
mvn package           # build all three modules
```

The `agent-code-examples` module is itself an aggregator of `example-*`
submodules; each produces a standalone fat jar the desktop can attach to.

Engine binaries resolve from Maven Central; no engine source lives here.
