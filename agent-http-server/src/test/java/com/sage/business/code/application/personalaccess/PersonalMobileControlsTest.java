package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.application.personalaccess.PersonalAccessService.Device;
import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import static org.junit.Assert.*;

/** mobile-controls/1: source-declared capability AND device grant decide each
 *  state; nothing silently reports AVAILABLE and all seven states are
 *  distinguishable. */
public class PersonalMobileControlsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PersonalMobileControls controls = new PersonalMobileControls(mapper, () -> 16 * 1024 * 1024);

    private Device device(String... actions) {
        Device d = new Device();
        d.id = "device-one";
        d.revision = 7;
        d.actions = new HashSet<String>(Arrays.asList(actions));
        d.resources = new java.util.ArrayList<PersonalResourceScope.Resource>();
        d.launchScopes = new java.util.ArrayList<PersonalResourceScope.LaunchScope>();
        return d;
    }

    private String state(ObjectNode view, String action) {
        return view.path("actions").path(action).path("state").asText();
    }

    @Test public void declaredCapabilityAndGrantBothRequiredForAvailable() {
        ObjectNode detail = mapper.createObjectNode();
        detail.putObject("capabilities").put("steer", true);
        ObjectNode sourceActions = detail.putObject("sourceCapabilities").putObject("actions");
        sourceActions.putObject("sideQuestion.create").put("state", "AVAILABLE");
        sourceActions.putObject("handoff.export").put("state", "UNSUPPORTED");
        Device granted = device("OBSERVE", "INTERACT", "HANDOFF");
        ObjectNode view = controls.nativeTask(granted, "store", detail, false, "TRANSCRIPTION_NOT_CONFIGURED");
        assertEquals("AVAILABLE", state(view, "task.message.send"));
        assertEquals("AVAILABLE", state(view, "sideQuestion.create"));
        assertEquals("UNSUPPORTED", state(view, "handoff.prepare"));
        assertEquals("NOT_INTEGRATED", state(view, "collaboration.create"));
        assertEquals("NOT_INTEGRATED", state(view, "transcription.submit"));
        assertEquals("TRANSCRIPTION_NOT_CONFIGURED",
                view.path("actions").path("transcription.submit").path("reasonCode").asText());
    }

    @Test public void missingSourceFieldsNeverSilentlyReportAvailable() {
        ObjectNode detail = mapper.createObjectNode();
        Device granted = device("OBSERVE", "INTERACT", "CONTROL", "APPROVE");
        ObjectNode view = controls.nativeTask(granted, "store", detail, true, null);
        assertEquals("UNKNOWN", state(view, "task.message.send"));
        assertEquals("UNKNOWN", state(view, "execution.stop"));
        assertEquals("NOT_INTEGRATED", state(view, "sideQuestion.create"));
        assertEquals("NOT_INTEGRATED", state(view, "material.upload"));
        // Grant-only integrated routes still resolve from the grant alone.
        assertEquals("AVAILABLE", state(view, "task.input.respond"));
        assertEquals("AVAILABLE", state(view, "transcription.submit"));
    }

    @Test public void grantDenialBeatsSourceReadyAndDeclaredFalseBlocks() {
        ObjectNode detail = mapper.createObjectNode();
        detail.putObject("capabilities").put("stop", true).put("pause", false);
        detail.putObject("sourceCapabilities").putObject("actions")
                .putObject("sideQuestion.create").put("state", "AVAILABLE");
        Device observer = device("OBSERVE");
        ObjectNode view = controls.nativeTask(observer, "store", detail, true, null);
        assertEquals("DENIED", state(view, "sideQuestion.create"));
        assertEquals("INTERACT_REQUIRED", view.path("actions").path("sideQuestion.create").path("reasonCode").asText());
        assertEquals("DENIED", state(view, "execution.stop"));
        assertEquals("BLOCKED_BY_STATE", state(view, "execution.pause"));
    }

    @Test public void acsFoundationRunControlNeedsActiveRunAndDeclaredCapability() {
        ObjectNode foundation = mapper.createObjectNode();
        foundation.putObject("capabilities").put("commandReceiptV1", "READY").put("boundary.contextAdvice", "READY");
        foundation.put("activeRunId", "run-one");
        ObjectNode runControl = foundation.putObject("runControl");
        runControl.putObject("STOP").put("availability", "AVAILABLE");
        runControl.putObject("PAUSE").put("availability", "UNAVAILABLE").put("reasonCode", "RUN_PAUSED");
        Device granted = device("OBSERVE", "INTERACT", "CONTROL", "CREATE");
        ObjectNode view = controls.acsSession(granted, foundation, true, null);
        assertEquals("AVAILABLE", state(view, "execution.stop"));
        assertEquals("BLOCKED_BY_STATE", state(view, "execution.pause"));
        assertEquals("AVAILABLE", state(view, "boundary.contextAdvice"));
        assertEquals("AVAILABLE", state(view, "session.create"));
        foundation.putNull("activeRunId");
        view = controls.acsSession(granted, foundation, true, null);
        assertEquals("BLOCKED_BY_STATE", state(view, "execution.stop"));
        foundation.remove("runControl");
        foundation.put("activeRunId", "run-one");
        view = controls.acsSession(granted, foundation, true, null);
        assertEquals("UNKNOWN", state(view, "execution.stop"));
    }

    @Test public void auxiliarySessionsSuppressMainOnlyActions() {
        ObjectNode foundation = mapper.createObjectNode();
        foundation.put("sessionPurpose", "AUXILIARY_READ_ONLY");
        foundation.put("activeRunId", "run-one");
        foundation.putObject("capabilities").put("boundary.contextAdvice", "READY");
        Device granted = device("OBSERVE", "INTERACT");
        ObjectNode view = controls.acsSession(granted, foundation, false, "TRANSCRIPTION_NOT_CONFIGURED");
        assertEquals("UNSUPPORTED", state(view, "sideQuestion.create"));
        assertEquals("UNSUPPORTED", state(view, "sideQuestion.deliver"));
        assertEquals("UNSUPPORTED", state(view, "contextPackage.create"));
    }

    @Test public void projectionStatesStayInsideTheSevenStateModel() {
        Device granted = device("OBSERVE", "INTERACT", "CONTROL", "APPROVE", "COLLABORATE", "ADOPT", "HANDOFF", "CREATE", "LAUNCH");
        granted.launchScopes.add(new PersonalResourceScope.LaunchScope("store", "project-one", "macos"));
        ObjectNode detail = mapper.createObjectNode();
        detail.put("engineId", "macos").put("recipeID", "mac.native-work/1").put("projectId", "project-one");
        detail.putObject("capabilities").put("steer", true).put("stop", true);
        ObjectNode scActions = detail.putObject("sourceCapabilities").putObject("actions");
        scActions.putObject("sideQuestion.create").put("state", "AVAILABLE");
        // Real worker vocabulary: a sealed source blocks the seal step, so
        // prepare stays BLOCKED_BY_STATE — the old flat "DISABLED" token
        // never existed on the wire.
        scActions.putObject("handoff.export").put("state", "AVAILABLE");
        scActions.putObject("handoff.seal").put("state", "BLOCKED_BY_STATE").put("reasonCode", "SOURCE_ALREADY_SEALED");
        ObjectNode view = controls.nativeTask(granted, "store", detail, true, null);
        assertEquals("mobile-controls/1", view.path("schemaVersion").asText());
        assertEquals("AVAILABLE", state(view, "material.upload"));
        assertEquals("BLOCKED_BY_STATE", state(view, "handoff.prepare"));
        for (java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> it = view.path("actions").elements(); it.hasNext(); )
            assertTrue(PersonalMobileControls.STATES.contains(it.next().path("state").asText()));
        Device denied = device("OBSERVE");
        view = controls.nativeTask(denied, "store", detail, true, null);
        assertEquals("DENIED", state(view, "material.upload"));
    }

    @Test public void actionRowsProjectLimits() {
        // §4.2 mobile-controls/1 declares per-action `limits`; each projected
        // bound is one the called route actually enforces.
        ObjectNode foundation = mapper.createObjectNode();
        foundation.putObject("capabilities").put("observationSnapshotV1", "READY");
        foundation.put("activeRunId", "run-one");
        ObjectNode view = controls.acsSession(device("OBSERVE", "INTERACT"), foundation, true, null);
        assertEquals("mobile-controls/1", view.path("schemaVersion").asText());
        JsonNode limits = view.path("actions").path("session.message.send").path("limits");
        assertEquals(16 * 1024 * 1024, limits.path("maxRequestBodyBytes").asInt());
        for (java.util.Iterator<Map.Entry<String, JsonNode>> it = view.path("actions").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> row = it.next();
            assertTrue(row.getKey() + " must carry its limits", row.getValue().has("limits"));
        }
    }

    @Test public void observationSnapshotGrantMatchesThePacRoute() {
        // The PAC route POST /personal/v1/agent/sessions/{id}/observation-snapshots
        // is authorized by OBSERVE (PersonalAccessServer keeps the default
        // action) — the controls row must gate on the same grant.
        ObjectNode foundation = mapper.createObjectNode();
        foundation.putObject("capabilities").put("observationSnapshotV1", "READY");
        foundation.put("activeRunId", "run-one");
        ObjectNode view = controls.acsSession(device("OBSERVE"), foundation, true, null);
        assertEquals("AVAILABLE", state(view, "observationSnapshot.create"));
        assertEquals("DENIED", state(controls.acsSession(
                device(), foundation, true, null), "observationSnapshot.create"));
    }
}
