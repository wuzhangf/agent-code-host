package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.sage.business.code.application.TaskApplicationService;
import com.sage.business.code.domain.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import org.junit.*;
public class WritableTerminalSettlementProtocolTest {
    private static final class Done extends RuntimeException{}
    @BeforeClass public static void isolate(){FoundationProtocolAcceptanceTest.isolateRuntimeOptionalStorage();}
    @AfterClass public static void restore(){FoundationProtocolAcceptanceTest.restoreRuntimeOptionalStorage();}
    private static void end(WritableCollaborationProtocolTest f,String rid)throws Exception {Method m=TaskApplicationService.class.getDeclaredMethod("transition",RunRecord.class,RunStatus.class);m.setAccessible(true);synchronized(f.fixtureServices().tasks){m.invoke(f.fixtureServices().tasks,f.fixtureServices().tasks.run(rid),RunStatus.BUDGET_EXHAUSTED);}}
    private static void settled(WritableCollaborationProtocolTest f,String rid,String group)throws Exception {
        f.await(()->"CANCELLED".equals(f.api("GET",group,null,f.b,null,200).path("state").asText()),"Terminal idle group did not cancel");JsonNode view=f.api("GET",group,null,f.b,null,200);for(JsonNode d:view.path("sharedBudget"))Assert.assertEquals(view.toString(),0,d.path("reserved").asLong());
    }
    @Test public void terminalAfterAuthorizationBeforeStartReleasesEveryUnspentPool()throws Exception {
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){@Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
            JsonNode result=super.api(method,path,key,client,body,expected);if("POST".equals(method)&&path.endsWith("/authorizations")){String rid=path.split("/")[4],group=path.substring(0,path.length()-"/authorizations".length());end(this,rid);settled(this,rid,group);Assert.assertEquals(1,modelCalls.get());throw new Done();}return result;
        }};run(f);
    }
    @Test public void terminalWithReadyCandidateRetainsItsBytesButReleasesUnusedReservations()throws Exception {
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){@Override protected void beforeWritableAdoption(String rid,String sid)throws Exception {String group="/api/v2/runs/"+rid+"/collaboration-groups/group-one";JsonNode before=api("GET",group,null,b,null,200);end(this,rid);settled(this,rid,group);Assert.assertEquals(before.path("candidates"),api("GET",group,null,b,null,200).path("candidates"));JsonNode stopped=api("GET",group,null,b,null,200),candidate=stopped.path("candidates").get(0);com.fasterxml.jackson.databind.node.ObjectNode late=mapper.createObjectNode().put("selectionId","late-selection").put("expectedGraphRevision",stopped.path("revision").asText());late.putArray("units").addObject().put("unitId",candidate.path("unitId").asText()).put("candidateId",candidate.path("candidateId").asText()).put("candidateFingerprint",candidate.path("candidateFingerprint").asText());Assert.assertEquals("CHILD_PARENT_STOPPED",api("POST",group+"/selections","late-selection",a,late,409).path("code").asText());Assert.assertEquals(before.path("selections"),api("GET",group,null,b,null,200).path("selections"));Assert.assertEquals("v1",Files.readString(Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot).resolve("x.txt")));throw new Done();}};run(f);
    }
    @Test public void terminalBeforeAdoptionDispatchCancelsOriginalReceiptWithoutApplyingOrChargingIntegration()throws Exception {
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){@Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
            JsonNode result=super.api(method,path,key,client,body,expected);if("POST".equals(method)&&path.endsWith("/adoptions")){String rid=path.split("/")[4],group=path.substring(0,path.length()-"/adoptions".length());end(this,rid);settled(this,rid,group);JsonNode adoption=api("GET",path+"/"+key,null,b,null,200);Assert.assertEquals(adoption.toString(),"CANCELLED",adoption.path("state").asText());Assert.assertEquals("NOT_APPLIED",adoption.path("effectState").asText());Assert.assertFalse(adoption.path("contextCommitted").asBoolean());Assert.assertEquals("v1",Files.readString(Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot).resolve("x.txt")));Assert.assertEquals(1,api("GET",group,null,b,null,200).path("sharedBudget").path("EDIT_ATTEMPT").path("spent").asLong());throw new Done();}return result;
        }};run(f);
    }

    @Test public void groupCancelBeforeDispatchCannotApplyOrReviveOriginalAdoption()throws Exception {
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){@Override protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception {
            JsonNode result=super.api(method,path,key,client,body,expected);if("POST".equals(method)&&path.endsWith("/adoptions")){
                String rid=path.split("/")[4],group=path.substring(0,path.length()-"/adoptions".length());com.fasterxml.jackson.databind.node.ObjectNode cancel=mapper.createObjectNode().put("requestId","cancel-pending-group");api("POST",group+"/cancel","cancel-pending-group",a,cancel,202);settled(this,rid,group);JsonNode beforeReplay=api("GET",group,null,b,null,200);api("POST",group+"/cancel","cancel-pending-group",a,cancel,202);Assert.assertEquals(beforeReplay.path("revision"),api("GET",group,null,b,null,200).path("revision"));
                JsonNode adoption=api("GET",path+"/"+key,null,b,null,200);Assert.assertEquals(adoption.toString(),"CANCELLED",adoption.path("state").asText());com.fasterxml.jackson.databind.node.ObjectNode recover=mapper.createObjectNode().put("requestId","inspect-cancelled");Assert.assertEquals("CANCELLED",api("POST",path+"/"+key+"/recover","inspect-cancelled",a,recover,202).path("state").asText());
                releaseModel.release();await(()->parentCallCount()>=3,"Original parent did not pass the cancelled boundary");Assert.assertEquals("v1",Files.readString(Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot).resolve("x.txt")));Assert.assertEquals(1,api("GET",group,null,b,null,200).path("sharedBudget").path("EDIT_ATTEMPT").path("spent").asLong());end(this,rid);throw new Done();
            }return result;
        }};run(f);
    }
    @Test public void runningGraphIsNeverPublishedWithoutItsDurableCoordinatorClaim()throws Exception {
        WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){
            @Override protected void afterWritableDispatch(String rid,String sid,String group)throws Exception {
                java.lang.reflect.Field resources=TaskApplicationService.class.getDeclaredField("collaborationResources");resources.setAccessible(true);Object writable=((java.util.List<?>)resources.get(fixtureServices().tasks)).stream().filter(x->x instanceof com.sage.business.code.application.collaboration.WritableCollaborationService).findFirst().orElseThrow();java.lang.reflect.Field field=writable.getClass().getDeclaredField("assignments");field.setAccessible(true);Object assignments=field.get(writable);java.lang.reflect.Field databaseField=assignments.getClass().getDeclaredField("database");databaseField.setAccessible(true);com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase database=(com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase)databaseField.get(assignments);
                database.inTransaction(c->{try(java.sql.Statement q=c.createStatement()){q.execute("CREATE TEMP TRIGGER require_graph_claim BEFORE UPDATE OF status ON writable_collaboration_records WHEN NEW.kind='GROUP' AND NEW.status='RUNNING' AND OLD.status='WAITING_FREEZE' BEGIN SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM writable_runtime_activities a WHERE a.run_id=NEW.run_id AND a.scope_id='GRAPH:'||NEW.entity_id AND a.kind='EXECUTION' AND a.state='ACTIVE') THEN RAISE(ABORT,'GRAPH_DISPATCH_NOT_CLAIMED') END; END");}return null;});
            }
        };try{f.start();f.exercise(false,false);}finally{f.releaseModel.release(100);f.close();}
    }
    private static void run(WritableCollaborationProtocolTest f)throws Exception {try{f.start();try{f.exercise(false,false);Assert.fail("Terminal hook not reached");}catch(Done expected){}}finally{f.releaseModel.release(100);f.close();}}
}
