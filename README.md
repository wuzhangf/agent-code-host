# agent-code-host

Public host surface for the **agent-code** engine — one repository holding the
three pieces that used to live in separate repos. The engine itself ships as
compiled binaries from Maven Central (`com.steercrew.agtcode:*`); everything
here is the layer you own and can fork.

## Projects

| Project | What it is |
|---|---|
| [`agent-http-server`](agent-http-server) | Plain-Java HTTP layer for the runtime: `BusinessHttpServer`, v1/v2 dispatch, stream admission, loopback+bearer auth, standalone launchers and host assembly. Published artifact other hosts consume. |
| [`agent-code-boot`](agent-code-boot) | Spring Boot host skeleton — embeds `agent-code-runtime` + `agent-http-server`, Spring owns config/lifecycle/packaging. |
| [`agent-code-examples`](agent-code-examples) | Runnable, forkable examples (custom endpoint, custom host, embedded server, event client, MCP tool, provider config). |

The three directories are independent Maven projects — no parent pom or
reactor links them; each is built and versioned on its own.

## Build

```bash
mvn -f agent-http-server/pom.xml package    # the HTTP layer
mvn -f agent-code-boot/pom.xml package      # the Spring Boot host
mvn -f agent-code-examples/pom.xml package  # all example fat jars
```

`agent-code-boot` and `agent-code-examples` resolve `agent-http-server`
as a normal Maven artifact. To test them against a local http-server
change first `mvn -f agent-http-server/pom.xml install`.

The `agent-code-examples` project is itself an aggregator of `example-*`
submodules; each produces a standalone fat jar the desktop can attach to.

Engine binaries resolve from Maven Central; no engine source lives here.
