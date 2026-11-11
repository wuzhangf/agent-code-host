package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.http.MaterialUploadApiHandler;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.application.gui.GuiMessage;
import com.sage.business.code.application.material.MaterialUploadService;
import com.sage.business.code.application.session.*;
import com.sage.business.code.contract.v2.session.*;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.WorkspaceRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import com.sage.business.code.infrastructure.gui.GuiSessionStore;
import com.sage.business.code.infrastructure.session.SqliteSessionRepository;
import com.sun.net.httpserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Actual material HTTP handler -> durable upload/binding -> SessionService
 * reference-text injection. The final gateway is controlled, never a model. */
public class PersonalAcsHandoffMaterialContractTest {
    PersonalWorkHandoffTest f;
    ControlPlaneDatabase db;SqliteSessionRepository repository;GuiSessionStore gui;
    SessionService sessions;MaterialUploadService materials;HttpServer target;String origin;
    int creates,messagePosts,gatewayCalls,rawPuts;String content,clientKey,sid;
    List<SessionAttachment> received;ObjectNode lastWire;
    boolean rejectFirstInput,dropBeforeAcceptance,historyHasOlder;
    String materialFailure;
    com.sage.business.code.application.run.RunQueryService runQueries;
    boolean dropRetryBefore,dropRetryAfter;
    int retryPosts;
    static final String OWNER="test-material-receiver-only";
    @Before public void setup()throws Exception {
        f=new PersonalWorkHandoffTest();f.setup();f.handoffSetup();f.scopeAcs();
        Path ws=Files.createDirectories(f.root.resolve("receiver-workspace"));
        Files.writeString(ws.resolve("README.md"),"isolated material consumer\n");
        for(String[] args:new String[][]{{"init","-q"},{"add","."},{"-c","user.name=Contract","-c","user.email=contract@invalid","commit","-qm","baseline"}}){
            List<String> argv=new ArrayList<>(Arrays.asList("git","-C",ws.toString()));argv.addAll(Arrays.asList(args));
            assertEquals(0,new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());
        }
        Path state=f.root.resolve("receiver-state");db=new ControlPlaneDatabase(state);
        WorkspaceRegistry workspaces=new WorkspaceRegistry(Collections.singletonMap("control",ws.toString()));
        repository=new SqliteSessionRepository(db,f.json);gui=new GuiSessionStore(state,f.json);
        sessions=new SessionService(repository,workspaces,f.json,new BusinessEventPublisher(new SqliteEventJournal(db,f.json)),
            new SessionService.MessageGateway(){
                public Map<String,Object> sendMessage(String session,String client,String text,String key,List<SessionAttachment> attachments){
                    gatewayCalls++;content=text;clientKey=client;received=attachments;
                    GuiMessage row=new GuiMessage();row.setId("msg-"+UUID.randomUUID());row.setSessionId(session);row.setClientMessageId(client);
                    row.setRole("user");row.setStatus("QUEUED");row.setContent(text);row.setRunId("receiver-run");
                    row.setCreatedAt(System.currentTimeMillis());row.setUpdatedAt(row.getCreatedAt());row.setMetadata(new LinkedHashMap<>());gui.insertMessage(row);
                    Map<String,Object> result=new LinkedHashMap<>();result.put("messageId",row.getId());result.put("runId",row.getRunId());result.put("status","QUEUED");return result;
                }
            });
        materials=new MaterialUploadService(db,workspaces,repository::find,null,f.json);sessions.messageMaterials(materials);
        MaterialUploadApiHandler handler=new MaterialUploadApiHandler(f.json,OWNER,materials);
        target=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        target.createContext("/",x->{try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(path.contains("material-uploads")&&materialFailure!=null)throw new BusinessException(materialFailure,"PROJECT_NOT_FOUND".equals(materialFailure)?404:403,"controlled refusal");
            if(path.contains("/chunks/"))rawPuts++;
            if(handler.handleGroup(x,path,method))return;
            if(path.equals("/api/v2/sessions")&&method.equals("POST")){
                creates++;SessionResponse result=sessions.create(f.json.treeToValue(f.json.readTree(x.getRequestBody()),SessionCreateRequest.class),x.getRequestHeaders().getFirst("Idempotency-Key"));sid=result.getSessionId();f.send(x,201,f.json.valueToTree(result));return;
            }
            if(path.endsWith("/messages")&&method.equals("GET")){
                ObjectNode page=f.json.createObjectNode().put("schemaVersion","session-message-page/1").put("sessionId",sid)
                        .put("hasOlder",historyHasOlder).put("hasNewer",false);page.set("items",f.json.valueToTree(gui.listMessages(sid)));f.send(x,200,page);return;
            }
            if(path.endsWith("/messages")&&method.equals("POST")){
                messagePosts++;lastWire=(ObjectNode)f.json.readTree(x.getRequestBody());
                if(dropBeforeAcceptance){dropBeforeAcceptance=false;x.close();return;}
                SessionMessageRequest request=f.json.treeToValue(lastWire,SessionMessageRequest.class);
                if(rejectFirstInput){rejectFirstInput=false;request.setContent("x".repeat(5000));}
                f.send(x,202,f.json.valueToTree(sessions.sendMessage(sid,request,x.getRequestHeaders().getFirst("Idempotency-Key"))));return;
            }
            if(path.contains("/failed-turn-retries")){
                if(method.equals("POST")){retryPosts++;if(dropRetryBefore){dropRetryBefore=false;x.close();return;}}
                if(dropRetryAfter&&method.equals("POST")){
                    dropRetryAfter=false;sessions.retryFailedTurn(sid,f.json.treeToValue(f.json.readTree(x.getRequestBody()),SessionFailedTurnRetryRequest.class),x.getRequestHeaders().getFirst("Idempotency-Key"));x.close();return;
                }
                if(new com.sage.business.code.adapter.http.V2ApiHandler(new SqliteEventJournal(db,f.json),new com.sage.business.code.application.event.LiveEventHub(),f.json,OWNER,Collections::emptyList,sessions).handleGroup(x,path,method))return;
            }
            if(path.startsWith("/api/v2/runs/")&&runQueries!=null&&method.equals("GET")){
                f.send(x,200,f.json.valueToTree(runQueries.view(path.substring("/api/v2/runs/".length()))));return;
            }
            if(path.startsWith("/api/v2/sessions/")&&method.equals("GET")){f.send(x,200,f.json.valueToTree(sessions.get(sid)));return;}
            f.send(x,404,f.json.createObjectNode().put("code","NOT_FOUND"));
        }catch(BusinessException error){f.send(x,error.getHttpStatus(),f.json.createObjectNode().put("code",error.getCode()));}
         catch(Exception error){f.send(x,500,f.json.createObjectNode().put("code","CONTROLLED_RECEIVER_FAILURE"));}
         finally{x.close();}});
        target.start();origin="http://127.0.0.1:"+target.getAddress().getPort();
        f.handoffs=new PersonalWorkHandoffService(f.records,f.auth,f.json,new PersonalWorkHandoffService.AcsUpstream(){
            public PersonalWorkHandoffService.Reply call(PersonalAccessService.Device d,String m,String p,JsonNode b,String k)throws IOException{return http(m,p,b==null?null:f.json.writeValueAsBytes(b),k);}
            public PersonalWorkHandoffService.Reply callBytes(PersonalAccessService.Device d,String m,String p,byte[] b,String k)throws IOException{return http(m,p,b,k);}
        },new PersonalWorkHandoffService.NativePort(){
            public PersonalLocalTaskAccess.Result call(PersonalAccessService.Device d,String b,String m,String p,JsonNode v,String k)throws IOException{return f.access.call(d,b,m,p,v,k);}
            public PersonalLocalTaskAccess.Result callBytes(PersonalAccessService.Device d,String b,String m,String p,byte[] v,Map<String,String> h,String k)throws IOException{return f.access.callBytes(d,b,m,p,v,h,k);}
        },f.materialRoot,f.signingRoot);
    }
    PersonalWorkHandoffService.Reply http(String method,String path,byte[] bytes,String key)throws IOException {
        HttpURLConnection c=(HttpURLConnection)URI.create(origin+path).toURL().openConnection();c.setInstanceFollowRedirects(false);c.setRequestMethod(method);
        c.setConnectTimeout(2000);c.setReadTimeout(5000);c.setRequestProperty("Authorization","Bearer "+OWNER);
        if(key!=null)c.setRequestProperty("Idempotency-Key",key);
        try{if(bytes!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);c.getOutputStream().write(bytes);}
            int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();return new PersonalWorkHandoffService.Reply(status,in==null?null:f.json.readTree(in));}
        finally{c.disconnect();}
    }
    @After public void cleanup()throws Exception {if(target!=null)target.stop(0);if(gui!=null)gui.close();if(db!=null)db.close();if(f!=null)f.cleanup();}
    String frozen()throws Exception {
        f.exportBytes=("complete-public-export\n"+"保留原文。".repeat(1200)+"\nCONTROL_HANDOFF_完整材料末尾中文").getBytes(StandardCharsets.UTF_8);
        ObjectNode create=f.createBody("material-flow");create.putObject("targetAgentRef").put("hostKey","local").put("kind","ACS").put("projectRef","control");
        String id=f.wh("POST","",create,"material-flow").body.path("handoffId").asText();
        f.cont(id,"material-flow","PREPARING");f.cont(id,"material-flow","PREPARING");f.conf(id,"material-flow",1);
        f.cont(id,"material-flow","SOURCE_SEALING");f.cont(id,"material-flow","FINALIZING_MATERIALS");return id;
    }
    @Test public void rejectedOriginalPromptRecoversSameSessionAndFullTextReachesRealMaterialConsumer()throws Exception {
        String id=frozen();f.cont(id,"material-flow","TARGET_STAGING");rejectFirstInput=true;
        try{f.cont(id,"material-flow","TARGET_STARTING");fail("old text rejection not surfaced");}catch(BusinessException e){assertEquals("INPUT_LIMIT_EXCEEDED",e.getCode());}
        String originalSid=sid,originalKey=lastWire.path("clientMessageId").asText();assertTrue(gui.listMessages(sid).isEmpty());assertEquals(0,gatewayCalls);
        JsonNode before=f.wh("GET","/"+id,null,null).body;String digest=before.path("materialDigest").asText();
        // Exact legacy recovery boundary: a durable original session and rejected
        // prompt predate the packaged material channel. Never create another sid.
        f.records.transaction(c->{PersonalWorkHandoffService.Handoff h=f.records.get(c,"local-handoff",f.did+":"+id,PersonalWorkHandoffService.Handoff.class);
            h.acsMaterialRef=null;h.acsMaterialSha256=null;h.acsPromptState=null;h.materialBinding="INLINE_REFERENCES";h.stagedRefs.clear();
            f.records.put(c,"local-handoff",f.did+":"+id,h);return null;});
        JsonNode continued=f.cont(id,"material-flow","TARGET_STARTING");assertEquals("TARGET_STARTING",continued.path("phase").asText());
        assertEquals(originalSid,sid);assertEquals(1,creates);assertEquals(originalKey,clientKey);assertTrue(content.length()<4000);assertEquals(1,gatewayCalls);
        assertEquals(1,lastWire.path("materials").size());assertTrue(rawPuts>0);assertEquals(1,received.size());
        String reference=received.get(0).getTextContent();assertTrue(reference.contains("<<reference-material"));
        assertTrue(reference.contains(new String(f.exportBytes,StandardCharsets.UTF_8)));assertTrue(reference.contains(digest));
        assertTrue(reference.contains("CONTROL_HANDOFF_完整材料末尾中文"));assertEquals(digest,continued.path("materialDigest").asText());
        JsonNode uploads=materials.list("control");boolean consumed=false;for(JsonNode upload:uploads.path("items"))if("CONSUMED".equals(upload.path("state").asText())){consumed=true;assertEquals(originalSid,upload.path("target").path("sessionId").asText());}
        assertTrue("actual sealed upload must bind and consume",consumed);
        f.cont(id,"material-flow","TARGET_STARTING");assertEquals(1,creates);assertEquals(2,messagePosts);assertEquals(1,gatewayCalls);assertEquals(1,f.sealPosts.get());
    }
    @Test public void materialAuthorizationAndProjectErrorsDoNotBecomeInlineFallback()throws Exception {
        String id=frozen();materialFailure="FORBIDDEN";
        try{f.cont(id,"material-flow","TARGET_STAGING");fail("authorization swallowed");}catch(BusinessException e){assertEquals("FORBIDDEN",e.getCode());}
        assertEquals("TARGET_STAGING",f.wh("GET","/"+id,null,null).body.path("phase").asText());assertEquals(0,creates);
        materialFailure="PROJECT_NOT_FOUND";
        try{f.cont(id,"material-flow","TARGET_STAGING");fail("project error swallowed");}catch(BusinessException e){assertEquals("PROJECT_NOT_FOUND",e.getCode());}
        assertEquals(0,creates);assertEquals(0,messagePosts);
    }
    @Test public void uncertainSendAndIncompleteHistoryCannotRepostThePrompt()throws Exception {
        String id=frozen();f.cont(id,"material-flow","TARGET_STAGING");dropBeforeAcceptance=true;
        try{f.cont(id,"material-flow","TARGET_STARTING");fail("unknown send should fail");}catch(IOException expected){}
        int posts=messagePosts;assertEquals(1,posts);assertTrue(gui.listMessages(sid).isEmpty());
        JsonNode pending=f.cont(id,"material-flow","TARGET_STARTING");assertEquals("TARGET_PROMPT_OUTCOME_UNKNOWN",pending.path("pendingReason").asText());assertEquals(posts,messagePosts);assertEquals(1,creates);
        // Even a historical explicit rejection cannot prove absence on a truncated page.
        f.records.transaction(c->{PersonalWorkHandoffService.Handoff h=f.records.get(c,"local-handoff",f.did+":"+id,PersonalWorkHandoffService.Handoff.class);h.failureCode="INPUT_LIMIT_EXCEEDED";h.failurePhase="TARGET_STARTING";f.records.put(c,"local-handoff",f.did+":"+id,h);return null;});
        historyHasOlder=true;f.cont(id,"material-flow","TARGET_STARTING");assertEquals(posts,messagePosts);assertEquals(1,creates);
    }
}
