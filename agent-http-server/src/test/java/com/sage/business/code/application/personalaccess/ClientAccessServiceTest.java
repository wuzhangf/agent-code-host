package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import org.junit.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.sage.business.code.application.personalaccess.ClientAccessWire.*;

public class ClientAccessServiceTest {
    PersonalLocalTaskAccessTest fixture;
    ClientAccessService service;
    AtomicInteger messages=new AtomicInteger();
    Map<String,JsonNode> ledger=new HashMap<String,JsonNode>();
    Map<String,JsonNode> bodies=new HashMap<String,JsonNode>();
    @Before public void setup()throws Exception{fixture=new PersonalLocalTaskAccessTest();fixture.setup();service=new ClientAccessService(fixture.auth,fixture.access,"acs-store","boot",this::source);}
    @After public void close()throws Exception{fixture.cleanup();}
    ObjectNode obj(){return JSON.createObjectNode();}
    JsonNode call(String method,String path,JsonNode body,String key)throws Exception{return service.handle(fixture.token,method,path,body,key,null);}
    void acsScope()throws Exception{
        PersonalAccessService.Device d=fixture.auth.authenticate(fixture.token);
        fixture.auth.updateScopeResources(fixture.did,"acs-scope",Long.toString(d.revision),Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION","acs-store","session-one")),Collections.emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE","CONTROL","INTERACT")));
    }
    JsonNode source(PersonalAccessService.Device d,String method,String path,JsonNode body,String key){
        if(path.endsWith("/foundation")){ObjectNode out=obj().put("sessionId","session-one").put("sessionVersion","4").put("activeRunId","run-one").put("runState","RUNNING").put("runStateVersion","7");out.putObject("capabilities").put("commandReceiptV1","READY");ObjectNode controls=out.putObject("runControl");for(String k:Arrays.asList("PAUSE","STOP"))controls.putObject(k).put("availability","AVAILABLE").putNull("reasonCode");controls.putObject("RESUME").put("availability","UNAVAILABLE").put("reasonCode","RUN_NOT_RESUMABLE");return out;}
        if(path.endsWith("/messages")&&method.equals("POST")){
            if(ledger.containsKey(key)){if(!bodies.get(key).equals(body))throw PersonalAccessService.fail("IDEMPOTENCY_KEY_REUSED",409);return ledger.get(key);}
            assertEquals(4,body.path("expectedSessionVersion").asInt());assertEquals(key,body.path("clientMessageId").asText());messages.incrementAndGet();JsonNode out=obj().put("runId","run-one").put("messageId","message-one").put("status","QUEUED");ledger.put(key,out);bodies.put(key,body.deepCopy());return out;
        }
        if(path.equals("/api/v2/operations/lookup")){JsonNode result=ledger.get(body.path("idempotencyKey").asText());if(result==null)throw PersonalAccessService.fail("NOT_FOUND",404);ObjectNode out=obj().put("operation","session.message").put("resourceId","session-one").put("status","APPLIED");out.set("result",result);return out;}
        if(path.startsWith("/api/v2/sessions/session-one/messages?")){ObjectNode out=obj().put("sessionId","session-one").put("hasOlder",false);ArrayNode items=out.putArray("items");items.addObject().put("id","message-one").put("role","assistant").put("content","Public answer").put("contentState","INLINE").put("contentRevision","1");items.addObject().put("id","private").put("role","system").put("content","private system").put("contentRevision","1");return out;}
        if(path.endsWith("/messages/message-one/content"))return obj().put("sessionId","session-one").put("messageId","message-one").put("role","assistant").put("contentRevision","1").put("text","Public answer").put("complete",true).put("hasMore",false);
        if(path.endsWith("/messages/private/content"))return obj().put("sessionId","session-one").put("messageId","private").put("role","system").put("contentRevision","1").put("text","private system").put("complete",true).put("hasMore",false);
        if(path.equals("/api/v2/runs/run-one"))return obj().put("sessionId","session-one").put("runId","run-one");
        if(path.contains("/commands")){if(method.equals("POST")){JsonNode prior=ledger.get(key);if(prior!=null)return prior;JsonNode out=obj().put("phase","REQUESTED").put("runId","run-one");ledger.put(key,out);return out;}return ledger.get(path.substring(path.lastIndexOf('/')+1));}
        throw PersonalAccessService.fail("NOT_FOUND",404);
    }
    @Test public void acsMessageUsesOriginalVersionAndLookupIsReadOnly()throws Exception{
        acsScope();JsonNode agent=call("GET","/agents",null,null).path("items").get(0);String aref=agent.path("agentRef").asText();String cref=call("GET","/agents/"+aref+"/conversations",null,null).path("items").get(0).path("conversationRef").asText();
        JsonNode view=call("GET","/conversations/"+cref,null,null);ObjectNode body=obj().put("requestId","m1").put("text","Hello").put("binding",view.path("message").path("binding").asText());
        assertTrue(call("POST","/conversations/"+cref+"/messages",body,"m1").path("accepted").asBoolean());call("POST","/conversations/"+cref+"/messages",body,"m1");assertEquals(1,messages.get());
        try{call("POST","/conversations/"+cref+"/messages",body.deepCopy().put("text","Changed"),"m1");fail();}catch(BusinessException e){assertEquals(409,e.getHttpStatus());}
        JsonNode receipt=call("POST","/receipts/lookup",obj().put("kind","MESSAGE").put("requestId","m1").put("conversationRef",cref).put("binding",body.path("binding").asText()),null);assertTrue(receipt.path("accepted").asBoolean());assertTrue(receipt.path("consumed").isNull());assertEquals(1,messages.get());
        JsonNode replies=call("GET","/conversations/"+cref+"/replies",null,null);assertEquals(1,replies.path("items").size());assertFalse(replies.toString().contains("private system"));
        String rref=replies.path("items").get(0).path("replyRef").asText();assertEquals("Public answer",call("GET","/conversations/"+cref+"/replies/"+rref+"/content",null,null).path("text").asText());
        ObjectNode forged=unref(rref,"r1").put("messageId","private");try{call("GET","/conversations/"+cref+"/replies/"+ref("r1",forged)+"/content",null,null);fail();}catch(BusinessException expected){assertEquals("SCOPE_DENIED",expected.getCode());}
        JsonNode pause=null;for(JsonNode a:view.path("actions"))if(a.path("actionId").asText().equals("execution.pause"))pause=a;assertNotNull(pause);
        ObjectNode control=obj().put("requestId","pause1").put("actionId","execution.pause").put("binding",pause.path("binding").asText());control.set("input",obj());JsonNode ack=call("POST","/conversations/"+cref+"/actions",control,"pause1");assertEquals("REQUESTED",ack.path("sourceStatus").asText());assertTrue(ack.path("consumed").isNull());
        fixture.auth.revoke(fixture.did);try{call("POST","/receipts/lookup",obj().put("kind","MESSAGE").put("requestId","m1").put("conversationRef",cref).put("binding",body.path("binding").asText()),null);fail();}catch(BusinessException denied){assertEquals(401,denied.getHttpStatus());}
    }
    @Test public void nativeLostReplyRetainsOriginalSourceKeyAndDoesNotExposePause()throws Exception{
        fixture.call("POST","/tasks",fixture.launch(),"original-launch");String aref=call("GET","/agents",null,null).path("items").get(0).path("agentRef").asText();String cref=call("GET","/agents/"+aref+"/conversations",null,null).path("items").get(0).path("conversationRef").asText();JsonNode view=call("GET","/conversations/"+cref,null,null);
        assertEquals("UNAVAILABLE",view.path("message").path("availability").asText());JsonNode steer=null;for(JsonNode a:view.path("actions")){if(a.path("actionId").asText().equals("execution.pause"))assertEquals("UNSUPPORTED",a.path("availability").asText());if(a.path("actionId").asText().equals("execution.supplement"))steer=a;}
        fixture.loseInput.set(true);ObjectNode body=obj().put("requestId","native-input").put("actionId","execution.supplement").put("binding",steer.path("binding").asText());body.putObject("input").put("text","More detail");
        assertTrue(call("POST","/conversations/"+cref+"/actions",body,"native-input").path("accepted").isNull());assertEquals(1,fixture.inputPosts.get());ObjectNode lookup=obj().put("kind","ACTION").put("requestId","native-input").put("conversationRef",cref).put("actionId","execution.supplement").put("binding",body.path("binding").asText());
        fixture.nativeState="SUCCEEDED";assertTrue(call("POST","/receipts/lookup",lookup,null).path("accepted").asBoolean());assertEquals(1,fixture.inputPosts.get());
        JsonNode page=call("GET","/conversations/"+cref+"/replies",null,null);assertEquals("TEXT_SNAPSHOT",page.path("representation").asText());assertEquals("BOUNDED_PUBLIC_OUTPUT",page.path("coverage").asText());assertFalse(page.toString().contains("private-workspace"));
    }
    @Test public void launchOnlyGrantDiscoversRegisteredEngineWithoutInventingATask()throws Exception{
        JsonNode agents=call("GET","/agents",null,null);assertEquals(1,agents.path("items").size());String agent=agents.path("items").get(0).path("agentRef").asText();assertEquals(0,call("GET","/agents/"+agent+"/conversations",null,null).path("items").size());assertEquals(0,fixture.launchPosts.get());
    }
    @Test public void codexDecisionPreservesScopeAndPiInputShapeIsSeparate()throws Exception{
        fixture.engineName="codex";PersonalAccessService.Device d=fixture.auth.authenticate(fixture.token);fixture.auth.updateScopeResources(fixture.did,"codex-scope",Long.toString(d.revision),Collections.emptyList(),Arrays.asList(new PersonalResourceScope.LaunchScope(fixture.backend,"project-one","codex")),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","APPROVE")));
        fixture.call("POST","/tasks",fixture.launch().put("requestKey","launch-codex").put("engineId","codex"),"launch-codex");fixture.question=obj().put("inputRequestId","q1").put("method","item/commandExecution/requestApproval").put("state","PENDING").put("generation","g1").put("nativeSessionId","native-session").putNull("nativeTurnId").put("targetCommandId","command-one").put("scope","Explicit command scope");fixture.question.putArray("options").add("accept").add("decline").add("cancel");
        String agent=call("GET","/agents",null,null).path("items").get(0).path("agentRef").asText();String cref=call("GET","/agents/"+agent+"/conversations",null,null).path("items").get(0).path("conversationRef").asText();JsonNode view=call("GET","/conversations/"+cref,null,null);JsonNode question=null;for(JsonNode a:view.path("actions"))if(a.path("actionId").asText().equals("input.respond"))question=a;
        assertEquals("DECISION",question.path("inputKind").asText());assertEquals("Explicit command scope",question.path("context").path("scope").asText());ObjectNode body=obj().put("requestId","approve1").put("actionId","input.respond").put("binding",question.path("binding").asText());body.putObject("input").put("text","arbitrary answer");try{call("POST","/conversations/"+cref+"/actions",body,"approve1");fail();}catch(BusinessException expected){assertEquals(422,expected.getHttpStatus());}
        body.set("input",obj().put("decision","decline"));assertTrue(call("POST","/conversations/"+cref+"/actions",body,"approve1").path("accepted").asBoolean());
    }
    @Test public void goldenVectorAndInvalidRefs()throws Exception{
        assertEquals("4448330948f702f32136c7efe0731f462042ce6e79bde74acf7dbfd2c44bcde3",sourceKey("daily-owner","DAILY","store-A","","CONVERSATION","conv-A","MESSAGE","req-1"));
        assertEquals("7111707a8a8eb599f762dc1c60f7f2bffb22adb8463b973ba0ec5c7ffb5309b0",sourceKey("device-1","WORKER_NATIVE","personal-A","worker-A","NATIVE_TASK","task-1","MESSAGE","req-1"));
        ObjectNode node=obj().put("sourceKind","FUTURE_HOST").put("storeId","s").putNull("nativeTurnId");assertEquals(node,unref(ref("b1",node),"b1"));
        try{unref("b1."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"storeId\":\"a\",\"storeId\":\"b\"}".getBytes("UTF-8")),"b1");fail();}catch(BusinessException expected){assertEquals(422,expected.getHttpStatus());}
    }
    @Test public void lockedVectorsMatchPythonAndBundleDigest()throws Exception{
        String root="/contracts/client-access/v1/";byte[] schema;try(java.io.InputStream in=getClass().getResourceAsStream(root+"client-access.schema.json");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){assertNotNull(in);byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))>=0)out.write(bytes,0,n);schema=out.toByteArray();}
        assertEquals(JSON.readTree(getClass().getResourceAsStream(root+"contract.lock.json")).path("schemaSha256").asText(),digest(schema));
        for(JsonNode vector:JSON.readTree(getClass().getResourceAsStream(root+"source-key-vectors.json"))){JsonNode v=vector.path("values");assertEquals(vector.path("sourceKey").asText(),sourceKey(v.get(1).asText(),v.get(2).asText(),v.get(3).asText(),v.get(4).asText(),v.get(5).asText(),v.get(6).asText(),v.get(7).asText(),v.get(8).asText()));}
        for(JsonNode vector:JSON.readTree(getClass().getResourceAsStream(root+"reference-vectors.json")))assertEquals(vector.path("value"),unref(vector.path("reference").asText(),vector.path("prefix").asText()));
    }
    @Test public void emptyLegacyPiOutputKeepsMessagesPathWithoutMacFallback()throws Exception {
        fixture.call("POST","/tasks",fixture.launch(),"original-launch");fixture.publicOutput="";
        JsonNode agents=call("GET","/agents",null,null);String agent=agents.path("items").get(0).path("agentRef").asText();
        String ref=call("GET","/agents/"+agent+"/conversations",null,null).path("items").get(0).path("conversationRef").asText();
        JsonNode replies=call("GET","/conversations/"+ref+"/replies",null,null);
        assertEquals("",replies.path("snapshot").path("text").asText());
        String reply=replies.path("snapshot").path("replyRef").asText();
        assertEquals("",call("GET","/conversations/"+ref+"/replies/"+reply+"/content",null,null).path("text").asText());
        // The legacy fixture has no /output endpoint; accidental fallback would fail above.
    }

}
