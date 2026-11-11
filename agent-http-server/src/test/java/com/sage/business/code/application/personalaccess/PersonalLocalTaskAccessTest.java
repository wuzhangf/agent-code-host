package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sun.net.httpserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real loopback HTTP; controlled source facts. No native model or user profile is loaded. */
public class PersonalLocalTaskAccessTest {
    final ObjectMapper json=new ObjectMapper();Path root;ControlPlaneDatabase db;PersonalAccessStore records;PersonalAccessService auth;PersonalLocalTaskAccess access;
    HttpServer host;ExecutorService workers;String secret,token,did;byte[] fixtureSealKey;volatile String backend="native-store";
    AtomicInteger launchPosts=new AtomicInteger(),inputPosts=new AtomicInteger();AtomicBoolean loseLaunch=new AtomicBoolean(),loseInput=new AtomicBoolean();
    ConcurrentMap<String,JsonNode> launches=new ConcurrentHashMap<String,JsonNode>(),inputs=new ConcurrentHashMap<String,JsonNode>();
    volatile CountDownLatch entered,release;
    volatile String engineName="pi",nativeState="RUNNING",publicOutput="Public protocol fixture reply";
    volatile ObjectNode question;volatile JsonNode lastQuestionResponse;
    // 新增(native-messages phase1):source fixtures for paged messages reads — recorded raw query, call count and an optional controlled reply.
    volatile ObjectNode messagesReply;volatile String messagesQuery,eventsQuery;AtomicInteger messageReads=new AtomicInteger(),eventReads=new AtomicInteger();
    // worker-events/1 seam: sseFrames switches the events route to a real SSE
    // reply (block list written verbatim, sseHold keeps the stream open);
    // the recorded Accept/Last-Event-ID prove what PAC negotiated upstream.
    volatile List<byte[]> sseFrames;volatile CountDownLatch sseHold;volatile String eventsAccept,eventsLastEventId;
    volatile int eventsErrorStatus;volatile String eventsErrorCode;volatile String taskRecipeId;
    volatile boolean pauseAvailable,resumeAvailable;
    @Before public void setup()throws Exception {
        root=Files.createTempDirectory("personal-native-http-");db=new ControlPlaneDatabase(root,false);records=new PersonalAccessStore(db,json);byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        fixtureSealKey=key.clone();auth=new PersonalAccessService(records,json,key,"acs-store","https://localhost");
        String poll=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();ObjectNode invite=auth.invitation("invite",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE","CONTROL")),"fixture-cert");
        auth.request(json.createObjectNode().put("inviteId","invite").put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",request).put("installationId","public-fixture").put("label","phone").put("pollingSecretHash",PersonalAccessService.hash(poll)));auth.decide(request,true);ObjectNode issued=auth.redeem(request,poll,"redeem");token=issued.path("accessToken").asText();did=issued.path("deviceGrantId").asText();
        auth.updateScopeResources(did,"native-scope","1",Collections.emptyList(),Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","APPROVE")));
        secret=UUID.randomUUID().toString()+UUID.randomUUID();host=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);workers=Executors.newFixedThreadPool(8);host.setExecutor(workers);host.createContext("/local/v1/",this::handle);host.start();
        access=new PersonalLocalTaskAccess("http://127.0.0.1:"+host.getAddress().getPort(),secret,records,auth,json);
    }
    @After public void cleanup()throws Exception {if(release!=null)release.countDown();host.stop(0);workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);db.close();try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    void handle(HttpExchange x){try{
        if(!("Bearer "+secret).equals(x.getRequestHeaders().getFirst("Authorization"))){send(x,401,json.createObjectNode());return;}
        String expected=x.getRequestHeaders().getFirst("X-Worker-Local-Store");if(expected!=null&&!expected.equals(backend)){send(x,409,json.createObjectNode().put("code","STORE_IDENTITY_CHANGED"));return;}
        String path=x.getRequestURI().getPath().substring("/local/v1".length()),method=x.getRequestMethod();JsonNode body=method.equals("POST")?json.readTree(x.getRequestBody()):null;
        if(path.equals("/identity")){send(x,200,json.createObjectNode().put("storeId",backend).put("storeGuard","HEADER_V1"));return;}
        if(path.equals("/projects")){ObjectNode out=json.createObjectNode();out.putArray("items").addObject().put("projectId","project-one").put("displayName","Fixture project").put("path","private-owner-path").put("headRevision","fixture-head");send(x,200,out);return;}
        if(path.equals("/engines")){ObjectNode out=json.createObjectNode();out.putArray("items").addObject().put("engineId",engineName).put("version","fixture-version");send(x,200,out);return;}
        if(path.equals("/task-preflights")){send(x,200,json.createObjectNode().put("projectId","project-one").put("engineId","pi").put("optionsRevision","frozen-options").put("profileHome","private-profile").put("profileId","USER_PROFILE"));return;}
        if(path.equals("/tasks")&&method.equals("POST")){
            launchPosts.incrementAndGet();if(entered!=null){entered.countDown();release.await(5,TimeUnit.SECONDS);}
            ObjectNode result=json.createObjectNode().put("taskId","native-one").put("engineId",engineName).put("engineVersion","fixture-version");launches.put(body.path("requestKey").asText(),result);
            if(loseLaunch.getAndSet(false)){x.close();return;}send(x,202,result);return;
        }
        if(path.startsWith("/task-requests/")){JsonNode result=launches.get(path.substring("/task-requests/".length()));send(x,result==null?404:200,result==null?json.createObjectNode().put("code","REQUEST_NOT_FOUND"):result);return;}
        if(path.equals("/tasks/native-one")&&method.equals("GET")){
            ObjectNode out=json.createObjectNode().put("taskId","native-one").put("engineId",engineName).put("storeId",backend).put("state",nativeState).put("generation","g1").put("nativeSessionId","native-session").put("currentCommandId","command-one").put("workspace","private-workspace");if(taskRecipeId!=null)out.put("recipeID",taskRecipeId);out.putObject("capabilities").put("stop",true).put("pause",pauseAvailable).put("resume",resumeAvailable).put("steer","RUNNING".equals(nativeState)).put("followUp","SUCCEEDED".equals(nativeState));send(x,200,out);return;
        }
        // 新增(native-messages phase1):messages/events GET record the raw forwarded query; messagesReply overrides the default legacy reply.
        if(path.equals("/tasks/native-one/messages")&&method.equals("GET")){messageReads.incrementAndGet();messagesQuery=x.getRequestURI().getRawQuery();send(x,200,messagesReply!=null?messagesReply:json.createObjectNode().put("text",publicOutput));return;}
        if(path.equals("/tasks/native-one/events")&&method.equals("GET")){
            eventReads.incrementAndGet();eventsQuery=x.getRequestURI().getRawQuery();
            eventsAccept=x.getRequestHeaders().getFirst("Accept");eventsLastEventId=x.getRequestHeaders().getFirst("Last-Event-ID");
            if(eventsErrorStatus!=0){send(x,eventsErrorStatus,json.createObjectNode().put("code",eventsErrorCode));return;}
            boolean negotiated=eventsAccept!=null&&eventsAccept.contains("text/event-stream")||eventsQuery!=null&&eventsQuery.contains("follow=1");
            if(sseFrames!=null&&negotiated){
                try{
                    x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");x.sendResponseHeaders(200,0);
                    for(byte[] frame:sseFrames){x.getResponseBody().write(frame);x.getResponseBody().flush();}
                    if(sseHold!=null)sseHold.await(15,TimeUnit.SECONDS);
                }catch(Exception ignored){}finally{x.close();}
                return;
            }
            ObjectNode feed=json.createObjectNode();feed.putArray("items");send(x,200,feed);return;
        }
        if(path.equals("/tasks/native-one/output")&&method.equals("GET")){send(x,200,json.createObjectNode().put("text",publicOutput).put("coverage","BOUNDED_PUBLIC_OUTPUT"));return;}
        if(path.equals("/tasks/native-one/input-requests")&&method.equals("GET")){ObjectNode out=json.createObjectNode();ArrayNode qs=out.putArray("items");if(question!=null)qs.add(question);send(x,200,out);return;}
        if(path.startsWith("/tasks/native-one/input-requests/")&&path.endsWith("/responses")&&method.equals("POST")){
            inputPosts.incrementAndGet();lastQuestionResponse=body.path("response").deepCopy();
            // Actual Pi INPUT_RESPONSE vocabulary: confirm boolean, input/editor
            // string via value/text, select an exact offered string. The Codex
            // item approval's legacy decision field stays a distinct source form.
            if(question!=null&&"pi".equals(engineName)){
                JsonNode answer=body.path("response"),value=answer.has("value")?answer.get("value"):answer.has("text")?answer.get("text"):answer.has("selected")?answer.get("selected"):answer.get("confirmed");String methodName=question.path("method").asText();
                boolean valid="confirm".equals(methodName)?value!=null&&value.isBoolean():value!=null&&value.isTextual();
                if(valid&&"select".equals(methodName)){valid=false;for(JsonNode option:question.path("options"))if(option.equals(value))valid=true;}
                if(!valid){send(x,409,json.createObjectNode().put("code","INVALID_NATIVE_RESPONSE"));return;}
            }
            ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("state","ACKNOWLEDGED");inputs.put(body.path("requestKey").asText(),result);if(question!=null)question.put("state","ANSWERED");if(loseInput.getAndSet(false)){x.close();return;}send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/input-requests")&&method.equals("GET")){send(x,200,json.createObjectNode().putArray("items"));return;}
        if((path.equals("/tasks/native-one/messages")||path.equals("/tasks/native-one/commands"))&&method.equals("POST")){
            inputPosts.incrementAndGet();ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("state","ACKNOWLEDGED");inputs.put(body.path("requestKey").asText(),result);
            if(loseInput.getAndSet(false)){x.close();return;}send(x,202,result);return;
        }
        if(path.startsWith("/tasks/native-one/interaction-requests/")){JsonNode result=inputs.get(path.substring("/tasks/native-one/interaction-requests/".length()));send(x,result==null?404:200,result==null?json.createObjectNode().put("code","REQUEST_NOT_FOUND"):result);return;}
        send(x,404,json.createObjectNode().put("code","NOT_FOUND"));
    }catch(Exception e){send(x,500,json.createObjectNode().put("code","FIXTURE_FAILURE"));}}
    void send(HttpExchange x,int status,JsonNode value){try{byte[] bytes=json.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}catch(Exception ignored){}finally{x.close();}}
    ObjectNode launch(){return json.createObjectNode().put("requestKey","original-launch").put("projectId","project-one").put("engineId","pi").put("optionsRevision","frozen-options").put("instruction","Public controlled native fixture");}
    ObjectNode answer(String key,JsonNode response){return json.createObjectNode().put("requestKey",key).put("generation","g1").put("nativeSessionId","native-session").put("targetCommandId","command-one").set("response",response);}
    void pendingQuestion(String method){question=json.createObjectNode().put("inputRequestId","q1").put("state","PENDING").put("method",method);question.putArray("options").add("Alpha").add("Beta");}
    PersonalLocalTaskAccess.Result call(String method,String tail,JsonNode body,String key)throws Exception{return access.remote(token,method,"/personal/v1/local-tasks/native-store"+tail,body,key,null);}
    // 新增(native-messages phase1):GET with a raw query string, exactly as the adapter forwards it.
    PersonalLocalTaskAccess.Result callq(String tail,String query)throws Exception{return access.remote(token,"GET","/personal/v1/local-tasks/native-store"+tail,null,null,query);}
    void denied(String code,Callable<?> task)throws Exception {try{task.call();fail("Expected "+code);}catch(BusinessException expected){assertEquals(code,expected.getCode());}}

    @Test public void lostLaunchReplyOnlyReadsOriginalNamespacedSourceKeyAndAppendsExactTask()throws Exception {
        loseLaunch.set(true);assertEquals("UNKNOWN",call("POST","/tasks",launch(),"original-launch").body.path("state").asText());assertEquals(1,launchPosts.get());
        denied("DEVICE_SCOPE_DENIED",()->auth.authorizeLocal(token,backend,"native-one","OBSERVE"));
        JsonNode recovered=call("GET","/task-requests/original-launch",null,null).body;assertEquals("OBSERVED",recovered.path("state").asText());assertEquals("native-one",recovered.path("taskId").asText());assertEquals(1,launchPosts.get());
        assertNotNull(auth.authorizeLocal(token,backend,"native-one","OBSERVE"));denied("DEVICE_SCOPE_DENIED",()->auth.authorizeLocal(token,backend,"sibling","OBSERVE"));
        assertTrue(launches.keySet().iterator().next().startsWith("mobile-"));assertNotEquals("original-launch",launches.keySet().iterator().next());
        call("POST","/tasks",launch(),"original-launch");assertEquals(1,launchPosts.get());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->call("POST","/tasks",launch().put("instruction","Changed"),"original-launch"));
    }
    @Test public void nativeInputLossKeepsOriginalGuardAndNeverRepostsDuringObservation()throws Exception {
        call("POST","/tasks",launch(),"original-launch");loseInput.set(true);
        ObjectNode body=json.createObjectNode().put("requestKey","original-input").put("kind","NATIVE_STEER").put("content","Controlled additional information").put("generation","g1").put("nativeSessionId","native-session").put("targetCommandId","command-one");
        assertEquals("UNKNOWN",call("POST","/tasks/native-one/messages",body,"original-input").body.path("state").asText());assertEquals(1,inputPosts.get());
        assertEquals("OBSERVED",call("GET","/tasks/native-one/requests/original-input",null,null).body.path("state").asText());
        call("POST","/tasks/native-one/messages",body,"original-input");assertEquals(1,inputPosts.get());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->call("POST","/tasks/native-one/messages",body.deepCopy().put("content","Changed"),"original-input"));
        denied("NATIVE_TARGET_CHANGED",()->call("POST","/tasks/native-one/messages",body.deepCopy().put("requestKey","new-input").put("targetCommandId","old-command"),"new-input"));assertEquals(1,inputPosts.get());
    }
    @Test public void pauseResumeUseSourceCapabilitiesAndRecoverLostReceiptWithoutReposting()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        ObjectNode body=json.createObjectNode().put("requestKey","pause-one").put("intent","PAUSE").put("generation","g1").put("nativeSessionId","native-session").put("targetCommandId","command-one");
        denied("NATIVE_ACTION_UNSUPPORTED",()->call("POST","/tasks/native-one/commands",body,"pause-one"));
        assertEquals(0,inputPosts.get());pauseAvailable=true;loseInput.set(true);
        assertEquals("UNKNOWN",call("POST","/tasks/native-one/commands",body,"pause-one").body.path("state").asText());
        pauseAvailable=false;
        assertEquals("OBSERVED",call("GET","/tasks/native-one/requests/pause-one",null,null).body.path("state").asText());
        call("POST","/tasks/native-one/commands",body,"pause-one");assertEquals(1,inputPosts.get());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->call("POST","/tasks/native-one/commands",body.deepCopy().put("intent","RESUME"),"pause-one"));
        resumeAvailable=true;ObjectNode resume=body.deepCopy().put("requestKey","resume-one").put("intent","RESUME");
        assertEquals("RESUME",call("POST","/tasks/native-one/commands",resume,"resume-one").body.path("operation").asText());
        assertEquals(2,inputPosts.get());
    }
    @Test public void mobileConfirmTextAndSelectReachNativeQuestionContract()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        ObjectNode[] answers={json.createObjectNode().put("confirmed",false),json.createObjectNode().put("text","中文、 空格 和标点。"),json.createObjectNode().put("value","Beta"),json.createObjectNode().put("text","")};
        String[] methods={"confirm","input","select","editor"};
        for(int i=0;i<answers.length;i++){pendingQuestion(methods[i]);String key="answer-"+i;
            assertEquals("OBSERVED",call("POST","/tasks/native-one/input-requests/q1/responses",answer(key,answers[i]),key).body.path("state").asText());
            assertEquals(answers[i],lastQuestionResponse);assertEquals("ANSWERED",question.path("state").asText());}
        assertEquals(4,inputPosts.get());
        // The existing source-specific decision wire is unchanged, not mapped
        // into a synthetic Pi approval or added to Pi's method vocabulary.
        engineName="codex";pendingQuestion("item/commandExecution/requestApproval");ObjectNode decision=json.createObjectNode().put("decision","accept");
        call("POST","/tasks/native-one/input-requests/q1/responses",answer("legacy-decision",decision),"legacy-decision");assertEquals(decision,lastQuestionResponse);
    }
    @Test public void textAnswerLossRecoversSameScopedKeyWithoutRedispatchOrBodyNormalization()throws Exception {
        call("POST","/tasks",launch(),"original-launch");pendingQuestion("input");loseInput.set(true);
        ObjectNode original=answer("answer-original",json.createObjectNode().put("text","  中文\nanswer  "));
        assertEquals("UNKNOWN",call("POST","/tasks/native-one/input-requests/q1/responses",original,"answer-original").body.path("state").asText());
        assertEquals("OBSERVED",call("POST","/tasks/native-one/input-requests/q1/responses",original,"answer-original").body.path("state").asText());assertEquals(1,inputPosts.get());
        assertEquals(original.path("response"),lastQuestionResponse);
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->call("POST","/tasks/native-one/input-requests/q1/responses",answer("answer-original",json.createObjectNode().put("text","changed")),"answer-original"));
        pendingQuestion("input");denied("NATIVE_TARGET_CHANGED",()->call("POST","/tasks/native-one/input-requests/q1/responses",answer("stale-answer",json.createObjectNode().put("text","x")).put("targetCommandId","stale"),"stale-answer"));assertEquals(1,inputPosts.get());
    }
    @Test public void questionAnswerRejectsUnknownMultipleAndOversizedFieldsWithoutDispatch()throws Exception {
        call("POST","/tasks",launch(),"original-launch");pendingQuestion("input");
        ObjectNode[] invalid={json.createObjectNode().put("cancelled",true),json.createObjectNode().put("text",1),json.createObjectNode().put("text","x").put("value","x"),json.createObjectNode().put("text",String.join("",Collections.nCopies(5462,"中")))};
        for(int i=0;i<invalid.length;i++){final int n=i;denied("INVALID_REQUEST",()->call("POST","/tasks/native-one/input-requests/q1/responses",answer("invalid-"+n,invalid[n]),"invalid-"+n));}
        assertEquals(0,inputPosts.get());
        String noApproval=observingDevice(Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")));
        denied("DEVICE_SCOPE_DENIED",()->access.remote(noApproval,"POST","/personal/v1/local-tasks/"+backend+"/tasks/native-one/input-requests/q1/responses",answer("no-approval",json.createObjectNode().put("text","x")),"no-approval",null));assertEquals(0,inputPosts.get());
    }
    @Test public void piAdapterDoesNotRewriteLegacyCodexSourceBodies()throws Exception {
        call("POST","/tasks",launch(),"original-launch");engineName="codex";
        ObjectNode body=json.createObjectNode().put("requestKey","legacy-aux").put("question","Legacy source question").put("contextPackageId","pkg-one");
        call("POST","/tasks/native-one/auxiliary-sessions",body,"legacy-aux");
        PersonalLocalTaskAccess.Request record=records.transaction(c->records.get(c,"local-aux",did+":legacy-aux",PersonalLocalTaskAccess.Request.class));
        assertEquals("pkg-one",record.wire.path("contextPackageId").asText());assertFalse(record.wire.has("contextPackageIds"));assertFalse(record.wire.path("requestKey").asText().equals("legacy-aux"));
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->call("POST","/tasks/native-one/auxiliary-sessions",body.deepCopy().put("question","Changed"),"legacy-aux"));
    }
    @Test public void revokeDoesNotWaitForSourceAndWithholdsAlreadyAdmittedLaunchResult()throws Exception {
        entered=new CountDownLatch(1);release=new CountDownLatch(1);ExecutorService tasks=Executors.newFixedThreadPool(2);
        try{Future<?> launching=tasks.submit(()->{try{call("POST","/tasks",launch(),"original-launch");fail("Revoked result must be withheld");}catch(BusinessException expected){assertEquals("DEVICE_REVOKED",expected.getCode());}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));tasks.submit(()->auth.revoke(did)).get(2,TimeUnit.SECONDS);release.countDown();launching.get(5,TimeUnit.SECONDS);assertEquals(1,launchPosts.get());
        }finally{release.countDown();tasks.shutdownNow();}
    }
    @Test public void launchReceiptBacksInitiatorOnListAndDetail()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        JsonNode rows=access.remote(token,"GET","/personal/v1/local-tasks",null,null,null).body;
        JsonNode initiator=null;
        for(JsonNode ref:rows.path("resources"))if("native-one".equals(ref.path("taskId").asText()))initiator=ref.path("initiator");
        assertNotNull("list row carries evidence-backed initiator",initiator);
        assertEquals("HUMAN",initiator.path("kind").asText());
        assertEquals("local-launch:original-launch",initiator.path("evidenceRef").asText());
        assertFalse("device label is device evidence, never a user name",initiator.has("actorLabel"));
        // R05: the same evidence surfaces on task detail, not only the list.
        JsonNode detail=call("GET","/tasks/native-one",null,null).body;
        assertEquals("HUMAN",detail.path("initiator").path("kind").asText());
        assertEquals("local-launch:original-launch",detail.path("initiator").path("evidenceRef").asText());
    }
    @Test public void sourceCatalogOmitsPrivatePathsAndUnregisteredBackendIsDenied()throws Exception {
        assertFalse(access.ownerCatalog().toString().contains("private-owner-path"));
        JsonNode preflight=call("POST","/task-preflights",json.createObjectNode().put("projectId","project-one").put("engineId","pi"),null).body;assertFalse(preflight.has("profileHome"));
        denied("DEVICE_SCOPE_DENIED",()->access.remote(token,"POST","/personal/v1/local-tasks/other-store/tasks",launch(),"original-launch",null));
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/task-preflights",json.createObjectNode().put("projectId","other-project").put("engineId","pi"),null));assertEquals(0,launchPosts.get());
    }
    String observingDevice(List<PersonalResourceScope.Resource> resources)throws Exception {
        String iid=UUID.randomUUID().toString(),rid=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();
        ObjectNode invite=auth.invitationResources(iid,resources,Collections.emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE")),"fixture-cert");
        auth.request(json.createObjectNode().put("inviteId",iid).put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",rid).put("installationId",UUID.randomUUID().toString()).put("label","Second device").put("pollingSecretHash",PersonalAccessService.hash(poll)));
        auth.decide(rid,true);return auth.redeem(rid,poll,UUID.randomUUID().toString()).path("accessToken").asText();
    }
    @Test public void authorizedSecondDeviceGetsImmutableLaunchTitleWithoutBorrowingInitiator()throws Exception {
        call("POST","/tasks",launch().put("instruction","Original task api_key=private-test-value\nFurther details"),"original-launch");
        String second=observingDevice(Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")));
        JsonNode row=access.remote(second,"GET","/personal/v1/local-tasks",null,null,null).body.path("resources").get(0);
        assertEquals("Original task api_key=[redacted]",row.path("displayName").asText());assertFalse(row.has("initiator"));
        JsonNode detail=access.remote(second,"GET","/personal/v1/local-tasks/"+backend+"/tasks/native-one",null,null,null).body;
        assertEquals(row.path("displayName"),detail.path("displayName"));assertFalse(detail.has("initiator"));
        assertEquals("local-launch:original-launch",call("GET","/tasks/native-one",null,null).body.path("initiator").path("evidenceRef").asText());
    }
    @Test public void launchTitleCannotCrossTaskStoreOrObserveGrantBoundary()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        String second=observingDevice(Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL","other-store","native-one"),new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"sibling")));
        for(JsonNode row:access.remote(second,"GET","/personal/v1/local-tasks",null,null,null).body.path("resources"))assertFalse(row.has("displayName"));
        denied("DEVICE_SCOPE_DENIED",()->access.remote(second,"GET","/personal/v1/local-tasks/"+backend+"/tasks/native-one",null,null,null));
    }
    @Test public void titleRequiresAcceptedLaunchEvidenceAndBoundsUnicodeWithoutGuessing()throws Exception {
        String instruction=String.join("",Collections.nCopies(140,"\uD83D\uDE80"));
        call("POST","/tasks",launch().put("instruction",instruction),"original-launch");
        String title=call("GET","/tasks/native-one",null,null).body.path("displayName").asText();
        assertEquals(128,title.codePointCount(0,title.length()));assertFalse(Character.isHighSurrogate(title.charAt(title.length()-1)));
        records.transaction(c->{PersonalLocalTaskAccess.Request r=records.get(c,"local-launch",did+":original-launch",PersonalLocalTaskAccess.Request.class);r.state="REJECTED";records.put(c,"local-launch",did+":original-launch",r);return null;});
        assertFalse(call("GET","/tasks/native-one",null,null).body.has("displayName"));
        assertFalse(access.remote(token,"GET","/personal/v1/local-tasks",null,null,null).body.path("resources").get(0).has("displayName"));
    }
    @Test public void inboxSignalReadsPendingQuestionDuringRunningTask()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        PersonalResourceScope.Resource resource=new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one");
        PersonalInboxService.SourceRead none=access.signal(auth.authenticate(token),resource,System.currentTimeMillis()+15000);
        assertTrue(none.verifiedKinds.contains("NEEDS_INPUT"));
        assertFalse(none.signals.stream().anyMatch(s->"NEEDS_INPUT".equals(s.kind)));
        question=json.createObjectNode().put("inputRequestId","q1").put("state","PENDING").put("title","Continue?")
                .put("generation","g1").put("nativeSessionId","native-session").put("targetCommandId","command-one");
        PersonalInboxService.SourceRead pending=access.signal(auth.authenticate(token),resource,System.currentTimeMillis()+15000);
        PersonalInboxService.Signal input=pending.signals.stream().filter(s->"NEEDS_INPUT".equals(s.kind)).findFirst().orElse(null);
        assertNotNull(input);
        assertEquals("input:q1",input.eventKey);
        assertEquals("INPUT_REQUEST",input.subject.path("type").asText());
        assertEquals("q1",input.subject.path("inputRequestId").asText());
        assertEquals("g1",input.subject.path("generation").asText());
        assertTrue(input.sourceRevision.contains("g1"));
    }
    @Test public void inboxSignalEmitsBoundedResultForSucceededTask()throws Exception {
        call("POST","/tasks",launch(),"original-launch"); nativeState="SUCCEEDED";
        PersonalInboxService.SourceRead read=access.signal(auth.authenticate(token),new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one"),System.currentTimeMillis()+15000);
        PersonalInboxService.Signal result=read.signals.stream().filter(s->"RESULT_READY".equals(s.kind)).findFirst().orElse(null);
        assertNotNull(result);
        assertEquals("NATIVE_TASK",result.subject.path("type").asText());
        assertEquals("native-one",result.subject.path("taskId").asText());
        assertFalse(result.title.isEmpty());
    }
    // ===== 新增测试(native-messages phase1,设计 §5.3/§8.2/§8.3):messages GET 分页白名单、能力标记与授权边界 =====
    @Test public void messagesGetPassesWhitelistPageQueryAndGatewayMarksTransport()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        messagesReply=json.createObjectNode().put("messageSchemaVersion","native-messages/1").put("taskId","native-one").put("storeId","native-store").put("transcriptRevision","7").put("latestSequence","12").put("hasMore",true).put("cursor","12").put("text","bounded accumulated output");
        messagesReply.putArray("items").addObject().put("messageId","m-12").put("sequence","12").put("revision","1").put("role","assistant").put("text","done").put("status","COMPLETED").put("nativeItemId","item-9");
        PersonalLocalTaskAccess.Result page=callq("/tasks/native-one/messages","before=13&limit=2");
        assertEquals("before=13&limit=2",messagesQuery);assertEquals(1,messageReads.get());
        assertEquals("native-messages/1",page.body.path("messageSchemaVersion").asText());
        assertEquals("native-messages-page/1",page.body.path("messageTransportVersion").asText());
        assertEquals("m-12",page.body.path("items").get(0).path("messageId").asText());assertEquals("12",page.body.path("items").get(0).path("sequence").asText());
        assertEquals("bounded accumulated output",page.body.path("text").asText());
        callq("/tasks/native-one/messages","cursor=12");assertEquals("cursor=12",messagesQuery);
        PersonalLocalTaskAccess.Result latest=callq("/tasks/native-one/messages",null);assertNull(messagesQuery);
        assertEquals("native-messages-page/1",latest.body.path("messageTransportVersion").asText());assertEquals(3,messageReads.get());
    }
    @Test public void messagesGetRejectsIllegalDuplicateMutuallyExclusiveAndUnknownParams()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        for(String bad:Arrays.asList("cursor=abc","cursor=01","cursor=-1","cursor=+1","cursor=9223372036854775808","cursor=18446744073709551615",
                "before=0","before=9223372036854775808","before=-3","limit=0","limit=101","limit=1.5","limit= 5",
                "cursor=1&before=2","before=2&cursor=1","cursor=1&cursor=2","before=3&before=3","limit=5&limit=6",
                "page=1","cursor=1&foo=2","cursor","cursor=","cursor=1=2","CURSOR=1","cursor==1"))
            denied("INVALID_REQUEST",()->callq("/tasks/native-one/messages",bad));
        assertEquals(0,messageReads.get());
    }
    @Test public void messagesGetAcceptsDecimalBoundariesAndKeepsLegacyViewWithoutMarker()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        for(String ok:Arrays.asList("cursor=0","cursor=9223372036854775807","before=1","before=9223372036854775807","limit=1","limit=50","limit=100","cursor=42&limit=7","before=42&limit=7")){
            PersonalLocalTaskAccess.Result r=callq("/tasks/native-one/messages",ok);assertEquals(ok,messagesQuery);
            assertEquals(publicOutput,r.body.path("text").asText());assertFalse(r.body.has("messageTransportVersion"));assertFalse(r.body.has("messageSchemaVersion"));
        }
        assertEquals(9,messageReads.get());
    }
    @Test public void sourceSuppliedTransportMarkerIsRegeneratedByGatewayNeverTrusted()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        messagesReply=json.createObjectNode().put("text","legacy shape").put("messageTransportVersion","native-messages-page/9");
        assertFalse(callq("/tasks/native-one/messages","cursor=1").body.has("messageTransportVersion"));
        messagesReply=json.createObjectNode().put("messageSchemaVersion","native-messages/1").put("messageTransportVersion","forged-transport/9").put("taskId","native-one");
        assertEquals("native-messages-page/1",callq("/tasks/native-one/messages",null).body.path("messageTransportVersion").asText());
    }
    @Test public void nonMessageReadBranchesKeepOriginalQueryRules()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        denied("INVALID_REQUEST",()->callq("/tasks/native-one","cursor=1"));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/output","limit=5"));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/metrics","cursor=1"));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/input-requests","cursor=1"));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/artifacts","cursor=1"));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/events","before=3"));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/events","limit=0"));
        assertEquals(0,eventReads.get());assertEquals(0,messageReads.get());
        PersonalLocalTaskAccess.Result events=callq("/tasks/native-one/events","cursor=1&limit=5");
        assertEquals("cursor=1&limit=5",eventsQuery);assertEquals(1,eventReads.get());assertEquals(200,events.status);
    }
    @Test public void pagedMessagesReadKeepsTaskAuthorizationBoundary()throws Exception {
        denied("DEVICE_SCOPE_DENIED",()->callq("/tasks/sibling/messages","cursor=1"));
        call("POST","/tasks",launch(),"original-launch");
        assertEquals(200,callq("/tasks/native-one/messages","cursor=1").status);assertEquals(1,messageReads.get());
        denied("DEVICE_SCOPE_DENIED",()->callq("/tasks/sibling/messages","cursor=1"));assertEquals(1,messageReads.get());
        auth.revoke(did);denied("DEVICE_REVOKED",()->callq("/tasks/native-one/messages","cursor=1"));assertEquals(1,messageReads.get());
    }
    @Test public void dispatchStartedAtIsTheDurableDispatchStampAndReplayed()throws Exception {
        ObjectNode stampLaunch=launch().put("requestKey","dispatch-stamp");
        PersonalLocalTaskAccess.Result launched=call("POST","/tasks",stampLaunch,"dispatch-stamp");
        assertTrue(launched.body.path("dispatchStartedAt").isIntegralNumber());
        long stamped=launched.body.path("dispatchStartedAt").asLong();
        assertTrue(stamped>0&&stamped<=System.currentTimeMillis());
        PersonalLocalTaskAccess.Result replay=call("POST","/tasks",stampLaunch,"dispatch-stamp");
        assertEquals(stamped,replay.body.path("dispatchStartedAt").asLong());
    }
}
