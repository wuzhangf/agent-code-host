# agent-code-boot

Spring Boot host for the **agent-code** engine — the local service that
[CodeAgent Desktop](https://github.com/wuzhangf/AgentDesktopCode) (Electron)
connects to over loopback HTTP.

This repository is intentionally thin: Spring Boot owns configuration,
lifecycle and packaging; the engine owns the entire HTTP contract surface
(`/health/live`, `/api/v2/*`, GUI routes) inside the same process.

## Repository layout

| Path | Role |
|---|---|
| `io.github.wuzhangf.agentcodeboot` | Boot application, `@ConfigurationProperties`, `SmartLifecycle` |
| `com.sage.business.code.bootstrap.EngineServiceBridge` | Same-package bridge into the engine's bootstrap internals (`ServiceConfig` fields are package-private by design); the only file that calls engine internals |
| `config/service.properties.example` | Engine configuration template |

## Engine artifacts

The engine is distributed as compiled binaries, not source. `pom.xml`
imports `com.sage.agentcode:agent-code-bom` and depends on
`agent-code-runtime`; versions are pinned by `agent.code.version`.

Until the engine artifacts are published to a Maven repository, build
against a local repository that already contains the installed build:

```bash
mvn -Dmaven.repo.local=<path-to>/.m2 package
```

When the artifacts land on a shared repository, declare it in
`pom.xml` (the `<repositories>` block is marked with a comment).

## Run

```bash
export CODEAGENT_SERVICE_TOKEN=<bearer-token>   # required by the engine
cp config/service.properties.example config/service.properties
java -jar target/agent-code-boot-*.jar
```

The engine binds `server.host:server.port` from `service.properties`
(loopback only). Point CodeAgent Desktop at that endpoint with
`CODEAGENT_SERVICE_ENDPOINT` / `CODEAGENT_SERVICE_TOKEN` /
`CODEAGENT_SERVICE_MODE=SHARED_ATTACH`.

Provenance: the host stamps `business.runtime.provenance.snapshotId`
(`agent-code-boot-<utc>-<guid>`) at startup; override the prefix via
`agentcode.engine.provenance-prefix` or `AGENTCODE_ENGINE_*` env vars.

## License

MIT — see [LICENSE](LICENSE). Engine jars referenced by the build are
distributed under separate proprietary terms.
