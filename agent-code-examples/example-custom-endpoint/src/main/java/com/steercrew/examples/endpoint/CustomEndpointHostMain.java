package com.steercrew.examples.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.bootstrap.BusinessServiceFactory;
import com.sage.business.code.bootstrap.BusinessServiceFactory.GuiServices;
import com.sage.business.code.bootstrap.RuntimeProvenance;
import com.sage.business.code.bootstrap.ServiceConfig;
import com.sage.business.code.bootstrap.ServiceJsonMapper;
import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sun.net.httpserver.HttpHandler;
import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Standalone host that serves the full stock /api/v2 surface plus
 * community endpoints under /api/v2/example/*. Run:
 *
 *   java -jar example-custom-endpoint-*-standalone.jar --config service.properties
 *   curl -H "Authorization: Bearer <token>" http://127.0.0.1:8766/api/v2/example/ping
 *
 * The host reads server.host/server.port/server.token straight from its
 * own service.properties — your composition root owns its config.
 */
public final class CustomEndpointHostMain {

    private CustomEndpointHostMain() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("business.runtime.provenance.snapshotId", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.snapshotId",
                    "example-endpoint-" + UUID.randomUUID());
        }
        if (System.getProperty("business.runtime.provenance.schemaVersion", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.schemaVersion",
                    RuntimeProvenance.SCHEMA_VERSION);
        }
        ServiceConfig config = ServiceConfig.load(args);
        String host = config.getHost();
        int port = config.getPort();
        String token = config.getToken();
        GuiServices services = BusinessServiceFactory.createGui(config);
        ObjectMapper mapper = ServiceJsonMapper.mapper();
        HttpHandler v2 = new ExampleEndpoints(services.v2, mapper);
        BusinessHttpServer server = new BusinessHttpServer(
                new InetSocketAddress(host, port), token,
                services.tasks, mapper, null, null, v2);
        server.start();
        System.out.println("example host listening on " + host + ":" + port
                + " — try GET /api/v2/example/ping");
    }

}
