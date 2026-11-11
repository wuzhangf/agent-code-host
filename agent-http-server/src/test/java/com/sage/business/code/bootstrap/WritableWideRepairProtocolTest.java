package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
/** Actual 8-unit DAG, five failed write candidates and the exact >64-path remediation union. */
public class WritableWideRepairProtocolTest extends WritableRepairProtocolTest {
 @Rule public final org.junit.rules.TestName scenarioName=new org.junit.rules.TestName();
 private boolean wideScenario(){return "completeFailedSelectionScopeAbove64PathsSurvivesRepairSelectionPreviewAndAdoption".equals(scenarioName.getMethodName());}
 private final AtomicInteger wideRepairCalls=new AtomicInteger();
 @Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
  if("POST".equals(method)&&path.endsWith("/previews")&&expected==202){
   java.net.http.HttpClient http=java.net.http.HttpClient.newHttpClient();int calls=modelCalls.get();
   for(int retry=0;;retry++){
    java.net.http.HttpRequest request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(15)).header("Authorization","Bearer "+bearer).header("X-CodeAgent-Client-Token",client).header("Idempotency-Key",key).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
    java.net.http.HttpResponse<String> response=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());JsonNode result=mapper.readTree(response.body());
    if(response.statusCode()==409&&"BASE_CAPTURE_BUSY".equals(result.path("code").asText())&&retry<2){Assert.assertEquals("Capture retries cannot invoke a model",calls,modelCalls.get());continue;}
    Assert.assertEquals(result.toString(),expected,response.statusCode());return result;
   }
  }
  return super.api(method,path,key,client,body,expected);
 }

 @Override protected boolean dagFixture(){return wideScenario();}
 @Override protected int expectedDagReadDependencies(){return wideScenario()?15:super.expectedDagReadDependencies();}
 @Override protected boolean repairFixture(){return wideScenario()||super.repairFixture();}
 @Override protected int unitEditAllowance(){return wideScenario()?1:super.unitEditAllowance();}
 @Override protected int repairModelAllowance(){return wideScenario()?10:super.repairModelAllowance();}
 @Override protected int repairEditAllowance(){return wideScenario()?7:super.repairEditAllowance();}
 @Override protected Path createFixtureRoot()throws Exception{Path root=super.createFixtureRoot();if(!wideScenario())return root;for(int i=0;i<65;i++)Files.writeString(root.resolve("workspace/context-"+i+".txt"),"fixed context "+i);return root;}
 @Override protected void customizeWritableGraph(ObjectNode graph){if(!wideScenario()){super.customizeWritableGraph(graph);return;}int index=0;for(JsonNode value:graph.path("units"))if("WRITE_CANDIDATE".equals(value.path("kind").asText())){ArrayNode reads=(ArrayNode)value.get("readScope");for(int i=0;i<13;i++)reads.add("context-"+(index*13+i)+".txt");index++;}Assert.assertEquals(5,index);}
 private boolean wideRepair(JsonNode request){return request.toString().contains("CHILD_WRITE_REPAIR_X")&&!request.path("tools").toString().contains("collaboration_control");}
 @Override protected void observeModelRequest(JsonNode request){super.observeModelRequest(request);if(wideRepair(request))wideRepairCalls.incrementAndGet();}
 @Override protected ObjectNode replyForModel(JsonNode request){if(!wideScenario()||!wideRepair(request))return super.replyForModel(request);int turn=wideRepairCalls.get();if(turn>=3)return mapper.createObjectNode().put("role","assistant").put("content","All five isolated files are repaired; full frozen read scope remains intact.");ObjectNode message=mapper.createObjectNode().put("role","assistant");ArrayNode calls=message.putArray("tool_calls");if(turn==1){ObjectNode args=mapper.createObjectNode();ArrayNode paths=args.putArray("paths");for(String name:Arrays.asList("x","y","a","b","d"))paths.add(name+".txt");calls.addObject().put("id","repair-read-five").put("type","function").putObject("function").put("name","workspace_batch_read").put("arguments",args.toString());}else for(String name:Arrays.asList("x","y","a","b","d")){ObjectNode args=mapper.createObjectNode().put("path",name+".txt");args.putArray("edits").addObject().put("oldText","broken").put("newText","v2");calls.addObject().put("id","repair-"+name).put("type","function").putObject("function").put("name","workspace_edit").put("arguments",args.toString());}return message;}
 @Override protected void exerciseRepair(String rid,String group)throws Exception{super.exerciseRepair(rid,group);if(!wideScenario())return;JsonNode view=api("GET",group,null,b,null,200);boolean verified=false;for(JsonNode candidate:view.path("candidates"))if("repair-one".equals(candidate.path("repairId").asText())){JsonNode manifest=api("GET",group+"/candidates/"+candidate.path("entityId").asText(),null,b,null,200).path("manifest");Assert.assertTrue(manifest.toString(),manifest.path("readDependencies").size()>=70);verified=true;}Assert.assertTrue(verified);}
 @Test public void completeFailedSelectionScopeAbove64PathsSurvivesRepairSelectionPreviewAndAdoption()throws Exception{exercise(false,false);Assert.assertEquals(3,wideRepairCalls.get());}
}
