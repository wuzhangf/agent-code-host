package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.*;
/** A group cancellation must fence recovery after an actual partial native file application. */
public class WritablePartialCancellationProtocolTest {
 @BeforeClass public static void isolate(){FoundationProtocolAcceptanceTest.isolateRuntimeOptionalStorage();}
 @AfterClass public static void restore(){FoundationProtocolAcceptanceTest.restoreRuntimeOptionalStorage();}
 @Test public void cancelledPartialAdoptionRecoveryOnlyObservesOriginalEffects()throws Exception {
  Assume.assumeTrue("Native NOSHARE_DELETE partial-effect probe requires Windows",System.getProperty("os.name").startsWith("Windows"));
  WritableCollaborationProtocolTest f=new WritableCollaborationProtocolTest(){
   private java.nio.channels.FileChannel blocked;
   @Override protected boolean dagFixture(){return true;}
   @Override protected boolean interruptedAdoptionFixture(){return true;}
   @Override protected void beforeWritableAdoption(String rid,String sid)throws Exception {Path managed=Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot);blocked=java.nio.channels.FileChannel.open(managed.resolve("y.txt"),java.util.Set.of(StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE));}
   @Override protected void exerciseInterruptedAdoption(String rid,String sid,String group)throws Exception {
    try{
     await(()->"NEEDS_ATTENTION".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Actual partial application did not retain Main");Path managed=Paths.get(fixtureServices().tasks.observationRun(rid).productWorkspaceRoot);Assert.assertEquals("v2",Files.readString(managed.resolve("x.txt")));Assert.assertEquals("v1",Files.readString(managed.resolve("y.txt")));blocked.close();blocked=null;
     JsonNode before=api("GET",group+"/adoptions/adopt-one",null,b,null,200);Assert.assertFalse(before.path("contextCommitted").asBoolean());Assert.assertNotEquals("NOT_APPLIED",before.path("effectState").asText());
     ObjectNode cancel=mapper.createObjectNode().put("requestId","cancel-partial");api("POST",group+"/cancel","cancel-partial",a,cancel,202);int calls=modelCalls.get();ObjectNode recover=mapper.createObjectNode().put("requestId","observe-partial");JsonNode observed=api("POST",group+"/adoptions/adopt-one/recover","observe-partial",a,recover,202);Assert.assertEquals("v2",Files.readString(managed.resolve("x.txt")));Assert.assertEquals("v1",Files.readString(managed.resolve("y.txt")));Assert.assertFalse(observed.path("contextCommitted").asBoolean());Assert.assertEquals(calls,modelCalls.get());JsonNode view=api("GET",group,null,b,null,200);Assert.assertTrue(view.path("cancelRequested").asBoolean());Assert.assertEquals("RECONCILIATION_REQUIRED",view.path("state").asText());Assert.assertNotEquals("NOT_APPLIED",observed.path("effectState").asText());
    }finally{if(blocked!=null){blocked.close();blocked=null;}}
   }
  };try{f.start();f.exercise(false,false);}finally{f.releaseModel.release(100);f.close();}
 }
}
