package com.sage.business.code.bootstrap;

import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sage.business.code.application.TaskApplicationService;
import com.sage.business.code.application.TaskCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

/** Foreground local-service launcher. Configuration is intentionally file based so secrets stay out of argv. */
public final class BusinessAgentServiceMain {
    private BusinessAgentServiceMain() { }

    /** Package-private for the provenance fail-fast characterization test. */
    static void requireRuntimeProvenance() {
        String snapshotId = System.getProperty(
                "business.runtime.provenance.snapshotId", "");
        String schemaVersion = System.getProperty(
                "business.runtime.provenance.schemaVersion", "");
        if ("false".equalsIgnoreCase(
                System.getProperty("business.runtime.provenance.required", "true"))) {
            return;
        }
        if (snapshotId.trim().isEmpty()
                || !"business-runtime-provenance/1".equals(schemaVersion)) {
            throw new IllegalStateException(
                    "RUNTIME_PROVENANCE_REQUIRED: startup requires non-empty "
                    + "business.runtime.provenance.snapshotId and schemaVersion="
                    + RuntimeProvenance.SCHEMA_VERSION);
        }
    }

    /**
     * Design 10 §5.2: container deployment opts in via
     * {@code -Ddeployment.profile=container}. Local development keeps the
     * loopback rule; a container network isolates the service instead.
     */
    static boolean containerDeploy() {
        return "container".equalsIgnoreCase(
                System.getProperty("deployment.profile", ""));
    }

    public static void main(String[] args) throws Exception {
        if ("CORE_RELEASE_ONLY".equals(System.getProperty("acs.release.mode",System.getenv("ACS_RELEASE_MODE")))) {
            CoreReleaseHostMain.main(args);return;
        }

        // WU-02: a service that cannot prove which snapshot it runs is not a
        // launchable service.  Fail fast before any port, log or state store
        // is touched; non-golden channels may opt out explicitly with
        // business.runtime.provenance.required=false but readiness will then
        // stay NOT_READY.
        requireRuntimeProvenance();
        ServiceConfig c = ServiceConfig.load(args);
        boolean loopbackHost = "127.0.0.1".equals(c.host) || "localhost".equals(c.host);
        if (!loopbackHost && !containerDeploy()) {
            throw new IllegalArgumentException("server.host must be loopback"
                    + " (-Ddeployment.profile=container allows container binds)");
        }
        String bindHost = loopbackHost ? "127.0.0.1" : c.host;
        // Model catalog validity is decided inside createGui: the
        // database is the catalog authority once imported, and a missing
        // or malformed catalog boots the service degraded (MODEL feature
        // not ready) so the Settings surface stays reachable to repair
        // it — startup must not die before the feature can be managed.
        LoggingBootstrap.initialize(c.logDirectory, c.logLevel,
                c.comparisonContentEnabled, c.commandOutputEnabled, c.maxContentChars,
                c.maxFileSize, c.maxHistory, c.totalSizeCap);
        final Logger log = LoggerFactory.getLogger(BusinessAgentServiceMain.class);
        final BusinessServiceFactory.GuiServices services =
                BusinessServiceFactory.createGui(c);
        final TaskApplicationService app = services.tasks;
        // Review HOST-P0-01: the parallel-plan Host entry rides the
        // SAME production HTTP surface; disabled/missing-config keeps
        // it registered but every call fails closed. The workspace
        // registry gates propose/execute to registered aliases only.
        final com.sage.business.code.application.parallel.ParallelPlanApplicationService
                parallel =
                com.sage.business.code.application.parallel.ParallelPlanApplicationService
                        .fromSystemProperties(
                                new com.sage.business.code.infrastructure.WorkspaceRegistry(
                                        c.workspaces));
        // Sessions holding an active FULL_ACCESS grant auto-approve exact
        // proposal fingerprints server-side; anything else stays gated.
        parallel.setExecutionPolicyResolver(services.executionPolicyResolver,
                com.sage.business.code.spi.LocalAccessPrincipals.LOCAL_SERVICE_PRINCIPAL);
        // The parallel plane shares the process-wide effective model port
        // (design 2026-10-08 §8.2): the merged file+database catalog with
        // resolved secrets — never a sysprop re-read that could not see
        // database-only providers.
        parallel.setModelPort(services.modelContext.port());
        final BusinessHttpServer server = new BusinessHttpServer(
                new InetSocketAddress(bindHost, c.port), c.token, app,
                BusinessServiceFactory.mapper(), services.adapter, parallel, services.v2,
                services.sandbox, services.clientSessions,
                services.registrationRequiredForMutation, c.getMultiClient());
        if (c.personalAccess.enabled) {
            try { server.personalAccess(c.personalAccess, c.getPersonalNoticeSettings(), c.stateDir); }
            catch (Exception unavailable) { log.warn("personal.access unavailable; local service remains active"); }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                log.info("service.stop event=service.stop");
                // MA04: stop the enterprise poll loop first — a tick in
                // flight must not publish while the rest of the plane is
                // already being torn down.
                if (services.catalogSynchronizer != null) {
                    services.catalogSynchronizer.close();
                }
                services.sessions.closeOrdinaryTurns();
                services.terminals.close();
                services.mcp.close();
                services.hooks.close();
                server.close();
                services.missionScheduler.close();
                services.executionApprovalDispatcher.close();
                services.executionApprovalScheduler.close();
                app.close();
                services.gui.close();
            }
        }, "business-agent-shutdown"));
        server.start();
        log.info("service.ready event=service.ready port={} logDir={} productVersion={}",
                c.port, c.logDirectory, TaskCanonicalizer.PRODUCT_VERSION);
        new CountDownLatch(1).await();
    }

    /** @deprecated use {@link ServiceConfig}; retained for source compatibility. */
    @Deprecated
    static final class Config extends ServiceConfig {
        public static Config load(String[] args) throws java.io.IOException {
            return new Config(ServiceConfig.load(args));
        }

        private Config(ServiceConfig source) {
            super(source.host, source.port, source.token, source.stateDir,
                    source.modelProperties, source.workspaces, source.logDirectory,
                    source.logLevel, source.comparisonContentEnabled,
                    source.commandOutputEnabled, source.maxContentChars,
                    source.process, source.trace, source.maxFileSize,
                    source.maxHistory, source.totalSizeCap,
                    source.guiDefaultWorkspace, source.guiDefaultWriteAllow,
                    source.guiDefaultWriteAllowAuto,
                    source.workerCount, source.queueCapacity, source.memory,
                    source.sandbox, source.getSource(), source.getMcp(),
                    source.isSkillsCatalogEnabled(), source.getHooks(),
                    source.getReadOnlySubagent(),
                    source.getWriteApprovalMode(),
                    source.getWriteApprovalTimeoutMillis(),
                    source.getJavaSemantic(), source.getTerminal(),
                    source.getMultiClient(), source.getWeb());
            this.environmentOverlay = source.getEnvironmentOverlay();
            this.agentProfilesDirectory = source.getAgentProfilesDirectory();
            setCatalogFileProperties(source.getCatalogFileProperties());
        }
    }
}
