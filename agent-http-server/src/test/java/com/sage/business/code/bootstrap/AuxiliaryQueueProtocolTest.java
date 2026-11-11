package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.*;
public class AuxiliaryQueueProtocolTest extends FoundationProtocolAcceptanceTest {
    private final java.util.concurrent.Semaphore auxiliaryGate=new java.util.concurrent.Semaphore(0);
    @After public void releaseAuxiliaryModels(){auxiliaryGate.release(100);}
    private String sid;private final List<JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
    @Override protected void configureCollaboration(ServiceConfig config){config.collaborationAuxiliaryEnabled=true;}
    @Override protected void awaitModelPermit(JsonNode request)throws java.io.IOException{
        if(!request.toString().contains("read-only auxiliary agent")){super.awaitModelPermit(request);return;}
        try{if(!auxiliaryGate.tryAcquire(25,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("Owned auxiliary gate elapsed");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
    }
    @Override protected void observeModelRequest(JsonNode request){requests.add(request);}
    private String createParent(boolean workspaceTask)throws Exception{
        ObjectNode setup=mapper.createObjectNode();if(workspaceTask){setup.put("workspaceAlias","sample").put("intent","EXPLAIN").put("executionMode","INSPECT").put("initialWorkspaceListing",false);setup.putArray("writeAllow");}
        JsonNode parent=api("POST","/api/v2/sessions","queue-parent",a,setup,201);sid=parent.path("sessionId").asText();String rid=null;
        if(workspaceTask){rid=api("POST","/api/v2/sessions/"+sid+"/messages","queue-main",a,mapper.createObjectNode().put("clientMessageId","queue-main").put("expectedSessionVersion",parent.path("sessionVersion").asLong()).put("content","Inspect README"),202).path("runId").asText();await(()->modelCalls.get()==1,"Main did not start");}
        String snapshot=api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","queue-snapshot",a,mapper.createObjectNode(),201).path("snapshotId").asText();
        api("POST","/api/v2/sessions/"+sid+"/context-packages","queue-package",a,mapper.createObjectNode().put("requestId","queue-package").put("snapshotId",snapshot),201);return rid;
    }
    private String child(String key)throws Exception{return api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions",key,a,mapper.createObjectNode().put("requestId",key).put("basePackageId","queue-package"),201).path("sessionId").asText();}
    private JsonNode turn(String child,String key,int version,String question,int status)throws Exception{return api("POST","/api/v2/sessions/"+child+"/auxiliary-turns",key,a,mapper.createObjectNode().put("turnId",key).put("expectedSessionVersion",Integer.toString(version)).put("packageId","queue-package").put("question",question),status);}
    private JsonNode result(String child,String turn)throws Exception{return api("GET","/api/v2/sessions/"+child+"/auxiliary-turns/"+turn,null,b,null,200);}
    @Test public void queuedTurnsCannotReadFutureQuestionsInTheirConversation()throws Exception{
        createParent(false);String one=child("blocker-one"),two=child("blocker-two"),ordered=child("ordered-side");
        turn(one,"block-one",1,"First independent blocker",202);turn(two,"block-two",1,"Second independent blocker",202);await(()->modelCalls.get()==2,"Global auxiliary slots did not fill");
        turn(ordered,"ordered-first",1,"FIRST_CONTEXT_ONLY",202);turn(ordered,"ordered-second",2,"FUTURE_QUESTION_MUST_NOT_LEAK",202);Assert.assertEquals("QUEUED",result(ordered,"ordered-first").path("state").asText());
        auxiliaryGate.release();await(()->modelCalls.get()==3,"Queued first turn did not start");Assert.assertTrue(requests.get(2).toString().contains("FIRST_CONTEXT_ONLY"));Assert.assertFalse(requests.get(2).toString().contains("FUTURE_QUESTION_MUST_NOT_LEAK"));
        auxiliaryGate.release(10);await(()->"SUCCEEDED".equals(result(ordered,"ordered-second").path("state").asText()),"Second turn did not follow the first answer");Assert.assertEquals(4,modelCalls.get());Assert.assertTrue(requests.get(3).toString().contains("persisted fixture reply"));Assert.assertTrue(requests.get(3).toString().contains("FUTURE_QUESTION_MUST_NOT_LEAK"));
    }
    @Test public void fullAuxiliaryQueueCannotBlockParentStopAndAllJobsCancelInTheirOwnScopes()throws Exception{
        String rid=createParent(true),one=child("active-one"),two=child("active-two"),queued=child("queued-side");
        turn(one,"active-first",1,"Block one",202);turn(two,"active-second",1,"Block two",202);await(()->modelCalls.get()==3,"Main and auxiliary model slots did not fill");
        for(int i=1;i<=6;i++)turn(queued,"queued-"+i,i,"Queued question "+i,202);
        Assert.assertEquals("AUXILIARY_QUEUE_FULL",turn(queued,"queue-overflow",7,"Must be rejected before reservation",429).path("code").asText());
        command(rid,"queue-priority-stop","STOP",-1,b);releaseModel.release();
        await(()->"APPLIED".equals(api("GET","/api/v2/runs/"+rid+"/commands/queue-priority-stop",null,b,null,200).path("phase").asText()),"Full auxiliary queue blocked STOP");
        auxiliaryGate.release(100);
        await(()->{if(!"CANCELLED".equals(result(one,"active-first").path("state").asText())||!"CANCELLED".equals(result(two,"active-second").path("state").asText()))return false;for(int i=1;i<=6;i++)if(!"CANCELLED".equals(result(queued,"queued-"+i).path("state").asText()))return false;return true;},"Parent STOP did not cancel every bounded job");
        Assert.assertEquals("Queued work dispatched after STOP",3,modelCalls.get());
    }
}
