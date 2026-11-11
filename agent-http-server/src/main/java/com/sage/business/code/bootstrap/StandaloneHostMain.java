package com.sage.business.code.bootstrap;

import java.util.UUID;

/**
 * Fat-jar entry point for the standalone host: stamps the runtime-provenance
 * properties the fail-fast gate in {@link BusinessAgentServiceMain} requires,
 * then delegates to the real launcher. Any caller that already supplies its own
 * snapshot identity keeps it by passing the same system properties.
 */
public final class StandaloneHostMain {

    private StandaloneHostMain() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("business.runtime.provenance.snapshotId", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.snapshotId",
                    "standalone-host-" + UUID.randomUUID());
        }
        if (System.getProperty("business.runtime.provenance.schemaVersion", "").isEmpty()) {
            System.setProperty("business.runtime.provenance.schemaVersion",
                    RuntimeProvenance.SCHEMA_VERSION);
        }
        BusinessAgentServiceMain.main(args);
    }
}
