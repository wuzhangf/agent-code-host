# agent-http-server

Plain-Java HTTP server layer for the AgentCodeService runtime: `BusinessHttpServer`, the v1/v2 route dispatch framework (`V2ApiDispatcher`, `V2ApiHandler`, `V2RouteGroup`, `V2HttpSupport`), stream/long-request admission, loopback-only binding plus bearer-token authentication, and the standalone launchers (`BusinessAgentServiceMain`, `CoreReleaseHostMain`, `MacPersonalAccessMain`) with `BusinessServiceFactory` host assembly.

Consumes the engine binaries from Maven Central (`com.steercrew.agtcode:agent-code-runtime`); contains no engine implementation. This repository is the public host surface shared by `agent-code-boot` and desktop loopback deployments.

## Build

```
mvn install
```

## Build your own host fat jar (community flow)

The engine kernel ships as binaries (`com.steercrew.agtcode:*` from Maven Central). This repository is the layer you control: handlers, dispatch, auth, launchers and host assembly.

```
mvn package -Pstandalone
java -jar target/agent-http-server-<version>-standalone.jar --config <your-service.properties>
```

- `config/service.properties.example` + `model-providers.properties.example`: copy and edit (token, state dir, model provider, `workspace.<alias>` entries).
- Entry point `StandaloneHostMain` stamps a standalone provenance identity, then runs the same `BusinessAgentServiceMain` launcher the desktop attach flow expects.
- Electron attaches at `http://127.0.0.1:<server.port>` with your `auth.token`; `/api/v2/capabilities` declares `v1+v2`.
- Fork this repo, change handlers or add your own `V2RouteGroup`, rebuild the same fat jar — the engine stays a binary dependency and cannot be modified from here.
