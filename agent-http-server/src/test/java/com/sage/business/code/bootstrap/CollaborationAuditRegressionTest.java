package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.domain.RunRecord;
import com.sage.business.code.domain.RunStatus;
import com.sage.business.code.application.TaskApplicationService;
import com.sage.business.code.application.collaboration.WritableCollaborationService;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;

/** Real HTTP/Product fixtures, with deterministic terminal and bounded-queue fault injection. */
public class CollaborationAuditRegressionTest {
    private static final RunStatus[] ENDED={RunStatus.MAX_STEPS_REACHED,RunStatus.BUDGET_EXHAUSTED,RunStatus.BUDGET_APPROVAL_DENIED};
    @BeforeClass public static void isolate(){FoundationProtocolAcceptanceTest.isolateRuntimeOptionalStorage();}
    @AfterClass public static void restore(){FoundationProtocolAcceptanceTest.restoreRuntimeOptionalStorage();}
    private static final class Done extends RuntimeException {}
    private static void end(FoundationProtocolAcceptanceTest fixture,String rid,RunStatus status)throws Exception {
        Method transition=TaskApplicationService.class.getDeclaredMethod("transition",RunRecord.class,RunStatus.class);transition.setAccessible(true);
        synchronized(fixture.fixtureServices().tasks){transition.invoke(fixture.fixtureServices().tasks,fixture.fixtureServices().tasks.run(rid),status);}
    }
    private static WritableCollaborationService writable(FoundationProtocolAcceptanceTest fixture)throws Exception {
        Field resources=TaskApplicationService.class.getDeclaredField("collaborationResources");resources.setAccessible(true);
        for(Object service:(List<?>)resources.get(fixture.fixtureServices().tasks))if(service instanceof WritableCollaborationService)return (WritableCollaborationService)service;
        throw new AssertionError("Missing actual writable coordinator");
    }
    @Test public void allBudgetAndStepTerminalOutcomesCancelPendingInputsAndRejectNewEffects()throws Exception {
        for(RunStatus status:ENDED){FoundationProtocolAcceptanceTest f=new FoundationProtocolAcceptanceTest(){@Override protected void configureCollaboration(ServiceConfig c){c.collaborationBoundaryEnabled=true;}};
            try{f.start();String sid=f.api("POST","/api/v2/sessions","audit-session",f.a,f.mapper.createObjectNode().put("workspaceAlias","sample"),201).path("sessionId").asText();
                String rid=f.api("POST","/api/v2/sessions/"+sid+"/messages","audit-start",f.a,f.mapper.createObjectNode().put("clientMessageId","audit-start").put("expectedSessionVersion",1).put("content","Inspect README"),202).path("runId").asText();
                f.await(()->f.modelCalls.get()==1,"Actual main request did not start");String endpoint="/api/v2/runs/"+rid+"/boundary-requests";
                ObjectNode input=f.mapper.createObjectNode().put("requestId","before-end").put("kind","CONTEXT_ADVICE").put("expectedExecutionEpoch","1").put("text","Use the frozen evidence");f.api("POST",endpoint,"before-end",f.a,input,202);
                end(f,rid,status);Assert.assertEquals(status.name(),"CANCELLED",f.api("GET",endpoint+"/before-end",null,f.b,null,200).path("state").asText());
                input.put("requestId","after-end");Assert.assertEquals(status.name(),"RUN_STOPPED",f.api("POST",endpoint,"after-end",f.b,input,409).path("code").asText());Assert.assertEquals(1,f.modelCalls.get());
            }finally{f.releaseModel.release(100);f.close();}
        }
    }
    @Test public void terminalOutcomesSupersedeHandoffAndCancelActiveAuxiliaryWithoutPublishingLateAnswer()throws Exception {
        for(RunStatus status:ENDED){BoundaryAssignmentProtocolTest f=new BoundaryAssignmentProtocolTest(){@Override protected void configureCollaboration(ServiceConfig c){super.configureCollaboration(c);c.collaborationAuxiliaryEnabled=true;}};
            try{f.start();String sid=f.api("POST","/api/v2/sessions","audit-source",f.a,f.mapper.createObjectNode().put("workspaceAlias","sample"),201).path("sessionId").asText();
                String rid=f.api("POST","/api/v2/sessions/"+sid+"/messages","audit-main",f.a,f.mapper.createObjectNode().put("clientMessageId","audit-main").put("expectedSessionVersion",1).put("content","Inspect README"),202).path("runId").asText();f.await(()->f.modelCalls.get()==1,"No original Product request");
                String revision=null;for(JsonNode profile:f.api("GET","/api/v2/agent-profiles",null,f.a,null,200).path("profiles"))if("reviewer".equals(profile.path("id").asText()))revision=profile.path("revision").asText();Assert.assertNotNull(revision);
                ObjectNode handoff=f.mapper.createObjectNode().put("transitionId","audit-handoff");handoff.putObject("target").put("agentProfileId","reviewer").put("agentProfileRevision",revision);String transitions="/api/v2/runs/"+rid+"/assignment-transitions";
                JsonNode preflight=f.api("POST",transitions+"/preflight",null,f.a,handoff,200);handoff.put("preflightDigest",preflight.path("preflightDigest").asText()).put("expectedExecutionEpoch","1").put("expectedRunStateVersion",preflight.path("runStateVersion").asText()).put("expectedModelSettingsRevision",preflight.path("modelSettingsRevision").asText());f.api("POST",transitions,"audit-handoff",f.a,handoff,202);
                String snapshot=f.api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","audit-snapshot",f.a,f.mapper.createObjectNode(),201).path("snapshotId").asText();f.api("POST","/api/v2/sessions/"+sid+"/context-packages","audit-package",f.a,f.mapper.createObjectNode().put("requestId","audit-package").put("snapshotId",snapshot),201);
                String auxiliary=f.api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","audit-side",f.a,f.mapper.createObjectNode().put("requestId","audit-side").put("basePackageId","audit-package"),201).path("sessionId").asText();String turns="/api/v2/sessions/"+auxiliary+"/auxiliary-turns";
                f.api("POST",turns,"audit-question",f.a,f.mapper.createObjectNode().put("turnId","audit-question").put("expectedSessionVersion","1").put("packageId","audit-package").put("question","Explain the fixed snapshot"),202);f.await(()->f.modelCalls.get()==2,"No actual auxiliary request");
                end(f,rid,status);f.await(()->"SUPERSEDED".equals(f.api("GET",transitions+"/audit-handoff",null,f.b,null,200).path("state").asText()),"Terminal outcome did not supersede handoff: "+status);
                f.releaseModel.release(10);f.await(()->"CANCELLED".equals(f.api("GET",turns+"/audit-question",null,f.b,null,200).path("state").asText()),"Terminal outcome did not stop auxiliary: "+status);
                Assert.assertTrue(f.api("GET",turns+"/audit-question",null,f.b,null,200).path("answer").isMissingNode());Assert.assertEquals(2,f.modelCalls.get());
                Assert.assertEquals("1",f.api("GET","/api/v2/runs/"+rid+"/execution-segments",null,f.b,null,200).path("current").path("executionEpoch").asText());
            }finally{f.releaseModel.release(100);f.close();}
        }
    }
    @Test public void terminalBeforeFreezeCancelsGroupAndReleasesAllUnspentReservations()throws Exception {
        for(RunStatus status:ENDED){WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){
            @Override protected void afterWritableDispatch(String rid,String sid,String group)throws Exception {
                end(this,rid,status);JsonNode ended=api("GET",group,null,b,null,200);Assert.assertEquals(status.name(),"CANCELLED",ended.path("state").asText());
                for(JsonNode dimension:ended.path("sharedBudget"))Assert.assertEquals(status.name()+" leaked reservation",0,dimension.path("reserved").asLong());
                Assert.assertEquals(0,ended.path("candidates").size());Assert.assertEquals(1,modelCalls.get());throw new Done();
            }};try{f.start();try{f.exercise(false,false);Assert.fail("Terminal injection hook was not reached");}catch(Done expected){}}finally{f.releaseModel.release(100);f.close();}
        }
    }
    @Test public void queuedGraphRemainsAProducerUntilItHasActuallySettledAfterStop()throws Exception {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){
            @Override protected void afterWritableDispatch(String rid,String sid,String group)throws Exception {
                Field field=WritableCollaborationService.class.getDeclaredField("coordinators");field.setAccessible(true);ExecutorService pool=(ExecutorService)field.get(writable(this));
                pool.submit(()->{entered.countDown();try{release.await(20,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});Assert.assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                releaseModel.release();await(()->parentCallCount()==2&&"RUNNING".equals(api("GET",group,null,b,null,200).path("state").asText()),"Graph was not queued behind the owned coordinator gate");
                command(rid,"audit-queued-stop","STOP",-1,a);releaseModel.release();await(()->"CANCELLED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("state").asText()),"Parent STOP did not settle");
                Assert.assertEquals("CANCEL_REQUESTED",api("GET",group,null,b,null,200).path("state").asText());JsonNode activity=api("GET","/api/v2/runs/"+rid+"/collaboration-activity",null,b,null,200);Assert.assertFalse("Queued original coordinator must prevent premature stop proof: "+activity,activity.path("producerQuiescent").asBoolean());Assert.assertTrue(activity.path("activeWriters").asInt()>0);
                release.countDown();await(()->api("GET","/api/v2/runs/"+rid+"/collaboration-activity",null,b,null,200).path("producerQuiescent").asBoolean(),"Original queue claim was not released after actual settlement");Assert.assertEquals(2,modelCalls.get());Assert.assertEquals(0,api("GET",group,null,b,null,200).path("candidates").size());throw new Done();
            }};
        try{f.start();try{f.exercise(false,false);Assert.fail("Queued graph hook was not reached");}catch(Done expected){}}finally{release.countDown();f.releaseModel.release(100);f.close();}
    }
    private static final class ReworkFixture extends WritableCollaborationProtocolTest {
        private final java.util.concurrent.atomic.AtomicInteger childRequests=new java.util.concurrent.atomic.AtomicInteger();
        @Override protected void observeModelRequest(JsonNode request){super.observeModelRequest(request);if(request.toString().contains("CHILD_WRITE_X")&&!request.path("tools").toString().contains("collaboration_control"))childRequests.incrementAndGet();}
        final boolean reject;ReworkFixture(boolean reject){this.reject=reject;}
        @Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
            if("POST".equals(method)&&path.endsWith("/authorizations")){((ObjectNode)body).put("retryLimit",1);for(JsonNode unit:body.path("units"))((ObjectNode)unit.path("total")).put("logicalCalls","8").put("httpRequests","8").put("editAttempts","4");}
            return super.api(method,path,key,client,body,expected);
        }
        @Override protected ObjectNode replyForModel(JsonNode request){
            // Each staged invocation has its own tool history, including the second attempt.
            if(request.toString().contains("CHILD_WRITE_X")&&!request.path("tools").toString().contains("collaboration_control")){
                int calls=(childRequests.get()-1)%3;
                if(calls>=2)return mapper.createObjectNode().put("role","assistant").put("content","Scoped staged edit complete");
                ObjectNode args=mapper.createObjectNode().put("path","x.txt");if(calls==1)args.putArray("edits").addObject().put("oldText","v1").put("newText","v2");ObjectNode message=mapper.createObjectNode().put("role","assistant");message.putArray("tool_calls").addObject().put("id",UUID.randomUUID().toString()).put("type","function").putObject("function").put("name",calls==0?"workspace_read":"workspace_edit").put("arguments",args.toString());return message;
            }return super.replyForModel(request);
        }
        @Override protected void beforeWritableAdoption(String rid,String sid)throws Exception {
            String group="/api/v2/runs/"+rid+"/collaboration-groups/group-one";JsonNode initial=api("GET",group,null,b,null,200),original=null;for(JsonNode child:initial.path("children"))if(!child.path("start").asBoolean())original=child;Assert.assertNotNull(original);
            if(reject){Field pool=WritableCollaborationService.class.getDeclaredField("coordinators");pool.setAccessible(true);((ExecutorService)pool.get(writable(this))).shutdown();}
            String endpoint=group+"/executions/"+original.path("entityId").asText()+"/rework";ObjectNode retry=mapper.createObjectNode().put("schemaVersion","collaboration-action/1").put("requestId","audit-rework").put("expectedRevision",original.path("revision").asText());
            JsonNode receipt=api("POST",endpoint,"audit-rework",a,retry,202);
            if(reject){Assert.assertEquals(receipt.toString(),"FAILED",receipt.path("state").asText());Assert.assertEquals("COLLABORATION_QUEUE_FULL",receipt.path("reasonCode").asText());Assert.assertEquals("FAILED",api("GET",group,null,b,null,200).path("state").asText());}
            else await(()->"READY".equals(api("GET",group,null,b,null,200).path("state").asText()),"Rework did not settle");
            int before=modelCalls.get();JsonNode replay=api("POST",endpoint,"audit-rework",b,retry,202);
            retry.put("requestId","different-key-same-attempt");Assert.assertEquals("CHILD_REWORK_NOT_ALLOWED",api("POST",endpoint,"different-key-same-attempt",a,retry,409).path("code").asText());Assert.assertEquals(before,modelCalls.get());Assert.assertEquals(reject?"FAILED":"COMPLETED",replay.path("state").asText());
            Assert.assertEquals("v1",Files.readString(Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot).resolve("x.txt")));
            command(rid,"audit-rework-stop","STOP",-1,a);releaseModel.release(10);await(()->"CANCELLED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("state").asText()),"Original parent did not stop");throw new Done();
        }
    }
    private void rework(boolean reject)throws Exception {ReworkFixture f=new ReworkFixture(reject);try{f.start();try{f.exercise(false,false);Assert.fail("Rework hook was not reached");}catch(Done expected){}}finally{f.releaseModel.release(100);f.close();}}
    @Test public void queueRejectionLeavesObservableFailedIntentAndNoNewModel()throws Exception {rework(true);}
    @Test public void differentKeysCannotClaimTheSameSecondAttempt()throws Exception {rework(false);}
    @Test public void childPublicationWaitingForHistoryNeverHoldsTheDatabaseMonitor()throws Exception {
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"audit-child-lock-order");t.setDaemon(true);return t;});
        try{f.start();WritableCollaborationService service=writable(f);
            Field h=WritableCollaborationService.class.getDeclaredField("history"),a=WritableCollaborationService.class.getDeclaredField("assignments"),r=WritableCollaborationService.class.getDeclaredField("records");h.setAccessible(true);a.setAccessible(true);r.setAccessible(true);
            com.sage.business.code.application.session.SessionRepository history=(com.sage.business.code.application.session.SessionRepository)h.get(service);
            com.sage.business.code.application.collaboration.CollaborationStore store=(com.sage.business.code.application.collaboration.CollaborationStore)a.get(service);
            com.sage.business.code.application.collaboration.WritableCollaborationRepository records=(com.sage.business.code.application.collaboration.WritableCollaborationRepository)r.get(service);
            String parent=f.api("POST","/api/v2/sessions","lock-parent",f.a,f.mapper.createObjectNode().put("workspaceAlias","sample"),201).path("sessionId").asText();
            com.sage.business.code.application.session.SessionRecordV2 child=history.find(parent).copy();child.sessionId="delegated-lock-child";child.parentSessionId=parent;child.sessionPurpose="DELEGATED_WRITE_CANDIDATE";history.inTransaction(c->{history.insert(c,child);return null;});
            Map<String,Object> payload=new LinkedHashMap<>();payload.put("sessionId",child.sessionId);payload.put("quiescent",true);payload.put("summary","A bounded public child result");
            com.sage.business.code.application.collaboration.WritableCollaborationRepository.Record record=records.create("lock-run",com.sage.business.code.application.collaboration.WritableCollaborationRepository.Kind.CHILD,"lock-child","lock-group","RUNNING",Collections.emptyMap(),payload);
            Method publish=WritableCollaborationService.class.getDeclaredMethod("publishChildState",com.sage.business.code.application.collaboration.WritableCollaborationRepository.Record.class,String.class,Map.class);publish.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<Thread> publisher=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.Future<?> result;
            synchronized(history){
                result=pool.submit(()->{publisher.set(Thread.currentThread());try{return publish.invoke(service,record,"CANDIDATE_READY",payload);}catch(Exception failure){throw new RuntimeException(failure);}});
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while((publisher.get()==null||publisher.get().getState()!=Thread.State.BLOCKED)&&System.nanoTime()<deadline)Thread.sleep(5);
                Assert.assertNotNull(publisher.get());Assert.assertEquals(Thread.State.BLOCKED,publisher.get().getState());
                java.util.concurrent.Future<Integer> probe=pool.submit(()->store.database.inTransaction(c->1));
                Assert.assertEquals("Waiting for Session must not retain the database monitor",Integer.valueOf(1),probe.get(2,java.util.concurrent.TimeUnit.SECONDS));
            }
            result.get(5,java.util.concurrent.TimeUnit.SECONDS);Assert.assertEquals(1,history.messages(child.sessionId).size());
        }finally{pool.shutdown();pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS);f.close();}
    }

}
