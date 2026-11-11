package com.sage.business.code.bootstrap;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * WU-02 characterization: the service must refuse to start without a bound
 * runtime provenance snapshot, and the opt-out switch must be explicit.
 */
public class BusinessAgentServiceMainProvenanceTest {
    private String previousSnapshotId;
    private String previousSchemaVersion;
    private String previousRequired;

    @Before
    public void rememberProperties() {
        previousSnapshotId = System.getProperty("business.runtime.provenance.snapshotId");
        previousSchemaVersion = System.getProperty("business.runtime.provenance.schemaVersion");
        previousRequired = System.getProperty("business.runtime.provenance.required");
    }

    @After
    public void restoreProperties() {
        restore("business.runtime.provenance.snapshotId", previousSnapshotId);
        restore("business.runtime.provenance.schemaVersion", previousSchemaVersion);
        restore("business.runtime.provenance.required", previousRequired);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    public void startupFailsWhenSnapshotIdMissing() {
        System.setProperty("business.runtime.provenance.snapshotId", "");
        System.setProperty("business.runtime.provenance.schemaVersion",
                RuntimeProvenance.SCHEMA_VERSION);
        System.clearProperty("business.runtime.provenance.required");
        try {
            BusinessAgentServiceMain.requireRuntimeProvenance();
            Assert.fail("empty snapshotId must fail startup");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().startsWith("RUNTIME_PROVENANCE_REQUIRED"));
        }
    }

    @Test
    public void startupFailsWhenSchemaVersionMissing() {
        System.setProperty("business.runtime.provenance.snapshotId", "business-test");
        System.setProperty("business.runtime.provenance.schemaVersion", "");
        System.clearProperty("business.runtime.provenance.required");
        try {
            BusinessAgentServiceMain.requireRuntimeProvenance();
            Assert.fail("empty schemaVersion must fail startup");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().startsWith("RUNTIME_PROVENANCE_REQUIRED"));
        }
    }

    @Test
    public void startupPassesWithBoundProvenance() {
        System.setProperty("business.runtime.provenance.snapshotId", "business-test");
        System.setProperty("business.runtime.provenance.schemaVersion",
                RuntimeProvenance.SCHEMA_VERSION);
        System.clearProperty("business.runtime.provenance.required");
        BusinessAgentServiceMain.requireRuntimeProvenance();
    }

    @Test
    public void explicitOptOutIsHonoured() {
        System.setProperty("business.runtime.provenance.snapshotId", "");
        System.setProperty("business.runtime.provenance.schemaVersion", "");
        System.setProperty("business.runtime.provenance.required", "false");
        BusinessAgentServiceMain.requireRuntimeProvenance();
    }
}
