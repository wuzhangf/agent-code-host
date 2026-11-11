package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.domain.BusinessException;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Actual packaged PiHost export -> final PAC material consumer. The original
 * controlled Worker child supplies outputs; no model or live state is used. */
public class PersonalPiHandoffContractTest {
    PersonalPiTaskAccessContractTest source;
    PersonalWorkHandoffService handoffs;
    Path materials,signing;
    AtomicInteger seals=new AtomicInteger(),auxCreates=new AtomicInteger();
    AtomicBoolean invalidReportId=new AtomicBoolean(true);
    String reportId,displayName="中文协作 / 人类显示名称";
    @Before public void setup()throws Exception {
        PersonalPiTaskAccessContractTest.startSource();
        source=new PersonalPiTaskAccessContractTest();source.setup();
        PersonalAccessService.Device d=source.auth.authenticate(source.token);
        Set<String> actions=new TreeSet<>(d.actions);actions.add("HANDOFF");actions.add("CREATE");
        source.auth.updateScopeResources(source.did,"handoff-scope",Long.toString(d.revision),d.resources,d.launchScopes,actions);
        materials=PersonalPiTaskAccessContractTest.root.resolve("handoff-materials");
        signing=PersonalPiTaskAccessContractTest.root.resolve("handoff-signing");
        handoffs=service();
    }
    @After public void cleanup()throws Exception {
        if(source!=null)source.cleanup();
        PersonalPiTaskAccessContractTest.stopSource();
    }
    PersonalWorkHandoffService service(){
        return new PersonalWorkHandoffService(source.records,source.auth,PersonalPiTaskAccessContractTest.JSON,
            (d,m,p,b,k)->{throw new AssertionError("No target write is part of finalization");},
            new PersonalWorkHandoffService.NativePort(){
                public PersonalLocalTaskAccess.Result call(PersonalAccessService.Device d,String backend,String method,String path,JsonNode body,String key)throws java.io.IOException {
                    if(method.equals("POST")&&path.endsWith("/source-seals"))seals.incrementAndGet();
                    if(method.equals("POST")&&path.endsWith("/auxiliary-sessions"))auxCreates.incrementAndGet();
                    PersonalLocalTaskAccess.Result result=source.access.call(d,backend,method,path,body,key);
                    if(method.equals("GET")&&path.contains("/handoff-export")&&result.body.path("items").isArray()){
                        for(JsonNode row:result.body.path("items"))if("COLLABORATION_REPORT".equals(row.path("kind").asText())){
                            assertEquals(displayName,row.path("name").asText());
                            // One malformed source id must fail closed. Restore the actual
                            // source row for same-phase recovery; do not loosen relName.
                            if(invalidReportId.get())((ObjectNode)row).put("id","../unsafe");
                        }
                    }
                    return result;
                }
                public PersonalLocalTaskAccess.Result callBytes(PersonalAccessService.Device d,String b,String m,String p,byte[] bytes,Map<String,String> headers,String k)throws java.io.IOException {
                    return source.access.callBytes(d,b,m,p,bytes,headers,k);
                }
            },materials,signing);
    }
    ObjectNode body(String key){return PersonalPiTaskAccessContractTest.JSON.createObjectNode().put("requestKey",key);}
    JsonNode call(String method,String tail,JsonNode body,String key)throws Exception {
        return handoffs.remote(source.token,method,"/personal/v1/work-handoffs"+tail,body,key,null).body;
    }
    JsonNode advance(String id,String phase)throws Exception {
        String key="handoff:actual-pi-handoff:"+phase;
        return call("POST","/"+id+"/continue",body(key),key);
    }
    @Test public void realChineseReportUsesStableIdAndFailedFinalizationResumesWithoutReseal()throws Exception {
        ObjectNode collab=body("handoff-collab").put("objective",displayName);
        collab.putArray("units").addObject().put("childId","a").put("goal","child-write handoff-child.txt");
        String group=source.post("/collaborations",collab).path("source").path("collaborationId").asText();
        source.waitDetail("/collaborations/"+group,"COMPLETED");reportId="collab-"+group;
        ObjectNode create=body("actual-pi-handoff");
        create.putObject("sourceRef").put("kind","WORKER_LOCAL").put("storeId",PersonalPiTaskAccessContractTest.backend).put("taskId",PersonalPiTaskAccessContractTest.task);
        create.putObject("targetAgentRef").put("hostKey","local").put("kind","ACS").put("projectRef","control");
        String id=call("POST","",create,"actual-pi-handoff").path("handoffId").asText();
        advance(id,"PREPARING");assertEquals("AWAITING_CONFIRM",advance(id,"PREPARING").path("phase").asText());
        String confirm="handoff:actual-pi-handoff:CONFIRM";
        call("POST","/"+id+"/confirm",body(confirm).put("expectedMaterialRevision",1),confirm);
        assertEquals("FINALIZING_MATERIALS",advance(id,"SOURCE_SEALING").path("phase").asText());
        assertEquals(1,seals.get());int preparedAux=auxCreates.get();
        try{advance(id,"FINALIZING_MATERIALS");fail("unsafe stable id accepted");}
        catch(BusinessException error){assertEquals("INVALID_REQUEST",error.getCode());}
        JsonNode failed=call("GET","/"+id,null,null);
        assertEquals("FINALIZING_MATERIALS",failed.path("phase").asText());
        assertEquals("INVALID_REQUEST",failed.path("failureCode").asText());
        assertTrue(failed.path("sourceSealed").asBoolean());
        invalidReportId.set(false);handoffs=service(); // same persisted flow, fresh consumer
        JsonNode frozen=advance(id,"FINALIZING_MATERIALS");
        assertEquals("TARGET_STAGING",frozen.path("phase").asText());
        assertTrue(frozen.path("failureCode").isMissingNode()||frozen.path("failureCode").isNull());
        assertEquals(1,seals.get());assertEquals(preparedAux,auxCreates.get());
        Path file=materials.resolve("handoffs").resolve(id).resolve("export-"+reportId+".json");
        JsonNode fact=PersonalPiTaskAccessContractTest.JSON.readTree(Files.readAllBytes(file));
        assertEquals(displayName,fact.path("name").asText());assertEquals(reportId,fact.path("id").asText());
        assertEquals("COLLABORATION_REPORT",fact.path("kind").asText());
        assertEquals("TARGET_STAGING",advance(id,"FINALIZING_MATERIALS").path("phase").asText());
        assertEquals(1,seals.get());assertEquals(preparedAux,auxCreates.get());
    }
}
