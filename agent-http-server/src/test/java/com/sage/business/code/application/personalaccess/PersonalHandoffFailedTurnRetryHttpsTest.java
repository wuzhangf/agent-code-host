package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.application.*;
import com.sage.business.code.application.gui.*;
import com.sage.business.code.application.session.*;
import com.sage.business.code.application.run.RunQueryService;
import com.sage.business.code.application.event.BusinessEventPublisher;
import com.sage.business.code.bootstrap.ServiceJsonMapper;
import com.sage.business.code.domain.*;
import com.sage.business.code.infrastructure.*;
import com.sage.business.code.infrastructure.event.SqliteEventJournal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import static org.junit.Assert.*;

/** Real PAC HTTPS, SessionService, material injection, GUI/task/run journal.
 * Only the final BusinessCodeExecutor is controlled: no model is invoked. */
public class PersonalHandoffFailedTurnRetryHttpsTest {
    PersonalHandoffReauthorizationHttpsTest h;PersonalAcsHandoffMaterialContractTest t;PersonalWorkHandoffTest f;
    TaskApplicationService tasks;GuiTaskOrchestrator orchestrator;AtomicInteger calls=new AtomicInteger();
    List<ApprovedBusinessTask> executed=Collections.synchronizedList(new ArrayList<ApprovedBusinessTask>());
    @Before public void setup()throws Exception {
        h=new PersonalHandoffReauthorizationHttpsTest();h.setup();t=h.t;f=h.f;
        WorkspaceRegistry registry=new WorkspaceRegistry(Collections.singletonMap("control",f.root.resolve("receiver-workspace").toString()));
        AtomicJsonStore journal=new AtomicJsonStore(f.root.resolve("receiver-task-state"),f.json);
        BusinessCodeExecutor executor=new BusinessCodeExecutor(){public BusinessExecutionResult execute(ApprovedBusinessTask task,BusinessRunContext context){
            executed.add(task);if(calls.incrementAndGet()==1){
                try{Files.writeString(Paths.get(task.getWorkspacePath()).resolve("partial-result.txt"),"existing partial result");}catch(Exception failure){throw new RuntimeException(failure);}
                throw new BusinessException("MODEL_TRANSPORT_FAILED",502,"controlled TLS handshake failure");
            }
            assertTrue("retry must retain original partial workspace effects",Files.exists(Paths.get(task.getWorkspacePath()).resolve("partial-result.txt")));
            return new BusinessExecutionResult(RunStatus.SUCCEEDED,"controlled-"+context.getRunId(),"已完整读取交接材料，并核对原任务、中文结尾和目标项目。接续工作已完成真实受控执行，保留原失败记录以及已有工作区成果，不重复过去的审批或修改。".repeat(3),Collections.emptyList(),Collections.emptyList(),Collections.singletonMap("result","PASSED"),null,null,null);
        }};
        tasks=new TaskApplicationService(journal,new TaskCanonicalizer(registry,f.json),registry,executor,f.json,f.root.resolve("receiver-locks"));
        orchestrator=new GuiTaskOrchestrator(t.gui,tasks,registry);tasks.addRunStateListener(orchestrator);t.runQueries=new RunQueryService(tasks,journal);
        replaceSessionService(registry);
    }
    void replaceSessionService(WorkspaceRegistry registry){
        t.sessions=new SessionService(t.repository,registry,f.json,new BusinessEventPublisher(new SqliteEventJournal(t.db,f.json)),new SessionService.MessageGateway(){
            public Map<String,Object> sendMessage(String sid,String client,String content,String key,List<SessionAttachment> attachments){return orchestrator.sendMessage(sid,client,content,key,attachments);}
            public String activeRunId(String sid){return orchestrator.activeRun(sid);}
            public Map<String,Object> retryFailedTurn(String sid,String original,String run,String client,String key){return orchestrator.retryFailedTurn(sid,original,run,client,key);}
            public Map<String,Object> failedTurnRetryView(String sid,String client){return orchestrator.failedTurnRetryView(sid,client);}
        });t.sessions.messageMaterials(t.materials);
    }
    @After public void cleanup()throws Exception {if(tasks!=null)tasks.close();if(h!=null)h.close();}
    String failedFlow()throws Exception {
        String id=h.prepare(true);h.cont(id,"PREPARING",200);JsonNode candidate=h.cont(id,"PREPARING",200);
        String key="handoff:reauth-flow:CONFIRM";h.call("POST","/"+id+"/confirm",f.json.createObjectNode().put("requestKey",key).put("expectedMaterialRevision",1).put("expectedMaterialDigest",candidate.path("candidateMaterialDigest").asText()),key,200);
        h.cont(id,"SOURCE_SEALING",200);h.cont(id,"FINALIZING_MATERIALS",200);h.cont(id,"TARGET_STAGING",200);h.cont(id,"TARGET_STARTING",200);
        String first=t.gui.listMessages(t.sid).get(0).getRunId();await(first);
        assertEquals(RunStatus.FAILED,tasks.run(first).status);assertEquals("FAILED",t.gui.listMessages(t.sid).get(1).getStatus());
        return id;
    }
    void await(String run)throws Exception {for(int i=0;i<400;i++){RunRecord r=tasks.run(run);if(r.status==RunStatus.FAILED||r.status==RunStatus.SUCCEEDED){Thread.sleep(80);return;}Thread.sleep(10);}fail("controlled execution did not settle");}
    ObjectNode retryBody(String id,String key)throws Exception {
        JsonNode view=h.call("GET","/"+id,null,null,200),execution=view.path("targetExecution");assertTrue(execution.toString(),execution.path("canRetry").asBoolean());
        return f.json.createObjectNode().put("requestKey",key).put("expectedFailedRunId",execution.path("expectedFailedRunId").asText())
                .put("expectedSessionVersion",execution.path("expectedSessionVersion").asLong()).put("expectedMaterialRevision",view.path("materialRevision").asLong())
                .put("expectedMaterialDigest",view.path("materialDigest").asText()).put("expectedSealId",view.path("sealId").asText());
    }
    @Test public void explicitRetryPreservesFrozenTaskAndOriginalFailureThenProvesSameHandoff()throws Exception {
        String id=failedFlow(),sid=t.sid;PersonalWorkHandoffService.Handoff before=h.row(id);String sealed=before.sourceSealRef,material=before.materialDigest;
        String originalMessages=f.json.writeValueAsString(t.gui.listMessages(sid));
        ObjectNode request=retryBody(id,"explicit-retry-one");capture("get-failed",h.call("GET","/"+id,null,null,200));String failed=request.path("expectedFailedRunId").asText();
        String failedRecord=f.json.writeValueAsString(tasks.run(failed));String taskRecord=f.json.writeValueAsString(tasks.revision(tasks.run(failed).taskId,tasks.run(failed).taskRevision));
        JsonNode ack=h.call("POST","/"+id+"/retry-target",request,"explicit-retry-one",200);
        capture("post-retry-body",request);capture("post-retry-ack",ack);
        assertEquals("TARGET_STARTING",ack.path("phase").asText());assertEquals(1,ack.path("targetRetries").size());
        JsonNode receipt=h.call("GET","/"+id+"/target-retries/explicit-retry-one",null,null,200);
        assertEquals("ACCEPTED",receipt.path("state").asText());String next=receipt.path("source").path("runId").asText();await(next);
        capture("get-retry",h.call("GET","/"+id+"/target-retries/explicit-retry-one",null,null,200));
        RunRecord successor=tasks.run(next);assertEquals(failed,successor.parentRunId);assertEquals(failed,successor.continuationOf);assertEquals(sid,successor.sessionId);
        assertEquals(RunStatus.FAILED,tasks.run(failed).status);assertEquals(RunStatus.SUCCEEDED,successor.status);
        assertEquals(2,executed.size());assertEquals(executed.get(0).getFingerprint(),executed.get(1).getFingerprint());
        byte[] first=f.json.writeValueAsBytes(executed.get(0).getInputAttachments()),second=f.json.writeValueAsBytes(executed.get(1).getInputAttachments());
        assertArrayEquals(first,second);assertEquals(PersonalAccessService.hashBytes(first),PersonalAccessService.hashBytes(second));
        assertTrue(executed.get(1).getInputAttachments().get(0).getTextContent().contains(new String(f.exportBytes,StandardCharsets.UTF_8)));
        assertTrue(originalMessages.contains("MODEL_TRANSPORT_FAILED"));assertEquals("FAILED",t.gui.listMessages(sid).get(0).getStatus());
        assertEquals(failedRecord,f.json.writeValueAsString(tasks.run(failed)));assertEquals(taskRecord,f.json.writeValueAsString(tasks.revision(tasks.run(failed).taskId,tasks.run(failed).taskRevision)));
        assertEquals(f.json.readTree(originalMessages).get(0),f.json.valueToTree(t.gui.listMessages(sid).get(0)));assertEquals(f.json.readTree(originalMessages).get(1),f.json.valueToTree(t.gui.listMessages(sid).get(1)));
        // Simulate loss of the successful ACK: replace the service object, then
        // query/replay the same persisted request; neither starts a third run.
        replaceSessionService(new WorkspaceRegistry(Collections.singletonMap("control",f.root.resolve("receiver-workspace").toString())));
        h.call("GET","/"+id+"/target-retries/explicit-retry-one",null,null,200);h.call("POST","/"+id+"/retry-target",request,"explicit-retry-one",200);
        assertEquals(2,calls.get());JsonNode complete=h.cont(id,"TARGET_STARTING",200);assertEquals("TAKEN_OVER",complete.path("phase").asText());
        capture("get-taken-over",complete);
        assertEquals(sid,h.row(id).targetTaskRef);assertEquals(sealed,h.row(id).sourceSealRef);assertEquals(material,h.row(id).materialDigest);assertEquals(1,t.creates);
        assertEquals(1,t.messagePosts);assertEquals(2,calls.get());
    }
    @Test public void changedFrozenBindingAndRevokedTargetNeverDispatchRetry()throws Exception {
        String id=failedFlow();ObjectNode request=retryBody(id,"denied-retry");
        ObjectNode wrong=request.deepCopy().put("expectedMaterialDigest","0".repeat(64));
        assertEquals("TARGET_RETRY_BINDING_MISMATCH",h.call("POST","/"+id+"/retry-target",wrong,"denied-retry",409).path("code").asText());
        PersonalAccessService.Device d=f.auth.authenticate(f.token);List<PersonalResourceScope.Resource> resources=new ArrayList<PersonalResourceScope.Resource>();
        for(PersonalResourceScope.Resource r:d.resources)if(!t.sid.equals(r.sessionId))resources.add(r);
        h.change(resources,d.launchScopes,d.actions);
        h.call("POST","/"+id+"/retry-target",request,"denied-retry",403);assertEquals(1,calls.get());assertTrue(h.row(id).targetRetries.isEmpty());
    }
    @Test public void unknownRetryOnlyQueriesOriginalKeyAndNeverDispatchesAgain()throws Exception {
        String id=failedFlow();ObjectNode request=retryBody(id,"unknown-retry");t.dropRetryBefore=true;
        h.call("POST","/"+id+"/retry-target",request,"unknown-retry",200);assertEquals(1,t.retryPosts);
        replaceSessionService(new WorkspaceRegistry(Collections.singletonMap("control",f.root.resolve("receiver-workspace").toString())));
        for(int i=0;i<3;i++)assertEquals("UNKNOWN",h.call("GET","/"+id+"/target-retries/unknown-retry",null,null,200).path("state").asText());
        h.call("POST","/"+id+"/retry-target",request,"unknown-retry",200);assertEquals(1,t.retryPosts);assertEquals(1,calls.get());
        ObjectNode another=request.deepCopy().put("requestKey","different-key");
        h.call("POST","/"+id+"/retry-target",another,"different-key",409);assertEquals(1,calls.get());
        assertEquals("UNKNOWN",h.reopenedRetryLookup(id,"unknown-retry").path("state").asText());assertEquals(1,t.retryPosts);assertEquals(1,calls.get());
    }
    @Test public void lostAcceptedRetryAckRecoversWithoutSecondExecution()throws Exception {
        String id=failedFlow();ObjectNode request=retryBody(id,"lost-retry");t.dropRetryAfter=true;
        h.call("POST","/"+id+"/retry-target",request,"lost-retry",200);
        JsonNode receipt=h.call("GET","/"+id+"/target-retries/lost-retry",null,null,200);assertEquals("ACCEPTED",receipt.path("state").asText());
        await(receipt.path("source").path("runId").asText());
        h.call("POST","/"+id+"/retry-target",request,"lost-retry",200);assertEquals(1,t.retryPosts);assertEquals(2,calls.get());
        assertEquals("ACCEPTED",h.reopenedRetryLookup(id,"lost-retry").path("state").asText());assertEquals(1,t.retryPosts);assertEquals(2,calls.get());
    }
    @Test public void sourceAndHandoffRightsAreFreshAfterEligibilityRead()throws Exception {
        String id=failedFlow();ObjectNode request=retryBody(id,"rights-retry");PersonalAccessService.Device d=f.auth.authenticate(f.token);
        Set<String> actions=new TreeSet<String>(d.actions);actions.remove("HANDOFF");h.change(d.resources,d.launchScopes,actions);
        h.call("POST","/"+id+"/retry-target",request,"rights-retry",403);
        List<PersonalResourceScope.Resource> targetOnly=new ArrayList<PersonalResourceScope.Resource>();for(PersonalResourceScope.Resource r:d.resources)if(t.sid.equals(r.sessionId))targetOnly.add(r);
        h.change(targetOnly,d.launchScopes,d.actions);h.call("POST","/"+id+"/retry-target",request,"rights-retry",403);
        assertEquals(1,calls.get());assertEquals(0,t.retryPosts);assertTrue(h.row(id).targetRetries.isEmpty());
    }
    @Test public void anotherActorCannotRetryTheOriginalFlow()throws Exception {
        String id=failedFlow();ObjectNode body=retryBody(id,"actor-retry");PersonalAccessService.Device d=f.auth.authenticate(f.token);
        String pair=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();ObjectNode invite=f.auth.invitationResources("retry-other",d.resources,d.launchScopes,d.actions,"test-cert");
        f.auth.request(f.json.createObjectNode().put("inviteId","retry-other").put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",pair).put("installationId","retry-other-install").put("label","other").put("pollingSecretHash",PersonalAccessService.hash(poll)));
        f.auth.decide(pair,true);String token=f.auth.redeem(pair,poll,"retry-other-redeem").path("accessToken").asText();
        h.callAs(token,"POST","/"+id+"/retry-target",body,"actor-retry",404);assertEquals(1,calls.get());assertEquals(0,t.retryPosts);
    }
    @Test public void genericRetryCannotBorrowFailedRunFromAnotherSession()throws Exception {
        String id=failedFlow();JsonNode execution=h.call("GET","/"+id,null,null,200).path("targetExecution");
        ObjectNode create=f.json.createObjectNode().put("schemaVersion","business-session/2.0").put("title","another session").put("workspaceAlias","control").put("intent","IMPLEMENT").put("interactionMode","ACT").put("executionMode","MODIFY").put("autonomyLevel","LOW").put("verificationLevel","VERIFY");
        String other=t.sessions.create(f.json.treeToValue(create,com.sage.business.code.contract.v2.session.SessionCreateRequest.class),"other-session").getSessionId();
        ObjectNode body=f.json.createObjectNode().put("originalClientMessageId",execution.path("originalClientMessageId").asText()).put("expectedFailedRunId",execution.path("runId").asText()).put("expectedSessionVersion",1).put("retryKey","cross-session");
        PersonalWorkHandoffService.Reply reply=t.http("POST","/api/v2/sessions/"+other+"/failed-turn-retries",f.json.writeValueAsBytes(body),"cross-session");
        assertEquals(409,reply.status);assertEquals("FAILED_TURN_BINDING_MISMATCH",reply.body.path("code").asText());
        PersonalWorkHandoffService.Reply lookup=t.http("GET","/api/v2/sessions/"+other+"/failed-turn-retries/cross-session",null,null);
        assertEquals("REJECTED",lookup.body.path("state").asText());assertEquals(1,calls.get());
    }
    @Test public void simultaneousSameKeyAndStaleOtherKeyNeverDuplicateExecution()throws Exception {
        String id=failedFlow();ObjectNode body=retryBody(id,"concurrent-retry");
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<JsonNode> call=()->{start.await();return h.call("POST","/"+id+"/retry-target",body,"concurrent-retry",200);};
            java.util.concurrent.Future<JsonNode> first=pool.submit(call),second=pool.submit(call);start.countDown();
            first.get(30,java.util.concurrent.TimeUnit.SECONDS);second.get(30,java.util.concurrent.TimeUnit.SECONDS);
            JsonNode receipt=h.call("GET","/"+id+"/target-retries/concurrent-retry",null,null,200);
            await(receipt.path("source").path("runId").asText());
            h.call("POST","/"+id+"/retry-target",body.deepCopy().put("requestKey","stale-other-key"),"stale-other-key",409);
            assertEquals(1,t.retryPosts);assertEquals(2,calls.get());assertEquals(1,h.row(id).targetRetries.size());
        } finally {pool.shutdownNow();}
    }
    void capture(String name,JsonNode body)throws Exception {String directory=System.getProperty("handoff.retry.capture");if(directory!=null){Path p=Paths.get(directory);Files.createDirectories(p);Files.writeString(p.resolve(name+".json"),f.json.writerWithDefaultPrettyPrinter().writeValueAsString(body),StandardCharsets.UTF_8);}}
}
