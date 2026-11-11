package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.sage.business.code.application.session.ObservationSnapshotStore;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import java.time.*;
import java.util.*;
import org.junit.*;

public class SnapshotMetadataLifecycleTest  {
    private final FoundationProtocolAcceptanceTest fixture=new FoundationProtocolAcceptanceTest();
    @BeforeClass public static void isolate(){FoundationProtocolAcceptanceTest.isolateRuntimeOptionalStorage();}
    @AfterClass public static void restore(){FoundationProtocolAcceptanceTest.restoreRuntimeOptionalStorage();}
    @Before public void start()throws Exception{fixture.start();}
    @After public void close()throws Exception{fixture.close();}
    @Test public void terminalMetadataIsReclaimedAfterReplayHorizonWhilePinsSurvive() throws Exception {
        java.nio.file.Path root=fixture.root;com.fasterxml.jackson.databind.ObjectMapper mapper=fixture.mapper;String a=fixture.a;
        JsonNode session=fixture.api("POST","/api/v2/sessions","metadata-session",a,mapper.createObjectNode().put("workspaceAlias","sample"),201);
        String sid=session.path("sessionId").asText(),pinned="obs-"+UUID.randomUUID();
        try(ControlPlaneDatabase db=new ControlPlaneDatabase(root.resolve("state"))){
            db.inTransaction(c->{try(java.sql.PreparedStatement q=c.prepareStatement("INSERT INTO session_observation_snapshots(snapshot_id,session_id,principal_id,key_hash,request_hash,status,created_at,expires_at,pins,bytes) VALUES(?,?,'local-owner',?,?,'FAILED',0,0,?,0)")){
                for(int i=0;i<10000;i++){q.setString(1,i==0?pinned:"obs-"+UUID.randomUUID());q.setString(2,sid);q.setString(3,"old-key-"+i);q.setString(4,"old-payload-"+i);q.setInt(5,i==0?1:0);q.addBatch();}q.executeBatch();}return null;});
            ObservationSnapshotStore snapshots=new ObservationSnapshotStore(db,new AtomicJsonStore(root.resolve("state"),mapper),mapper,Clock.fixed(Instant.ofEpochMilli(3L*86400000),ZoneOffset.UTC),1000);
            try{snapshots.begin("local-owner",sid,"new-key","payload");Assert.fail("Metadata quota must still be enforced");}catch(com.sage.business.code.domain.BusinessException expected){Assert.assertEquals("SNAPSHOT_QUOTA_EXCEEDED",expected.getCode());}
            for(int i=0;i<110;i++)snapshots.collect();
            Assert.assertNotNull(snapshots.get("local-owner",sid,pinned));Assert.assertTrue(snapshots.begin("local-owner",sid,"new-key","payload").fresh);
            long remaining=db.inTransaction(c->{try(java.sql.Statement q=c.createStatement();java.sql.ResultSet r=q.executeQuery("SELECT COUNT(*) FROM session_observation_snapshots")){return r.next()?r.getLong(1):0L;}});Assert.assertEquals(2L,remaining);
        }
    }
}
