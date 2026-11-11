package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.domain.RunRecord;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.agent.product.code.CodeExecutionCheckpoint;
import java.nio.file.*;
import org.junit.*;
/** Restore only the stale Host projection after an actual paused adoption, then restart the real services. */
public class WritableProjectionRecoveryProtocolTest extends WritableCollaborationProtocolTest {
    private boolean loseProjection;private String before;
    @Test public void committedNativeCheckpointReconcilesStaleHostProjectionWithoutAnotherModelOrFileApply()throws Exception{loseProjection=true;exercise(true,false);}
    @Override protected void beforeWritableAdoption(String rid,String sid){if(loseProjection)before=fixtureServices().tasks.observationRun(rid).checkpointJson;}
    @Override protected void afterPausedWritableAdopted(String rid,String sid)throws Exception{
        if(!loseProjection)return;RunRecord installed=fixtureServices().tasks.observationRun(rid);String nativeHash=CodeExecutionCheckpoint.fromJson(installed.checkpointJson).getCheckpointHash();Assert.assertNotEquals(nativeHash,CodeExecutionCheckpoint.fromJson(before).getCheckpointHash());String managed=installed.productWorkspaceRoot;int calls=modelCalls.get();String group="/api/v2/runs/"+rid+"/collaboration-groups/group-one";JsonNode budget=api("GET",group,null,b,null,200).path("sharedBudget");Assert.assertTrue(budget.isObject());Assert.assertTrue(budget.size()>0);
        stopService();AtomicJsonStore authority=new AtomicJsonStore(root.resolve("state"),mapper);String path="runs/"+rid+"/state.json";RunRecord stale=authority.read(path,RunRecord.class);stale.checkpointJson=before;authority.write(path,stale);restartService();
        JsonNode observed=api("GET",group+"/adoptions/adopt-one",null,b,null,200);Assert.assertEquals("ADOPTED",observed.path("state").asText());Assert.assertEquals(before,fixtureServices().tasks.observationRun(rid).checkpointJson);
        ObjectNode action=mapper.createObjectNode().put("requestId","reconcile-lost-host-ack").put("expectedRevision",observed.path("revision").asText());JsonNode recovered=api("POST",group+"/adoptions/adopt-one/recover","reconcile-lost-host-ack",a,action,202);Assert.assertEquals(recovered.toString(),"ADOPTED",recovered.path("state").asText());Assert.assertEquals(nativeHash,CodeExecutionCheckpoint.fromJson(fixtureServices().tasks.observationRun(rid).checkpointJson).getCheckpointHash());Assert.assertEquals(nativeHash,recovered.path("continuationCheckpointHash").asText());Assert.assertEquals(calls,modelCalls.get());Assert.assertEquals("v2",Files.readString(Paths.get(managed).resolve("x.txt")));Assert.assertEquals(budget,api("GET",group,null,b,null,200).path("sharedBudget"));
        Assert.assertEquals(recovered,api("POST",group+"/adoptions/adopt-one/recover","reconcile-lost-host-ack",a,action,202));Assert.assertEquals(calls,modelCalls.get());
    }
}
