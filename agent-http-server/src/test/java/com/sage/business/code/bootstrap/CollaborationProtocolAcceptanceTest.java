package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.*;

/** Actual HTTP -> Factory -> Product/model path; no controller substitute. */
public class CollaborationProtocolAcceptanceTest extends FoundationProtocolAcceptanceTest {
    private final java.util.List<JsonNode> modelRequests=new java.util.concurrent.CopyOnWriteArrayList<>();
    @Override protected void observeModelRequest(JsonNode request){modelRequests.add(request);}
    @Override protected void configureCollaboration(ServiceConfig config){config.collaborationAuxiliaryEnabled=true;}
    @Test public void frozenAuxiliaryAnswerIsIndependentIdempotentAndCannotBecomeMain()throws Exception {
        String sid=api("POST","/api/v2/sessions","parent-create",a,mapper.createObjectNode(),201).path("sessionId").asText();
        JsonNode snapshot=api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","base-snapshot",a,mapper.createObjectNode(),201);
        ObjectNode cp=mapper.createObjectNode().put("requestId","package-one").put("snapshotId",snapshot.path("snapshotId").asText());
        JsonNode packageOne=api("POST","/api/v2/sessions/"+sid+"/context-packages","package-one",a,cp,201);
        Assert.assertEquals("READY",packageOne.path("state").asText());Assert.assertEquals(0,modelCalls.get());
        JsonNode parentBefore=api("GET","/api/v2/sessions/"+sid,null,b,null,200);
        ObjectNode create=mapper.createObjectNode().put("requestId","side-one").put("basePackageId","package-one");
        JsonNode aux=api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","side-one",a,create,201);
        Assert.assertEquals(aux,api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","side-one",b,create,201));
        String child=aux.path("sessionId").asText();
        ObjectNode turn=mapper.createObjectNode().put("turnId","question-one").put("expectedSessionVersion","1").put("packageId","package-one").put("question","What is known from this frozen context?");
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","question-one",a,turn,202);
        await(()->modelCalls.get()==1,"Auxiliary did not call the local model");releaseModel.release();
        String result="/api/v2/sessions/"+child+"/auxiliary-turns/question-one";
        await(()->"SUCCEEDED".equals(api("GET",result,null,b,null,200).path("state").asText()),"Auxiliary answer did not publish");
        JsonNode answer=api("GET",result,null,b,null,200);Assert.assertTrue(answer.path("answer").asText().contains("AUXILIARY_AGENT"));
        Assert.assertEquals(answer,api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","question-one",b,turn,202));Assert.assertEquals(1,modelCalls.get());
        turn.put("question","Changed payload");Assert.assertEquals("IDEMPOTENCY_KEY_REUSED",api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","question-one",b,turn,409).path("code").asText());
        ObjectNode main=mapper.createObjectNode().put("clientMessageId","forbidden-main").put("content","Write a file");
        Assert.assertEquals("AUXILIARY_SESSION_RESTRICTED",api("POST","/api/v2/sessions/"+child+"/messages","forbidden-main",b,main,409).path("code").asText());
        ObjectNode parentAfter=(ObjectNode)api("GET","/api/v2/sessions/"+sid,null,b,null,200);((ObjectNode)parentBefore).remove("eventCursor");parentAfter.remove("eventCursor");Assert.assertEquals(parentBefore,parentAfter);
        Assert.assertEquals("CONTEXT_PACKAGE_NOT_FOUND",api("GET","/api/v2/sessions/"+child+"/context-packages/package-one",null,b,null,404).path("code").asText());
        Assert.assertEquals(1,modelCalls.get());
    }
    @Test public void cancellationRejectsLateAnswerAndLeavesParentAlone()throws Exception {
        String sid=api("POST","/api/v2/sessions","cancel-parent",a,mapper.createObjectNode(),201).path("sessionId").asText();
        String snapshot=api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","cancel-snapshot",a,mapper.createObjectNode(),201).path("snapshotId").asText();
        api("POST","/api/v2/sessions/"+sid+"/context-packages","cancel-package",a,mapper.createObjectNode().put("requestId","cancel-package").put("snapshotId",snapshot),201);
        String child=api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","cancel-side",a,mapper.createObjectNode().put("requestId","cancel-side").put("basePackageId","cancel-package"),201).path("sessionId").asText();
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","cancel-turn",a,mapper.createObjectNode().put("turnId","cancel-turn").put("expectedSessionVersion","1").put("packageId","cancel-package").put("question","Analyze frozen evidence"),202);
        await(()->modelCalls.get()==1,"No auxiliary model request");
        String resource="/api/v2/sessions/"+child+"/auxiliary-turns/cancel-turn";
        Assert.assertEquals("CANCEL_REQUESTED",api("POST",resource+"/cancel","cancel-command",b,null,200).path("state").asText());releaseModel.release();
        await(()->"CANCELLED".equals(api("GET",resource,null,b,null,200).path("state").asText()),"Cancel did not settle");
        Assert.assertTrue(api("GET",resource,null,b,null,200).path("answer").isMissingNode());
    }
    @Test public void auxiliaryTurnModelIsFrozenIndependentlyOfParentChanges()throws Exception{
        String sid=api("POST","/api/v2/sessions","models-parent",a,mapper.createObjectNode(),201).path("sessionId").asText();
        String snapshot=api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","models-snapshot",a,mapper.createObjectNode(),201).path("snapshotId").asText();
        api("POST","/api/v2/sessions/"+sid+"/context-packages","models-package",a,mapper.createObjectNode().put("requestId","models-package").put("snapshotId",snapshot),201);
        String child=api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","models-side",a,mapper.createObjectNode().put("requestId","models-side").put("basePackageId","models-package"),201).path("sessionId").asText();
        ObjectNode question=mapper.createObjectNode().put("turnId","frozen-model").put("expectedSessionVersion","1").put("packageId","models-package").put("question","Explain frozen evidence");
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","frozen-model",a,question,202);await(()->modelCalls.get()==1,"Frozen auxiliary request did not start");
        String parentModels="/api/v2/sessions/"+sid+"/model-settings";JsonNode before=api("GET",parentModels,null,b,null,200);ObjectNode patch=mapper.createObjectNode().put("expectedSettingsRevision",before.path("settingsRevision").asLong());patch.putObject("settings").put("modelAlias","fixture-model-target");api("PATCH",parentModels,"parent-model-change",b,patch,200);
        releaseModel.release();String first="/api/v2/sessions/"+child+"/auxiliary-turns/frozen-model";await(()->"SUCCEEDED".equals(api("GET",first,null,b,null,200).path("state").asText()),"Frozen model answer did not finish");Assert.assertEquals("fixture-model",modelRequests.get(0).path("model").asText());
        JsonNode parentAfter=api("GET",parentModels,null,b,null,200);ObjectNode next=question.deepCopy().put("turnId","own-model").put("modelAlias","fixture-model-target").put("expectedSessionVersion",api("GET",first,null,b,null,200).path("sessionVersion").asText());
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","own-model",a,next,202);await(()->modelCalls.get()==2,"Independent model selection did not start");Assert.assertEquals("fixture-model-target",modelRequests.get(1).path("model").asText());releaseModel.release();
        await(()->"SUCCEEDED".equals(api("GET","/api/v2/sessions/"+child+"/auxiliary-turns/own-model",null,b,null,200).path("state").asText()),"Independent model answer did not finish");Assert.assertEquals(parentAfter,api("GET",parentModels,null,b,null,200));
    }

}
