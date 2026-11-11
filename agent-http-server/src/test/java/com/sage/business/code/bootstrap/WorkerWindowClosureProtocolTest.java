package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.*;
import java.util.UUID;

/** Real HTTP admission and the actual paused Product cannot reopen a settled Worker attempt. */
public class WorkerWindowClosureProtocolTest extends FoundationProtocolAcceptanceTest {
    @Test public void closingTheOriginalWindowIsDurableIrreversibleAndCannotResumeModels()throws Exception {
        ObjectNode request=mapper.createObjectNode().put("workspaceAlias","sample").put("interactionMode","ACT").put("intent","EXPLAIN").put("executionMode","INSPECT").put("autonomyLevel","OFF").put("verificationLevel","NONE").put("initialWorkspaceListing",false).put("maxModelTurns",5).put("executionOwnership","WORKER_ATTEMPT");
        request.set("executionBinding",mapper.readTree("{\"schemaVersion\":\"worker-session-binding/1\",\"workerId\":\"worker-one\",\"workerTaskId\":\"task-one\",\"attemptId\":\"attempt-one\",\"attemptEpoch\":\"1\",\"workspaceId\":\"workspace-one\",\"workspaceGeneration\":\"1\",\"provider\":\"agentcode\"}"));
        JsonNode session=api("POST","/api/v2/sessions","closing-session",a,request,201);String sid=session.path("sessionId").asText();
        String id=UUID.randomUUID().toString();ObjectNode registration=mapper.createObjectNode().put("registrationKey",id).put("clientId",id).put("processInstanceId",id).put("clientKind","WORKER");registration.putArray("protocols").add("v2");String worker=api("POST","/api/v2/client-sessions",id,null,registration,201).path("clientSessionToken").asText();
        String path="/api/v2/sessions/"+sid+"/worker-execution-window";ObjectNode window=mapper.createObjectNode().put("schemaVersion","worker-execution-window/1").put("runCommandId","closing-start").put("deadlineEpochMillis",Long.toString(System.currentTimeMillis()+120000));window.set("executionBinding",request.path("executionBinding"));api("POST",path,"window-closing",worker,window,200);
        JsonNode accepted=api("POST","/api/v2/sessions/"+sid+"/messages","closing-start",a,mapper.createObjectNode().put("clientMessageId","closing-start").put("content","Explain the README").put("expectedSessionVersion",session.path("sessionVersion").asLong()),202);String rid=accepted.path("runId").asText();await(()->modelCalls.get()==1,"Actual Product did not start");
        command(rid,"closing-pause","PAUSE",api("GET","/api/v2/runs/"+rid,null,b,null,200).path("stateVersion").asLong(),a);releaseModel.release();await(()->"PAUSED_FOR_HUMAN".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Product did not pause");
        api("POST",path+"/close","deny-desktop",a,window,403);
        JsonNode closed=api("POST",path+"/close","closing-cut",worker,window,200);Assert.assertTrue(closed.path("noForwardWrites").asBoolean());Assert.assertEquals("CLOSING",closed.path("state").asText());
        Assert.assertEquals(closed,api("POST",path+"/close","closing-cut",worker,window,200));Assert.assertEquals(closed,api("GET",path,null,b,null,200));Assert.assertEquals(closed,api("POST",path,"window-closing",worker,window,200));
        ObjectNode wrong=window.deepCopy().put("deadlineEpochMillis",Long.toString(System.currentTimeMillis()+240000));Assert.assertEquals("EXECUTION_OWNERSHIP_MISMATCH",api("POST",path+"/close","wrong-window",worker,wrong,409).path("code").asText());
        try{fixtureServices().tasks.applyControlCommand(rid,"closed-resume","RESUME",null,fixtureServices().tasks.observationRun(rid).stateVersion,"closed-resume");Assert.fail("Closed attempt resumed");}catch(com.sage.business.code.domain.BusinessException failure){Assert.assertEquals("WORKER_ATTEMPT_CLOSING",failure.getCode());}
        String evidence="/api/v2/runs/"+rid+"/collaboration-evidence";ObjectNode export=mapper.createObjectNode().put("schemaVersion","collaboration-evidence-export-request/1").put("exportId","closing-cut-evidence");api("POST",evidence,"closing-cut-evidence",a,export,403);JsonNode sealed=api("POST",evidence,"closing-cut-evidence",worker,export,200);Assert.assertEquals("READY",sealed.path("state").asText());Assert.assertEquals(sealed,api("POST",evidence,"closing-cut-evidence",worker,export,200));Assert.assertFalse(sealed.toString().contains("sourceRoot"));JsonNode bytes=api("GET",evidence+"/closing-cut-evidence/bytes?offset=0",null,worker,null,200);Assert.assertTrue(java.util.Base64.getDecoder().decode(bytes.path("content").asText()).length>0);
        ObjectNode ack=mapper.createObjectNode().put("schemaVersion","collaboration-evidence-upload-ack/1").put("exportId","closing-cut-evidence").put("digest",sealed.path("digest").asText()).put("manifestDigest",sealed.path("manifestDigest").asText()).put("sizeBytes",sealed.path("sizeBytes").asText()).put("artifactId","provider-evidence-attempt-one").put("receiptRef","artifact-receipt:one");Assert.assertEquals("UPLOAD_ACKNOWLEDGED",api("POST",evidence+"/closing-cut-evidence/upload-ack","ack-closing-cut-evidence",worker,ack,200).path("state").asText());
        Assert.assertEquals(1,modelCalls.get());command(rid,"closing-stop","STOP",-1,a);
    }
}
