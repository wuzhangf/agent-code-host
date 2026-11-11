package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import org.junit.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PersonalInboxServiceTest {
    ObjectMapper json; Path root; ControlPlaneDatabase db; PersonalAccessStore store; PersonalAccessService auth; PersonalInboxService inbox; PersonalAccessService.Device device;
    @Before public void setup() throws Exception {
        json=new ObjectMapper(); root=Files.createTempDirectory("personal-inbox-"); db=new ControlPlaneDatabase(root,false); store=new PersonalAccessStore(db,json);
        byte[] key=new byte[32]; new java.security.SecureRandom().nextBytes(key); auth=new PersonalAccessService(store,json,key,"acs-store","https://localhost");
        ObjectNode invite=auth.invitation("invite",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE")),"cert"); String poll="poll";
        String request="pairing"; auth.request(json.createObjectNode().put("inviteId","invite").put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",request).put("installationId","fixture").put("label","phone").put("pollingSecretHash",PersonalAccessService.hash(poll))); auth.decide(request,true);
        ObjectNode issued=auth.redeem(request,poll,"redeem"); device=auth.authenticate(issued.path("accessToken").asText());
        inbox=new PersonalInboxService(store,auth,json,"acs-store").reader((d,r,deadline)->read(signal("NEEDS_INPUT","approval:a1","a1:1")));
    }
    @After public void cleanup() throws Exception {db.close(); try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}

    PersonalInboxService.SourceRead read(PersonalInboxService.Signal... signals) {
        PersonalInboxService.SourceRead out=new PersonalInboxService.SourceRead();
        out.verifiedKinds.addAll(Arrays.asList("NEEDS_INPUT","RESULT_READY","ATTENTION_REQUIRED"));
        out.signals.addAll(Arrays.asList(signals)); return out;
    }
    PersonalInboxService.Signal signal(String kind,String event,String revision) {
        PersonalInboxService.Signal s=new PersonalInboxService.Signal(kind,event,revision,"CURRENT_STATE");
        s.subject=json.createObjectNode().put("type","APPROVAL").put("event",event); s.title=event; return s;
    }

    @Test public void projectsCurrentStateWithSubjectAndSourceCoverage() {
        inbox.scan(device,device.resources.get(0));
        ObjectNode page=inbox.list(device,null,30);
        assertEquals("personal-inbox/1",page.path("schemaVersion").asText());
        assertEquals("1",page.path("grantRevision").asText());
        assertTrue(page.has("listRevision"));
        assertEquals(1,page.path("items").size());
        JsonNode item=page.path("items").get(0);
        assertEquals("NEEDS_INPUT",item.path("kind").asText());
        assertEquals("a1:1",item.path("sourceRevision").asText());
        assertEquals("APPROVAL",item.path("subject").path("type").asText());
        assertTrue(item.path("observedAt").asLong()>0);
        assertFalse(item.has("read"));
        JsonNode source=page.path("sources").get(0);
        assertTrue(source.path("available").asBoolean());
        assertTrue(source.path("lastSuccessfulAt").asLong()>0);
    }
    @Test public void resolvedObjectsDisappearOnlyWhenKindVerified() {
        inbox.scan(device,device.resources.get(0)); assertEquals(1,inbox.list(device,null,30).path("items").size());
        inbox.reader((d,r,deadline)->read()); inbox.scan(device,device.resources.get(0));
        assertEquals(0,inbox.list(device,null,30).path("items").size());
    }
    @Test public void unavailableSourceRetainsLocatorMarkedUnavailable() {
        inbox.scan(device,device.resources.get(0));
        inbox.reader((d,r,deadline)->{throw new java.io.IOException("offline");});
        inbox.scan(device,device.resources.get(0));
        ObjectNode page=inbox.list(device,null,30);
        assertEquals(1,page.path("items").size());
        assertFalse(page.path("items").get(0).path("sourceAvailable").asBoolean());
        assertFalse(page.path("sources").get(0).path("available").asBoolean());
    }
    @Test public void unverifiedKindKeepsLocatorMarked() {
        inbox.scan(device,device.resources.get(0));
        inbox.reader((d,r,deadline)->{
            PersonalInboxService.SourceRead out=new PersonalInboxService.SourceRead();
            out.verifiedKinds.addAll(Arrays.asList("RESULT_READY","ATTENTION_REQUIRED")); return out;
        });
        inbox.scan(device,device.resources.get(0));
        ObjectNode page=inbox.list(device,null,30);
        assertEquals(1,page.path("items").size());
        assertEquals("KIND_UNVERIFIED",page.path("items").get(0).path("coverage").asText());
        assertTrue(page.path("sources").get(0).path("unverifiedKinds").toString().contains("NEEDS_INPUT"));
    }
    @Test public void revisionChangeResequencesAndExpiresCursor() {
        AtomicReference<PersonalInboxService.Signal> current=new AtomicReference<PersonalInboxService.Signal>(signal("NEEDS_INPUT","approval:a1","a1:1"));
        inbox.reader((d,r,deadline)->read(signal("NEEDS_INPUT","approval:a1","a1:1"),signal("RESULT_READY","message:m1","m1:1")));
        inbox.scan(device,device.resources.get(0));
        ObjectNode page=inbox.list(device,null,1);
        String cursor=page.path("nextCursor").asText(); assertFalse(cursor.isEmpty());
        assertEquals(1,inbox.list(device,cursor,30).path("items").size());
        current.set(signal("NEEDS_INPUT","approval:a1","a1:2"));
        inbox.reader((d,r,deadline)->read(current.get(),signal("RESULT_READY","message:m1","m1:1")));
        inbox.scan(device,device.resources.get(0));
        try{inbox.list(device,cursor,30);fail("expired cursor must be rejected");}catch(BusinessException expected){assertEquals("INBOX_CURSOR_EXPIRED",expected.getCode());}
        ObjectNode fresh=inbox.list(device,null,30);
        assertEquals("a1:2",fresh.path("items").get(0).path("sourceRevision").asText());
    }
    @Test public void noticeSinkFiresAfterCommitForNewAndChangedItemsOnly() {
        List<PersonalInboxService.ItemChange> committed=new ArrayList<PersonalInboxService.ItemChange>();
        AtomicReference<String> owner=new AtomicReference<String>();
        inbox.noticeSink((d,items)->{owner.set(d);committed.addAll(items);});
        inbox.scan(device,device.resources.get(0));
        assertEquals(device.id,owner.get());assertEquals(1,committed.size());
        assertEquals("NEEDS_INPUT",committed.get(0).kind);assertEquals("a1:1",committed.get(0).sourceRevision);
        assertNotNull(committed.get(0).sourceKey);assertNotNull(committed.get(0).eventKey);
        committed.clear();
        // An unchanged business revision produces no notice work.
        inbox.scan(device,device.resources.get(0));assertTrue(committed.isEmpty());
        inbox.reader((d,r,deadline)->read(signal("NEEDS_INPUT","approval:a1","a1:2")));
        inbox.scan(device,device.resources.get(0));
        assertEquals(1,committed.size());assertEquals("a1:2",committed.get(0).sourceRevision);
        committed.clear();
        // Resolved deletions are not notice events.
        inbox.reader((d,r,deadline)->read());
        inbox.scan(device,device.resources.get(0));assertTrue(committed.isEmpty());
    }
    @Test public void detailReverifiesAndReportsResolution() {
        AtomicReference<PersonalInboxService.SourceRead> live=new AtomicReference<PersonalInboxService.SourceRead>(read(signal("NEEDS_INPUT","approval:a1","a1:1")));
        inbox.reader((d,r,deadline)->live.get()); inbox.scan(device,device.resources.get(0));
        String item=inbox.list(device,null,30).path("items").get(0).path("itemId").asText();
        ObjectNode detail=inbox.detail(device,item); assertFalse(detail.path("resolved").asBoolean()); assertTrue(detail.path("sourceAvailable").asBoolean());
        live.set(read()); assertTrue(inbox.detail(device,item).path("resolved").asBoolean());
        inbox.reader((d,r,deadline)->{throw new java.io.IOException("offline");});
        ObjectNode unknown=inbox.detail(device,item);
        assertTrue(unknown.path("currentStateUnknown").asBoolean()); assertFalse(unknown.path("sourceAvailable").asBoolean());
    }
    @Test public void deviceIsolationAndGrantInvalidation() throws Exception {
        inbox.scan(device,device.resources.get(0));
        String poll2="poll-2",request2="pairing-2";
        ObjectNode invite2=auth.invitation("invite-2",Arrays.asList("session-one"),new TreeSet<String>(Arrays.asList("OBSERVE")),"cert");
        auth.request(json.createObjectNode().put("inviteId","invite-2").put("inviteSecret",invite2.path("inviteSecret").asText()).put("pairingRequestId",request2).put("installationId","other").put("label","tablet").put("pollingSecretHash",PersonalAccessService.hash(poll2)));
        auth.decide(request2,true); PersonalAccessService.Device other=auth.authenticate(auth.redeem(request2,poll2,"redeem-2").path("accessToken").asText());
        assertEquals(0,inbox.list(other,null,30).path("items").size());
        auth.updateScope(device.id,"scope-1","1",Arrays.asList("session-two"),new TreeSet<String>(Arrays.asList("OBSERVE")));
        PersonalAccessService.Device rescoped=auth.activeDevice(device.id);
        assertEquals(2,rescoped.revision);
        inbox.scanDevice(rescoped);
        ObjectNode page=inbox.list(rescoped,null,30);
        assertEquals("2",page.path("grantRevision").asText());
        for(JsonNode item:page.path("items"))assertNotEquals("session-one",item.path("resource").path("sessionId").asText());
    }
    @Test public void fairRotationCoversResourcesBeyondOneRoundBudget() throws Exception {
        List<PersonalResourceScope.Resource> many=new ArrayList<PersonalResourceScope.Resource>();
        for(int i=0;i<20;i++)many.add(new PersonalResourceScope.Resource("ACS_SESSION","acs-store","session-"+i));
        ObjectNode invite=auth.invitationResources("invite-many",many,Collections.<PersonalResourceScope.LaunchScope>emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE")),"cert");
        String poll="poll-many",request="pairing-many";
        auth.request(json.createObjectNode().put("inviteId","invite-many").put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",request).put("installationId","wide").put("label","wide").put("pollingSecretHash",PersonalAccessService.hash(poll)));
        auth.decide(request,true); PersonalAccessService.Device wide=auth.authenticate(auth.redeem(request,poll,"redeem-many").path("accessToken").asText());
        Map<String,AtomicInteger> counts=new HashMap<String,AtomicInteger>();
        inbox.reader((d,r,deadline)->{counts.computeIfAbsent(r.key(),k->new AtomicInteger()).incrementAndGet();return read();});
        inbox.scanAll(); assertEquals(16,counts.size());
        inbox.scanAll(); assertEquals(21,counts.size());
        for(AtomicInteger count:counts.values())assertTrue(count.get()<=2);
    }
}
