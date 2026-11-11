package com.sage.business.code.bootstrap;

import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sage.business.code.adapter.http.ExecutionPermissionApiHandler;
import com.sage.business.code.application.TaskApplicationService;
import com.sage.business.code.application.parallel.ParallelPlanApplicationService;
import com.sage.business.code.infrastructure.WorkspaceRegistry;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Host-side bridge into the engine's bootstrap package. Lives in the engine's
 * own package deliberately: {@link ServiceConfig} exposes its parsed fields
 * package-privately, so the only clean way to consume them outside the engine
 * jar is a same-package bridge. Keeps every engine call in one file so the
 * Spring host never touches bootstrap internals directly.
 *
 * <p>Sequence mirrors {@link BusinessAgentServiceMain}: provenance first,
 * then file-based config, logging, service graph, HTTP surface. The phone
 * personal-access surface is intentionally not wired yet.
 */
public final class EngineServiceBridge {

    private ServiceConfig config;
    private BusinessServiceFactory.GuiServices services;
    private BusinessHttpServer server;

    public void start(String configPath, String provenancePrefix) throws Exception {
        // Same provenance contract the standalone launcher stamps: without it
        // the engine boots but readiness reports an unproven runtime.
        System.setProperty("business.runtime.provenance.snapshotId",
                provenancePrefix + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC).format(Instant.now())
                + "-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("business.runtime.provenance.schemaVersion",
                RuntimeProvenance.SCHEMA_VERSION);
        BusinessAgentServiceMain.requireRuntimeProvenance();

        ServiceConfig c = ServiceConfig.load(new String[] { "--config", configPath });
        boolean loopbackHost = "127.0.0.1".equals(c.host) || "localhost".equals(c.host);
        if (!loopbackHost && !"container".equalsIgnoreCase(
                System.getProperty("deployment.profile", ""))) {
            throw new IllegalArgumentException("server.host must be loopback"
                    + " (-Ddeployment.profile=container allows container binds)");
        }
        String bindHost = loopbackHost ? "127.0.0.1" : c.host;

        LoggingBootstrap.initialize(c.logDirectory, c.logLevel,
                c.comparisonContentEnabled, c.commandOutputEnabled,
                c.maxContentChars, c.maxFileSize, c.maxHistory, c.totalSizeCap);

        final BusinessServiceFactory.GuiServices s = BusinessServiceFactory.createGui(c);
        final TaskApplicationService app = s.tasks;

        final ParallelPlanApplicationService parallel =
                ParallelPlanApplicationService.fromSystemProperties(
                        new WorkspaceRegistry(c.workspaces));
        parallel.setExecutionPolicyResolver(s.executionPolicyResolver,
                ExecutionPermissionApiHandler.LOCAL_SERVICE_PRINCIPAL);
        parallel.setModelPort(s.modelContext.port());

        this.server = new BusinessHttpServer(
                new InetSocketAddress(bindHost, c.port), c.token, app,
                BusinessServiceFactory.mapper(), s.adapter, parallel, s.v2,
                s.sandbox, s.clientSessions, s.registrationRequiredForMutation,
                c.getMultiClient());
        this.services = s;
        this.config = c;
        server.start();
    }

    /** Shutdown order mirrors BusinessAgentServiceMain's shutdown hook. */
    public void stop() {
        if (services == null) {
            return;
        }
        if (services.catalogSynchronizer != null) {
            services.catalogSynchronizer.close();
        }
        services.terminals.close();
        services.mcp.close();
        services.hooks.close();
        server.close();
        services.missionScheduler.close();
        services.executionApprovalDispatcher.close();
        services.executionApprovalScheduler.close();
        services.tasks.close();
        services.gui.close();
        services = null;
        server = null;
        config = null;
    }

    public int port() {
        return config != null ? config.port : -1;
    }
}
