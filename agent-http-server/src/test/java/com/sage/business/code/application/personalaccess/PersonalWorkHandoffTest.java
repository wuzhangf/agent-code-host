package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * C5 work handoff (D §9 / brief §4-§5): PAC-side handoff state machine against
 * the real loopback native fixture plus a stubbed ACS upstream. Asserts phase
 * progression, seal binding, material freeze/staging, launch idempotency,
 * cancel races and the cross-host import proof path — all through
 * {@link PersonalWorkHandoffService#remote} exactly as the adapter dispatches.
 */
public class PersonalWorkHandoffTest extends PersonalLocalTaskAccessTest {
    PersonalWorkHandoffService handoffs;
    Path materialRoot,signingRoot;
    AtomicInteger sealPosts=new AtomicInteger(),exportReads=new AtomicInteger(),uploadPosts=new AtomicInteger(),targetLaunches=new AtomicInteger(),attachPosts=new AtomicInteger();
    volatile ObjectNode attachBody;
    volatile ObjectNode sealBody;volatile Map<String,ObjectNode> uploads=new ConcurrentHashMap<String,ObjectNode>();
    volatile boolean sourceStopped;
    volatile String sourceStopMethod="WINDOWS_JOB_ACTIVE_ZERO";
    volatile ObjectNode launchBody;volatile boolean sealPending;
    volatile byte[] exportBytes="final export bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    volatile boolean emitGitBaseline;
    volatile byte[] gitPatchBytes="diff --git a/staged.txt b/staged.txt\nnew file\n+staged body\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    volatile boolean targetHasAssistantMessage=true;
    /** Real-worker shape: turns ledger keyed by commandId, message items with
     * role/commandId/nativeTurnId — never a `kind` field. */
    volatile boolean targetIdle;volatile String targetCommand="launch-req-1";
    final ObjectNode targetTurns=json.createObjectNode();
    final ArrayNode targetMsgs=json.createArrayNode();
    final Map<String,ObjectNode> targetOps=new ConcurrentHashMap<String,ObjectNode>();
    // GAP-02/D06 ACS upstream fixture: a dispatching stub stands in for the
    // real loopback ACS — it captures the session create wire, serves the
    // session view, records posted message rows and answers evidence reads
    // (messages + run activities) exactly like the real history surface.
    volatile ObjectNode acsSession,acsCreateWire;volatile int acsCreatePosts;
    final ArrayNode acsItems=json.createArrayNode();
    final ArrayNode acsActivityItems=json.createArrayNode();

    /** The launch turn settles once the fake idles: the create key records a
     *  SUCCEEDED turn and the assistant reply lands under that commandId. */
    void targetSettle(){
        if(targetTurns.has(targetCommand))return;
        targetTurns.putObject(targetCommand).put("state","SUCCEEDED").put("nativeTurnId","turn-1");
        if(targetHasAssistantMessage)
            targetMsgs.addObject().put("role","assistant").put("commandId",targetCommand)
                    .put("nativeTurnId","turn-1").put("occurredAt",System.currentTimeMillis())
                    .put("text","handoff acknowledged, continuing");
    }

    static String sha(byte[] b){try{byte[] d=java.security.MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte v:d)s.append(String.format("%02x",v&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}

    /** The dispatching ACS stub: real loopback shape for session create/read,
     *  message post/list, run activities and the material-uploads profile.
     *  A workspace-bound message stamps runId on the consumed user row — the
     *  real recordQueued behaviour the evidence predicate binds on. */
    PersonalWorkHandoffService.Reply acsDispatch(PersonalAccessService.Device d,String method,String path,JsonNode body,String key){
        try{
            String bare=path.contains("?")?path.substring(0,path.indexOf('?')):path;
            if("POST".equals(method)&&"/api/v2/sessions".equals(bare)){
                acsCreatePosts++;acsCreateWire=(ObjectNode)body;
                return new PersonalWorkHandoffService.Reply(201,acsSession.deepCopy());}
            if("GET".equals(method)&&bare.startsWith("/api/v2/runs/")&&bare.endsWith("/activities")){
                return new PersonalWorkHandoffService.Reply(200,json.createObjectNode().set("items",acsActivityItems));}
            if(bare.startsWith("/api/v2/sessions/")&&bare.endsWith("/messages")){
                if("GET".equals(method))
                    return new PersonalWorkHandoffService.Reply(200,json.createObjectNode().put("schemaVersion","session-message-page/1")
                            .put("hasOlder",false).put("hasNewer",false).set("items",acsItems));
                if("POST".equals(method)){
                    ObjectNode row=json.createObjectNode().put("id","m-"+acsItems.size()).put("role","user")
                            .put("clientMessageId",body.path("clientMessageId").asText("")).put("status","SUCCEEDED")
                            .put("createdAt",System.currentTimeMillis()).put("content",body.path("content").asText(""));
                    acsItems.add(row);
                    return new PersonalWorkHandoffService.Reply(202,json.createObjectNode().put("messageId",row.path("id").asText()).put("state","QUEUED"));}}
            if("GET".equals(method)&&bare.startsWith("/api/v2/sessions/")){
                return new PersonalWorkHandoffService.Reply(200,acsSession);}
            if(("POST".equals(method)||"PUT".equals(method))&&bare.contains("/material-uploads")){
                if(bare.endsWith("/seal"))
                    return new PersonalWorkHandoffService.Reply(200,json.createObjectNode().put("uploadKey","mu-acs").put("materialRef","mat-acs"));
                if(bare.contains("/chunks/"))
                    return new PersonalWorkHandoffService.Reply(200,json.createObjectNode().put("stored",true));
                return new PersonalWorkHandoffService.Reply(202,json.createObjectNode().put("uploadKey","mu-acs").put("state","OPEN"));}
            return new PersonalWorkHandoffService.Reply(404,json.createObjectNode().put("code","NOT_FOUND"));
        }catch(Exception failure){return new PersonalWorkHandoffService.Reply(500,json.createObjectNode().put("code","FIXTURE_FAILURE"));}
    }

    @Before public void handoffSetup()throws Exception {
        materialRoot=root.resolve("handoff-materials");signingRoot=root.resolve("handoff-signing");
        handoffs=new PersonalWorkHandoffService(records,auth,json,
                this::acsDispatch,
                new PersonalWorkHandoffService.NativePort(){
                    public PersonalLocalTaskAccess.Result call(PersonalAccessService.Device d,String backend,String m,String p,JsonNode b,String k)throws java.io.IOException{return access.call(d,backend,m,p,b,k);}
                    public PersonalLocalTaskAccess.Result callBytes(PersonalAccessService.Device d,String backend,String m,String p,byte[] bytes,java.util.Map<String,String> headers,String k)throws java.io.IOException{return access.callBytes(d,backend,m,p,bytes,headers,k);}
                },
                materialRoot,signingRoot);
    }

    /** Owned routes first; everything else delegates before the body stream is consumed. */
    @Override void handle(HttpExchange x){try{
        String path=x.getRequestURI().getPath().substring("/local/v1".length()),method=x.getRequestMethod();
        boolean handoffTask=path.equals("/tasks")&&method.equals("POST");
        boolean owned=handoffTask||path.startsWith("/tasks/native-one/source-seals")
                ||path.equals("/tasks/native-one/commands")&&method.equals("POST")
                ||path.startsWith("/tasks/native-one/commands/")&&method.equals("GET")
                ||sourceStopped&&path.equals("/tasks/native-one")&&method.equals("GET")
                ||path.startsWith("/tasks/native-one/handoff-export")||path.startsWith("/tasks/native-one/auxiliary-sessions")
                ||path.startsWith("/tasks/native-one/collaborations")||path.startsWith("/tasks/native-one/artifacts")
                ||path.startsWith("/tasks/native-target")||path.startsWith("/projects/project-one/material-uploads")
                ||path.startsWith("/material-uploads")||path.startsWith("/task-requests/");
        if(!owned){super.handle(x);return;}
        if(!("Bearer "+secret).equals(x.getRequestHeaders().getFirst("Authorization"))){send(x,401,json.createObjectNode());return;}
        JsonNode body=(method.equals("POST")||method.equals("PUT"))&&!path.contains("/chunks/")?json.readTree(x.getRequestBody()):null;
        if(path.startsWith("/tasks/native-one/commands/")&&method.equals("GET")){
            JsonNode result=inputs.get(path.substring("/tasks/native-one/commands/".length()));send(x,result==null?404:200,result==null?json.createObjectNode().put("code","REQUEST_NOT_FOUND"):result);return;
        }
        if(path.equals("/tasks/native-one/commands")&&method.equals("POST")){
            inputPosts.incrementAndGet();boolean stop="STOP".equals(body.path("intent").asText());
            if(stop)sourceStopped=true;
            ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("taskId","native-one").put("state",stop?"APPLIED":"ACKNOWLEDGED");
            inputs.put(body.path("requestKey").asText(),result);
            if(loseInput.getAndSet(false)){x.close();return;}send(x,202,result);return;
        }
        if(sourceStopped&&path.equals("/tasks/native-one")&&method.equals("GET")){
            ObjectNode result=json.createObjectNode().put("taskId","native-one").put("engineId",engineName).put("storeId",backend).put("state","CANCELLED").put("generation","g1").put("nativeSessionId","native-session").put("currentCommandId","command-one").put("quiescent",true).put("intakeClosed",true);
            if(sourceStopMethod!=null)result.putObject("stopProof").put("method",sourceStopMethod);send(x,200,result);return;
        }
        if(path.equals("/tasks")&&method.equals("POST")){
            launchPosts.incrementAndGet();if(entered!=null){entered.countDown();release.await(5,TimeUnit.SECONDS);}
            // Handoff launches carry the takeover prompt; materials ride the
            // first follow-up message, never the create body.
            boolean handoffLaunch=body.has("materials")||body.path("instruction").asText("").contains("接手");
            if(handoffLaunch){targetLaunches.incrementAndGet();launchBody=(ObjectNode)body.deepCopy();
                // currentCommandId starts as the task's create requestKey —
                // the interact guard fences on exactly this value.
                targetCommand=body.path("requestKey").asText();}
            ObjectNode result=json.createObjectNode().put("taskId",handoffLaunch?"native-target":"native-one").put("engineId","pi").put("engineVersion","fixture-version");
            launches.put(body.path("requestKey").asText(),result);
            if(loseLaunch.getAndSet(false)){x.close();return;}send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/source-seals")&&method.equals("POST")){
            sealPosts.incrementAndGet();sealBody=(ObjectNode)body.deepCopy();
            if(sealPending){send(x,200,json.createObjectNode().put("sealId","seal-1").put("state","SEALING"));return;}
            send(x,200,json.createObjectNode().put("sealId","seal-1").put("state","SEALED").set("affectedChildren",json.createArrayNode()));return;
        }
        if(path.equals("/tasks/native-one/source-seals")&&method.equals("GET")){
            send(x,200,json.createObjectNode().put("sealId","seal-1").put("state",sealPending?"SEALING":"SEALED"));return;
        }
        if(path.equals("/tasks/native-one/handoff-export")&&method.equals("GET")){
            exportReads.incrementAndGet();
            ArrayNode parts=json.createArrayNode();
            parts.add(json.createObjectNode().put("part","export-final").put("sizeBytes",exportBytes.length).put("sha256",sha(exportBytes)).put("type","BUNDLE"));
            ObjectNode export=json.createObjectNode().put("taskId","native-one").put("exportId","hx-1").put("state","READY").set("parts",parts);
            if(emitGitBaseline){
                // Producer shape per design §9.3: fact rows plus a digest-bound
                // payload part fetched through the chunks route.
                parts.add(json.createObjectNode().put("part","git-staged").put("sizeBytes",gitPatchBytes.length).put("sha256",sha(gitPatchBytes)).put("role","PATCH"));
                ArrayNode baseline=export.putArray("gitBaseline");
                baseline.addObject().put("kind","REPOSITORY").put("present",true).put("head","a1b2c3").put("detached",true).put("baseCommit","base-sha");
                baseline.addObject().put("kind","PATCH_STAGED").put("state","AVAILABLE").put("part","git-staged").put("sha256",sha(gitPatchBytes)).put("sizeBytes",gitPatchBytes.length);
                baseline.addObject().put("kind","BUNDLE").put("state","EMPTY").put("reason","NO_LOCAL_COMMITS");
                baseline.addObject().put("kind","UNTRACKED_MANIFEST").putArray("files").addObject().put("path","untracked.txt").put("status","UNSEALED");
            }
            send(x,200,export);return;
        }
        if(path.startsWith("/tasks/native-one/handoff-export/")&&path.contains("/chunks/")&&method.equals("GET")){
            byte[] served=path.contains("/git-staged/")?gitPatchBytes:exportBytes;
            send(x,200,json.createObjectNode().put("bytes",Base64.getEncoder().encodeToString(served)).put("sizeBytes",served.length));return;
        }
        if(path.equals("/tasks/native-one/auxiliary-sessions")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.equals("/tasks/native-one/auxiliary-sessions")&&method.equals("POST")){
            send(x,202,json.createObjectNode().put("auxiliarySessionId","aux-1").put("taskId","native-one").put("state","ACTIVE"));return;
        }
        if(path.equals("/tasks/native-one/auxiliary-sessions/aux-1")&&method.equals("GET")){
            send(x,200,json.createObjectNode().put("auxiliarySessionId","aux-1").put("taskId","native-one").put("state","ANSWERED").put("answer","fixture narrative"));return;
        }
        if(path.equals("/tasks/native-one/collaborations")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.equals("/tasks/native-one/artifacts")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.equals("/projects/project-one/material-uploads")&&method.equals("POST")){
            uploadPosts.incrementAndGet();String key="mu-"+uploadPosts.get();
            ObjectNode result=json.createObjectNode().put("uploadKey",key).put("state","OPEN");
            uploads.put(key,(ObjectNode)body.deepCopy());send(x,202,result);return;
        }
        if(path.startsWith("/projects/project-one/material-uploads/")||path.startsWith("/material-uploads/")){
            // Codex mounts uploads project-scoped; Pi mounts them flat — the
            // fixture accepts both mounts and serves identical rows.
            String[] seg=path.split("/");
            int keyIdx="material-uploads".equals(seg[1])?2:4;
            if(seg.length==keyIdx+2&&seg[keyIdx+1].equals("seal")&&method.equals("POST")){
                String key=seg[keyIdx];
                send(x,200,json.createObjectNode().put("uploadKey",key).put("materialRef","mat-"+key).put("sha256",body.path("sha256").asText()));return;
            }
            if(seg.length==keyIdx+3&&seg[keyIdx+1].equals("chunks")&&(method.equals("PUT")||method.equals("POST"))){
                // Raw chunk bytes; digest in ?digest= or x-chunk-sha256.
                byte[] raw=x.getRequestBody().readAllBytes();
                String q=x.getRequestURI().getQuery();
                String digest=q!=null&&q.startsWith("digest=")?q.substring(7):x.getRequestHeaders().getFirst("x-chunk-sha256");
                if(digest==null||!PersonalAccessService.hashBytes(raw).equals(digest)){send(x,409,json.createObjectNode().put("code","MATERIAL_CHUNK_DIGEST_MISMATCH"));return;}
                send(x,200,json.createObjectNode().put("stored",true).put("nextOffset",Long.parseLong(seg[keyIdx+2])+raw.length));return;
            }
            if(seg.length==keyIdx+1&&method.equals("GET")){
                ObjectNode up=uploads.get(seg[keyIdx]);send(x,up==null?404:200,up==null?json.createObjectNode().put("code","NOT_FOUND"):up);return;
            }
            send(x,404,json.createObjectNode().put("code","NOT_FOUND"));return;
        }
        if(path.startsWith("/task-requests/")&&method.equals("GET")){
            String key=path.substring("/task-requests/".length());
            JsonNode op=targetOps.get(key);if(op==null)op=launches.get(key);
            send(x,op==null?404:200,op==null?json.createObjectNode().put("code","REQUEST_NOT_FOUND"):op);return;
        }
        if(path.equals("/tasks/native-target")&&method.equals("GET")){
            // Real worker detail(): guards the interact fence reads plus the
            // follow-up capability gate — no invented convenience fields.
            if(targetIdle)targetSettle();
            ObjectNode t=json.createObjectNode().put("taskId","native-target")
                    .put("state",targetIdle?"SUCCEEDED":"RUNNING")
                    .put("generation","gen-1").putNull("nativeSessionId")
                    .put("nativeTurnId","turn-1").put("currentCommandId",targetCommand);
            t.putObject("capabilities").put("followUp",targetIdle).put("completedSessionFollowUpAfterRestart",false);
            send(x,200,t);return;
        }
        if(path.equals("/tasks/native-target/messages")&&method.equals("POST")){
            // Real interact(): exact body whitelist + the generation/session/
            // turn/command guard, NATIVE_FOLLOW_UP only on an idle task.
            for(String f:new String[]{"requestKey","kind","content","materials","generation","nativeSessionId","nativeTurnId","targetCommandId"})
                if(!body.has(f)){send(x,400,json.createObjectNode().put("code","INVALID_REQUEST:"+f));return;}
            if(!"gen-1".equals(body.path("generation").asText(null))
                    ||!(body.path("nativeSessionId").isNull())
                    ||!"turn-1".equals(body.path("nativeTurnId").asText(null))
                    ||!targetCommand.equals(body.path("targetCommandId").asText(null))){
                send(x,409,json.createObjectNode().put("code","NATIVE_TARGET_CHANGED"));return;}
            if("NATIVE_FOLLOW_UP".equals(body.path("kind").asText())&&!targetIdle){
                send(x,409,json.createObjectNode().put("code","NATIVE_NOT_IDLE"));return;}
            String key=body.path("requestKey").asText();
            if(targetOps.containsKey(key)){send(x,200,targetOps.get(key));return;}
            ObjectNode op=json.createObjectNode().put("requestKey",key).put("taskId","native-target").put("state","ACCEPTED");
            targetOps.put(key,op);
            // The accepted command becomes a recorded turn; the assistant
            // reply lands under the same commandId — the evidence surface.
            targetTurns.putObject(key).put("state","SUCCEEDED").put("nativeTurnId","turn-2");
            targetMsgs.addObject().put("role","assistant").put("commandId",key).put("nativeTurnId","turn-2")
                    .put("occurredAt",System.currentTimeMillis()).put("text","handoff materials received, continuing");
            attachPosts.incrementAndGet();attachBody=(ObjectNode)body.deepCopy();
            send(x,202,op);return;
        }
        if(path.equals("/tasks/native-target/messages")&&method.equals("GET")){
            ObjectNode out=json.createObjectNode();
            out.set("turns",targetTurns);out.set("items",targetMsgs);
            send(x,200,out);return;
        }
        send(x,404,json.createObjectNode().put("code","NOT_FOUND"));
    }catch(Exception e){e.printStackTrace();send(x,500,json.createObjectNode().put("code","FIXTURE_FAILURE"));}finally{x.close();}}

    PersonalWorkHandoffService.Result wh(String method,String tail,JsonNode body,String key)throws Exception {
        return handoffs.remote(token,method,"/personal/v1/work-handoffs"+tail,body,key,null);
    }
    PersonalWorkHandoffService.Result whq(String tail,String query)throws Exception {
        return handoffs.remote(token,"GET","/personal/v1/work-handoffs"+tail,null,null,query);
    }
    ObjectNode createBody(String key){ObjectNode out=json.createObjectNode().put("requestKey",key);
        out.set("sourceRef",json.createObjectNode().put("kind","WORKER_LOCAL").put("storeId",backend).put("taskId","native-one"));
        out.set("targetAgentRef",json.createObjectNode().put("hostKey","local").put("kind","WORKER_LOCAL").put("storeId",backend).put("engineId","pi").put("projectRef","project-one"));
        return out;}
    void scope()throws Exception{
        PersonalAccessService.Device d=auth.authenticate(token);
        auth.updateScopeResources(did,"wh-scope-"+UUID.randomUUID(),Long.toString(d.revision),
            Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")),
            Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),
            new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","HANDOFF")));
    }
    /** ACS-target lanes additionally need CREATE — the admission branch
     *  refuses an ACS target when the device cannot create sessions. */
    void scopeAcs()throws Exception{
        PersonalAccessService.Device d=auth.authenticate(token);
        auth.updateScopeResources(did,"wh-scope-"+UUID.randomUUID(),Long.toString(d.revision),
            Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")),
            Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),
            new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","HANDOFF","CREATE")));
    }

    /** Drive one same-host handoff through every phase to TAKEN_OVER. */
    JsonNode takeover(String requestKey)throws Exception {
        JsonNode created=wh("POST","",createBody(requestKey),requestKey).body;
        String hid=created.path("handoffId").asText();
        assertTrue(hid.startsWith("wh-"));
        assertEquals("PREPARING",created.path("phase").asText());
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":PREPARING"),"handoff:"+requestKey+":PREPARING");
        JsonNode ready=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":PREPARING"),"handoff:"+requestKey+":PREPARING").body;
        assertEquals("AWAITING_CONFIRM",ready.path("phase").asText());
        denied("MATERIAL_REVISION_CONFLICT",()->wh("POST","/"+hid+"/confirm",
                json.createObjectNode().put("requestKey","handoff:"+requestKey+":CONFIRM").put("expectedMaterialRevision",99),"handoff:"+requestKey+":CONFIRM"));
        JsonNode confirmed=wh("POST","/"+hid+"/confirm",json.createObjectNode().put("requestKey","handoff:"+requestKey+":CONFIRM").put("expectedMaterialRevision",1),"handoff:"+requestKey+":CONFIRM").body;
        assertEquals("SOURCE_SEALING",confirmed.path("phase").asText());
        JsonNode sealed=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":SOURCE_SEALING"),"handoff:"+requestKey+":SOURCE_SEALING").body;
        assertEquals("FINALIZING_MATERIALS",sealed.path("phase").asText());
        assertTrue(sealed.path("sourceSealed").asBoolean());
        JsonNode frozen=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":FINALIZING_MATERIALS"),"handoff:"+requestKey+":FINALIZING_MATERIALS").body;
        assertEquals("TARGET_STAGING",frozen.path("phase").asText());
        JsonNode staged=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":TARGET_STAGING"),"handoff:"+requestKey+":TARGET_STAGING").body;
        assertEquals("TARGET_STARTING",staged.path("phase").asText());
        JsonNode done=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":TARGET_STARTING"),"handoff:"+requestKey+":TARGET_STARTING").body;
        // The freshly launched target is still RUNNING — the follow-up and
        // the takeover probe only land once it runs idle. Settle the fake,
        // then re-drive under the same (unspent) phase key.
        for(int i=0;i<4&&"TARGET_STARTING".equals(done.path("phase").asText());i++){
            targetIdle=true;
            done=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:"+requestKey+":TARGET_STARTING"),"handoff:"+requestKey+":TARGET_STARTING").body;
        }
        return done;
    }

    @Test public void sameHostNativeHandoffSealsStagesLaunchesAndProvesTakeover()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scope();
        JsonNode done=takeover("wh-one");
        assertEquals("TAKEN_OVER",done.path("phase").asText());
        assertEquals("native-target",done.path("targetTaskRef").asText());
        assertEquals("seal-1",done.path("sealId").asText());
        assertEquals("MATERIAL_UPLOADS",done.path("materialBinding").asText());
        // Pi source seal carried the handoff identity, not a native task name.
        assertEquals(done.path("handoffId").asText(),sealBody.path("target").asText());
        assertTrue(sealBody.path("requestKey").asText().startsWith("mobile-"));
        // The frozen manifest is work-handoff/1 with a real digest and items.
        JsonNode materials=wh("GET","/"+done.path("handoffId").asText()+"/materials",null,null).body;
        JsonNode manifest=materials.path("manifest");
        assertEquals("work-handoff/1",manifest.path("schemaVersion").asText());
        assertTrue(manifest.path("materialDigest").asText().matches("[a-f0-9]{64}"));
        assertEquals("MISSING",manifest.path("gitBaselineState").asText());
        assertTrue(manifest.path("items").size()>=5);
        // Every staged item was bound to the launch through the upload profile.
        assertTrue(uploadPosts.get()>=5);
        assertEquals(1,targetLaunches.get());
        assertTrue(launchBody.path("instruction").asText().contains("承接"));
        // Create carries no materials[]; staged refs ride the first
        // NATIVE_FOLLOW_UP (priority-ordered, bounded to the engine's 4-max).
        assertTrue(attachPosts.get()>=1);
        assertEquals("NATIVE_FOLLOW_UP",attachBody.path("kind").asText());
        assertTrue(attachBody.path("materials").size()>=4);
        assertTrue(attachBody.path("materials").size()<=4);
        assertEquals("frozen-options",launchBody.path("optionsRevision").asText());
        // Material chunk reads are bounded and digest-stamped.
        JsonNode chunk=whq("/"+done.path("handoffId").asText()+"/materials/handoff.md/chunks","offset=0").body;
        assertEquals(0,chunk.path("offset").asLong());
        assertTrue(chunk.path("chunkSHA256").asText().matches("[a-f0-9]{64}"));
        String md=new String(Base64.getDecoder().decode(chunk.path("bytes").asText()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(md.contains("工作交接")||md.contains("handoffId"));
        // The request recovery surface resolves the original client key.
        JsonNode recovery=wh("GET","/requests/wh-one",null,null).body;
        assertEquals("OBSERVED",recovery.path("state").asText());
        assertEquals(done.path("handoffId").asText(),recovery.path("handoffId").asText());
        // Seal proof is present and targets verify through the local store.
        assertTrue(done.path("sealProof").asText().length()>100);
        assertEquals("RSA-SHA256",done.path("sealProofScheme").asText());
    }

    /** GAP-04: a producer that emits export.gitBaseline sees the fact rows land
     *  in the frozen manifest verbatim, and the layered patch payload is
     *  staged through the same digest-bound chunk route as any other part. */
    @Test public void nativeHandoffCollectsGitBaselineAndPatchParts()throws Exception {
        emitGitBaseline=true;
        call("POST","/tasks",launch(),"original-launch");scope();
        JsonNode done=takeover("wh-git");
        assertEquals("TAKEN_OVER",done.path("phase").asText());
        JsonNode manifest=wh("GET","/"+done.path("handoffId").asText()+"/materials",null,null).body.path("manifest");
        JsonNode baseline=manifest.path("gitBaseline");
        assertTrue("gitBaseline persisted verbatim: "+baseline,baseline.isArray()&&baseline.size()==4);
        assertEquals("REPOSITORY",baseline.get(0).path("kind").asText());
        assertEquals("a1b2c3",baseline.get(0).path("head").asText());
        assertEquals("AVAILABLE",baseline.get(1).path("state").asText());
        assertEquals("git-staged",baseline.get(1).path("part").asText());
        assertFalse("baseline marked missing despite rows","MISSING".equals(manifest.path("gitBaselineState").asText()));
        // The git-staged payload arrived as a staged item with the PATCH profile.
        JsonNode staged=null;
        for(JsonNode item:manifest.path("items"))if("export-git-staged".equals(item.path("relName").asText()))staged=item;
        assertTrue("export-git-staged item staged",staged!=null);
        assertEquals("PATCH",staged.path("type").asText());
        assertEquals(sha(gitPatchBytes),staged.path("sha256").asText());
        // Round-trip: chunk read returns the producer bytes verbatim.
        byte[] pulled=Base64.getDecoder().decode(whq("/"+done.path("handoffId").asText()+"/materials/export-git-staged/chunks","offset=0").body.path("bytes").asText());
        assertTrue("patch bytes verbatim",java.util.Arrays.equals(gitPatchBytes,pulled));
    }

    @Test public void createReplayAndPhaseKeyMismatchAreEnforced()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scope();
        JsonNode first=wh("POST","",createBody("wh-two"),"wh-two").body;
        JsonNode replay=wh("POST","",createBody("wh-two"),"wh-two").body;
        assertEquals(first.path("handoffId").asText(),replay.path("handoffId").asText());
        ObjectNode changed=createBody("wh-two");changed.set("options",json.createObjectNode().put("note","changed"));
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->wh("POST","",changed,"wh-two"));
        denied("IDEMPOTENCY_KEY_MISMATCH",()->wh("POST","",createBody("wh-two"),"other-key"));
        String hid=first.path("handoffId").asText();
        denied("HANDOFF_KEY_PHASE_MISMATCH",()->wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-two:TARGET_STAGING"),"handoff:wh-two:TARGET_STAGING"));
        denied("HANDOFF_KEY_PHASE_MISMATCH",()->wh("POST","/"+hid+"/confirm",json.createObjectNode().put("requestKey","bogus").put("expectedMaterialRevision",1),"bogus"));
        denied("INVALID_REQUEST",()->wh("POST","/"+hid+"/confirm",json.createObjectNode().put("requestKey","handoff:wh-two:CONFIRM").put("expectedMaterialRevision",1).put("extra","x"),"handoff:wh-two:CONFIRM"));
    }

    @Test public void cancelBeforeSealLeavesSourceUnsealedAndCancelAfterTakeoverMarksReverseHandoff()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scope();
        JsonNode created=wh("POST","",createBody("wh-three"),"wh-three").body;
        String hid=created.path("handoffId").asText();
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-three:PREPARING"),"handoff:wh-three:PREPARING");
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-three:PREPARING"),"handoff:wh-three:PREPARING");
        JsonNode cancelled=wh("POST","/"+hid+"/cancel",json.createObjectNode().put("requestKey","handoff:wh-three:CANCEL"),"handoff:wh-three:CANCEL").body;
        assertEquals("CANCELLED",cancelled.path("phase").asText());
        assertFalse(cancelled.path("sourceSealed").asBoolean());
        assertEquals(0,sealPosts.get());
        denied("HANDOFF_ALREADY_TERMINAL",()->wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-three:TARGET_STAGING"),"handoff:wh-three:TARGET_STAGING"));
        // Replay of the spent cancel key returns the same record.
        assertEquals("CANCELLED",wh("POST","/"+hid+"/cancel",json.createObjectNode().put("requestKey","handoff:wh-three:CANCEL"),"handoff:wh-three:CANCEL").body.path("phase").asText());
        // A completed handoff declines destructive cancel honestly.
        JsonNode done=takeover("wh-four");
        String hid2=done.path("handoffId").asText();
        JsonNode declined=wh("POST","/"+hid2+"/cancel",json.createObjectNode().put("requestKey","handoff:wh-four:CANCEL"),"handoff:wh-four:CANCEL").body;
        assertEquals("TAKEN_OVER",declined.path("phase").asText());
        assertTrue(declined.path("requiresReverseHandoff").asBoolean());
    }

    @Test public void sealPendingStaysHonestAndRetriable()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scope();
        JsonNode created=wh("POST","",createBody("wh-five"),"wh-five").body;
        String hid=created.path("handoffId").asText();
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-five:PREPARING"),"handoff:wh-five:PREPARING");
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-five:PREPARING"),"handoff:wh-five:PREPARING");
        wh("POST","/"+hid+"/confirm",json.createObjectNode().put("requestKey","handoff:wh-five:CONFIRM").put("expectedMaterialRevision",1),"handoff:wh-five:CONFIRM");
        sealPending=true;
        JsonNode pending=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-five:SOURCE_SEALING"),"handoff:wh-five:SOURCE_SEALING").body;
        assertEquals("SOURCE_SEALING",pending.path("phase").asText());
        assertEquals("SEAL_PENDING",pending.path("pendingReason").asText());
        // No target write may begin while the seal outcome is unknown.
        denied("HANDOFF_KEY_PHASE_MISMATCH",()->wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-five:TARGET_STAGING"),"handoff:wh-five:TARGET_STAGING"));
        sealPending=false;
        JsonNode sealed=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-five:SOURCE_SEALING"),"handoff:wh-five:SOURCE_SEALING").body;
        assertEquals("FINALIZING_MATERIALS",sealed.path("phase").asText());
        assertEquals(2,sealPosts.get());
    }

    @Test public void grantAndRevisionBoundariesApply()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        PersonalAccessService.Device d=auth.authenticate(token);
        // HANDOFF missing entirely: create is refused before any source call.
        auth.updateScopeResources(did,"nohandoff-"+UUID.randomUUID(),Long.toString(d.revision),
            Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")),
            Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),
            new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT")));
        denied("DEVICE_SCOPE_DENIED",()->wh("POST","",createBody("wh-six"),"wh-six"));
        scope();
        JsonNode created=wh("POST","",createBody("wh-six"),"wh-six").body;
        String hid=created.path("handoffId").asText();
        // An unchanged required scope may be freshly reauthorized after a grant bump.
        PersonalAccessService.Device later=auth.authenticate(token);
        auth.updateScopeResources(did,"bump-"+UUID.randomUUID(),Long.toString(later.revision),
            Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")),
            Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),
            new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","HANDOFF")));
        JsonNode resumed=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-six:PREPARING"),"handoff:wh-six:PREPARING").body;
        assertEquals(1,resumed.path("reauthorizations").size());
        assertEquals(Long.toString(later.revision),resumed.path("reauthorizations").get(0).path("fromGrantRevision").asText());
        assertEquals("handoff:wh-six:PREPARING",resumed.path("reauthorizations").get(0).path("phaseKey").asText());
        // Cross-host to an unregistered peer is refused.
        ObjectNode peerBody=createBody("wh-seven");
        peerBody.set("targetAgentRef",json.createObjectNode().put("hostKey","peer-unknown").put("kind","WORKER_LOCAL").put("storeId",backend).put("engineId","pi").put("projectRef","project-one"));
        denied("PEER_NOT_TRUSTED",()->wh("POST","",peerBody,"wh-seven"));
    }

    @Test public void importVerifiesPeerProofStagesChunksAndStartsOnce()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scope();
        // A completed same-host handoff supplies a real manifest + seal proof.
        JsonNode staged=takeover("wh-export");
        String hid=staged.path("handoffId").asText();
        JsonNode materials=wh("GET","/"+hid+"/materials",null,null).body;
        JsonNode manifest=materials.path("manifest");
        JsonNode proof=materials.path("sealProof");
        // Register this PAC's own key under a peer alias; verification re-derives the binding.
        JsonNode signing=handoffs.publicSigningKey();
        ObjectNode registered=handoffs.registerPeer(json.createObjectNode()
                .put("peerHostRef","peer-A").put("kid",signing.path("kid").asText()).put("publicKey",signing.path("publicKey").asText()));
        assertTrue(registered.path("registered").asBoolean());
        // Wrong sourceHostRef / unregistered peer is refused.
        ObjectNode badImport=json.createObjectNode().put("requestKey","imp-bad");
        badImport.set("manifest",manifest);badImport.set("sealProof",proof);badImport.put("sourceHostRef","peer-B");
        denied("PEER_NOT_TRUSTED",()->wh("POST","/imports",badImport,"imp-bad"));
        // A forged signature is refused before staging.
        ObjectNode forged=json.createObjectNode().put("requestKey","imp-forge");
        forged.set("manifest",manifest);
        forged.set("sealProof",json.createObjectNode().put("scheme","RSA-SHA256").put("signature","AAAA").put("sourceKeyId",signing.path("kid").asText()));
        forged.put("sourceHostRef","peer-A");
        denied("SEAL_PROOF_INVALID",()->wh("POST","/imports",forged,"imp-forge"));
        ObjectNode create=json.createObjectNode().put("requestKey","imp-one");
        create.set("manifest",manifest);create.set("sealProof",proof);create.put("sourceHostRef","peer-A");
        JsonNode imported=wh("POST","/imports",create,"imp-one").body;
        String iid=imported.path("importId").asText();
        assertTrue(iid.startsWith("whi-"));
        assertEquals("STAGED",imported.path("phase").asText());
        // Undeclared material is refused; declared bytes upload as verified chunks.
        denied("MANIFEST_MATERIAL_UNDECLARED",()->wh("POST","/imports/"+iid+"/materials",
                json.createObjectNode().put("relName","stray.bin").put("offset",0).put("chunkDigest",sha("x".getBytes())).put("dataBase64",Base64.getEncoder().encodeToString("x".getBytes())),null));
        for(JsonNode item:manifest.path("items")){
            String name=item.path("relName").asText();
            // Pull each staged item through the source handoff's chunk read.
            byte[] bytes;
            java.io.ByteArrayOutputStream all=new java.io.ByteArrayOutputStream();long offset=0;
            for(int hop=0;hop<16;hop++){
                JsonNode chunk=whq("/"+hid+"/materials/"+name+"/chunks","offset="+offset).body;
                byte[] slice=Base64.getDecoder().decode(chunk.path("bytes").asText());
                all.write(slice,0,slice.length);offset=chunk.path("nextOffset").asLong();
                if(slice.length==0||offset>=item.path("sizeBytes").asLong())break;
            }
            bytes=all.toByteArray();
            String digest=sha(bytes);
            denied("MATERIAL_CHUNK_DIGEST_MISMATCH",()->wh("POST","/imports/"+iid+"/materials",
                    json.createObjectNode().put("relName",name).put("offset",0).put("chunkDigest","0".repeat(64))
                            .put("dataBase64",Base64.getEncoder().encodeToString(bytes)),null));
            wh("POST","/imports/"+iid+"/materials",
                    json.createObjectNode().put("relName",name).put("offset",0).put("chunkDigest",digest)
                            .put("dataBase64",Base64.getEncoder().encodeToString(bytes)),null);
        }
        JsonNode complete=wh("GET","/imports/"+iid,null,null).body;
        for(JsonNode row:complete.path("materials"))assertTrue(row.path("complete").asBoolean());
        int launchesBefore=targetLaunches.get();
        JsonNode started=wh("POST","/imports/"+iid+"/start",json.createObjectNode().put("requestKey","handoff:imp-one:START"),"handoff:imp-one:START").body;
        assertEquals("STARTED",started.path("phase").asText());
        assertEquals("native-target",started.path("targetTaskRef").asText());
        // Same start key replays the same binding; no second launch.
        JsonNode replay=wh("POST","/imports/"+iid+"/start",json.createObjectNode().put("requestKey","handoff:imp-one:START"),"handoff:imp-one:START").body;
        assertEquals("native-target",replay.path("targetTaskRef").asText());
        assertEquals(launchesBefore+1,targetLaunches.get());
        // Cancel after start requires real stop proof; the running fixture yields none.
        JsonNode cancelling=wh("POST","/imports/"+iid+"/cancel",json.createObjectNode().put("requestKey","handoff:imp-one:CANCEL"),"handoff:imp-one:CANCEL").body;
        assertTrue(Arrays.asList("CANCELLING","CANCELLED").contains(cancelling.path("phase").asText()));
    }

    /** R24/R11: the cross-host loop in one JVM — this host's own id plays the
     *  remote target and its signing key plays the peer's, so the real
     *  RSA-SHA256 report path runs end to end. Forgery, wrong binding and a
     *  missing kid never upgrade the handoff; only the peer-signed report
     *  closes REMOTE_CONSUMPTION → TAKEN_OVER. */
    @Test public void remoteHandoffClosesOnlyOnSignedPeerConsumptionReport()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scope();
        String selfHost=auth.ownerStatus().path("personalHostId").asText();
        JsonNode signing=handoffs.publicSigningKey();
        for(String peer:new String[]{selfHost,"peer-A"})
            handoffs.registerPeer(json.createObjectNode()
                    .put("peerHostRef",peer).put("kid",signing.path("kid").asText()).put("publicKey",signing.path("publicKey").asText()));
        // The "target PAC" is a second paired device on this service: its own
        // grant revision absorbs the import-side scope appends, exactly like
        // two real PACs — the source device's revision stays frozen.
        String poll2=UUID.randomUUID().toString(),request2=UUID.randomUUID().toString();
        ObjectNode invite2=auth.invitation("invite-b",Arrays.asList("session-b"),new TreeSet<String>(Arrays.asList("OBSERVE","CONTROL")),"fixture-cert");
        auth.request(json.createObjectNode().put("inviteId","invite-b").put("inviteSecret",invite2.path("inviteSecret").asText())
                .put("pairingRequestId",request2).put("installationId","public-fixture-b").put("label","phone-b")
                .put("pollingSecretHash",PersonalAccessService.hash(poll2)));
        auth.decide(request2,true);
        ObjectNode issued2=auth.redeem(request2,poll2,"redeem");
        final String token2=issued2.path("accessToken").asText(),did2=issued2.path("deviceGrantId").asText();
        auth.updateScopeResources(did2,"peer-scope","1",
                Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-target")),
                Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),
                new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","HANDOFF")));
        ObjectNode create=createBody("wh-remote");
        ((ObjectNode)create.get("targetAgentRef")).put("hostKey",selfHost);
        JsonNode created=wh("POST","",create,"wh-remote").body;
        String hid=created.path("handoffId").asText();
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-remote:PREPARING"),"handoff:wh-remote:PREPARING");
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-remote:PREPARING"),"handoff:wh-remote:PREPARING");
        JsonNode confirmed=wh("POST","/"+hid+"/confirm",json.createObjectNode().put("requestKey","handoff:wh-remote:CONFIRM").put("expectedMaterialRevision",1),"handoff:wh-remote:CONFIRM").body;
        assertEquals("SOURCE_SEALING",confirmed.path("phase").asText());
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-remote:SOURCE_SEALING"),"handoff:wh-remote:SOURCE_SEALING");
        wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-remote:FINALIZING_MATERIALS"),"handoff:wh-remote:FINALIZING_MATERIALS");
        JsonNode starting=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-remote:TARGET_STAGING"),"handoff:wh-remote:TARGET_STAGING").body;
        assertEquals("TARGET_STARTING",starting.path("phase").asText());
        assertEquals("PAC_EXPORT",starting.path("materialBinding").asText());
        // No importBinding yet: the record waits honestly, never claims progress.
        JsonNode waiting=wh("POST","/"+hid+"/continue",json.createObjectNode().put("requestKey","handoff:wh-remote:TARGET_STARTING"),"handoff:wh-remote:TARGET_STARTING").body;
        assertEquals("AWAITING_PEER_IMPORT",waiting.path("pendingReason").asText());

        // Target side: stage the manifest, relay every item, start the import.
        JsonNode materials=wh("GET","/"+hid+"/materials",null,null).body;
        JsonNode manifest=materials.path("manifest"),proof=materials.path("sealProof");
        ObjectNode importReq=json.createObjectNode().put("requestKey","imp-remote");
        importReq.set("manifest",manifest);importReq.set("sealProof",proof);importReq.put("sourceHostRef","peer-A");
        JsonNode imported=handoffs.remote(token2,"POST","/personal/v1/work-handoffs/imports",importReq,"imp-remote",null).body;
        String iid=imported.path("importId").asText();
        for(JsonNode item:manifest.path("items")){
            String name=item.path("relName").asText();
            java.io.ByteArrayOutputStream all=new java.io.ByteArrayOutputStream();long offset=0;
            for(int hop=0;hop<16;hop++){
                JsonNode chunk=whq("/"+hid+"/materials/"+name+"/chunks","offset="+offset).body;
                byte[] slice=Base64.getDecoder().decode(chunk.path("bytes").asText());
                all.write(slice,0,slice.length);offset=chunk.path("nextOffset").asLong();
                if(slice.length==0||offset>=item.path("sizeBytes").asLong())break;
            }
            byte[] bytes=all.toByteArray();
            handoffs.remote(token2,"POST","/personal/v1/work-handoffs/imports/"+iid+"/materials",
                    json.createObjectNode().put("relName",name).put("offset",0).put("chunkDigest",sha(bytes))
                            .put("dataBase64",Base64.getEncoder().encodeToString(bytes)),null,null);
        }
        JsonNode started=handoffs.remote(token2,"POST","/personal/v1/work-handoffs/imports/"+iid+"/start",
                json.createObjectNode().put("requestKey","handoff:imp-remote:START"),"handoff:imp-remote:START",null).body;
        assertEquals("STARTED",started.path("phase").asText());
        String target=started.path("targetTaskRef").asText();

        // The import's launch turn must run idle before the material
        // follow-up and the evidence probe can land — settle the fake, then
        // the lazy probe on GET attaches the staged materials and signs the
        // consumption report on real turn+reply facts.
        targetIdle=true;
        JsonNode view=handoffs.remote(token2,"GET","/personal/v1/work-handoffs/imports/"+iid,null,null,null).body;
        JsonNode report=view.path("consumptionReport");
        assertEquals("RSA-SHA256",report.path("scheme").asText());
        assertEquals(iid,report.path("importId").asText());
        assertEquals(hid,report.path("handoffId").asText());
        assertTrue(report.path("evidenceDigest").asText().matches("[a-f0-9]{64}"));

        // Forgery: real binding, fake signature → 403, phase unchanged.
        ObjectNode forgedBinding=json.createObjectNode().put("importId",iid).put("targetTaskRef",target);
        forgedBinding.set("consumptionReport",((ObjectNode)report.deepCopy()).put("signature","AAAA"));
        ObjectNode forgedBody=json.createObjectNode().put("requestKey","handoff:wh-remote:TARGET_STARTING");
        forgedBody.set("importBinding",forgedBinding);
        denied("CONSUMPTION_PROOF_INVALID",()->wh("POST","/"+hid+"/continue",forgedBody,"handoff:wh-remote:TARGET_STARTING"));
        assertEquals("TARGET_STARTING",wh("GET","/"+hid,null,null).body.path("phase").asText());
        // A report bound to a different handoff is replay, never proof.
        ObjectNode wrongBinding=json.createObjectNode().put("importId",iid).put("targetTaskRef",target);
        wrongBinding.set("consumptionReport",((ObjectNode)report.deepCopy()).put("handoffId","wh-other"));
        ObjectNode wrongBody=json.createObjectNode().put("requestKey","handoff:wh-remote:TARGET_STARTING");
        wrongBody.set("importBinding",wrongBinding);
        denied("CONSUMPTION_PROOF_INVALID",()->wh("POST","/"+hid+"/continue",wrongBody,"handoff:wh-remote:TARGET_STARTING"));

        // The genuine peer-signed report closes the loop.
        ObjectNode goodBinding=json.createObjectNode().put("importId",iid).put("targetTaskRef",target);
        goodBinding.set("consumptionReport",report);
        ObjectNode goodBody=json.createObjectNode().put("requestKey","handoff:wh-remote:TARGET_STARTING");
        goodBody.set("importBinding",goodBinding);
        JsonNode done=wh("POST","/"+hid+"/continue",goodBody,"handoff:wh-remote:TARGET_STARTING").body;
        assertEquals("TAKEN_OVER",done.path("phase").asText());
        assertEquals(target,done.path("targetTaskRef").asText());
    }

    /** R03: a manifest-declared zero-byte item can never ride nonzero
     *  chunks — it completes through a single empty slice whose digest is
     *  verified. A nonzero declared item still refuses empty bytes. */
    @Test public void zeroByteMaterialCompletesThroughEmptyChunkSlice()throws Exception {
        exportBytes=new byte[0];
        call("POST","/tasks",launch(),"original-launch");scope();
        JsonNode staged=takeover("wh-zero");
        String hid=staged.path("handoffId").asText();
        JsonNode materials=wh("GET","/"+hid+"/materials",null,null).body;
        JsonNode manifest=materials.path("manifest"),proof=materials.path("sealProof");
        JsonNode zero=null;
        for(JsonNode item:manifest.path("items"))if(item.path("sizeBytes").asLong()==0)zero=item;
        assertNotNull("fixture manifest declares a zero-byte item",zero);
        JsonNode signing=handoffs.publicSigningKey();
        handoffs.registerPeer(json.createObjectNode()
                .put("peerHostRef","peer-A").put("kid",signing.path("kid").asText()).put("publicKey",signing.path("publicKey").asText()));
        ObjectNode req=json.createObjectNode().put("requestKey","imp-zero");
        req.set("manifest",manifest);req.set("sealProof",proof);req.put("sourceHostRef","peer-A");
        String iid=wh("POST","/imports",req,"imp-zero").body.path("importId").asText();
        // Empty slice for the zero item: accepted, verified, complete.
        wh("POST","/imports/"+iid+"/materials",
                json.createObjectNode().put("relName",zero.path("relName").asText()).put("offset",0)
                        .put("chunkDigest",sha(new byte[0])).put("dataBase64",""),null);
        JsonNode rows=wh("GET","/imports/"+iid,null,null).body.path("materials");
        for(JsonNode row:rows)if(row.path("relName").asText().equals(zero.path("relName").asText()))
            assertTrue("zero-byte item completes through the empty slice",row.path("complete").asBoolean());
        // A nonzero declared item still refuses an empty payload.
        for(JsonNode item:manifest.path("items"))if(item.path("sizeBytes").asLong()>0){
            denied("INVALID_MATERIAL",()->wh("POST","/imports/"+iid+"/materials",
                    json.createObjectNode().put("relName",item.path("relName").asText()).put("offset",0)
                            .put("chunkDigest",sha(new byte[0])).put("dataBase64",""),null));
            break;
        }
    }

    // ===== GAP-02/D06: executable ACS targets + strengthened takeover evidence =====

    JsonNode cont(String hid,String rk,String phase)throws Exception {
        return wh("POST","/"+hid+"/continue",
                json.createObjectNode().put("requestKey","handoff:"+rk+":"+phase),
                "handoff:"+rk+":"+phase).body;
    }
    JsonNode conf(String hid,String rk,long revision)throws Exception {
        return wh("POST","/"+hid+"/confirm",
                json.createObjectNode().put("requestKey","handoff:"+rk+":CONFIRM").put("expectedMaterialRevision",revision),
                "handoff:"+rk+":CONFIRM").body;
    }
    /** Same phase walk as takeover(), but keeps the unspent TARGET_STARTING key
     *  for caller-driven evidence probes. */
    void driveToStarting(String hid,String rk)throws Exception {
        cont(hid,rk,"PREPARING");
        JsonNode ready=cont(hid,rk,"PREPARING");
        assertEquals("AWAITING_CONFIRM",ready.path("phase").asText());
        JsonNode confirmed=conf(hid,rk,1);
        assertEquals("SOURCE_SEALING",confirmed.path("phase").asText());
        JsonNode sealed=cont(hid,rk,"SOURCE_SEALING");
        assertEquals("FINALIZING_MATERIALS",sealed.path("phase").asText());
        JsonNode frozen=cont(hid,rk,"FINALIZING_MATERIALS");
        assertEquals("TARGET_STAGING",frozen.path("phase").asText());
        JsonNode staged=cont(hid,rk,"TARGET_STAGING");
        assertEquals("TARGET_STARTING",staged.path("phase").asText());
    }
    /** recordQueued on the real upstream stamps the consuming run's ids on
     *  the takeover prompt row — the fixture mirrors exactly that. */
    void acsConsume(String runId){
        for(int i=acsItems.size()-1;i>=0;i--){
            ObjectNode row=(ObjectNode)acsItems.get(i);
            if("user".equals(row.path("role").asText())){row.put("runId",runId).put("taskId","task-1");return;}
        }
    }
    void acsAssistant(String clientKey,String runId,String content,String status){
        ObjectNode row=json.createObjectNode().put("id","a-"+acsItems.size()).put("role","assistant")
                .put("clientMessageId",clientKey).put("status",status)
                .put("createdAt",System.currentTimeMillis()).put("content",content)
                .put("contentBytes",content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if(runId!=null)row.put("runId",runId).put("taskId","task-1");
        acsItems.add(row);
    }
    ObjectNode acsTarget(String projectRef){
        ObjectNode t=json.createObjectNode().put("hostKey","local").put("kind","ACS");
        if(projectRef!=null)t.put("projectRef",projectRef);
        return t;
    }
    ObjectNode acsSessionFixture(String workspace){
        return json.createObjectNode().put("sessionId","acs-target").put("sessionVersion",1)
                .put("workspaceAlias",workspace).put("executionMode","MODIFY")
                .put("interactionMode","ACT").put("intent","IMPLEMENT");
    }

    /** GAP-02/D05: an ACS target with no executable lane is refused at
     *  admission — never created as a read-only shell. A WORKER_LOCAL source
     *  carries no workspaceAlias and no projectRef was named, so no lane
     *  exists to bind. */
    @Test public void acsTargetWithoutExecutionLaneIsRefusedAtAdmission()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scopeAcs();
        denied("HANDOFF_TARGET_NO_EXECUTION_LANE",()->wh("POST","",
                createBody("wh-acslane").set("targetAgentRef",acsTarget(null)),"wh-acslane"));
        // The refusal is durable: replaying the same create cannot mint a shell.
        denied("HANDOFF_TARGET_NO_EXECUTION_LANE",()->wh("POST","",
                createBody("wh-acslane2").set("targetAgentRef",acsTarget(null)),"wh-acslane2"));
        assertEquals(0,acsCreatePosts);
    }

    /** D06: a read-only-shaped answer can never be takeover proof — even on an
     *  executable-bound target, an assistant reply that carries no consuming
     *  run (the ordinary-chat "assistant-"+messageId shape a workspace-free
     *  shell produces) is rejected and the handoff stays honestly pending. */
    @Test public void acsRunFreeReplyIsNotTakeoverEvidence()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scopeAcs();
        acsSession=acsSessionFixture("sample");
        JsonNode created=wh("POST","",createBody("wh-acsread")
                .set("targetAgentRef",acsTarget("sample")),"wh-acsread").body;
        String hid=created.path("handoffId").asText();
        // Admission resolved the executable lane and exposes it on the record.
        assertEquals("sample",created.path("targetAgentRef").path("workspaceAlias").asText());
        driveToStarting(hid,"wh-acsread");
        JsonNode started=cont(hid,"wh-acsread","TARGET_STARTING");
        assertEquals("acs-target",started.path("targetTaskRef").asText());
        // Executable bind — the create wire is a workspace-bound MODIFY session,
        // never the old ASK/INSPECT workspace-free shell.
        assertEquals(1,acsCreatePosts);
        assertEquals("sample",acsCreateWire.path("workspaceAlias").asText());
        assertEquals("MODIFY",acsCreateWire.path("executionMode").asText());
        assertEquals("IMPLEMENT",acsCreateWire.path("intent").asText());
        assertEquals("ACT",acsCreateWire.path("interactionMode").asText());
        assertEquals("LOW",acsCreateWire.path("autonomyLevel").asText());
        assertEquals("VERIFY",acsCreateWire.path("verificationLevel").asText());
        assertEquals("INLINE_REFERENCES",started.path("materialBinding").asText());
        // Run-free assistant rows — exactly what a read-only target shape can
        // produce — are rejected even when the text looks substantive.
        acsAssistant("assistant-m-0",null,"已核对交接材料与目标项目一致，现在继续执行未完成的工作并报告核对结果。","SUCCEEDED");
        JsonNode pending=cont(hid,"wh-acsread","TARGET_STARTING");
        assertEquals("TARGET_STARTING",pending.path("phase").asText());
        assertEquals("TARGET_VERIFY_PENDING",pending.path("pendingReason").asText());
        assertTrue(pending.path("receipts").toString().contains("CONSUMPTION_NOT_PROVEN"));
    }

    /** D06: the takeover predicate — a real run-bound assistant turn with
     *  non-acknowledgement substance or tool activity. A bare "收到" bound to
     *  the consuming run is rejected; the run-bound substantive turn plus
     *  folded tool activity proves the takeover. */
    @Test public void acsAckRejectedThenRunBoundEvidenceTakesOver()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scopeAcs();
        acsSession=acsSessionFixture("sample");
        JsonNode created=wh("POST","",createBody("wh-acstake")
                .set("targetAgentRef",acsTarget("sample")),"wh-acstake").body;
        String hid=created.path("handoffId").asText();
        driveToStarting(hid,"wh-acstake");
        JsonNode started=cont(hid,"wh-acstake","TARGET_STARTING");
        assertEquals("acs-target",started.path("targetTaskRef").asText());
        // The consuming workspace run stamped run-1 on the takeover prompt.
        acsConsume("run-1");
        // 1) Prompt consumed, no assistant turn yet — honest pending.
        JsonNode pending=cont(hid,"wh-acstake","TARGET_STARTING");
        assertEquals("TARGET_STARTING",pending.path("phase").asText());
        assertEquals("TARGET_VERIFY_PENDING",pending.path("pendingReason").asText());
        // 2) A run-bound bare acknowledgement is still not takeover evidence.
        acsAssistant("assistant:run-1","run-1","收到","SUCCEEDED");
        pending=cont(hid,"wh-acstake","TARGET_STARTING");
        assertEquals("TARGET_STARTING",pending.path("phase").asText());
        assertEquals("TARGET_VERIFY_PENDING",pending.path("pendingReason").asText());
        // 3) A failed run-bound turn is rejected too — only a live turn counts.
        acsAssistant("assistant:run-1f","run-1","已核对材料并继续执行交接工作第一步的完整核对报告与接续动作。","FAILED");
        pending=cont(hid,"wh-acstake","TARGET_STARTING");
        assertEquals("TARGET_VERIFY_PENDING",pending.path("pendingReason").asText());
        // 4) Real folded tool activity + a substantive run-bound turn → TAKEN_OVER.
        acsActivityItems.addObject().put("activityId","act-1").put("kind","FILE_READ")
                .put("toolName","workspace_read").put("status","SUCCEEDED");
        acsAssistant("assistant:run-1ok","run-1","已读取 handoff.md 与 conversation.txt，核对交接材料与目标项目一致：代码与文档描述相符，从接手第一步继续执行未完成工作并报告实际动作。","SUCCEEDED");
        JsonNode done=cont(hid,"wh-acstake","TARGET_STARTING");
        assertEquals("TAKEN_OVER",done.path("phase").asText());
        assertEquals("acs-target",done.path("targetTaskRef").asText());
    }

    /** D06 boundary: a substantive-but-unbound reply (different run) and an
     *  ack reply under our run are both rejected; only the reply bound through
     *  the prompt's own run counts. */
    @Test public void acsEvidenceRequiresTheConsumingRunNotAnyReply()throws Exception {
        call("POST","/tasks",launch(),"original-launch");scopeAcs();
        acsSession=acsSessionFixture("sample");
        JsonNode created=wh("POST","",createBody("wh-acsrun")
                .set("targetAgentRef",acsTarget("sample")),"wh-acsrun").body;
        String hid=created.path("handoffId").asText();
        driveToStarting(hid,"wh-acsrun");
        cont(hid,"wh-acsrun","TARGET_STARTING");
        acsConsume("run-1");
        // A substantive reply from a DIFFERENT run must not satisfy the probe.
        acsAssistant("assistant:run-9","run-9","已核对交接材料与目标项目一致，从接手第一步继续执行并报告实际动作与核对结果。","SUCCEEDED");
        JsonNode pending=cont(hid,"wh-acsrun","TARGET_STARTING");
        assertEquals("TARGET_VERIFY_PENDING",pending.path("pendingReason").asText());
        // Non-trivial output alone under the bound run also proves takeover
        // (the activity lane stays empty here — the reply carries the facts).
        acsAssistant("assistant:run-1","run-1","已核对交接材料、目标项目与实际代码一致，现执行接手第一步并更新任务进展，核对结果与材料相符。","SUCCEEDED");
        JsonNode done=cont(hid,"wh-acsrun","TARGET_STARTING");
        assertEquals("TAKEN_OVER",done.path("phase").asText());
    }
    @Test public void sourceStopRetainsPiPlatformProofContractsAndRefusesMissingOrUnknownProof()throws Exception {
        scope();int index=0;
        for(String method:Arrays.asList("DARWIN_KQUEUE_NO_FORK_GROUP_ZERO","OWNED_PROCESS_GROUP","MODEL_NO_FORK_AND_REGISTERED_PROCESS_GROUP_ZERO")){
            sourceStopped=false;sourceStopMethod=null;String key="proof-platform-"+(index++);
            String id=wh("POST","",createBody(key),key).body.path("handoffId").asText();
            cont(id,key,"PREPARING");cont(id,key,"PREPARING");
            String confirm="handoff:"+key+":CONFIRM";wh("POST","/"+id+"/confirm",json.createObjectNode().put("requestKey",confirm).put("expectedMaterialRevision",1),confirm);
            int before=sealPosts.get();assertEquals("SEAL_PENDING",cont(id,key,"SOURCE_SEALING").path("pendingReason").asText());assertEquals(before,sealPosts.get());
            sourceStopMethod="UNKNOWN_PROCESS_PROOF";assertEquals("SEAL_PENDING",cont(id,key,"SOURCE_SEALING").path("pendingReason").asText());assertEquals(before,sealPosts.get());
            sourceStopMethod=method;assertEquals("FINALIZING_MATERIALS",cont(id,key,"SOURCE_SEALING").path("phase").asText());assertEquals(before+1,sealPosts.get());
        }
    }
}
