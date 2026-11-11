package com.sage.business.code.bootstrap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.infrastructure.Hashing;
import java.nio.file.*;
import java.sql.*;
import org.junit.*;
/** Bounded material publication, expiry and fail-closed filesystem failures on the actual HTTP composition. */
public class CollaborationMaterialProtocolTest extends FoundationProtocolAcceptanceTest {
 @Override protected void configureCollaboration(ServiceConfig config){config.collaborationAuxiliaryEnabled=true;}
 private String sid,snapshot;
 private void setupMaterial()throws Exception{
  sid=api("POST","/api/v2/sessions","material-parent",a,mapper.createObjectNode(),201).path("sessionId").asText();
  snapshot=api("POST","/api/v2/sessions/"+sid+"/observation-snapshots","material-snapshot",a,mapper.createObjectNode(),201).path("snapshotId").asText();
 }
 private JsonNode pack(String key,int status)throws Exception{return api("POST","/api/v2/sessions/"+sid+"/context-packages",key,a,mapper.createObjectNode().put("requestId",key).put("snapshotId",snapshot),status);}
 private Connection db()throws Exception{return DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/gui-sessions.db"));}
 @Test public void packageQuotaReplayAndExpiryRemainBounded()throws Exception{
  setupMaterial();for(int i=0;i<20;i++)Assert.assertEquals("READY",pack("pack-"+i,201).path("state").asText());pack("pack-0",201);
  Assert.assertEquals("CONTEXT_PACKAGE_QUOTA_EXCEEDED",pack("overflow",409).path("code").asText());
  try(Connection c=db();Statement q=c.createStatement()){q.executeUpdate("UPDATE context_packages SET payload_json=json_set(payload_json,'$.expiresAt',1) WHERE entity_id='pack-0'");}
  api("GET","/api/v2/sessions/"+sid+"/context-packages/pack-0",null,b,null,410);pack("overflow",201);
  api("GET","/api/v2/sessions/"+sid+"/context-packages/pack-0",null,b,null,410);Assert.assertEquals(0,modelCalls.get());
 }
 @Test public void failedPublicationIsInvisibleAndFailedGcKeepsItsReservation()throws Exception{
  setupMaterial();Path destination=root.resolve("state/collaboration/packages/"+Hashing.sha256(sid+"\nblocked")+".json");Files.createDirectories(destination);Path blocker=destination.resolve("fixture-blocker");Files.writeString(blocker,"owned failure fixture");
  Assert.assertEquals("STATE_STORE_ERROR",pack("blocked",503).path("code").asText());api("GET","/api/v2/sessions/"+sid+"/context-packages/blocked",null,b,null,409);
  try(Connection c=db();Statement q=c.createStatement()){q.executeUpdate("UPDATE context_packages SET payload_json=json_set(payload_json,'$.createdAt',1) WHERE entity_id='blocked'");}
  pack("after-failure",201);
  try(Connection c=db();Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT status,json_extract(payload_json,'$.storageBytes') FROM context_packages WHERE entity_id='blocked'")){Assert.assertTrue(r.next());Assert.assertEquals("GC_PENDING",r.getString(1));Assert.assertTrue(r.getLong(2)>0);}
  Files.delete(blocker);Files.delete(destination);pack("after-reclaim",201);api("GET","/api/v2/sessions/"+sid+"/context-packages/blocked",null,b,null,410);
  try(Connection c=db();Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT json_extract(payload_json,'$.storageBytes') FROM context_packages WHERE entity_id='blocked'")){Assert.assertTrue(r.next());Assert.assertEquals(0,r.getLong(1));}
 }
 @Test public void absentSealedTreeRequiresExplicitTextOnlyResearch()throws Exception{
  setupMaterial();pack("text-material",201);String child=api("POST","/api/v2/sessions/"+sid+"/auxiliary-sessions","text-child",a,mapper.createObjectNode().put("requestId","text-child").put("basePackageId","text-material"),201).path("sessionId").asText();
  ObjectNode turn=mapper.createObjectNode().put("turnId","text-research").put("expectedSessionVersion","1").put("packageId","text-material").put("question","Inspect frozen text");turn.putArray("researchTasks").add(mapper.createObjectNode().put("taskId","inspect-text").put("role","repo-explorer").put("question","Read local source files"));
  Assert.assertEquals("RESEARCH_VIEW_UNAVAILABLE",api("POST","/api/v2/sessions/"+child+"/auxiliary-turns","text-research",a,turn,409).path("code").asText());Assert.assertEquals(0,modelCalls.get());
  JsonNode normal=api("GET","/api/v2/sessions",null,b,null,200);Assert.assertFalse(normal.toString(),normal.toString().contains(child));
 }
}
