package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

public class BoundaryAssignmentProtocolTest extends FoundationProtocolAcceptanceTest {
    private volatile boolean scripted;
    private volatile boolean repeatAfterModelChange;
    private String targetRevision;
    private final List<JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
    @Override protected void configureCollaboration(ServiceConfig config){
        config.collaborationBoundaryEnabled=true;config.collaborationHandoffEnabled=true;
        try{Path dir=config.getAgentProfilesDirectory();Files.createDirectories(dir);
            mapper.writeValue(dir.resolve("reviewer.json").toFile(),mapper.createObjectNode().put("schemaVersion","agent-profile/1.1").put("id","reviewer").put("name","Reviewer").put("description","A pinned read-only reviewer specialty")
                    .put("instructions","TARGET_PROFILE_REVIEWER: Inspect carefully and retain the existing task contract."));
            mapper.writeValue(dir.resolve("document-only.json").toFile(),mapper.createObjectNode().put("schemaVersion","agent-profile/1.1").put("id","document-only").put("name","Document").put("description","Different tool preset").put("executionToolPreset","DOCUMENT_DESIGN_V1").put("instructions","Analyze documentation only"));
            targetRevision=com.sage.business.code.application.session.AgentProfileCatalog.load(dir,mapper).resolve("reviewer").getRevision();
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    @After public void captureCollaborationFacts()throws Exception {
        if(root==null)return;
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"))){
            for(String table:new String[]{"boundary_requests","assignment_transitions","execution_assignments"})
                try(java.sql.Statement q=c.createStatement();java.sql.ResultSet r=q.executeQuery("SELECT payload_json FROM "+table)){
                    while(r.next())System.out.println("COLLABORATION_FACT "+table+" "+r.getString(1));
                }
        }
        Path runs=root.resolve("state/runs");if(Files.isDirectory(runs))try(java.util.stream.Stream<Path> dirs=Files.list(runs)){
            dirs.forEach(dir->{com.sage.business.code.domain.RunRecord run=new com.sage.business.code.infrastructure.AtomicJsonStore(root.resolve("state"),mapper).read("runs/"+dir.getFileName()+"/state.json",com.sage.business.code.domain.RunRecord.class);
                if(run!=null)System.out.println("COLLABORATION_RUN "+run.status+" "+run.failureCode+" "+run.failureMessage);
                if(run!=null&&run.productWorkspaceRoot!=null){
                    com.agent.product.code.runtime.PersistentCodeCheckpointStore store=new com.agent.product.code.runtime.PersistentCodeCheckpointStore(run.productWorkspaceRoot);
                    try{com.agent.product.code.runtime.PersistentCodeCheckpointStore.Record latest=store.loadLatest(null);if(latest!=null)System.out.println("COLLABORATION_PRODUCT "+mapper.writeValueAsString(latest.getCheckpoint().getCollaboration()));}catch(Exception ignored){}
                }});
        }
        Path logs=root.resolve("logs");if(Files.isDirectory(logs))try(java.util.stream.Stream<Path> logFiles=Files.walk(logs)){
            logFiles.filter(Files::isRegularFile).forEach(log->{try{Files.lines(log).filter(line->line.contains("transaction failed")||line.contains("SQLITE_")).forEach(line->System.out.println("COLLABORATION_SQL "+line));}catch(Exception ignored){}});
        }
        System.out.println("COLLABORATION_MODEL_CALLS "+modelCalls.get());
    }
    @Override protected void observeModelRequest(JsonNode request){requests.add(request);}
    @Override protected ObjectNode replyForModel(JsonNode request){
        if(scripted&&(modelCalls.get()==1||repeatAfterModelChange&&modelCalls.get()==2)){ObjectNode message=mapper.createObjectNode().put("role","assistant").put("content","");
            ObjectNode call=mapper.createObjectNode().put("index",0).put("id","read-before-boundary").put("type","function");
            call.set("function",mapper.createObjectNode().put("name","workspace_read").put("arguments","{\"path\":\"README.md\"}"));message.putArray("tool_calls").add(call);return message;}
        return super.replyForModel(request);
    }
    private String startMain()throws Exception{
        ObjectNode setup=mapper.createObjectNode().put("workspaceAlias","sample").put("interactionMode","ACT").put("intent","EXPLAIN").put("executionMode","INSPECT");setup.putArray("writeAllow");
        String sid=api("POST","/api/v2/sessions","main-session",a,setup,201).path("sessionId").asText();
        String rid=api("POST","/api/v2/sessions/"+sid+"/messages","main-start",a,mapper.createObjectNode().put("clientMessageId","main-start").put("expectedSessionVersion",api("GET","/api/v2/sessions/"+sid,null,a,null,200).path("sessionVersion").asLong()).put("content","Inspect README and explain it"),202).path("runId").asText();
        await(()->{JsonNode current=api("GET","/api/v2/runs/"+rid,null,b,null,200);if(java.util.Arrays.asList("FAILED","NEEDS_ATTENTION").contains(current.path("sourceStatus").asText()))Assert.fail(current.toString());return modelCalls.get()>=1;},"No Product model request");return rid;
    }
    @Test public void mainReportAndAdviceArePersistedAtNextProductBoundary()throws Exception{
        scripted=true;String rid=startMain();
        ObjectNode report=mapper.createObjectNode().put("requestId","report-one").put("kind","REQUEST_REPORT").put("expectedExecutionEpoch","1").put("text","Report progress then continue");
        api("POST","/api/v2/runs/"+rid+"/boundary-requests","report-one",b,report,202);
        ObjectNode advice=mapper.createObjectNode().put("requestId","advice-one").put("kind","CONTEXT_ADVICE").put("expectedExecutionEpoch","1").put("text","ADVICE_FROZEN: mention the existing README");
        api("POST","/api/v2/runs/"+rid+"/boundary-requests","advice-one",a,advice,202);
        Assert.assertEquals("PENDING",api("GET","/api/v2/runs/"+rid+"/boundary-requests/report-one",null,b,null,200).path("state").asText());
        releaseModel.release(10);
        await(()->"ANSWERED".equals(api("GET","/api/v2/runs/"+rid+"/boundary-requests/report-one",null,b,null,200).path("state").asText()),"Main report did not publish");
        await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Main did not continue after report");
        Assert.assertEquals("CONSUMED",api("GET","/api/v2/runs/"+rid+"/boundary-requests/advice-one",null,b,null,200).path("state").asText());
        Assert.assertTrue(requests.stream().anyMatch(v->v.toString().contains("ADVICE_FROZEN")));Assert.assertTrue(modelCalls.get()>=3);
    }
    @Test public void profileHandoffRetainsSameRunAndUsesTargetInstructions()throws Exception{
        String rid=startMain();JsonNode run=api("GET","/api/v2/runs/"+rid,null,b,null,200);String sid=run.path("sessionId").asText();
        JsonNode settings=api("GET","/api/v2/sessions/"+sid+"/model-settings",null,b,null,200);
        ObjectNode request=mapper.createObjectNode().put("transitionId","handoff-one");ObjectNode target=mapper.createObjectNode().put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        request.set("target",target);JsonNode preflight=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);
        request.put("expectedRunStateVersion",preflight.path("runStateVersion").asText()).put("expectedExecutionEpoch","1").put("expectedModelSettingsRevision",settings.path("settingsRevision").asText())
                .put("preflightDigest",preflight.path("preflightDigest").asText());
        api("POST","/api/v2/runs/"+rid+"/assignment-transitions","handoff-one",b,request,202);
        await(()->"PAUSE_REQUESTED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("controlState").asText()),"Controlled pause was not dispatched");releaseModel.release();
        String transition="/api/v2/runs/"+rid+"/assignment-transitions/handoff-one";
        await(()->modelCalls.get()>=2,"Target profile did not resume Product: "+api("GET",transition,null,b,null,200));
        await(()->"ACTIVE".equals(api("GET",transition,null,b,null,200).path("state").asText()),"Target startup proof did not settle");
        Assert.assertTrue(requests.get(1).toString().contains("TARGET_PROFILE_REVIEWER"));
        JsonNode identity=api("GET","/api/v2/runs/"+rid+"/execution-segments",null,b,null,200).path("current");Assert.assertEquals("2",identity.path("executionEpoch").asText());
        Assert.assertEquals(rid,identity.path("runId").asText());releaseModel.release(10);
        await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Target did not finish original Run");
        Assert.assertEquals("handoff-one",api("POST","/api/v2/runs/"+rid+"/assignment-transitions","handoff-one",a,request,202).path("transitionId").asText());
    }
    @Test public void incompatiblePreflightHasNoControlEffectsAndStopSupersedesAcceptedHandoff()throws Exception{
        String rid=startMain();JsonNode profiles=api("GET","/api/v2/agent-profiles",null,a,null,200);String differentRevision=null;
        for(JsonNode profile:profiles.path("profiles"))if("document-only".equals(profile.path("id").asText()))differentRevision=profile.path("revision").asText();Assert.assertNotNull(differentRevision);
        ObjectNode unsupported=mapper.createObjectNode();unsupported.putObject("target").put("harnessId","codex").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        Assert.assertEquals("HANDOFF_ADAPTER_UNSUPPORTED",api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,unsupported,409).path("code").asText());
        unsupported.with("target").put("harnessId","product-code").put("agentProfileRevision","changed-profile-revision");Assert.assertEquals("PROFILE_CHANGED",api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,unsupported,409).path("code").asText());
        ObjectNode rejected=mapper.createObjectNode();rejected.putObject("target").put("agentProfileId","document-only").put("agentProfileRevision",differentRevision);
        Assert.assertEquals("PROFILE_INCOMPATIBLE",api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,rejected,409).path("code").asText());
        Assert.assertTrue(api("GET","/api/v2/runs/"+rid+"/commands",null,b,null,200).path("items").isEmpty());Assert.assertEquals(1,modelCalls.get());
        ObjectNode request=mapper.createObjectNode().put("transitionId","stop-handoff");request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);
        request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());
        api("POST","/api/v2/runs/"+rid+"/assignment-transitions","stop-handoff",b,request,202);
        api("POST","/api/v2/runs/"+rid+"/boundary-requests","during-handoff",a,mapper.createObjectNode().put("requestId","during-handoff").put("kind","CONTEXT_ADVICE").put("expectedExecutionEpoch","1").put("text","Do not change the frozen task"),409);
        await(()->"PAUSE_REQUESTED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("controlState").asText()),"Handoff did not register its pause before the STOP race");
        JsonNode stop=command(rid,"priority-stop","STOP",-1,a);Assert.assertFalse(stop.toString(),Arrays.asList("REJECTED","UNKNOWN").contains(stop.path("phase").asText()));releaseModel.release(10);
        await(()->"SUPERSEDED".equals(api("GET","/api/v2/runs/"+rid+"/assignment-transitions/stop-handoff",null,b,null,200).path("state").asText()),"STOP did not supersede the transition");
        Assert.assertEquals("1",api("GET","/api/v2/runs/"+rid+"/execution-segments",null,b,null,200).path("current").path("executionEpoch").asText());Assert.assertEquals(1,modelCalls.get());
    }
    @Test public void competingTransitionsAdmitOneIntentAndCancellationRetainsSourceEpoch()throws Exception{
        String rid=startMain();ObjectNode request=mapper.createObjectNode().put("transitionId","race-one");request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);
        request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());
        api("POST","/api/v2/runs/"+rid+"/assignment-transitions","race-one",b,request,202);
        ObjectNode competitor=request.deepCopy().put("transitionId","race-two");api("POST","/api/v2/runs/"+rid+"/assignment-transitions","race-two",a,competitor,409);
        Assert.assertEquals("race-one",api("POST","/api/v2/runs/"+rid+"/assignment-transitions","race-one",a,request,202).path("transitionId").asText());
        JsonNode cancelled=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/race-one/cancel","cancel-handoff",a,null,200);Assert.assertEquals("ABORTED",cancelled.path("state").asText());
        Assert.assertEquals("1",api("GET","/api/v2/runs/"+rid+"/execution-segments",null,b,null,200).path("current").path("executionEpoch").asText());Assert.assertEquals(1,modelCalls.get());command(rid,"race-stop","STOP",-1,a);releaseModel.release(10);
    }

    @Test public void completeProductRequestFootprintRejectsTinyTargetBeforePause()throws Exception{
        String rid=startMain();ObjectNode request=mapper.createObjectNode();ObjectNode target=request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        target.putObject("modelSelection").put("mode","EXPLICIT").putObject("settings").put("modelAlias","fixture-tiny");
        Assert.assertEquals("MODEL_BUDGET_INVALID",api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,409).path("code").asText());
        Assert.assertTrue(api("GET","/api/v2/runs/"+rid+"/commands",null,b,null,200).path("items").isEmpty());Assert.assertEquals(1,modelCalls.get());
        command(rid,"tiny-stop","STOP",-1,a);releaseModel.release(10);
    }

    @Test public void retainedPendingInputFollowsSameIdIntoTargetEpoch()throws Exception{pendingPolicy("RETAIN_PENDING");}
    @Test public void cancelledPendingInputDoesNotEnterTargetModel()throws Exception{pendingPolicy("CANCEL_PENDING");}
    private void pendingPolicy(String policy)throws Exception{
        String rid=startMain();String bid="pending-advice";
        api("POST","/api/v2/runs/"+rid+"/boundary-requests",bid,b,mapper.createObjectNode().put("requestId",bid).put("kind","CONTEXT_ADVICE").put("expectedExecutionEpoch","1").put("text","PENDING_POLICY_MARKER: preserve the immutable task"),202);
        ObjectNode request=mapper.createObjectNode().put("transitionId","pending-handoff").put("pendingInputPolicy",policy);
        request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);
        request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());
        api("POST","/api/v2/runs/"+rid+"/assignment-transitions","pending-handoff",b,request,202);await(()->"PAUSE_REQUESTED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("controlState").asText()),"Controlled pause was not dispatched");releaseModel.release();
        await(()->modelCalls.get()==2,"Target did not consume the frozen pending-input policy");
        boolean retained="RETAIN_PENDING".equals(policy);
        await(()->(retained?"CONSUMED":"CANCELLED").equals(api("GET","/api/v2/runs/"+rid+"/boundary-requests/"+bid,null,b,null,200).path("state").asText()),"Pending input did not settle");
        JsonNode receipt=api("GET","/api/v2/runs/"+rid+"/boundary-requests/"+bid,null,b,null,200);
        Assert.assertEquals(bid,receipt.path("requestId").asText());Assert.assertEquals(retained?"2":"1",receipt.path("executionEpoch").asText());
        Assert.assertEquals(retained,requests.get(1).toString().contains("PENDING_POLICY_MARKER"));
        releaseModel.release(10);await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Target did not finish");Assert.assertEquals(2,modelCalls.get());
    }

    @Test public void modelSettingsInstallOnlyAtNextSemanticRequest()throws Exception{
        scripted=true;String rid=startMain(),sid=api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sessionId").asText();String route="/api/v2/sessions/"+sid+"/model-settings";
        JsonNode before=api("GET",route,null,a,null,200);ObjectNode change=mapper.createObjectNode().put("expectedSettingsRevision",before.path("settingsRevision").asLong());change.putObject("settings").put("modelAlias","fixture-model-target");
        JsonNode pending=api("PATCH",route,"safe-model-change",b,change,202);Assert.assertEquals(pending.toString(),"PENDING",pending.path("transitionState").asText());Assert.assertEquals("fixture-model-target",pending.path("desired").path("modelAlias").asText());
        Assert.assertEquals(1,modelCalls.get());Assert.assertEquals("fixture-model",requests.get(0).path("model").asText());releaseModel.release();
        await(()->modelCalls.get()==2,"Target model was not installed at the next semantic request");Assert.assertEquals("fixture-model-target",requests.get(1).path("model").asText());
        JsonNode applied=api("GET",route,null,a,null,200);Assert.assertEquals(applied.toString(),"APPLIED",applied.path("transitionState").asText());Assert.assertEquals("fixture-model-target",applied.path("effective").path("modelAlias").asText());
        Assert.assertEquals("1",api("GET","/api/v2/runs/"+rid+"/execution-segments",null,b,null,200).path("current").path("executionEpoch").asText());
        releaseModel.release(10);await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Main did not finish after model change");Assert.assertEquals(2,modelCalls.get());
    }

    @Test public void simultaneousClientsAdmitOnlyOneHandoffAndKeepOrdinaryControlsFenced()throws Exception{
        String rid=startMain(),sid=api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sessionId").asText();ObjectNode request=mapper.createObjectNode();request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);
        JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);java.util.List<java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>>> replies=new ArrayList<>();
        for(int i=0;i<2;i++){final int index=i;replies.add(java.util.concurrent.CompletableFuture.supplyAsync(()->{try{start.await();String id="simultaneous-"+index;return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/v2/runs/"+rid+"/assignment-transitions")).header("Authorization","Bearer "+bearer).header("X-CodeAgent-Client-Token",index==0?a:b).header("Idempotency-Key",id).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(request.deepCopy().put("transitionId",id).toString())).build(),java.net.http.HttpResponse.BodyHandlers.ofString());}catch(Exception failure){throw new IllegalStateException(failure);}}));}
        start.countDown();int accepted=0,rejected=0;String winner=null;for(java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>> reply:replies){java.net.http.HttpResponse<String> result=reply.get(15,java.util.concurrent.TimeUnit.SECONDS);if(result.statusCode()==202){accepted++;winner=mapper.readTree(result.body()).path("transitionId").asText();}else{Assert.assertEquals(result.body(),409,result.statusCode());rejected++;}}
        Assert.assertEquals(1,accepted);Assert.assertEquals(1,rejected);String settingsRoute="/api/v2/sessions/"+sid+"/model-settings";ObjectNode change=mapper.createObjectNode().put("expectedSettingsRevision",check.path("modelSettingsRevision").asLong());change.putObject("settings").put("modelAlias","fixture-model-target");Assert.assertEquals("TRANSITION_PENDING",api("PATCH",settingsRoute,"blocked-settings",a,change,409).path("code").asText());
        ObjectNode resume=mapper.createObjectNode().put("schemaVersion","run-command/1").put("commandId","blocked-resume").put("intent","RESUME").put("expectedStateVersion",api("GET","/api/v2/runs/"+rid,null,b,null,200).path("stateVersion").asLong());Assert.assertEquals("TRANSITION_PENDING",api("POST","/api/v2/runs/"+rid+"/commands","blocked-resume",a,resume,409).path("code").asText());
        api("POST","/api/v2/runs/"+rid+"/assignment-transitions/"+winner+"/cancel","cancel-winner",b,null,200);command(rid,"simultaneous-stop","STOP",-1,a);releaseModel.release(10);Assert.assertEquals(1,modelCalls.get());
    }

    @Test public void lostModelAckReconcilesTheInstalledEpochWithoutReinstall()throws Exception{
        scripted=true;repeatAfterModelChange=true;String rid=startMain(),sid=api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sessionId").asText(),route="/api/v2/sessions/"+sid+"/model-settings";
        JsonNode before=api("GET",route,null,a,null,200);ObjectNode patch=mapper.createObjectNode().put("expectedSettingsRevision",before.path("settingsRevision").asLong());patch.putObject("settings").put("modelAlias","fixture-model-target");api("PATCH",route,"lost-model-ack",b,patch,202);
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"));java.sql.Statement q=c.createStatement()){q.execute("CREATE TRIGGER fixture_lost_model_ack BEFORE UPDATE ON sessions WHEN NEW.model_settings_state='APPLIED' AND OLD.model_settings_state='PENDING' BEGIN SELECT RAISE(ABORT,'fixture lost model ack'); END");}
        releaseModel.release();await(()->modelCalls.get()==2,"Target model request did not proceed despite its lost Host ack");Assert.assertEquals("fixture-model-target",requests.get(1).path("model").asText());
        String installed=(String)com.agent.product.code.CodeExecutionCheckpoint.fromJson(fixtureServices().tasks.assignmentCheckpointProof(rid)).getCollaboration().get("modelEpochId");
        Assert.assertEquals("PENDING",api("GET",route,null,a,null,200).path("transitionState").asText());
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"));java.sql.Statement q=c.createStatement()){q.execute("DROP TRIGGER fixture_lost_model_ack");}
        releaseModel.release();await(()->modelCalls.get()==3,"Main did not reach the next boundary");JsonNode applied=api("GET",route,null,b,null,200);Assert.assertEquals(applied.toString(),"APPLIED",applied.path("transitionState").asText());Assert.assertEquals(installed,applied.path("effectiveEpochId").asText());
        Assert.assertEquals(installed,com.agent.product.code.CodeExecutionCheckpoint.fromJson(fixtureServices().tasks.assignmentCheckpointProof(rid)).getCollaboration().get("modelEpochId"));releaseModel.release(10);
        await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Main did not finish after ack recovery");Assert.assertEquals(3,modelCalls.get());
    }

    @Test public void abortingPreparedHandoffRetainsTheOriginalUserPause()throws Exception{
        String rid=startMain();JsonNode before=api("GET","/api/v2/runs/"+rid,null,b,null,200);command(rid,"original-user-pause","PAUSE",before.path("stateVersion").asLong(),a);releaseModel.release();
        await(()->"APPLIED".equals(api("GET","/api/v2/runs/"+rid+"/commands/original-user-pause",null,b,null,200).path("phase").asText()),"Original user pause did not settle");
        String originalCheckpoint=fixtureServices().tasks.observationRun(rid).checkpointJson;java.util.concurrent.CountDownLatch prepared=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        java.lang.reflect.Field resources=com.sage.business.code.application.TaskApplicationService.class.getDeclaredField("collaborationResources");resources.setAccessible(true);Object coordinator=null;for(Object resource:(List<?>)resources.get(fixtureServices().tasks))if(resource instanceof com.sage.business.code.application.collaboration.AssignmentTransitionService)coordinator=resource;Assert.assertNotNull(coordinator);
        java.lang.reflect.Method observe=coordinator.getClass().getDeclaredMethod("observeDurableSteps",java.util.function.Consumer.class);observe.setAccessible(true);observe.invoke(coordinator,(java.util.function.Consumer<String>)step->{if("PREPARED".equals(step)){prepared.countDown();try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Prepared test gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}});
        try{
            ObjectNode request=mapper.createObjectNode().put("transitionId","paused-handoff");request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());
            api("POST","/api/v2/runs/"+rid+"/assignment-transitions","paused-handoff",b,request,202);Assert.assertTrue("Handoff did not reach its durable prepared boundary",prepared.await(15,java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals("ABORTED",api("POST","/api/v2/runs/"+rid+"/assignment-transitions/paused-handoff/cancel","abort-paused-handoff",a,null,200).path("state").asText());
            Assert.assertEquals("PAUSED_FOR_HUMAN",fixtureServices().tasks.observationRun(rid).status.name());Assert.assertEquals("original-user-pause",fixtureServices().tasks.observationRun(rid).pauseCommandId);Assert.assertEquals(originalCheckpoint,fixtureServices().tasks.observationRun(rid).checkpointJson);Assert.assertEquals(1,modelCalls.get());
        }finally{release.countDown();command(rid,"paused-handoff-stop","STOP",-1,a);}
    }

    @Test public void lostSourceInputAckIsRepairedBeforeRetargetingPendingInputs()throws Exception{
        scripted=true;String rid=startMain();ObjectNode advice=mapper.createObjectNode().put("requestId","lost-source-input").put("kind","CONTEXT_ADVICE").put("expectedExecutionEpoch","1").put("text","ONCE_ONLY_SOURCE_ADVICE: retain the existing task contract");api("POST","/api/v2/runs/"+rid+"/boundary-requests","lost-source-input",a,advice,202);
        try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"));java.sql.Statement q=c.createStatement()){q.execute("CREATE TRIGGER fixture_lost_source_ack BEFORE UPDATE ON boundary_requests WHEN NEW.status='CONSUMED' AND OLD.status='DELIVERED' BEGIN SELECT RAISE(ABORT,'fixture lost source consumption ack'); END");}
        releaseModel.release();await(()->modelCalls.get()==2,"Source did not commit and use its advice");
        java.util.concurrent.CountDownLatch prepared=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.lang.reflect.Field resources=com.sage.business.code.application.TaskApplicationService.class.getDeclaredField("collaborationResources");resources.setAccessible(true);Object coordinator=null;for(Object resource:(List<?>)resources.get(fixtureServices().tasks))if(resource instanceof com.sage.business.code.application.collaboration.AssignmentTransitionService)coordinator=resource;
        java.lang.reflect.Method observe=coordinator.getClass().getDeclaredMethod("observeDurableSteps",java.util.function.Consumer.class);observe.setAccessible(true);observe.invoke(coordinator,(java.util.function.Consumer<String>)step->{if("PREPARED".equals(step)){prepared.countDown();try{if(!release.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Prepared test gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}});
        try{
            ObjectNode request=mapper.createObjectNode().put("transitionId","lost-source-handoff").put("pendingInputPolicy","RETAIN_PENDING");request.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",targetRevision);JsonNode check=api("POST","/api/v2/runs/"+rid+"/assignment-transitions/preflight",null,b,request,200);request.put("preflightDigest",check.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",check.path("runStateVersion").asText()).put("expectedModelSettingsRevision",check.path("modelSettingsRevision").asText());api("POST","/api/v2/runs/"+rid+"/assignment-transitions","lost-source-handoff",b,request,202);
            await(()->"PAUSE_REQUESTED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("controlState").asText()),"Source pause was not dispatched");releaseModel.release();Assert.assertTrue(prepared.await(15,java.util.concurrent.TimeUnit.SECONDS));
            try(java.sql.Connection c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"));java.sql.Statement q=c.createStatement()){q.execute("DROP TRIGGER fixture_lost_source_ack");}release.countDown();await(()->modelCalls.get()==3,"Target did not resume");
            JsonNode receipt=api("GET","/api/v2/runs/"+rid+"/boundary-requests/lost-source-input",null,b,null,200);Assert.assertEquals("CONSUMED",receipt.path("state").asText());Assert.assertEquals("1",receipt.path("executionEpoch").asText());Assert.assertFalse(receipt.has("retargetedFromEpoch"));
            String target=requests.get(2).toString();int first=target.indexOf("ONCE_ONLY_SOURCE_ADVICE");Assert.assertTrue(first>=0);Assert.assertEquals(first,target.lastIndexOf("ONCE_ONLY_SOURCE_ADVICE"));releaseModel.release(10);await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Target did not finish");Assert.assertEquals(3,modelCalls.get());
        }finally{release.countDown();releaseModel.release(10);}
    }

}
