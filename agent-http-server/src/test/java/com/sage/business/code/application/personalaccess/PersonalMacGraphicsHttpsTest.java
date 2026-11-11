package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;
/** Production TLS adapter + real authenticated HTTP source; fixture facts are not platform qualification. */
public class PersonalMacGraphicsHttpsTest {
    PersonalMacAdoptionTest mac;PersonalAccessServer server;SSLContext tls;boolean enabled=true,contract=true,valid=true;String recipe="mac.app-workflow/1",epoch="1";
    boolean injectReadPayload;
    AtomicInteger posts=new AtomicInteger(),reads=new AtomicInteger(),artifactReads=new AtomicInteger();boolean lose,revokeDuring;Map<String,JsonNode> receipts=new HashMap<>();String root;
    ObjectNode obj(){return mac.obj();}
    ObjectNode profile(){ObjectNode p=obj().put("id","project-one").put("bundleID","com.sage.graphics.fixture").put("windowTitle","Fixture").put("helperPath","/private/secret");p.putArray("allowedActions").add("capture").add("press").add("setValue");p.putArray("allowedElementIdentifiers").add("fixture.input").add("fixture.apply");return p;}
    @Before public void setup()throws Exception {
        mac=new PersonalMacAdoptionTest();mac.setup();mac.f.host.removeContext("/local/v1/");mac.f.host.createContext("/local/v1/",this::source);mac.f.host.createContext("/worker-host/v1/",this::source);
        scope("OBSERVE","LAUNCH","CONTROL","GRAPHICS_OBSERVE","GRAPHICS_CAPTURE","GRAPHICS_ACT");
        MacPacFixtureMain.TlsMaterial material=MacPacFixtureMain.ephemeralTls(mac.f.root);tls=material.context;
        server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,material.certificate,mac.f.auth,new ClientSessionRegistry("acs-store","graphics-tls-boot"),mac.f.json,new URI("http://127.0.0.1:"+mac.f.host.getAddress().getPort()),UUID.randomUUID().toString()).localTasks(mac.f.access);server.start();root="/personal/v1/local-tasks/"+mac.f.backend;
    }
    @After public void close()throws Exception {if(server!=null)server.close();if(mac!=null)mac.close();}
    void scope(String...actions){PersonalAccessService.Device d=mac.f.auth.authenticate(mac.f.token);mac.f.auth.updateScopeResources(mac.f.did,UUID.randomUUID().toString(),Long.toString(d.revision),Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",mac.f.backend,"native-one")),Arrays.asList(new PersonalResourceScope.LaunchScope(mac.f.backend,"project-one","macos")),new TreeSet<>(Arrays.asList(actions)));}
    ObjectNode task(){ObjectNode t=obj().put("taskId","native-one").put("projectId","project-one").put("engineId","macos").put("profileId","MAC_MANAGED").put("recipeID",recipe).put("generation","g1").put("nativeSessionId","mac-session-one").put("segmentId","segment-one").put("ownerEpoch",epoch).put("graphicsObservationRevision","revision-one").put("graphicsObservationValid",valid).put("state","WAITING_INPUT");t.set("graphicsProfile",profile());if(injectReadPayload){t.putObject("observation").putArray("elements").addObject().put("value","private-AX-value");t.put("screenshot","private-PNG-bytes");}return t;}
    ObjectNode body(String key,String route){ObjectNode b=obj().put("requestKey",key);b.putObject("guard").put("generation","g1").put("nativeSessionId","mac-session-one").put("segmentId","segment-one").put("ownerEpoch","1");if(!route.equals("observe"))b.put("observationRevision","revision-one");if(route.equals("actions"))b.put("action","press").put("elementIdentifier","fixture.apply");return b;}
    ObjectNode artifact(){ObjectNode a=obj().put("artifactId",mac.artifactId).put("path","graphics-capture.png").put("status","AVAILABLE").put("size",mac.artifact.length).put("sha256",ClientAccessWire.digest(mac.artifact)).put("mediaType","image/png").put("observationRevision","revision-one").put("segmentId","segment-one").put("ownerEpoch","1").put("capturedAt","2026-10-25T00:00:00Z").put("width",12).put("height",10);a.putObject("target").put("bundleID","com.sage.graphics.fixture").put("windowID",7).put("pid",42);return a;}
    void source(HttpExchange x){try{
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
        if(!("Bearer "+mac.f.secret).equals(x.getRequestHeaders().getFirst("Authorization"))){mac.f.send(x,401,obj().put("code","LOCAL_AUTH_REQUIRED"));return;}
        String store=x.getRequestHeaders().getFirst("X-Worker-Local-Store");if(!path.equals("/local/v1/identity")&&!mac.f.backend.equals(store)){mac.f.send(x,409,obj().put("code","STORE_IDENTITY_CHANGED"));return;}
        if(path.equals("/worker-host/v1/manifest")){ObjectNode manifest=obj();ArrayNode c=manifest.putArray("contracts");if(contract)c.add("mac-graphics/1");mac.f.send(x,200,manifest);return;}
        if(path.equals("/local/v1/task-preflights")){ObjectNode pre=obj().put("projectId","project-one").put("engineId","macos").put("profileId","MAC_MANAGED").putNull("profileHome").putNull("headRevision").putNull("treeRevision").put("hasUncommittedChanges",false).put("loadsUserExtensions",false).put("workingCopyPolicy","REGISTERED_TARGET_ONLY").put("registryDigest",String.join("",Collections.nCopies(64,"a"))).put("optionsRevision","frozen-options");ObjectNode meta=pre.putObject("macProfile").put("recipeID",recipe).put("remoteGraphicsEnabled",enabled);meta.set("graphicsProfile",profile());mac.f.send(x,200,pre);return;}
        if(path.equals("/local/v1/tasks/native-one")){mac.f.send(x,200,task());return;}
        if(path.startsWith("/local/v1/tasks/native-one/graphics/")&&method.equals("POST")){
            JsonNode b=mac.f.json.readTree(x.getRequestBody());assertEquals(b.path("requestKey").asText(),x.getRequestHeaders().getFirst("Idempotency-Key"));posts.incrementAndGet();ObjectNode r=obj().put("taskId","native-one").put("requestKey",b.path("requestKey").asText()).put("state","APPLIED").put("leak","private-host-data");ObjectNode result=r.putObject("result").put("outcome","CONFIRMED");ObjectNode o=result.putObject("observation").put("revision","revision-one").put("segmentId","segment-one").put("ownerEpoch","1");o.putObject("target").put("bundleID","com.sage.graphics.fixture").put("windowID",7).put("pid",42).put("arbitraryPath","/private/secret");o.putArray("elements").addObject().put("identifier","fixture.input").put("value","visible").put("canSetValue",true).put("path","/private/secret");if(path.endsWith("/capture"))result.set("artifact",artifact());receipts.put(b.path("requestKey").asText(),r);if(revokeDuring)mac.f.auth.revoke(mac.f.did);if(lose){lose=false;x.close();return;}mac.f.send(x,202,r);return;
        }
        if(path.startsWith("/local/v1/tasks/native-one/interaction-requests/")){reads.incrementAndGet();JsonNode r=receipts.get(path.substring(path.lastIndexOf('/')+1));mac.f.send(x,r==null?404:200,r==null?obj().put("code","NOT_FOUND"):r);return;}
        if(path.endsWith("/graphics/observations")){ObjectNode out=obj().put("bounded",true);out.putArray("items");mac.f.send(x,200,out);return;}
        if(path.equals("/local/v1/tasks/native-one/events")){ObjectNode out=obj().put("cursor","1").put("cursorExpired",false);ObjectNode event=out.putArray("items").addObject().put("taskId","native-one").put("kind","GRAPHICS_RESULT").put("outcome","CONFIRMED").put("sequence","1").put("cursor","1");if(injectReadPayload){event.putObject("observation").putArray("elements").addObject().put("value","private-AX-value");event.putObject("artifact").put("bytes","private-PNG-bytes");}mac.f.send(x,200,out);return;}
        if(path.equals("/local/v1/tasks/native-one/artifacts")){artifactReads.incrementAndGet();ObjectNode out=obj().put("bounded",true);out.putArray("items").add(artifact());mac.f.send(x,200,out);return;}
        if(path.equals("/local/v1/tasks/native-one/artifacts/"+mac.artifactId)){artifactReads.incrementAndGet();ObjectNode out=artifact().put("encoding","base64").put("bytes",Base64.getEncoder().encodeToString(mac.artifact));if(mac.badArtifactHash)out.put("sha256",String.join("",Collections.nCopies(64,"0")));mac.f.send(x,200,out);return;}
        mac.source(x);
    }catch(Throwable e){mac.f.send(x,500,obj().put("code","FIXTURE_FAILURE"));}}
    JsonNode call(String method,String tail,JsonNode body,String key,int status)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.port()+root+tail).openConnection();c.setSSLSocketFactory(tls.getSocketFactory());c.setInstanceFollowRedirects(false);c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+mac.f.token);if(key!=null)c.setRequestProperty("Idempotency-Key",key);c.setReadTimeout(12000);
        try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){mac.f.json.writeValue(o,body);}}int actual=c.getResponseCode();JsonNode r=mac.f.json.readTree(actual>=400?c.getErrorStream():c.getInputStream());assertEquals(r.toString(),status,actual);return r;}finally{c.disconnect();}
    }
    JsonNode action(String route,String key,int status)throws Exception{return call("POST","/tasks/native-one/graphics/"+route,body(key,route),key,status);}
    @Test public void explicitGraphicsPreflightAndDetailAreWhitelisted()throws Exception {
        JsonNode pre=call("POST","/task-preflights",obj().put("projectId","project-one").put("engineId","macos"),null,200);assertEquals("REGISTERED_TARGET_ONLY",pre.path("workingCopyPolicy").asText());assertTrue(pre.path("headRevision").isNull());assertFalse(pre.toString().contains("/private/secret"));
        JsonNode detail=call("GET","/tasks/native-one",null,null,200);assertTrue(detail.path("graphicsAccess").path("act").asBoolean());assertFalse(detail.toString().contains("helperPath"));recipe="mac.unknown/1";assertEquals("LOCAL_PROFILE_NOT_SUPPORTED",call("POST","/task-preflights",obj().put("projectId","project-one").put("engineId","macos"),null,409).path("code").asText());
    }
    @Test public void oldObserveControlApproveGrantCannotReadOrActAndNewRightsAreIndependent()throws Exception {
        scope("OBSERVE","LAUNCH","CONTROL","APPROVE");for(String route:Arrays.asList("observe","capture","actions"))assertEquals("DEVICE_SCOPE_DENIED",action(route,"old-"+route,403).path("code").asText());assertEquals(0,posts.get());
        scope("OBSERVE","LAUNCH","GRAPHICS_OBSERVE");assertEquals("visible",action("observe","new-observe",200).path("source").path("result").path("observation").path("elements").get(0).path("value").asText());action("capture","no-capture",403);action("actions","no-act",403);assertEquals(1,posts.get());mac.f.denied("INVALID_SCOPE",()->{scope("OBSERVE","LAUNCH","GRAPHICS_ACT");return null;});
    }
    @Test public void manifestOperatorGateAndStaleTargetDenyBeforeDispatch()throws Exception {
        enabled=false;assertEquals("REMOTE_GRAPHICS_DISABLED",action("observe","disabled",403).path("code").asText());enabled=true;contract=false;assertEquals("LOCAL_SOURCE_UPGRADE_REQUIRED",action("observe","old-host",409).path("code").asText());contract=true;epoch="2";assertEquals("NATIVE_TARGET_CHANGED",action("observe","stale",409).path("code").asText());epoch="1";valid=false;action("actions","invalid-revision",409);assertEquals(0,posts.get());
    }
    @Test public void lostResponseRestartOriginalKeyRecoveryNeverRepostsAndKeepsScope()throws Exception {
        lose=true;JsonNode first=action("actions","lost-press",200);assertEquals("UNKNOWN",first.path("state").asText());assertEquals(1,posts.get());mac.f.access=new PersonalLocalTaskAccess("http://127.0.0.1:"+mac.f.host.getAddress().getPort(),mac.f.secret,mac.f.records,mac.f.auth,mac.f.json);server.localTasks(mac.f.access);
        epoch="2";JsonNode recovered=call("GET","/tasks/native-one/graphics/requests/lost-press",null,null,200);assertEquals("OBSERVED",recovered.path("state").asText());assertFalse(recovered.toString().contains("private-host-data"));assertFalse(recovered.toString().contains("\"pid\""));assertEquals("segment-one",recovered.path("source").path("result").path("observation").path("segmentId").asText());
        action("actions","lost-press",200);assertEquals(1,posts.get());assertEquals(2,reads.get());ObjectNode changed=body("lost-press","actions").put("elementIdentifier","fixture.input");call("POST","/tasks/native-one/graphics/actions",changed,"lost-press",409);
        scope("OBSERVE","LAUNCH","CONTROL");int before=reads.get();call("GET","/tasks/native-one/graphics/requests/lost-press",null,null,403);assertEquals(before,reads.get());
    }
    @Test public void pathsPidsScriptsSiblingAndChangedGrantAreRejected()throws Exception {
        for(String unsafe:Arrays.asList("pid","path","url","script")){ObjectNode b=body("unsafe-"+unsafe,"actions").put(unsafe,"override");call("POST","/tasks/native-one/graphics/actions",b,"unsafe-"+unsafe,400);}call("POST","/tasks/sibling/graphics/observe",body("sibling","observe"),"sibling",403);assertEquals(0,posts.get());
        revokeDuring=true;action("actions","revoke-during",401);assertEquals(1,posts.get());call("GET","/tasks/native-one/graphics/requests/revoke-during",null,null,401);assertEquals(0,reads.get());
    }
    @Test public void screenshotArtifactUsesCaptureRightAndExistingBoundedHashChannel()throws Exception {
        JsonNode capture=action("capture","capture-one",200),meta=capture.path("source").path("result").path("artifact");assertEquals(mac.artifactId,meta.path("artifactId").asText());assertEquals("image/png",meta.path("mediaType").asText());assertEquals("revision-one",meta.path("observationRevision").asText());assertFalse(meta.has("path"));assertFalse(meta.path("target").has("pid"));
        JsonNode bytes=call("GET","/tasks/native-one/artifacts/"+mac.artifactId,null,null,200);assertArrayEquals(mac.artifact,Base64.getDecoder().decode(bytes.path("bytes").asText()));assertFalse(bytes.has("path"));
        mac.badArtifactHash=true;assertEquals("LOCAL_ARTIFACT_CHANGED",call("GET","/tasks/native-one/artifacts/"+mac.artifactId,null,null,409).path("code").asText());mac.badArtifactHash=false;
        scope("OBSERVE","LAUNCH","CONTROL","APPROVE");int before=artifactReads.get();call("GET","/tasks/native-one/artifacts/"+mac.artifactId,null,null,403);call("GET","/tasks/native-one/artifacts",null,null,403);assertEquals(before,artifactReads.get());
    }

    @Test public void captureOnlyCanCaptureAndReadBytesWithoutAccessibilityPayload()throws Exception {
        scope("OBSERVE","LAUNCH","GRAPHICS_CAPTURE");JsonNode result=action("capture","capture-only",200),source=result.path("source").path("result");
        assertEquals("CONFIRMED",source.path("outcome").asText());assertEquals(mac.artifactId,source.path("artifact").path("artifactId").asText());assertEquals("revision-one",source.path("observation").path("revision").asText());assertFalse(source.path("observation").has("elements"));
        JsonNode recovered=call("GET","/tasks/native-one/graphics/requests/capture-only",null,null,200);assertFalse(recovered.path("source").path("result").path("observation").has("elements"));assertEquals(1,posts.get());
        assertTrue(call("GET","/tasks/native-one/artifacts/"+mac.artifactId,null,null,200).has("bytes"));call("GET","/tasks/native-one/graphics/observations",null,null,403);action("observe","capture-cannot-observe",403);action("actions","capture-cannot-act",403);
    }
    @Test public void actWithControlOnlyCanActWithoutAccessibilityOrScreenshotPayload()throws Exception {
        scope("OBSERVE","LAUNCH","CONTROL","GRAPHICS_ACT");JsonNode result=action("actions","act-only",200);assertEquals("CONFIRMED",result.path("source").path("result").path("outcome").asText());assertFalse(result.path("source").path("result").path("observation").has("elements"));
        JsonNode recovered=call("GET","/tasks/native-one/graphics/requests/act-only",null,null,200);assertFalse(recovered.path("source").path("result").path("observation").has("elements"));assertEquals(1,posts.get());call("GET","/tasks/native-one/graphics/observations",null,null,403);call("GET","/tasks/native-one/artifacts/"+mac.artifactId,null,null,403);action("capture","act-cannot-capture",403);
    }
    @Test public void originalReceiptProjectionUsesCurrentObserveGrantWithoutChangingHostFacts()throws Exception {
        JsonNode captured=action("capture","changing-observe",200);assertEquals("visible",captured.path("source").path("result").path("observation").path("elements").get(0).path("value").asText());
        scope("OBSERVE","LAUNCH","GRAPHICS_CAPTURE");JsonNode restricted=call("GET","/tasks/native-one/graphics/requests/changing-observe",null,null,200);assertFalse(restricted.path("source").path("result").path("observation").has("elements"));assertEquals(mac.artifactId,restricted.path("source").path("result").path("artifact").path("artifactId").asText());assertFalse(action("capture","changing-observe",200).path("source").path("result").path("observation").has("elements"));assertEquals(1,posts.get());
        scope("OBSERVE","LAUNCH","GRAPHICS_CAPTURE","GRAPHICS_OBSERVE");JsonNode restored=call("GET","/tasks/native-one/graphics/requests/changing-observe",null,null,200);assertEquals("visible",restored.path("source").path("result").path("observation").path("elements").get(0).path("value").asText());assertEquals(1,posts.get());
    }
    @Test public void ordinaryObserveTaskAndEventFeedsDoNotCarryGraphicsPayloads()throws Exception {
        injectReadPayload=true;scope("OBSERVE","LAUNCH");JsonNode detail=call("GET","/tasks/native-one",null,null,200),events=call("GET","/tasks/native-one/events?cursor=0&limit=10",null,null,200);
        assertFalse(detail.path("graphicsAccess").path("observe").asBoolean());assertFalse(detail.toString().contains("private-AX-value"));assertFalse(detail.toString().contains("private-PNG-bytes"));assertEquals("GRAPHICS_RESULT",events.path("items").get(0).path("kind").asText());assertEquals("CONFIRMED",events.path("items").get(0).path("outcome").asText());assertFalse(events.toString().contains("private-AX-value"));assertFalse(events.toString().contains("private-PNG-bytes"));assertEquals("1",events.path("cursor").asText());
    }

}
