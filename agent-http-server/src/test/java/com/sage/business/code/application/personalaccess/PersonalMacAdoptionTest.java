package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** FIXTURE_ONLY: actual authenticated HTTP; no Apple tools, model or production account. */
public class PersonalMacAdoptionTest {
    PersonalLocalTaskAccessTest f;ClientAccessService uca;
    boolean wrongProfile,badArtifactHash,withheldArtifact;
    String engineAvailability="AVAILABLE",engineReason="VALIDATION_PROFILE_UNCONFIGURED";
    String publicSummary="ACCEPTANCE_SATISFIED";
    final java.util.concurrent.atomic.AtomicInteger messageReads=new java.util.concurrent.atomic.AtomicInteger(),outputReads=new java.util.concurrent.atomic.AtomicInteger();
    final byte[] artifact="Immutable Mac verification evidence".getBytes(StandardCharsets.UTF_8);
    final String artifactId="file-"+ClientAccessWire.digest("verification-report\0native-one".getBytes(StandardCharsets.UTF_8));
    @Before public void setup()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();f.engineName="macos";
        PersonalAccessService.Device d=f.auth.authenticate(f.token);
        f.auth.updateScopeResources(f.did,"mac-adoption",Long.toString(d.revision),Collections.emptyList(),
            Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one","macos")),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","APPROVE")));
        f.host.removeContext("/local/v1/");f.host.createContext("/local/v1/",this::source);
        uca=new ClientAccessService(f.auth,f.access,"acs-store","mac-fixture-boot",(device,method,path,body,key)->{throw new AssertionError("No ACS execution belongs to a Mac task");});
    }
    @After public void close()throws Exception {if(f!=null)f.cleanup();}
    ObjectNode obj(){return f.json.createObjectNode();}
    ObjectNode launch(){return f.launch().put("engineId","macos");}
    void source(HttpExchange x){try {
        String path=x.getRequestURI().getPath().substring("/local/v1".length()),method=x.getRequestMethod();
        if(!("Bearer "+f.secret).equals(x.getRequestHeaders().getFirst("Authorization"))){f.send(x,401,obj().put("code","LOCAL_AUTH_REQUIRED"));return;}
        String store=x.getRequestHeaders().getFirst("X-Worker-Local-Store");if(store!=null&&!store.equals(f.backend)){f.send(x,409,obj().put("code","STORE_IDENTITY_CHANGED"));return;}
        if(path.equals("/engines")){ObjectNode out=obj();out.putArray("items").addObject().put("engineId","macos").put("version","fixture").put("profileId","MAC_MANAGED").put("availability",engineAvailability).put("reasonCode",engineReason);f.send(x,200,out);return;}
        if(path.equals("/task-preflights")){
            ObjectNode out=obj().put("engineId","macos").put("projectId","project-one").put("profileId",wrongProfile?"USER_PROFILE":"MAC_MANAGED").putNull("profileHome").put("loadsUserExtensions",false).put("hasUncommittedChanges",false).put("headRevision","fixture-head").put("workingCopyPolicy","COMMITTED_BASE_ONLY").put("optionsRevision","frozen-options");
            ObjectNode mac=out.putObject("macProfile").put("productProfile","MAC_BUILD").put("role","APPLE_VALIDATOR").put("availability","AVAILABLE").put("environmentDigest","environment").put("targetBindingDigest","target");
            mac.putObject("toolchain").put("developerDirectory","/private/fixture-toolchain").put("toolchainVersion","Xcode fixture");
            mac.putObject("verificationTemplate").put("scheme","FrozenScheme").put("developerDirectory","/private/fixture-toolchain").put("configuration","Debug");
            f.send(x,200,out);return;
        }
        if(path.equals("/tasks")&&method.equals("POST")){
            JsonNode body=f.json.readTree(x.getRequestBody());assertEquals(5,body.size());assertEquals(body.path("requestKey").asText(),x.getRequestHeaders().getFirst("Idempotency-Key"));f.launchPosts.incrementAndGet();
            ObjectNode receipt=obj().put("taskId","native-one").put("engineId","macos").put("engineVersion","fixture").put("profileId",wrongProfile?"USER_PROFILE":"MAC_MANAGED").put("state","ACCEPTED");f.launches.put(body.path("requestKey").asText(),receipt);
            if(f.loseLaunch.getAndSet(false)){x.close();return;}f.send(x,202,receipt);return;
        }
        if(path.equals("/tasks/native-one")&&method.equals("GET")){
            ObjectNode out=obj().put("taskId","native-one").put("engineId","macos").put("profileId","MAC_MANAGED").put("state","RECOVERY_REQUIRED").put("generation","g1").put("nativeSessionId","mac-session-one").put("nativeTurnId","turn-one").put("currentCommandId","command-one").put("localQuiescent",false).put("remoteStopConfirmed",true).put("quiescent",false);
            out.putObject("capabilities").put("stop",true).put("steer",false).put("followUp",false).put("pause",false).put("resume",false);
            out.putObject("clientAccess").put("conversationRef","forged-by-source");f.send(x,200,out);return;
        }
        if(path.equals("/tasks/native-one/messages")&&method.equals("GET")){
            messageReads.incrementAndGet();ObjectNode messages=obj().put("coverage","NO_CONVERSATIONAL_MODEL");messages.putArray("items");f.send(x,200,messages);return;
        }
        if(path.equals("/tasks/native-one/output")&&method.equals("GET")){
            outputReads.incrementAndGet();f.send(x,200,obj().put("text",publicSummary).put("coverage","PUBLIC_VERIFICATION_SUMMARY"));return;
        }
        if(path.equals("/tasks/native-one/commands")&&method.equals("POST")){
            JsonNode body=f.json.readTree(x.getRequestBody());assertEquals("STOP",body.path("intent").asText());f.inputPosts.incrementAndGet();
            ObjectNode receipt=obj().put("requestKey",body.path("requestKey").asText()).put("state","REQUESTED");f.inputs.put(body.path("requestKey").asText(),receipt);f.send(x,202,receipt);return;
        }
        if(path.equals("/tasks/native-one/artifacts")){
            ObjectNode out=obj().put("coverage","MAC_VERIFICATION_EVIDENCE").put("bounded",false);out.putArray("items").addObject().put("artifactId",artifactId).put("path","verification-report").put("status",withheldArtifact?"WITHHELD_SIZE":"AVAILABLE").put("size",artifact.length).put("sha256",ClientAccessWire.digest(artifact));f.send(x,200,out);return;
        }
        if(path.equals("/tasks/native-one/artifacts/"+artifactId)){
            f.send(x,200,obj().put("artifactId",artifactId).put("path","verification-report").put("sha256",badArtifactHash?String.join("",Collections.nCopies(64,"0")):ClientAccessWire.digest(artifact)).put("encoding","base64").put("bytes",Base64.getEncoder().encodeToString(artifact)));return;
        }
        f.handle(x);
    }catch(Throwable failure){f.send(x,500,obj().put("code","FIXTURE_FAILURE"));}}
    void granted()throws Exception {assertEquals("OBSERVED",f.call("POST","/tasks",launch(),"original-launch").body.path("state").asText());}
    @Test public void macIsAnExplicitLaunchScopeAndUnknownEngineStillDenied()throws Exception {
        PersonalResourceScope.checkedLaunchScopes(Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one","macos")));
        f.denied("INVALID_SCOPE",()->PersonalResourceScope.checkedLaunchScopes(Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one","unknown"))));
        f.denied("DEVICE_SCOPE_DENIED",()->f.call("POST","/tasks",launch().put("engineId","codex"),"original-launch"));assertEquals(0,f.launchPosts.get());
    }
    @Test public void discoveryAndPreflightPreserveMacFactsWithoutHostPaths()throws Exception {
        JsonNode catalog=f.access.ownerCatalog();assertEquals("MAC_MANAGED",catalog.path("engines").get(0).path("profileId").asText());assertEquals("AVAILABLE",catalog.path("engines").get(0).path("availability").asText());
        JsonNode preflight=f.call("POST","/task-preflights",obj().put("projectId","project-one").put("engineId","macos"),null).body;
        assertEquals("MAC_MANAGED",preflight.path("profileId").asText());assertTrue(preflight.path("profileHome").isNull());assertFalse(preflight.path("loadsUserExtensions").asBoolean());assertFalse(preflight.toString().contains("/private/fixture"));assertEquals("FrozenScheme",preflight.path("macProfile").path("verificationTemplate").path("scheme").asText());
        wrongProfile=true;f.denied("LOCAL_PROFILE_NOT_SUPPORTED",()->f.call("POST","/task-preflights",obj().put("projectId","project-one").put("engineId","macos"),null));
    }
    @Test public void originalFiveFieldLaunchRecoversAndProjectsTheSameGrantedUcaTask()throws Exception {
        f.loseLaunch.set(true);assertEquals("UNKNOWN",f.call("POST","/tasks",launch(),"original-launch").body.path("state").asText());
        assertEquals("OBSERVED",f.call("GET","/task-requests/original-launch",null,null).body.path("state").asText());assertEquals(1,f.launchPosts.get());
        JsonNode detail=f.call("GET","/tasks/native-one",null,null).body,projection=detail.path("clientAccess");
        assertEquals("client-access/1",projection.path("protocolVersion").asText());assertEquals("acs-store",projection.path("storeId").asText());assertTrue(projection.path("conversationRef").asText().startsWith("c1."));
        JsonNode manifest=uca.handle(f.token,"GET","/manifest",null,null,null);assertEquals(manifest.path("storeId"),projection.path("storeId"));
        JsonNode view=uca.handle(f.token,"GET","/conversations/"+projection.path("conversationRef").asText(),null,null,null);assertEquals("RECOVERY_REQUIRED",view.path("sourceState").asText());assertEquals(projection.path("agentRef"),view.path("agentRef"));
        assertFalse(detail.path("quiescent").asBoolean());f.denied("DEVICE_SCOPE_DENIED",()->f.call("GET","/tasks/sibling",null,null));
        f.call("POST","/tasks",launch(),"original-launch");assertEquals(1,f.launchPosts.get());
    }
    @Test public void macCannotMasqueradeAsUserProfileOrAcceptTypedPathOverrides()throws Exception {
        f.denied("INVALID_REQUEST",()->f.call("POST","/tasks",launch().set("macVerificationSpec",obj().put("developerDirectory","/private/untrusted")),"original-launch"));assertEquals(0,f.launchPosts.get());
        wrongProfile=true;JsonNode rejected=f.call("POST","/tasks",launch(),"original-launch").body;assertEquals("UNKNOWN",rejected.path("state").asText());assertEquals("LOCAL_PROFILE_NOT_SUPPORTED",rejected.path("code").asText());assertEquals(1,f.launchPosts.get());f.denied("DEVICE_SCOPE_DENIED",()->f.auth.authorizeLocal(f.token,f.backend,"native-one","OBSERVE"));
    }
    @Test public void guardedStopKeepsRequestedFactAndOriginalKey()throws Exception {
        granted();ObjectNode stop=obj().put("requestKey","original-stop").put("intent","STOP").put("generation","g1").put("nativeSessionId","mac-session-one").put("nativeTurnId","turn-one").put("targetCommandId","command-one");
        assertEquals("REQUESTED",f.call("POST","/tasks/native-one/commands",stop,"original-stop").body.path("source").path("state").asText());
        f.call("POST","/tasks/native-one/commands",stop,"original-stop");assertEquals(1,f.inputPosts.get());
    }
    @Test public void artifactsRemainScopedBoundedAndHashVerified()throws Exception {
        granted();JsonNode bytes=f.call("GET","/tasks/native-one/artifacts/"+artifactId,null,null).body;assertArrayEquals(artifact,Base64.getDecoder().decode(bytes.path("bytes").asText()));
        badArtifactHash=true;f.denied("LOCAL_ARTIFACT_CHANGED",()->f.call("GET","/tasks/native-one/artifacts/"+artifactId,null,null));badArtifactHash=false;withheldArtifact=true;f.denied("LOCAL_ARTIFACT_CHANGED",()->f.call("GET","/tasks/native-one/artifacts/"+artifactId,null,null));
        f.denied("DEVICE_SCOPE_DENIED",()->f.call("GET","/tasks/sibling/artifacts/"+artifactId,null,null));
    }
    @Test public void unconfiguredMacIsUnavailableInUcaDirectoryWithoutLaunching()throws Exception {
        engineAvailability="UNCONFIGURED";
        JsonNode rows=uca.handle(f.token,"GET","/agents",null,null,null).path("items");
        assertEquals(1,rows.size());assertEquals("macos",rows.get(0).path("engineId").asText());
        assertEquals("UNAVAILABLE",rows.get(0).path("availability").asText());
        assertEquals("VALIDATION_PROFILE_UNCONFIGURED",rows.get(0).path("reasonCode").asText());
        engineAvailability="UNAVAILABLE";engineReason="APPLE_TOOLCHAIN_UNAVAILABLE";
        rows=uca.handle(f.token,"GET","/agents",null,null,null).path("items");
        assertEquals("UNAVAILABLE",rows.get(0).path("availability").asText());assertEquals(engineReason,rows.get(0).path("reasonCode").asText());
        engineAvailability="AVAILABLE";
        rows=uca.handle(f.token,"GET","/agents",null,null,null).path("items");assertEquals("AVAILABLE",rows.get(0).path("availability").asText());
        assertEquals(0,f.launchPosts.get());
    }

    @Test public void macPublicSummaryUsesOriginalOutputForSnapshotAndRevisionBoundContent()throws Exception {
        granted();String ref=f.call("GET","/tasks/native-one",null,null).body.path("clientAccess").path("conversationRef").asText();
        publicSummary=String.join("",Collections.nCopies(1800,"Mac acceptance 中文🙂 "));
        String route="/conversations/"+ref;
        JsonNode replies=uca.handle(f.token,"GET",route+"/replies",null,null,null),snapshot=replies.path("snapshot");
        String revision=ClientAccessWire.digest(publicSummary.getBytes(StandardCharsets.UTF_8));
        assertEquals("BOUNDED_PUBLIC_OUTPUT",replies.path("coverage").asText());assertEquals(revision,replies.path("revision").asText());assertEquals(revision,snapshot.path("contentRevision").asText());assertFalse(snapshot.path("text").asText().isEmpty());assertFalse(snapshot.path("textComplete").asBoolean());
        String reply=snapshot.path("replyRef").asText(),contentRoute=route+"/replies/"+reply+"/content";
        StringBuilder actual=new StringBuilder();String cursor=null,firstCursor=null;
        for(int i=0;i<10;i++){
            JsonNode page=uca.handle(f.token,"GET",contentRoute,null,null,cursor==null?null:"cursor="+cursor);
            assertEquals(revision,page.path("contentRevision").asText());actual.append(page.path("text").asText());
            if(page.path("complete").asBoolean())break;cursor=page.path("nextCursor").asText();if(firstCursor==null)firstCursor=cursor;
        }
        assertEquals(publicSummary,actual.toString());assertEquals(messageReads.get(),outputReads.get());assertTrue(outputReads.get()>1);
        assertTrue(uca.handle(f.token,"GET",route+"/replies",null,null,"ifRevision="+revision).path("unchanged").asBoolean());
        publicSummary += "changed";final String stale=firstCursor;
        f.denied("CONTENT_REVISION_CHANGED",()->uca.handle(f.token,"GET",contentRoute,null,null,"cursor="+stale));
        int before=outputReads.get();f.auth.revoke(f.did);
        f.denied("DEVICE_REVOKED",()->uca.handle(f.token,"GET",route+"/replies",null,null,null));assertEquals(before,outputReads.get());
    }

}
