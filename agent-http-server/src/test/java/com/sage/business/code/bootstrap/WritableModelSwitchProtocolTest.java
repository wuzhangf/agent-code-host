package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.*;
/** Actual parent switches its next semantic request while an independently frozen child is in-flight. */
public class WritableModelSwitchProtocolTest extends WritableCollaborationProtocolTest {
 private boolean scenario,done;private final Semaphore childPermit=new Semaphore(0);private final List<JsonNode> parentRequests=new CopyOnWriteArrayList<>(),childRequests=new CopyOnWriteArrayList<>();
 private static final class ScenarioCompleted extends RuntimeException{}
 private boolean child(JsonNode request){return request.toString().contains("CHILD_WRITE_X")&&!request.path("tools").toString().contains("collaboration_control");}
 @Override protected void observeModelRequest(JsonNode request){super.observeModelRequest(request);if(child(request))childRequests.add(request);else parentRequests.add(request);}
 @Override protected void awaitModelPermit(JsonNode request)throws java.io.IOException{if(scenario&&child(request)){try{if(!childPermit.tryAcquire(60,TimeUnit.SECONDS))throw new java.io.IOException("Child model gate expired");}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new java.io.IOException(stop);}return;}super.awaitModelPermit(request);}
 @Override protected boolean deleteFixtureRoot(){return done||super.deleteFixtureRoot();}
 @After public void releaseAll(){childPermit.release(100);releaseModel.release(100);}
 @Test public void parentModelChangeDoesNotRebindOrRestartFrozenActiveChild()throws Exception{scenario=true;try{exercise(false,false);Assert.fail("Scenario hook not reached");}catch(ScenarioCompleted completed){Assert.assertTrue(done);}}
 @Override protected void afterWritableDispatch(String rid,String sid,String group)throws Exception{
  if(!scenario)return;JsonNode frozen=api("GET",group,null,b,null,200).path("authorization").path("sources");String route="/api/v2/sessions/"+sid+"/model-settings";JsonNode current=api("GET",route,null,b,null,200);ObjectNode patch=mapper.createObjectNode().put("expectedSettingsRevision",current.path("settingsRevision").asLong());patch.putObject("settings").put("modelAlias","fixture-model-target");Assert.assertEquals("PENDING",api("PATCH",route,"switch-parent-only",b,patch,202).path("transitionState").asText());
  releaseModel.release();await(()->parentRequests.size()==2&&childRequests.size()==1,"Independent parent and child model requests did not overlap");Assert.assertEquals("fixture-model-target",parentRequests.get(1).path("model").asText());Assert.assertEquals("fixture-model",childRequests.get(0).path("model").asText());Assert.assertEquals("APPLIED",api("GET",route,null,b,null,200).path("transitionState").asText());
  releaseModel.release();await(()->parentRequests.size()==3,"Parent could not continue while frozen child was held");Assert.assertEquals(1,childRequests.size());Assert.assertEquals("fixture-model-target",parentRequests.get(2).path("model").asText());childPermit.release(10);await(()->"READY".equals(api("GET",group,null,b,null,200).path("state").asText()),"Frozen child did not seal");Assert.assertEquals(3,childRequests.size());for(JsonNode request:childRequests)Assert.assertEquals("fixture-model",request.path("model").asText());Assert.assertEquals(frozen,api("GET",group,null,b,null,200).path("authorization").path("sources"));Assert.assertEquals("v1",Files.readString(Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot).resolve("x.txt")));
  command(rid,"stop-model-switch-fixture","STOP",-1,a);releaseModel.release(10);await(()->"CANCELLED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("state").asText()),"Actual parent did not stop");done=true;throw new ScenarioCompleted();
 }
}
