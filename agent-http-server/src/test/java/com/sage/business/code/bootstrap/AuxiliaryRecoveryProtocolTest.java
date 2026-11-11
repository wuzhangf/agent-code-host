package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import org.junit.*;

/** Restart reconciles a persisted in-flight request without issuing an automatic model retry. */
public class AuxiliaryRecoveryProtocolTest extends FoundationProtocolAcceptanceTest {
    @Override protected void configureCollaboration(ServiceConfig config){config.collaborationAuxiliaryEnabled=true;}
    private String parent,child;
    private ObjectNode question;
    private void setupTurn()throws Exception{
        parent=api("POST","/api/v2/sessions","recover-parent",a,mapper.createObjectNode(),201).path("sessionId").asText();
        String snap=api("POST","/api/v2/sessions/"+parent+"/observation-snapshots","recover-snapshot",a,mapper.createObjectNode(),201).path("snapshotId").asText();
        api("POST","/api/v2/sessions/"+parent+"/context-packages","recover-package",a,mapper.createObjectNode().put("requestId","recover-package").put("snapshotId",snap),201);
        child=api("POST","/api/v2/sessions/"+parent+"/auxiliary-sessions","recover-side",a,mapper.createObjectNode().put("requestId","recover-side").put("basePackageId","recover-package"),201).path("sessionId").asText();
        question=mapper.createObjectNode().put("turnId","first").put("expectedSessionVersion","1").put("packageId","recover-package").put("question","Explain this immutable evidence");
    }
    private Connection db()throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"));}
    @Test public void unknownAttemptRequiresExplicitRetryAndPreservesItsModelBinding()throws Exception{
        setupTurn();api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","first",a,question,202);
        await(()->modelCalls.get()==1,"First request did not reach the actual model adapter");
        stopService();releaseModel.release();
        // Durable crash-boundary fixture: an issued request with no persisted completion.
        try(Connection c=db();PreparedStatement q=c.prepareStatement("UPDATE auxiliary_turns SET status='RUNNING',payload_json=json_set(payload_json,'$.state','RUNNING','$.packagePinOwned',0) WHERE scope_id=? AND entity_id='first'")){q.setString(1,child);Assert.assertEquals(1,q.executeUpdate());}
        restartService();String first="/api/v2/sessions/"+child+"/auxiliary-turns/first";
        JsonNode unknown=api("GET",first,null,b,null,200);Assert.assertEquals(unknown.toString(),"OUTCOME_UNKNOWN",unknown.path("state").asText());Assert.assertEquals(1,modelCalls.get());
        ObjectNode retry=question.deepCopy().put("turnId","retry-one").put("expectedSessionVersion","2").put("retryOfTurnId","first");
        JsonNode admitted=api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","retry-one",b,retry,202);Assert.assertEquals(2,admitted.path("attemptNo").asInt());Assert.assertEquals(unknown.path("modelBinding"),admitted.path("modelBinding"));
        await(()->modelCalls.get()==2,"Explicit retry did not start");releaseModel.release();
        await(()->"SUCCEEDED".equals(api("GET","/api/v2/sessions/"+child+"/auxiliary-turns/retry-one",null,b,null,200).path("state").asText()),"Retry did not publish");
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","retry-one",a,retry,202);Assert.assertEquals(2,modelCalls.get());
        retry.put("turnId","retry-two").put("expectedSessionVersion","3");api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","retry-two",a,retry,409);
    }
    @Test public void liveBasePinSurvivesExpiryAndCrossScopeAndMalformedRequestsAreRejected()throws Exception{
        setupTurn();try(Connection c=db();Statement q=c.createStatement()){q.executeUpdate("UPDATE context_packages SET payload_json=json_set(payload_json,'$.expiresAt',1)");}
        JsonNode pinned=api("GET","/api/v2/sessions/"+parent+"/context-packages/recover-package",null,a,null,200);Assert.assertTrue(pinned.path("pins").asLong()>0);
        api("GET","/api/v2/sessions/"+child+"/context-packages/recover-package",null,a,null,404);
        ObjectNode malformed=question.deepCopy();malformed.putArray("researchTasks").add(mapper.createObjectNode().put("taskId",1).put("role","repo-explorer").put("question","Inspect"));
        api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","first",a,malformed,400);Assert.assertEquals(0,modelCalls.get());
        api("PATCH","/api/v2/sessions/"+child,"illegal-upgrade",a,mapper.createObjectNode().put("expectedSessionVersion",1).put("executionMode","EDIT"),409);
        Assert.assertEquals(0,modelCalls.get());
    }
}
