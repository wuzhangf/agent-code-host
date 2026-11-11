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
import java.io.*;

/** PAC -> the actual packaged PiHost -> original deterministic Worker child.
 * The child's controlled output is not a real model qualification. */
public class PersonalPiTaskAccessContractTest {
    static final ObjectMapper JSON=new ObjectMapper();static Process host;static Path root;static String backend,task,origin,nativeToken;
    ControlPlaneDatabase database;PersonalAccessStore records;PersonalAccessService auth;PersonalLocalTaskAccess access;String token,did;
    @BeforeClass public static void startSource()throws Exception {
        Assume.assumeTrue("Explicit packaged Pi inputs are required for this integration contract",System.getProperty("pi.bundle")!=null&&System.getProperty("pi.worker")!=null&&System.getProperty("pi.contract.output")!=null);
        Path output=Paths.get(System.getProperty("pi.contract.output"));Files.createDirectories(output);root=Files.createTempDirectory(output,"pi-http-");
        Path bundle=Paths.get(System.getProperty("pi.bundle")),worker=Paths.get(System.getProperty("pi.worker"));
        nativeToken=UUID.randomUUID().toString()+UUID.randomUUID();
        ProcessBuilder pb=new ProcessBuilder(bundle.resolve("runtime/node.exe").toString(),Paths.get("verification/pi-pac-contract-host.mjs").toAbsolutePath().toString(),bundle.toString(),worker.toString(),root.toString());
        pb.environment().keySet().removeIf(k->k.startsWith("MODEL_")||k.startsWith("LLM_HEADER_")||k.startsWith("PAC_")||k.equals("ACS_TOKEN"));
        pb.environment().put("PI_CONTRACT_TOKEN",nativeToken);pb.environment().put("PI_TELEMETRY","off");pb.environment().put("PI_OFFLINE","1");pb.environment().put("GIT_CONFIG_NOSYSTEM","1");pb.environment().put("GIT_CONFIG_GLOBAL","NUL");
        pb.redirectError(root.resolve("source.stderr.log").toFile());host=pb.start();
        ExecutorService reader=Executors.newSingleThreadExecutor();String line;
        try{line=reader.submit(()->new BufferedReader(new InputStreamReader(host.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine()).get(60,TimeUnit.SECONDS);}finally{reader.shutdownNow();}
        JsonNode ready=JSON.readTree(line);backend=ready.path("backendStoreId").asText();task=ready.path("taskId").asText();origin="http://127.0.0.1:"+ready.path("port").asInt();
    }
    @AfterClass public static void stopSource()throws Exception {if(host!=null&&host.isAlive()){host.getOutputStream().write('\n');host.getOutputStream().flush();if(!host.waitFor(15,TimeUnit.SECONDS))host.destroyForcibly().waitFor();}}
    @Before public void setup()throws Exception {
        Path state=Files.createTempDirectory(root,"pac-");database=new ControlPlaneDatabase(state,false);records=new PersonalAccessStore(database,JSON);byte[] seal=new byte[32];new java.security.SecureRandom().nextBytes(seal);
        auth=new PersonalAccessService(records,JSON,seal,"contract-acs","https://localhost");
        String iid=UUID.randomUUID().toString(),rid=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();
        ObjectNode invite=auth.invitationResources(iid,Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,task)),Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"demo","pi")),new TreeSet<>(Arrays.asList("OBSERVE","INTERACT","CONTROL","COLLABORATE","ADOPT","LAUNCH")),"public-contract-pin");
        auth.request(JSON.createObjectNode().put("inviteId",iid).put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",rid).put("installationId",UUID.randomUUID().toString()).put("label","contract").put("pollingSecretHash",PersonalAccessService.hash(poll)));auth.decide(rid,true);
        ObjectNode issued=auth.redeem(rid,poll,UUID.randomUUID().toString());token=issued.path("accessToken").asText();did=issued.path("deviceGrantId").asText();
        access=new PersonalLocalTaskAccess(origin,nativeToken,records,auth,JSON);
    }
    @After public void cleanup(){if(database!=null)database.close();}
    JsonNode get(String tail)throws Exception{return access.remote(token,"GET","/personal/v1/local-tasks/"+backend+"/tasks/"+task+tail,null,null,null).body;}
    JsonNode post(String tail,ObjectNode body)throws Exception{return access.remote(token,"POST","/personal/v1/local-tasks/"+backend+"/tasks/"+task+tail,body,body.path("requestKey").asText(),null).body;}
    ObjectNode body(String key){return JSON.createObjectNode().put("requestKey",key);}
    void denied(String code,Callable<?> call)throws Exception{try{call.call();fail("Expected "+code);}catch(BusinessException error){assertEquals(code,error.getCode());}}
    JsonNode waitDetail(String tail,String state)throws Exception {long deadline=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<deadline){JsonNode row=get(tail);if(state.equals(row.path("state").asText()))return row;Thread.sleep(25);}throw new AssertionError("SOURCE_WAIT_TIMEOUT");}
    @Test public void actualPiAuxContentRevisionBindingAndOriginalKeyRecovery()throws Exception {
        ObjectNode create=body("aux-create").put("question","Return a read-only contract answer");create.putObject("budget").put("maxTotalTokens","1000");
        JsonNode accepted=post("/auxiliary-sessions",create);assertEquals("OBSERVED",accepted.path("state").asText());String aux=accepted.path("source").path("auxSessionId").asText();assertFalse(aux.isEmpty());
        JsonNode detail=waitDetail("/auxiliary-sessions/"+aux,"SUCCEEDED");assertEquals(task,detail.path("taskId").asText());assertEquals(backend,detail.path("backendStoreId").asText());assertEquals("PAC_CONTENT_DIGEST_V1",detail.path("answerRevisionKind").asText());assertFalse(detail.path("answerRevision").asText().isEmpty());assertEquals("1000",detail.path("budget").path("caps").path("maxTotalTokens").asText());
        assertTrue(get("/auxiliary-sessions/"+aux+"/messages").path("items").isArray());
        denied("AUX_ANSWER_CHANGED",()->post("/auxiliary-sessions/"+aux+"/deliver",body("aux-stale").put("expectedAnswerRevision","stale")));
        ObjectNode deliver=body("aux-deliver").put("expectedAnswerRevision",detail.path("answerRevision").asText());post("/auxiliary-sessions/"+aux+"/deliver",deliver);
        assertEquals("OBSERVED",post("/auxiliary-sessions/"+aux+"/deliver",deliver).path("state").asText());
        PersonalLocalTaskAccess.Request record=records.transaction(c->records.get(c,"local-aux",did+":aux-deliver",PersonalLocalTaskAccess.Request.class));assertFalse(record.wire.has("expectedAnswerRevision"));assertEquals(1,record.wire.size());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->post("/auxiliary-sessions/"+aux+"/deliver",deliver.deepCopy().put("expectedAnswerRevision","changed")));
        denied("LOCAL_BINDING_MISMATCH",()->get("/auxiliary-sessions/not-owned"));
    }
    @Test public void actualPiDagPreviewAdoptionAndChildMembershipUseRealRoutes()throws Exception {
        ObjectNode spec=body("collab-preflight").put("objective","Explicit child goals");ArrayNode units=spec.putArray("units");units.addObject().put("childId","a").put("goal","child-write out-a.txt").putObject("budget").put("maxTotalTokens","1000");units.addObject().put("childId","b").put("goal","child-write out-b.txt").putArray("dependsOn").add("a");
        ObjectNode ambiguous=spec.deepCopy().put("requestKey","ambiguous-plan");ambiguous.putObject("dependencies").put("note","Run this free text as a DAG");denied("PI_COLLAB_SPEC_REQUIRED",()->post("/collaborations/preflight",ambiguous));
        JsonNode pre=post("/collaborations/preflight",spec).path("source");assertFalse(pre.path("preflightId").asText().isEmpty());assertTrue(pre.path("planDigest").asText().matches("[a-f0-9]{64}"));
        ObjectNode create=spec.deepCopy().put("requestKey","collab-create").put("expectedPreflightId",pre.path("preflightId").asText()).put("expectedPreflightDigest",pre.path("planDigest").asText());
        JsonNode result=post("/collaborations",create);String group=result.path("source").path("collaborationId").asText();assertFalse(group.isEmpty());
        JsonNode done=waitDetail("/collaborations/"+group,"COMPLETED");assertEquals(task,done.path("taskId").asText());assertEquals(2,done.path("children").size());assertEquals("SUCCEEDED",done.path("children").get(1).path("state").asText());
        assertEquals("1000",done.path("children").get(0).path("budget").path("caps").path("maxTotalTokens").asText());
        assertEquals(group,post("/collaborations",create).path("source").path("collaborationId").asText());assertEquals(1,get("/collaborations").path("items").size());
        assertTrue(get("/collaborations/"+group+"/children/a/messages").path("items").isArray());
        denied("LOCAL_BINDING_MISMATCH",()->get("/collaborations/"+group+"/children/foreign/messages"));
        denied("PI_CHILD_SELECTION_REQUIRED",()->post("/collaborations/"+group+"/previews",body("preview-ambiguous")));
        JsonNode preview=post("/collaborations/"+group+"/previews",body("preview-a").put("childId","a")).path("source");String id=preview.path("previewId").asText();
        JsonNode full=get("/collaborations/"+group+"/previews/"+id);assertEquals("a",full.path("childId").asText());
        ObjectNode adoption=body("adopt-a").put("expectedPreviewId",id).put("expectedPreviewDigest",full.path("planDigest").asText());
        ObjectNode outside=adoption.deepCopy().put("mode","FILES");outside.putArray("paths").add("not-reviewed.txt");
        denied("PREVIEW_STALE",()->PersonalPiTaskAdapter.adopt(outside,full,task,group,id));
        ObjectNode empty=((ObjectNode)full).deepCopy();empty.putArray("files");
        denied("ADOPTION_NO_REVIEWED_FILES",()->PersonalPiTaskAdapter.adopt(adoption,empty,task,group,id));
        denied("PREVIEW_STALE",()->post("/collaborations/"+group+"/previews/"+id+"/adoptions",adoption.deepCopy().put("requestKey","bad-preview").put("expectedPreviewDigest",String.join("",Collections.nCopies(64,"0")))));
        JsonNode adopted=post("/collaborations/"+group+"/previews/"+id+"/adoptions",adoption);assertEquals("ADOPTED",adopted.path("source").path("outcome").asText());
        assertEquals("OBSERVED",post("/collaborations/"+group+"/previews/"+id+"/adoptions",adoption).path("state").asText());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->post("/collaborations/"+group+"/previews/foreign-preview/adoptions",adoption));
        denied("DEVICE_SCOPE_DENIED",()->access.remote(token,"GET","/personal/v1/local-tasks/other-store/tasks/"+task+"/collaborations",null,null,null));
        denied("DEVICE_SCOPE_DENIED",()->access.remote(token,"GET","/personal/v1/local-tasks/"+backend+"/tasks/foreign-task/collaborations",null,null,null));
        PersonalLocalTaskAccess.Request record=records.transaction(c->records.get(c,"local-collab",did+":collab-create",PersonalLocalTaskAccess.Request.class));assertFalse(record.wire.has("units"));assertTrue(record.wire.path("children").isArray());assertEquals(pre.path("planDigest"),record.wire.path("expectedPlanDigest"));
    }
    @Test public void actualPiTypedContextSourcesFreezeOwnedBytesAndRejectOpaqueReferences()throws Exception {
        ObjectNode create=body("package-create").put("scope","Explicit references").put("notes","An explicitly supplied summary");
        ArrayNode sources=create.putArray("sources");sources.addObject().put("kind","file").put("path","seed.txt");sources.addObject().put("kind","text").put("text","Explicit中文 context");
        JsonNode made=post("/context-packages",create).path("source");String packageId=made.path("packageId").asText();assertFalse(packageId.isEmpty());assertEquals(3,made.path("sourceCount").asInt());
        JsonNode frozen=get("/context-packages/"+packageId);assertEquals(task,frozen.path("taskId").asText());assertEquals(backend,frozen.path("backendStoreId").asText());assertTrue(frozen.path("digestVerified").asBoolean());
        String bytes=frozen.path("sources").get(0).path("content").asText();
        Files.writeString(root.resolve("state/workspaces").resolve(task).resolve("seed.txt"),"Changed only inside isolated contract task\n",java.nio.charset.StandardCharsets.UTF_8);
        JsonNode after=get("/context-packages/"+packageId);assertEquals(frozen.path("digest"),after.path("digest"));assertEquals(bytes,after.path("sources").get(0).path("content").asText());
        assertEquals(packageId,post("/context-packages",create).path("source").path("packageId").asText());
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->post("/context-packages",create.deepCopy().put("notes","Changed intent")));
        ObjectNode escaped=body("package-escape");escaped.putArray("sources").addObject().put("kind","file").put("path","../outside.txt");denied("PACKAGE_SOURCE_OUT_OF_SCOPE",()->post("/context-packages",escaped));
        ObjectNode opaque=body("package-opaque");opaque.putArray("includeTaskIds").add(task);denied("PI_CONTEXT_SOURCE_REQUIRED",()->post("/context-packages",opaque));
        denied("LOCAL_BINDING_MISMATCH",()->get("/context-packages/foreign-package"));
        PersonalLocalTaskAccess.Request record=records.transaction(c->records.get(c,"local-material",did+":package-create",PersonalLocalTaskAccess.Request.class));assertFalse(record.wire.has("scope"));assertFalse(record.wire.has("notes"));assertEquals(3,record.wire.path("sources").size());
    }
}
