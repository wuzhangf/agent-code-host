package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpExchange;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;

/** Real scoped PAC transport; deterministic source. No GUI or model qualification. */
public class PersonalNativeOfficeAccessTest {
    PersonalLocalTaskAccessTest f;
    ObjectNode preflight,lastWire;
    boolean declared=true,badDetail=false,documents=false;
    ObjectNode obj(){return f.json.createObjectNode();}
    @Before public void setup()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();f.engineName="macos";
        PersonalAccessService.Device d=f.auth.authenticate(f.token);
        f.auth.updateScopeResources(f.did,"office-adopt",Long.toString(d.revision),Collections.emptyList(),
            Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one","macos")),new TreeSet<>(Arrays.asList("OBSERVE","LAUNCH","CONTROL")));
        preflight=obj().put("projectId","project-one").put("engineId","macos").put("engineVersion","0.1.0").put("profileId","MAC_MANAGED")
            .putNull("profileHome").put("loadsUserExtensions",false).putNull("headRevision").putNull("treeRevision").put("hasUncommittedChanges",false)
            .put("workingCopyPolicy","TASK_OWNED_OUTPUT").put("optionsRevision","frozen-office");
        preflight.putObject("macProfile").put("recipeID",NativeOfficeContract.RECIPE).put("protocolVersion",NativeOfficeContract.PROTOCOL)
            .put("kind","OFFICE_WORKSPACE").put("catalogDigest",String.join("",Collections.nCopies(64,"a"))).put("workspaceIdentity","private-inode").put("root","/private/office")
            .putArray("engines").add("EXTERNAL_TOOLS").add("OWNED_FRAMEWORK");
        f.host.removeContext("/local/v1/");f.host.createContext("/local/v1/",this::source);
        f.host.createContext("/worker-host/v1/manifest",x->{ObjectNode out=obj();if(declared){ArrayNode contracts=out.putArray("contracts").add(NativeOfficeContract.PROTOCOL);if(documents)contracts.add("mac-native-documents/1");}f.send(x,200,out);});
    }
    @After public void close()throws Exception {f.cleanup();}
    ObjectNode spec(){ObjectNode value=obj().put("schemaVersion","mac-native-work-spec/1").put("recipeID",NativeOfficeContract.RECIPE)
        .put("workspaceRef","project-one").put("goal","Prepare report").put("engine","EXTERNAL_TOOLS");
        value.putArray("requiredCapabilities").add("mac.files.read").add("mac.files.write");
        value.putArray("acceptance").addObject().put("name","report.txt").put("containsText","report");return value;}
    ObjectNode create(){ObjectNode value=f.launch().put("engineId","macos").put("instruction","Prepare report").put("optionsRevision","frozen-office");value.set("nativeWorkSpec",spec());return value;}
    void source(HttpExchange x){try{
        String path=x.getRequestURI().getPath();
        if(path.equals("/local/v1/tasks/native-one")&&badDetail){ObjectNode detail=obj().put("taskId","native-one").put("engineId","macos").put("recipeID",NativeOfficeContract.RECIPE);ObjectNode input=spec();input.put("hostRoot","/private/office");detail.set("nativeWorkSpec",input);f.send(x,200,detail);return;}
        if(path.equals("/local/v1/projects")){ObjectNode catalog=obj();catalog.putArray("items").addObject().put("projectId","project-one").put("displayName","Office").put("kind","OFFICE_WORKSPACE").put("recipeID",NativeOfficeContract.RECIPE).put("path","/private/office");f.send(x,200,catalog);return;}
        if(path.equals("/local/v1/task-preflights")){f.send(x,200,preflight);return;}
        if(path.equals("/local/v1/tasks")&&x.getRequestMethod().equals("POST")){
            lastWire=(ObjectNode)f.json.readTree(x.getRequestBody());f.launchPosts.incrementAndGet();
            assertEquals(lastWire.path("requestKey").asText(),x.getRequestHeaders().getFirst("Idempotency-Key"));
            ObjectNode receipt=obj().put("taskId","native-one").put("engineId","macos").put("profileId","MAC_MANAGED").put("state","ACCEPTED");
            f.launches.put(lastWire.path("requestKey").asText(),receipt);
            if(f.loseLaunch.getAndSet(false)){x.close();return;}f.send(x,202,receipt);return;
        }
        f.handle(x);
    }catch(Throwable error){f.send(x,500,obj().put("code","FIXTURE_FAILURE"));}}
    @Test public void officeAdoptionPreservesNonGitPolicyAndFiltersHostPaths()throws Exception {
        JsonNode catalog=f.access.ownerCatalog();assertEquals("OFFICE_WORKSPACE",catalog.path("projects").get(0).path("kind").asText());assertFalse(catalog.toString().contains("/private/office"));
        JsonNode view=f.call("POST","/task-preflights",obj().put("projectId","project-one").put("engineId","macos"),null).body;
        assertEquals("TASK_OWNED_OUTPUT",view.path("workingCopyPolicy").asText());assertTrue(view.path("headRevision").isNull());
        assertEquals(NativeOfficeContract.PROTOCOL,view.path("macProfile").path("protocolVersion").asText());
        assertEquals(2,view.path("macProfile").path("engines").size());assertFalse(view.toString().contains("private-inode"));assertFalse(view.toString().contains("/private/office"));
    }
    @Test public void lostOfficeCreationRecoversOriginalSpecWithoutAnotherPost()throws Exception {
        ObjectNode body=create();f.loseLaunch.set(true);
        assertEquals("UNKNOWN",f.call("POST","/tasks",body,"original-launch").body.path("state").asText());
        assertEquals(spec(),lastWire.path("nativeWorkSpec"));assertNotEquals("original-launch",lastWire.path("requestKey").asText());
        f.access=new PersonalLocalTaskAccess("http://127.0.0.1:"+f.host.getAddress().getPort(),f.secret,f.records,f.auth,f.json);
        assertEquals("OBSERVED",f.call("GET","/task-requests/original-launch",null,null).body.path("state").asText());
        f.call("POST","/tasks",body,"original-launch");assertEquals(1,f.launchPosts.get());
        ObjectNode changed=create();((ObjectNode)changed.path("nativeWorkSpec")).put("goal","changed");
        f.denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->f.call("POST","/tasks",changed,"original-launch"));assertEquals(1,f.launchPosts.get());
    }
    @Test public void undeclaredOrUnsupportedOfficeProfileNeverDispatches()throws Exception {
        declared=false;f.denied("LOCAL_SOURCE_UPGRADE_REQUIRED",()->f.call("POST","/tasks",create(),"original-launch"));
        declared=true;preflight.with("macProfile").put("protocolVersion","mac-native-capabilities/2");
        f.denied("LOCAL_PROFILE_NOT_SUPPORTED",()->f.call("POST","/tasks",create(),"original-launch"));assertEquals(0,f.launchPosts.get());
    }
    @Test public void documentCapabilityRequiresSeparateSourceDeclaration()throws Exception {
        ObjectNode body=create().put("requestKey","document-launch");((ArrayNode)body.path("nativeWorkSpec").path("requiredCapabilities")).add("mac.documents.extract");
        f.denied("LOCAL_SOURCE_UPGRADE_REQUIRED",()->f.call("POST","/tasks",body,"document-launch"));
        assertEquals(0,f.launchPosts.get());documents=true;
        f.call("POST","/tasks",body,"document-launch");assertEquals(1,f.launchPosts.get());
        assertEquals(body.path("nativeWorkSpec"),lastWire.path("nativeWorkSpec"));
    }
    @Test public void mismatchedWorkspaceGoalAndLegacyRecipeCannotReceiveOfficeSpec()throws Exception {
        ObjectNode mismatch=create();((ObjectNode)mismatch.path("nativeWorkSpec")).put("workspaceRef","other");
        f.denied("INVALID_NATIVE_WORK_SPEC",()->f.call("POST","/tasks",mismatch,"original-launch"));
        f.denied("INVALID_NATIVE_WORK_SPEC",()->f.call("POST","/tasks",create().put("instruction","different"),"original-launch"));
        ObjectNode old=create();old.remove("nativeWorkSpec");f.denied("INVALID_NATIVE_WORK_SPEC",()->f.call("POST","/tasks",old,"original-launch"));
        preflight.with("macProfile").put("recipeID","apple.verify-and-repair/1");
        f.denied("LOCAL_PROFILE_NOT_SUPPORTED",()->f.call("POST","/tasks",create(),"original-launch"));assertEquals(0,f.launchPosts.get());
    }
    @Test public void sharedOfficeSpecVectorsHaveIdenticalStructuralAndUtf8Results()throws Exception {
        JsonNode vectors=f.json.readTree(getClass().getResourceAsStream("/mac-native/office-vectors.json"));
        for(JsonNode vector:vectors.path("vectors")){
            boolean valid=true;try{NativeOfficeContract.validate(vector.path("spec"));}catch(IllegalArgumentException error){valid=false;}
            assertEquals(vector.path("name").asText(),vector.path("valid").asBoolean(),valid);
        }
    }
    @Test public void aggregateBodyLimitRejectsBeforeCreatingDispatchIntent()throws Exception {
        ObjectNode body=create();com.fasterxml.jackson.databind.node.ArrayNode requirements=f.json.createArrayNode();
        for(int i=0;i<16;i++)requirements.addObject().put("name","report"+i+".txt").put("containsText",String.join("",Collections.nCopies(4096,"x")));
        ((ObjectNode)body.path("nativeWorkSpec")).set("acceptance",requirements);
        f.denied("NATIVE_WORK_REQUEST_TOO_LARGE",()->f.call("POST","/tasks",body,"original-launch"));assertEquals(0,f.launchPosts.get());
    }

    @Test public void translatedOriginalKeyMustAlsoFitBeforeSavingIntent()throws Exception {
        ObjectNode body=create();com.fasterxml.jackson.databind.node.ArrayNode requirements=f.json.createArrayNode();
        for(int i=0;i<16;i++)requirements.addObject().put("name","report"+i+".txt").put("containsText",String.join("",Collections.nCopies(4096,"x")));
        ((ObjectNode)body.path("nativeWorkSpec")).set("acceptance",requirements);
        int excess=f.json.writeValueAsBytes(body).length-65520;
        ((ObjectNode)requirements.get(15)).put("containsText",String.join("",Collections.nCopies(4096-excess,"x")));
        assertEquals(65520,f.json.writeValueAsBytes(body).length);
        f.denied("NATIVE_WORK_REQUEST_TOO_LARGE",()->f.call("POST","/tasks",body,"original-launch"));assertEquals(0,f.launchPosts.get());
        f.denied("NOT_FOUND",()->f.call("GET","/task-requests/original-launch",null,null));
    }

    @Test public void unknownNestedOfficeFieldsAreNotForwardedAsPublicTaskFacts()throws Exception {
        f.call("POST","/tasks",create(),"original-launch");badDetail=true;
        f.denied("LOCAL_REPLY_INVALID",()->f.call("GET","/tasks/native-one",null,null));
    }

}
