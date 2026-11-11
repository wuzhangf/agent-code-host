package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.concurrent.*;
import org.junit.*;
/** A held actual review HTTP call must not block cancellation or be reported quiescent before it returns. */
public class WritableReviewCancellationProtocolTest extends WritableCollaborationProtocolTest {
    private boolean cancellation,completed;private final CountDownLatch started=new CountDownLatch(1);private final Semaphore gate=new Semaphore(0);
    private static final class Completed extends RuntimeException{}
    @Override protected boolean deleteFixtureRoot(){return completed||super.deleteFixtureRoot();}
    @After public void releaseReview(){gate.release(20);}
    @Override protected void awaitModelPermit(JsonNode request)throws IOException{if(cancellation&&request.path("tools").toString().contains("emit_research_finding")){started.countDown();try{if(!gate.tryAcquire(25,TimeUnit.SECONDS))throw new IOException("Review fixture gate timed out");}catch(InterruptedException stopped){Thread.currentThread().interrupt();throw new IOException(stopped);}return;}super.awaitModelPermit(request);}
    @Test public void groupCancellationRetainsActualReviewUntilItsTransportStopsThenReleasesUnusedReservations()throws Exception{cancellation=true;try{exercise(false,true);Assert.fail("Cancelled review must not continue into adoption");}catch(Completed expected){Assert.assertTrue(completed);}}
    @Override protected void awaitReview(String group,String reviewId)throws Exception{
        if(!cancellation){super.awaitReview(group,reviewId);return;}Assert.assertTrue("Review never opened its actual HTTP window",started.await(10,TimeUnit.SECONDS));String rid=group.split("/")[4];JsonNode running=api("GET",group+"/reviews/"+reviewId,null,b,null,200);Assert.assertEquals("RUNNING",running.path("state").asText());Assert.assertFalse(running.path("quiescent").asBoolean());JsonNode cancelled=api("POST",group+"/cancel","cancel-review-group",a,mapper.createObjectNode().put("requestId","cancel-review-group"),202);Assert.assertEquals("CANCEL_REQUESTED",cancelled.path("state").asText());JsonNode held=api("GET",group+"/reviews/"+reviewId,null,b,null,200);Assert.assertTrue(held.path("cancelRequested").asBoolean());Assert.assertFalse(held.path("quiescent").asBoolean());Assert.assertEquals("RUNNING",held.path("state").asText());gate.release();await(()->"CANCELLED".equals(api("GET",group+"/reviews/"+reviewId,null,b,null,200).path("state").asText()),"Review did not settle after its actual HTTP call returned");await(()->"CANCELLED".equals(api("GET",group,null,b,null,200).path("state").asText()),"Group reported cancellation before all producers stopped");JsonNode settled=api("GET",group,null,b,null,200);Assert.assertTrue(settled.path("sharedBudget").isObject());for(JsonNode dimension:settled.path("sharedBudget"))Assert.assertEquals("0",dimension.path("reserved").asText());Assert.assertFalse(settled.path("adoptions").elements().hasNext());command(rid,"stop-after-cancelled-review","STOP",-1,a);releaseModel.release(10);await(()->"CANCELLED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("state").asText()),"Parent STOP was blocked by review cancellation");completed=true;throw new Completed();
    }
}
