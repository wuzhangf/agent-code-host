package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

/** Real native writes and checkpoint mutation history survive a profile continuation with one writer lease. */
public class WritableAssignmentProtocolTest extends BoundaryAssignmentProtocolTest {
    private final List<JsonNode> observed=new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean writeScenario(JsonNode request){return request.toString().contains("Write counter.txt as counter=1");}
    @Override protected void observeModelRequest(JsonNode request){if(writeScenario(request))observed.add(request);else super.observeModelRequest(request);}
    @Override protected ObjectNode replyForModel(JsonNode request){
        if(!writeScenario(request))return super.replyForModel(request);
        int n=modelCalls.get();if(n==1||n==3){ObjectNode reply=mapper.createObjectNode().put("role","assistant").put("content","");ObjectNode call=mapper.createObjectNode().put("index",0).put("id","write-"+n).put("type","function");
            call.set("function",mapper.createObjectNode().put("name","workspace_write").put("arguments","{\"path\":\"counter.txt\",\"content\":\"counter="+(n==1?1:2)+"\"}"));reply.putArray("tool_calls").add(call);return reply;}
        return mapper.createObjectNode().put("role","assistant").put("content",n==2?"LATE_SOURCE_FINAL_MUST_NOT_PUBLISH":"TARGET_COMPLETED_ORIGINAL_WRITE_TASK");
    }
    @Test public void profileContinuationPreservesWriteScopeMutationHistoryAndSpentBudget()throws Exception{
        ObjectNode setup=mapper.createObjectNode().put("workspaceAlias","sample").put("intent","IMPLEMENT").put("executionMode","MODIFY").put("interactionMode","ACT").put("autonomyLevel","LOW").put("verificationLevel","COMPILE").put("maxModelTurns",8).put("maxEditAttempts",3).put("initialWorkspaceListing",false);setup.putArray("writeAllow").add("counter.txt");
        JsonNode parent=api("POST","/api/v2/sessions","writer-session",a,setup,201);String sid=parent.path("sessionId").asText();
        String rid=api("POST","/api/v2/sessions/"+sid+"/messages","writer-start",a,mapper.createObjectNode().put("clientMessageId","writer-start").put("expectedSessionVersion",parent.path("sessionVersion").asLong()).put("content","Write counter.txt as counter=1, inspect it, then update it to counter=2 and explain"),202).path("runId").asText();
        await(()->modelCalls.get()==1,"Source write task did not start");releaseModel.release();await(()->modelCalls.get()==2,"Source did not continue after its first native write");
        com.sage.business.code.domain.RunRecord original=fixtureServices().tasks.observationRun(rid);String fingerprint=fixtureServices().tasks.revision(original.taskId,original.taskRevision).fingerprint;
        Assert.assertEquals("counter=1",Files.readString(Paths.get(original.productWorkspaceRoot).resolve("counter.txt")));
        String revision=null;for(JsonNode profile:api("GET","/api/v2/agent-profiles",null,b,null,200).path("profiles"))if("reviewer".equals(profile.path("id").asText()))revision=profile.path("revision").asText();
        ObjectNode request=mapper.createObjectNode().put("transitionId","writer-handoff");request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",revision);
        JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedExecutionEpoch","1").put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());
        api("POST","/api/v2/runs/"+rid+"/assignment-transitions","writer-handoff",b,request,202);releaseModel.release();await(()->modelCalls.get()==3,"Target writer did not continue");
        await(()->"ACTIVE".equals(api("GET","/api/v2/runs/"+rid+"/assignment-transitions/writer-handoff",null,b,null,200).path("state").asText()),"Target writer activation not proven");
        Assert.assertTrue(observed.get(2).toString().contains("TARGET_PROFILE_REVIEWER"));Assert.assertTrue(observed.get(2).toString().contains("counter=1"));releaseModel.release();
        await(()->modelCalls.get()==4,"Target did not continue after its native write");
        com.sage.business.code.domain.RunRecord completed=fixtureServices().tasks.observationRun(rid);Assert.assertEquals(original.taskId,completed.taskId);Assert.assertEquals(original.taskRevision,completed.taskRevision);Assert.assertEquals(fingerprint,fixtureServices().tasks.revision(completed.taskId,completed.taskRevision).fingerprint);
        Assert.assertEquals(original.productWorkspaceRoot,completed.productWorkspaceRoot);Assert.assertEquals("counter=2",Files.readString(Paths.get(completed.productWorkspaceRoot).resolve("counter.txt")));Assert.assertEquals(4,modelCalls.get());
        com.agent.product.code.runtime.PersistentCodeCheckpointStore.Record latest=new com.agent.product.code.runtime.PersistentCodeCheckpointStore(completed.productWorkspaceRoot).loadLatest(null);
        Map<?,?> budget=(Map<?,?>)latest.getCheckpoint().getCollaboration().get("modelBudget");Assert.assertTrue(((Number)budget.get("httpRequests")).intValue()>=3);Assert.assertEquals("2",latest.getCheckpoint().getCollaboration().get("executionEpoch"));
        command(rid,"writer-priority-stop","STOP",-1,a);releaseModel.release(10);await(()->"APPLIED".equals(api("GET","/api/v2/runs/"+rid+"/commands/writer-priority-stop",null,b,null,200).path("phase").asText()),"STOP did not settle the original writer");
        for(JsonNode message:api("GET","/api/v2/sessions/"+sid+"/messages?limit=100",null,b,null,200).path("items"))if("COMPLETED".equals(message.path("status").asText()))Assert.assertFalse(message.path("content").asText().contains("LATE_SOURCE_FINAL_MUST_NOT_PUBLISH"));
    }
}
