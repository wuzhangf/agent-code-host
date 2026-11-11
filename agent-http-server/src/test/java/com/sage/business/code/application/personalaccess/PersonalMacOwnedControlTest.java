package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;

/** Production PAC commands and original upload identity, ephemeral loopback source. */
public class PersonalMacOwnedControlTest {
 PersonalMacMaterialUploadTest upload;
 PersonalLocalTaskAccessTest f;
 ObjectNode facts,lastBody;
 boolean lose;
 @Before public void setup() throws Exception {
  upload=new PersonalMacMaterialUploadTest();upload.setup();f=upload.f;grant();
  facts=f.json.createObjectNode().put("taskId","native-one").put("projectId","project-one").put("recipeID","mac.native-work/1").put("engineId","macos").put("state","PAUSED_MATERIALS").put("quiescent",true).put("runtimeActive",false).put("materialPauseReady",true).put("generation","g1").put("nativeSessionId","native-session").put("currentCommandId","command-one");
  facts.putObject("nativeWorkSpec").put("engine","OWNED_FRAMEWORK");facts.putObject("capabilities").put("pause",true).put("continue",true).put("resume",false).put("stop",true);
  f.host.createContext("/local/v1/tasks/native-one",x->{try{
   assertEquals("Bearer "+f.secret,x.getRequestHeaders().getFirst("Authorization"));
   String path=x.getRequestURI().getPath();
   if(path.endsWith("/commands")){
    f.inputPosts.incrementAndGet();lastBody=(ObjectNode)f.json.readTree(x.getRequestBody());
    ObjectNode result=f.json.createObjectNode().put("requestKey",lastBody.path("requestKey").asText()).put("taskId","native-one").put("kind",lastBody.path("intent").asText()).put("state","APPLIED");f.inputs.put(lastBody.path("requestKey").asText(),result);
    if(lose){lose=false;x.close();return;}f.send(x,200,result);return;
   }
   if(path.contains("/interaction-requests/")){JsonNode r=f.inputs.get(path.substring(path.lastIndexOf('/')+1));f.send(x,r==null?404:200,r==null?f.json.createObjectNode().put("code","NOT_FOUND"):r);return;}
   f.send(x,200,facts);
  }catch(Throwable e){x.close();}});
 }
 void grant(){String revision=Long.toString(f.auth.authenticate(f.token).revision);f.auth.updateScopeResources(f.did,"control-scope-"+revision,revision,Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",f.backend,"native-one")),Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one","macos")),new TreeSet<>(Arrays.asList("OBSERVE","CONTROL","LAUNCH","INTERACT")));}
 @After public void close()throws Exception{upload.close();}
 ObjectNode command(String key,String intent,boolean material){ObjectNode b=f.json.createObjectNode().put("requestKey",key).put("intent",intent).put("generation","g1").put("nativeSessionId","native-session").putNull("nativeTurnId").put("targetCommandId","command-one");if(material)b.putArray("sealedUploadKeys").add("client-one");return b;}
 String sealed()throws Exception{upload.beginCall();f.call("POST","/workspaces/project-one/uploads/client-one/chunks",f.json.createObjectNode().put("requestKey","client-one-chunk-0").put("offset",0).put("sha256",upload.hash).put("dataBase64",Base64.getEncoder().encodeToString("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8))),"client-one-chunk-0");f.call("POST","/workspaces/project-one/uploads/client-one/seal",f.json.createObjectNode().put("requestKey","client-one-seal"),"client-one-seal");return upload.uploads.keySet().iterator().next();}
 @Test public void continueUsesExactSealedDeviceRevisionKeyAndLostReplyOnlyLooksUp()throws Exception{
  String source=sealed();lose=true;ObjectNode b=command("continue-original","CONTINUE",true);
  assertEquals("UNKNOWN",f.call("POST","/tasks/native-one/commands",b,"continue-original").body.path("state").asText());assertEquals(source,lastBody.path("sealedUploadKeys").get(0).asText());assertNotEquals("client-one",source);
  facts.path("capabilities");((ObjectNode)facts.path("capabilities")).put("continue",false);
  f.access=new PersonalLocalTaskAccess("http://127.0.0.1:"+f.host.getAddress().getPort(),f.secret,f.records,f.auth,f.json);
  assertEquals("OBSERVED",f.call("GET","/tasks/native-one/requests/continue-original",null,null).body.path("state").asText());f.call("POST","/tasks/native-one/commands",b,"continue-original");assertEquals(1,f.inputPosts.get());
 }
 @Test public void pauseAndColdContinueAreTypedNotLegacyResume()throws Exception{f.call("POST","/tasks/native-one/commands",command("pause-original","PAUSE",false),"pause-original");assertEquals("PAUSE",lastBody.path("intent").asText());f.call("POST","/tasks/native-one/commands",command("continue-cold","CONTINUE",false),"continue-cold");assertEquals("CONTINUE",lastBody.path("intent").asText());}
 @Test public void changedPayloadNeverReusesTheOriginalContinue()throws Exception{sealed();ObjectNode b=command("continue-original","CONTINUE",true);f.call("POST","/tasks/native-one/commands",b,"continue-original");ObjectNode other=b.deepCopy();other.putArray("sealedUploadKeys").add("different");f.denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->f.call("POST","/tasks/native-one/commands",other,"continue-original"));assertEquals(1,f.inputPosts.get());}
 @Test public void oldRevisionCannotAdoptPreviousUpload()throws Exception{sealed();grant();f.denied("NOT_FOUND",()->f.call("POST","/tasks/native-one/commands",command("continue-rev","CONTINUE",true),"continue-rev"));assertEquals(0,f.inputPosts.get());}
 @Test public void unsealedAndNonQuietMaterialAreRejectedBeforeCommandDispatch()throws Exception{upload.beginCall();f.denied("MATERIAL_UPLOAD_UNCONFIRMED",()->f.call("POST","/tasks/native-one/commands",command("continue-open","CONTINUE",true),"continue-open"));facts.put("quiescent",false);f.denied("MATERIAL_PAUSE_REQUIRED",()->f.call("POST","/tasks/native-one/commands",command("continue-running","CONTINUE",true),"continue-running"));assertEquals(0,f.inputPosts.get());}
 @Test public void taskProjectCannotBorrowAnotherWorkspaceUpload()throws Exception{sealed();facts.put("projectId","sibling");f.denied("DEVICE_SCOPE_DENIED",()->f.call("POST","/tasks/native-one/commands",command("continue-other-project","CONTINUE",true),"continue-other-project"));assertEquals(0,f.inputPosts.get());}
 @Test public void revokeBetweenSealLookupAndCommandPreventsDispatch()throws Exception{String source=sealed();f.host.createContext("/native/v1/workspaces/project-one/uploads/"+source,x->{f.auth.revoke(f.did);f.send(x,200,upload.uploads.get(source));});f.denied("DEVICE_REVOKED",()->f.call("POST","/tasks/native-one/commands",command("continue-revoked","CONTINUE",true),"continue-revoked"));assertEquals(0,f.inputPosts.get());}
}
