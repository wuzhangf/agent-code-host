package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class PersonalAccessServiceTest {
    private ObjectMapper mapper=new ObjectMapper();
    private Path root;private ControlPlaneDatabase db;private PersonalAccessService service;
    private byte[] key=new byte[32];private AtomicLong clock=new AtomicLong(System.currentTimeMillis());
    private String poll,request,inviteSecret;
    @Before public void setup()throws Exception{root=Files.createTempDirectory("pac-service-");new java.security.SecureRandom().nextBytes(key);open();}
    private void open(){db=new ControlPlaneDatabase(root,false);service=new PersonalAccessService(new PersonalAccessStore(db,mapper),mapper,key,"store-test","https://localhost:9443",clock::get);}
    @After public void cleanup()throws Exception{db.close();try(java.util.stream.Stream<Path> walk=Files.walk(root)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    private ObjectNode pair(){String invitation=UUID.randomUUID().toString();poll=UUID.randomUUID().toString()+UUID.randomUUID();request=UUID.randomUUID().toString();ObjectNode i=service.invitation(invitation,Arrays.asList("session-one"),new HashSet<String>(Arrays.asList("OBSERVE","CONTROL")),"public-cert-hash");inviteSecret=i.path("inviteSecret").asText();ObjectNode body=mapper.createObjectNode().put("inviteId",invitation).put("inviteSecret",inviteSecret).put("pairingRequestId",request).put("pollingSecretHash",PersonalAccessService.hash(poll)).put("installationId",UUID.randomUUID().toString()).put("label","phone");service.request(body);return body;}
    private ObjectNode issued(){pair();service.decide(request,true);return service.redeem(request,poll,"redeem-one");}
    private void denied(String code,Runnable r){try{r.run();fail("expected "+code);}catch(BusinessException e){assertEquals(code,e.getCode());}}
    @Test public void approvalRequiredAndScopeIsOwnerBound(){pair();denied("PAIRING_NOT_APPROVED",()->service.redeem(request,poll,"r1"));service.decide(request,true);ObjectNode cred=service.redeem(request,poll,"r1");assertEquals("phone",service.authorize(cred.path("accessToken").asText(),"session-one","CONTROL").label);denied("DEVICE_SCOPE_DENIED",()->service.authorize(cred.path("accessToken").asText(),"other","OBSERVE"));}
    @Test public void invitationAndPairingReplayConflictAndSingleBinding(){ObjectNode b=pair();assertEquals("PENDING",service.request(b).path("state").asText());ObjectNode changed=b.deepCopy().put("label","different");denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->service.request(changed));ObjectNode other=b.deepCopy().put("pairingRequestId",UUID.randomUUID().toString());denied("INVITATION_BOUND",()->service.request(other));}
    @Test public void redemptionLossReusesSameCredentialAndNeverReauthorizesRevokedDevice(){ObjectNode original=issued();assertEquals(original,service.redeem(request,poll,"redeem-one"));denied("PAIRING_REDEEMED",()->service.redeem(request,poll,"different"));service.revoke(original.path("deviceGrantId").asText());denied("DEVICE_REVOKED",()->service.redeem(request,poll,"redeem-one"));}
    @Test public void refreshLossExactReplayAndReuseCommitsRevocation(){ObjectNode first=issued();String old=first.path("refreshToken").asText();ObjectNode second=service.refresh(old,"refresh-one");assertEquals(second,service.refresh(old,"refresh-one"));denied("REFRESH_REUSE",()->service.refresh(old,"different"));denied("DEVICE_REVOKED",()->service.authenticate(second.path("accessToken").asText()));db.close();open();denied("DEVICE_REVOKED",()->service.authenticate(second.path("accessToken").asText()));}
    @Test public void accessExpiresButRefreshCanRecover(){ObjectNode first=issued();clock.addAndGet(PersonalAccessService.ACCESS_TTL+1);denied("ACCESS_EXPIRED",()->service.authenticate(first.path("accessToken").asText()));ObjectNode next=service.refresh(first.path("refreshToken").asText(),"rotate");assertNotNull(service.authenticate(next.path("accessToken").asText()));}
    @Test public void revokeSurvivesRestartAndDoesNotRestoreTokenFamily(){ObjectNode first=issued();service.revoke(first.path("deviceGrantId").asText());db.close();open();denied("DEVICE_REVOKED",()->service.authenticate(first.path("accessToken").asText()));denied("DEVICE_REVOKED",()->service.refresh(first.path("refreshToken").asText(),"r"));}
    @Test public void expiredInvitationAndWrongPollingCannotRedeem(){pair();denied("PAIRING_UNAUTHORIZED",()->service.observePairing(request,"wrong"));clock.addAndGet(PersonalAccessService.INVITE_TTL+1);denied("PAIRING_EXPIRED",()->service.decide(request,true));}
    @Test public void credentialsAreNotPlaintextAtRest()throws Exception{ObjectNode first=issued();String dump=db.inTransaction(c->{try(java.sql.Statement s=c.createStatement();java.sql.ResultSet r=s.executeQuery("SELECT payload FROM personal_access_record")){StringBuilder b=new StringBuilder();while(r.next())b.append(r.getString(1));return b.toString();}});for(String secret:Arrays.asList(first.path("accessToken").asText(),first.path("refreshToken").asText(),poll,inviteSecret))assertFalse(dump.contains(secret));}
    @Test public void concurrentRedemptionIssuesOnlyOneGrant()throws Exception{pair();service.decide(request,true);ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<ObjectNode>> replies=new ArrayList<>();for(int i=0;i<4;i++)replies.add(pool.submit(()->service.redeem(request,poll,"same")));ObjectNode first=replies.get(0).get();for(Future<ObjectNode> reply:replies)assertEquals(first,reply.get());assertEquals(1,service.devices().size());}finally{pool.shutdownNow();}}
    @Test public void fourUnboundInvitationsStillConsumePendingQuota(){
        for(int i=0;i<4;i++)service.invitation("unbound-"+i,Arrays.asList("session-one"),new HashSet<>(Arrays.asList("OBSERVE")),"public-cert-hash");
        denied("PAIRING_LIMIT",()->service.invitation("fifth",Arrays.asList("session-one"),new HashSet<>(Arrays.asList("OBSERVE")),"public-cert-hash"));
    }
    @Test public void pendingAndApprovedPairingsStillConsumeQuota(){
        for(int i=0;i<4;i++){pair();if(i%2==0)service.decide(request,true);}
        denied("PAIRING_LIMIT",()->pair());
    }
    @Test public void redeemedRevokedPairingsReleaseQuotaWithoutRebindingOldInvitations(){
        ObjectNode original=null;
        for(int i=0;i<4;i++){ObjectNode body=pair();if(original==null)original=body;service.decide(request,true);ObjectNode grant=service.redeem(request,poll,"redeem");service.revoke(grant.path("deviceGrantId").asText());}
        assertEquals("PENDING",service.request(pair()).path("state").asText());
        final ObjectNode reused=original.deepCopy().put("pairingRequestId",UUID.randomUUID().toString());
        denied("INVITATION_BOUND",()->service.request(reused));
    }
    @Test public void rejectedPairingsReleaseQuotaButRemainSingleUse(){
        ObjectNode rejected=pair();service.decide(request,false);
        for(int i=0;i<4;i++)pair();
        denied("PAIRING_LIMIT",()->pair());
        denied("INVITATION_BOUND",()->service.request(rejected.deepCopy().put("pairingRequestId",UUID.randomUUID().toString())));
    }
    @Test public void missingOrUnknownBoundPairingStillConsumesQuota(){
        for(int i=0;i<4;i++)pair();
        db.inTransaction(c->{try(java.sql.Statement s=c.createStatement()){s.executeUpdate("UPDATE personal_access_record SET payload=json_set(payload,'$.state','UNRECOGNIZED') WHERE kind='pairing'");}return null;});
        denied("PAIRING_LIMIT",()->pair());
        db.inTransaction(c->{try(java.sql.Statement s=c.createStatement()){s.executeUpdate("DELETE FROM personal_access_record WHERE kind='pairing'");}return null;});
        denied("PAIRING_LIMIT",()->pair());
    }
    @Test public void releasedInviteQuotaDoesNotRelaxApprovalOrConcurrentDeviceLimit()throws Exception {
        String revoke=null;
        for(int i=0;i<4;i++){ObjectNode grant=issued();revoke=grant.path("deviceGrantId").asText();}
        pair();denied("DEVICE_LIMIT",()->service.decide(request,true));
        service.revoke(revoke);service.decide(request,true);
        final String firstRequest=request,firstPoll=poll;
        pair();service.decide(request,true);final String secondRequest=request,secondPoll=poll;
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<String>> outcomes=Arrays.asList(pool.submit(()->{try{service.redeem(firstRequest,firstPoll,"one");return "GRANTED";}catch(BusinessException e){return e.getCode();}}),pool.submit(()->{try{service.redeem(secondRequest,secondPoll,"two");return "GRANTED";}catch(BusinessException e){return e.getCode();}}));
            List<String> values=Arrays.asList(outcomes.get(0).get(),outcomes.get(1).get());assertEquals(1,Collections.frequency(values,"GRANTED"));assertEquals(1,Collections.frequency(values,"DEVICE_LIMIT"));
            int active=0;for(JsonNode d:service.devices())if("ACTIVE".equals(d.path("state").asText()))active++;assertEquals(4,active);
        }finally{pool.shutdownNow();}
    }
    @Test public void wrongSealKeyAndStoreFailClosed(){issued();byte[] different=key.clone();different[0]^=1;denied("HOST_IDENTITY_CHANGED",()->new PersonalAccessService(new PersonalAccessStore(db,mapper),mapper,different,"store-test","https://localhost"));denied("HOST_IDENTITY_CHANGED",()->new PersonalAccessService(new PersonalAccessStore(db,mapper),mapper,key,"other-store","https://localhost"));}
    @Test public void oldControlNeverImpliesInteractAndScopeCasPersists() {
        ObjectNode first=issued();String token=first.path("accessToken").asText(),did=first.path("deviceGrantId").asText();
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","INTERACT"));
        Set<String> actions=new TreeSet<String>(Arrays.asList("OBSERVE","INTERACT"));
        ObjectNode changed=service.updateScope(did,"scope-one","1",Arrays.asList("session-one"),actions);
        assertEquals("2",changed.path("grantRevision").asText());
        assertEquals(changed,service.updateScope(did,"scope-one","1",Arrays.asList("session-one"),actions));
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->service.updateScope(did,"scope-one","2",Arrays.asList("session-one"),actions));
        denied("GRANT_REVISION_CONFLICT",()->service.updateScope(did,"scope-two","1",Arrays.asList("session-one"),actions));
        assertNotNull(service.authorize(token,"session-one","INTERACT"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","CONTROL"));
        db.close();open();assertNotNull(service.authorize(token,"session-one","INTERACT"));
        service.updateScope(did,"scope-three","2",Arrays.asList("session-two"),new TreeSet<String>(Arrays.asList("OBSERVE")));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","OBSERVE"));
        assertNotNull(service.authorize(token,"session-two","OBSERVE"));
    }
    @Test public void unsupportedActionsAndRevokedScopeUpdatesFailClosed() {
        ObjectNode first=issued();String did=first.path("deviceGrantId").asText();
        denied("INVALID_SCOPE",()->service.updateScope(did,"scope-invalid","1",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH"))));
        service.revoke(did);
        denied("DEVICE_REVOKED",()->service.updateScope(did,"scope-revoked","2",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE"))));
    }

    @Test public void approvalIsExplicitAndDoesNotImplyInteractOrControl() {
        ObjectNode first=issued();String token=first.path("accessToken").asText(),did=first.path("deviceGrantId").asText();
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","APPROVE"));
        service.updateScope(did,"approve-scope","1",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE","APPROVE")));
        assertNotNull(service.authorize(token,"session-one","APPROVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","INTERACT"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","CONTROL"));
        db.close();open();assertNotNull(service.authorize(token,"session-one","APPROVE"));
    }

    @Test public void assignmentRequiresIndependentGrantAndDoesNotAddOtherActions() {
        ObjectNode first=issued();String token=first.path("accessToken").asText(),did=first.path("deviceGrantId").asText();
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","ASSIGN"));
        service.updateScope(did,"assign-scope","1",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE","ASSIGN")));
        assertNotNull(service.authorize(token,"session-one","ASSIGN"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","APPROVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","INTERACT"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","CONTROL"));
    }

    @Test public void collaborationAndAdoptionAreSeparateDurableGrants() {
        ObjectNode first=issued();String token=first.path("accessToken").asText(),did=first.path("deviceGrantId").asText();
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","COLLABORATE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","ADOPT"));
        service.updateScope(did,"collab-scope","1",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE","COLLABORATE")));
        assertNotNull(service.authorize(token,"session-one","COLLABORATE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","ADOPT"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","APPROVE"));
        service.updateScope(did,"adopt-scope","2",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE","ADOPT")));
        db.close();open();assertNotNull(service.authorize(token,"session-one","ADOPT"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","COLLABORATE"));
    }
    @Test public void nativeResourcesAreExactStoreTaskBindingsAndCannotBeDroppedByLegacyScopeUpdate() {
        ObjectNode first=issued();String token=first.path("accessToken").asText(),did=first.path("deviceGrantId").asText();
        java.util.List<PersonalResourceScope.Resource> refs=Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL","native-store","native-one"));
        service.updateScopeResources(did,"native-scope","1",refs,Collections.emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE","CONTROL")));
        assertNotNull(service.authorizeLocal(token,"native-store","native-one","CONTROL"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLocal(token,"another-store","native-one","OBSERVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLocal(token,"native-store","native-two","OBSERVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLocal(token,"native-store","native-one","APPROVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","OBSERVE"));
        denied("SCOPE_V2_CLIENT_REQUIRED",()->service.updateScope(did,"old-ui","2",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE"))));
        db.close();open();assertNotNull(service.authorizeLocal(token,"native-store","native-one","OBSERVE"));
    }
    @Test public void launchScopeDoesNotExposeOtherTasksAndOnlyProvenOriginalTaskCanBeAppended() {
        ObjectNode first=issued();String token=first.path("accessToken").asText(),did=first.path("deviceGrantId").asText();
        service.updateScopeResources(did,"launch-scope","1",Collections.emptyList(),Arrays.asList(new PersonalResourceScope.LaunchScope("native-store","project-one","pi")),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH")));
        assertNotNull(service.authorizeLaunch(token,"native-store","project-one","pi"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLaunch(token,"native-store","project-one","codex"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLocal(token,"native-store","native-one","OBSERVE"));
        denied("GRANT_REVISION_CONFLICT",()->service.appendLaunchedTask(did,1,"native-store","project-one","pi","native-one"));
        service.appendLaunchedTask(did,2,"native-store","project-one","pi","native-one");
        assertNotNull(service.authorizeLocal(token,"native-store","native-one","OBSERVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLocal(token,"native-store","sibling","OBSERVE"));
        denied("GRANT_REVISION_CONFLICT",()->service.appendLaunchedTask(did,2,"native-store","project-one","pi","late-other-task"));
        service.updateScopeResources(did,"retain-task-only","3",Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL","native-store","native-one")),Collections.emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE")));
        assertNotNull(service.authorizeLocal(token,"native-store","native-one","OBSERVE"));
        denied("DEVICE_SCOPE_DENIED",()->service.authorizeLaunch(token,"native-store","project-one","pi"));
    }
    @Test public void durableScopeHasOneResourceAuthorityAndSqlRejectsOldBinaryWrites() {
        ObjectNode first=issued();String did=first.path("deviceGrantId").asText();
        db.inTransaction(c->{try(java.sql.PreparedStatement p=c.prepareStatement("SELECT payload FROM personal_access_record WHERE kind='device' AND id=?")){p.setString(1,did);try(java.sql.ResultSet r=p.executeQuery()){assertTrue(r.next());JsonNode value=mapper.readTree(r.getString(1));assertTrue(value.path("resources").isArray());assertFalse(value.has("sessions"));}}
            try(java.sql.PreparedStatement p=c.prepareStatement("UPDATE personal_access_record SET payload=? WHERE kind='device' AND id=?")){p.setString(1,"{\"sessions\":[\"other\"]}");p.setString(2,did);try{p.executeUpdate();fail("Old binary must not erase restrictions");}catch(java.sql.SQLException expected){assertTrue(expected.getMessage().contains("RESOURCE_SCOPE_V2_REQUIRED"));}}return null;});
        assertNotNull(service.authorize(first.path("accessToken").asText(),"session-one","OBSERVE"));
    }
    @Test public void legacySessionsMigrateWithoutWideningAccessOrRotatingCredentials() {
        ObjectNode first=issued();String did=first.path("deviceGrantId").asText();
        db.inTransaction(c->{try(java.sql.Statement q=c.createStatement()){q.execute("DROP TRIGGER personal_scope_v2_insert");q.execute("DROP TRIGGER personal_scope_v2_update");}
            try(java.sql.PreparedStatement q=c.prepareStatement("SELECT payload FROM personal_access_record WHERE kind='device' AND id=?")){q.setString(1,did);try(java.sql.ResultSet r=q.executeQuery()){r.next();ObjectNode old=(ObjectNode)mapper.readTree(r.getString(1));old.remove("resources");old.remove("launchScopes");old.put("scopeVersion",1);old.putArray("sessions").add("session-one");try(java.sql.PreparedStatement w=c.prepareStatement("UPDATE personal_access_record SET payload=? WHERE kind='device' AND id=?")){w.setString(1,mapper.writeValueAsString(old));w.setString(2,did);w.executeUpdate();}}}
            try(java.sql.PreparedStatement q=c.prepareStatement("UPDATE personal_access_record SET payload=json_set(payload,'$.keyCheck',?) WHERE kind='host' AND id='identity'")){q.setString(1,PersonalAccessService.hash(Base64.getEncoder().encodeToString(key)));q.executeUpdate();}return null;});
        db.close();open();String token=first.path("accessToken").asText();
        assertNotNull(service.authorize(token,"session-one","CONTROL"));denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"other","OBSERVE"));denied("DEVICE_SCOPE_DENIED",()->service.authorize(token,"session-one","INTERACT"));
        ObjectNode view=service.host(service.authenticate(token),"boot");assertEquals(2,view.path("scopeVersion").asInt());assertEquals("ACS_SESSION",view.path("resources").get(0).path("kind").asText());assertEquals("store-test",view.path("resources").get(0).path("backendStoreId").asText());assertEquals("1",view.path("grantRevision").asText());
    }
}
