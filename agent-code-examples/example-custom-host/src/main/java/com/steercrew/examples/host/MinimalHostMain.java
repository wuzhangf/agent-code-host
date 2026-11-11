package com.steercrew.examples.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sage.business.code.bootstrap.BusinessServiceFactory;
import com.sage.business.code.bootstrap.BusinessServiceFactory.GuiServices;
import com.sage.business.code.bootstrap.RuntimeProvenance;
import com.sage.business.code.bootstrap.ServiceConfig;
import com.sage.business.code.bootstrap.ServiceJsonMapper;
import com.sage.business.code.adapter.http.BusinessHttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * The smallest possible engine host: load the same service.properties the
 * stock launcher consumes, assemble the shared services, and bind the
 * stock HTTP surface. Replace any seam — auth, dispatcher, launch flags —
 * this file is your composition root.
 */
public final class MinimalHostMain {

    private MinimalHostMain() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("business.runtime.provenance.snapshotId", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.snapshotId",
                    "minimal-host-" + UUID.randomUUID());
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
        BusinessHttpServer server = new BusinessHttpServer(
                new InetSocketAddress(host, port), token,
                services.tasks, mapper, null, null, services.v2);
        server.start();
        System.out.println("minimal host on " + host + ":" + port
                + " (v1+v2, bearer auth)");
    }

}
