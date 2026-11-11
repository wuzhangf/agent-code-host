package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/** A live Product loop, independent Q&A, and the actual Plan read-only facade over sealed blobs. */
public class AuxiliaryLiveResearchProtocolTest extends FoundationProtocolAcceptanceTest {
    private volatile boolean live;
    private final AtomicInteger mainCalls=new AtomicInteger();
    private final List<JsonNode> researchRequests=new java.util.concurrent.CopyOnWriteArrayList<>();
    @Override protected void configureCollaboration(ServiceConfig config){config.collaborationAuxiliaryEnabled=true;
        try{for(int i=0;i<20;i++)Files.write(workspace.resolve("counter-"+i+".txt"),Collections.singletonList("sealed-counter-"+i));}catch(Exception e){throw new IllegalStateException(e);}}
    private boolean research(JsonNode request){for(JsonNode tool:request.path("tools"))if("emit_research_finding".equals(tool.path("function").path("name").asText()))return true;return false;}
    private boolean auxiliary(JsonNode request){return request.toString().contains("read-only auxiliary agent");}
    @Override protected void observeModelRequest(JsonNode request){if(!live)return;if(research(request))researchRequests.add(request);else if(!auxiliary(request))mainCalls.incrementAndGet();}
    @Override protected void awaitModelPermit(JsonNode request)throws java.io.IOException{
        if(!live){super.awaitModelPermit(request);return;}
        if(!research(request)&&!auxiliary(request))try{Thread.sleep(80);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
    }
    private ObjectNode tool(String name,String arguments){ObjectNode reply=mapper.createObjectNode().put("role","assistant").put("content","");
        ObjectNode call=mapper.createObjectNode().put("index",0).put("id","call-"+UUID.randomUUID()).put("type","function");
        call.set("function",mapper.createObjectNode().put("name",name).put("arguments",arguments));reply.putArray("tool_calls").add(call);return reply;}
    @Override protected ObjectNode replyForModel(JsonNode request){
        if(!live||auxiliary(request))return super.replyForModel(request);
        if(research(request)){
            if(request.toString().contains("FAIL_THIS_TASK"))return mapper.createObjectNode().put("role","assistant").put("content","No verified finding is available for this task.");
            boolean observed=false;for(JsonNode message:request.path("messages"))if(message.hasNonNull("tool_call_id"))observed=true;
            if(!observed)return tool("workspace_batchRead","{\"paths\":[\"counter-0.txt\"]}");
            return tool("emit_research_finding","{\"summary\":\"Verified sealed counter material\",\"claims\":[{\"claimId\":\"c1\",\"statement\":\"The frozen file contains sealed-counter-0\",\"evidenceRefs\":[\"obs/1\"],\"inferred\":false,\"confidence\":\"high\"}],\"evidenceRefs\":[\"obs/1\"],\"candidatePaths\":[\"counter-0.txt\"],\"confidence\":\"high\"}");
        }
        int n=mainCalls.get(),index=(Math.max(0,n-1)/2)%20;
        if(n%2==1)return tool("workspace_read","{\"path\":\"counter-"+index+".txt\"}");
        return tool("workspace_write","{\"path\":\"counter-"+index+".txt\",\"content\":\"main-counter-"+n+"\"}");
    }
    @Test public void liveMainContinuesWhileFrozenQuestionAndFileResearchFinish()throws Exception{
        live=true;ObjectNode setup=mapper.createObjectNode().put("workspaceAlias","sample").put("interactionMode","ACT").put("intent","IMPLEMENT").put("executionMode","MODIFY").put("autonomyLevel","LOW").put("verificationLevel","COMPILE").put("maxModelTurns",1000).put("maxEditAttempts",1000).put("initialWorkspaceListing",false);for(int i=0;i<20;i++)setup.withArray("writeAllow").add("counter-"+i+".txt");
        JsonNode parent=api("POST","/api/v2/sessions","live-parent",a,setup,201);String sid=parent.path("sessionId").asText();
        String rid=api("POST","/api/v2/sessions/"+sid+"/messages","live-start",a,mapper.createObjectNode().put("clientMessageId","live-start").put("expectedSessionVersion",parent.path("sessionVersion").asLong()).put("content","Update the numbered counter files in order and keep progressing"),202).path("runId").asText();
        await(()->mainCalls.get()>=2,"Main Product loop did not advance");
        Path productRoot=Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot);
        await(()->Files.readString(productRoot.resolve("counter-0.txt")).startsWith("main-counter-"),"Main writer did not finish its first authorized edit");
        JsonNode snap=api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","live-snapshot",b,mapper.createObjectNode(),201);
        JsonNode pack=api("POST","/api/v2/sessions/"+sid+"/context-packages","live-package",b,mapper.createObjectNode().put("requestId","live-package").put("profile","RESEARCH").put("snapshotId",snap.path("snapshotId").asText()),201);
        Assert.assertEquals("SEALED_ARTIFACT_TREE",pack.path("researchViewMode").asText());Assert.assertTrue(pack.path("sealedFiles").has("counter-0.txt"));
        String child=api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","live-side",b,mapper.createObjectNode().put("requestId","live-side").put("basePackageId","live-package"),201).path("sessionId").asText();
        int before=mainCalls.get();ObjectNode question=mapper.createObjectNode().put("turnId","live-question").put("expectedSessionVersion","1").put("packageId","live-package").put("question","What is known from the frozen evidence?");
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","live-question",b,question,202);
        String questionPath="/api/v2/sessions/"+child+"/auxiliary-turns/live-question";
        await(()->Arrays.asList("SUCCEEDED","FAILED","CANCELLED","OUTCOME_UNKNOWN").contains(api("GET",questionPath,null,b,null,200).path("state").asText()),"Frozen Q&A did not complete");
        JsonNode answered=api("GET",questionPath,null,b,null,200);Assert.assertEquals(answered.toString(),"SUCCEEDED",answered.path("state").asText());
        Files.write(workspace.resolve("counter-0.txt"),Collections.singletonList("live file changed after package publication"));
        ObjectNode batch=mapper.createObjectNode().put("turnId","live-research").put("expectedSessionVersion",answered.path("sessionVersion").asText()).put("packageId","live-package").put("question","Read and cite the sealed counter file");
        batch.putArray("researchTasks").add(mapper.createObjectNode().put("taskId","inspect-one").put("role","repo-explorer").put("question","Read counter-0.txt and report its frozen contents"));
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","live-research",b,batch,202);
        String researchPath="/api/v2/sessions/"+child+"/auxiliary-turns/live-research";
        await(()->Arrays.asList("SUCCEEDED","FAILED","CANCELLED","OUTCOME_UNKNOWN").contains(api("GET",researchPath,null,b,null,200).path("state").asText()),"Actual Plan research did not complete");
        JsonNode researchResult=api("GET",researchPath,null,b,null,200);Assert.assertEquals(researchResult.toString(),"SUCCEEDED",researchResult.path("state").asText());
        JsonNode result=mapper.readTree(researchResult.path("answer").asText());
        Assert.assertTrue(result.toString(),result.path("findings").size()>0);Assert.assertEquals(result.path("baselineFingerprint"),result.path("postBatchBaselineFingerprint"));
        ObjectNode mixed=mapper.createObjectNode().put("turnId","mixed-research").put("expectedSessionVersion",researchResult.path("sessionVersion").asText()).put("packageId","live-package").put("question","Inspect three independent aspects of the sealed tree");
        mixed.putArray("researchTasks").add(mapper.createObjectNode().put("taskId","one").put("role","repo-explorer").put("question","Read counter-0.txt"))
            .add(mapper.createObjectNode().put("taskId","two").put("role","risk-reviewer").put("question","FAIL_THIS_TASK: return no verified finding"))
            .add(mapper.createObjectNode().put("taskId","three").put("role","dependency-tracer").put("question","Read counter-0.txt and trace its evidence"));
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","mixed-research",b,mixed,202);
        String mixedPath="/api/v2/sessions/"+child+"/auxiliary-turns/mixed-research";
        await(()->"SUCCEEDED".equals(api("GET",mixedPath,null,b,null,200).path("state").asText()),"Mixed research batch did not publish its per-task outcomes");
        JsonNode mixedResult=mapper.readTree(api("GET",mixedPath,null,b,null,200).path("answer").asText());Assert.assertEquals(mixedResult.toString(),3,mixedResult.path("taskOutcomes").size());
        int failed=0;for(JsonNode outcome:mixedResult.path("taskOutcomes")){Assert.assertTrue(outcome.toString(),outcome.path("modelCalls").asInt()<=4);if(!outcome.path("errorCode").asText().isEmpty())failed++;}
        Assert.assertEquals(mixedResult.toString(),1,failed);Assert.assertTrue(mixedResult.toString(),mixedResult.path("findings").size()>=1);
        Assert.assertTrue(researchRequests.stream().anyMatch(v->v.toString().contains("sealed-counter-0")));
        Assert.assertFalse(researchRequests.stream().anyMatch(v->v.toString().contains("live file changed after package publication")));
        await(()->mainCalls.get()>before,"Main loop did not continue during auxiliary work");
        Path actualRoot=Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot);
        await(()->Files.readString(actualRoot.resolve("counter-"+((before/2)%20)+".txt")).startsWith("main-counter-"),"Main did not write its independently authorized directory while auxiliary work ran");
        Assert.assertEquals("RUNNING",api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText());
        Assert.assertTrue(api("GET","/api/v2/runs/"+rid+"/commands",null,b,null,200).path("items").isEmpty());
        command(rid,"live-stop","STOP",-1,a);
    }
}
