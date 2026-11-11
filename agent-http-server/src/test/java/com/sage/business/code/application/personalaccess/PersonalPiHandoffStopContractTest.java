package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Production PAC -> packaged original PiHost -> owned Windows processes.
 * Controlled original Worker child, zero model; not phone/TLS qualification. */
public class PersonalPiHandoffStopContractTest {
    PersonalPiTaskAccessContractTest source;
    PersonalWorkHandoffService service;
    BufferedReader hostOutput;
    int stops,seals,queries;boolean loseStop,loseSeal,hideReceipt,denyReceipt,changedGeneration;
    String stopKey,flow;
    Path workspace;
    @Before public void setup()throws Exception {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Assume.assumeNotNull(System.getProperty("pi.bundle"),System.getProperty("pi.worker"),System.getProperty("pi.contract.output"));
        Path output=Paths.get(System.getProperty("pi.contract.output"));Files.createDirectories(output);
        Path root=Files.createTempDirectory(output,"pi-stop-");PersonalPiTaskAccessContractTest.root=root;
        PersonalPiTaskAccessContractTest.nativeToken=UUID.randomUUID().toString()+UUID.randomUUID();
        Path bundle=Paths.get(System.getProperty("pi.bundle"));
        ProcessBuilder pb=new ProcessBuilder(bundle.resolve("runtime/node.exe").toString(),Paths.get("verification/pi-handoff-stop-contract-host.mjs").toAbsolutePath().toString(),bundle.toString(),System.getProperty("pi.worker"),root.toString());
        pb.environment().keySet().removeIf(k->k.startsWith("MODEL_")||k.startsWith("LLM_HEADER_")||k.startsWith("PAC_")||k.equals("ACS_TOKEN"));
        pb.environment().put("PI_CONTRACT_TOKEN",PersonalPiTaskAccessContractTest.nativeToken);pb.environment().put("PI_TELEMETRY","off");pb.environment().put("PI_OFFLINE","1");pb.environment().put("GIT_CONFIG_NOSYSTEM","1");pb.environment().put("GIT_CONFIG_GLOBAL","NUL");
        pb.redirectError(root.resolve("source.stderr.log").toFile());PersonalPiTaskAccessContractTest.host=pb.start();
        hostOutput=new BufferedReader(new InputStreamReader(PersonalPiTaskAccessContractTest.host.getInputStream(),StandardCharsets.UTF_8));
        JsonNode ready=PersonalPiTaskAccessContractTest.JSON.readTree(readLine());
        PersonalPiTaskAccessContractTest.backend=ready.path("backendStoreId").asText();PersonalPiTaskAccessContractTest.task=ready.path("taskId").asText();PersonalPiTaskAccessContractTest.origin="http://127.0.0.1:"+ready.path("port").asInt();
        source=new PersonalPiTaskAccessContractTest();source.setup();
        PersonalAccessService.Device d=source.auth.authenticate(source.token);Set<String> actions=new TreeSet<>(d.actions);actions.add("HANDOFF");actions.add("CREATE");
        source.auth.updateScopeResources(source.did,"handoff-stop-scope",Long.toString(d.revision),d.resources,d.launchScopes,actions);
        workspace=root.resolve("state/workspaces").resolve(PersonalPiTaskAccessContractTest.task);
        service=newService();
    }
    String readLine()throws Exception {ExecutorService pool=Executors.newSingleThreadExecutor();try{return pool.submit(()->hostOutput.readLine()).get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}}
    @After public void cleanup()throws Exception {if(source!=null)source.cleanup();PersonalPiTaskAccessContractTest.stopSource();}
    PersonalWorkHandoffService newService(){return new PersonalWorkHandoffService(source.records,source.auth,PersonalPiTaskAccessContractTest.JSON,
        (d,m,p,b,k)->{throw new AssertionError("No ACS mutation in stop/seal contract");},
        new PersonalWorkHandoffService.NativePort(){
        public PersonalLocalTaskAccess.Result call(PersonalAccessService.Device d,String backend,String method,String path,JsonNode body,String key)throws IOException {
            if(method.equals("POST")&&path.endsWith("/commands")){stops++;stopKey=key;}
            if(method.equals("POST")&&path.endsWith("/source-seals"))seals++;
            if(method.equals("GET")&&path.contains("/commands/")){
                queries++;
                if(denyReceipt)return new PersonalLocalTaskAccess.Result(403,PersonalPiTaskAccessContractTest.JSON.createObjectNode().put("code","DEVICE_SCOPE_DENIED"));
                if(hideReceipt)return new PersonalLocalTaskAccess.Result(404,PersonalPiTaskAccessContractTest.JSON.createObjectNode().put("code","REQUEST_NOT_FOUND"));
            }
            PersonalLocalTaskAccess.Result reply=source.access.call(d,backend,method,path,body,key);
            if(method.equals("POST")&&path.endsWith("/commands")&&loseStop){loseStop=false;throw new IOException("Controlled loss after real STOP reply");}
            if(method.equals("POST")&&path.endsWith("/source-seals")&&loseSeal){loseSeal=false;throw new IOException("Controlled loss after real seal reply");}
            if(changedGeneration&&method.equals("GET")&&path.equals("/tasks/"+PersonalPiTaskAccessContractTest.task)){
                ObjectNode changed=((ObjectNode)reply.body).deepCopy();changed.put("generation","different-generation");return new PersonalLocalTaskAccess.Result(reply.status,changed);
            }
            return reply;
        }
        public PersonalLocalTaskAccess.Result callBytes(PersonalAccessService.Device d,String backend,String method,String path,byte[] bytes,Map<String,String> headers,String key)throws IOException {
            return source.access.callBytes(d,backend,method,path,bytes,headers,key);
        }},PersonalPiTaskAccessContractTest.root.resolve("materials"),PersonalPiTaskAccessContractTest.root.resolve("signing"));}
    ObjectNode body(String key){return PersonalPiTaskAccessContractTest.JSON.createObjectNode().put("requestKey",key);}
    JsonNode rawGet(String tail)throws Exception{return source.access.call(source.auth.authenticate(source.token),PersonalPiTaskAccessContractTest.backend,"GET","/tasks/"+PersonalPiTaskAccessContractTest.task+tail,null,null).body;}
    JsonNode call(String method,String tail,JsonNode body,String key)throws Exception{return service.remote(source.token,method,"/personal/v1/work-handoffs"+tail,body,key,null).body;}
    JsonNode seal()throws Exception {String key="handoff:stop-flow:SOURCE_SEALING";return call("POST","/"+flow+"/continue",body(key),key);}
    void prepare()throws Exception {
        ObjectNode create=body("stop-flow");create.putObject("sourceRef").put("kind","WORKER_LOCAL").put("storeId",PersonalPiTaskAccessContractTest.backend).put("taskId",PersonalPiTaskAccessContractTest.task);
        create.putObject("targetAgentRef").put("hostKey","local").put("kind","ACS").put("projectRef","control");
        flow=call("POST","",create,"stop-flow").path("handoffId").asText();String key="handoff:stop-flow:PREPARING";
        call("POST","/"+flow+"/continue",body(key),key);assertEquals("AWAITING_CONFIRM",call("POST","/"+flow+"/continue",body(key),key).path("phase").asText());
        key="handoff:stop-flow:CONFIRM";call("POST","/"+flow+"/confirm",body(key).put("expectedMaterialRevision",1),key);
    }
    @Test public void mainAndEscapedDescendantDrainBeforeSealAndRestartKeepsTerminal()throws Exception {
        JsonNode before=source.get("");assertEquals("SUCCEEDED",before.path("state").asText());assertFalse(before.path("quiescent").asBoolean());
        long descendant=Long.parseLong(Files.readString(workspace.resolve("descendant-pid.txt")));assertTrue(ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false));
        prepare();assertEquals("FINALIZING_MATERIALS",seal().path("phase").asText());assertEquals(1,stops);assertEquals(1,seals);
        JsonNode stopped=source.get("");assertTrue(stopped.path("quiescent").asBoolean());assertEquals("WINDOWS_JOB_ACTIVE_ZERO",stopped.path("stopProof").path("method").asText());assertTrue(stopped.path("intakeClosed").asBoolean());
        assertEquals("APPLIED",source.access.call(source.auth.authenticate(source.token),PersonalPiTaskAccessContractTest.backend,"GET","/tasks/"+PersonalPiTaskAccessContractTest.task+"/commands/"+stopKey,null,null).body.path("state").asText());
        assertFalse(ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false));long size=Files.size(workspace.resolve("heartbeat.txt"));Thread.sleep(150);assertEquals(size,Files.size(workspace.resolve("heartbeat.txt")));
        JsonNode oldSeal=rawGet("/source-seals").path("items").get(0);assertEquals(flow,oldSeal.path("target").asText());
        PersonalPiTaskAccessContractTest.host.getOutputStream().write("restart\n".getBytes(StandardCharsets.UTF_8));PersonalPiTaskAccessContractTest.host.getOutputStream().flush();JsonNode restarted=PersonalPiTaskAccessContractTest.JSON.readTree(readLine()).path("task");
        assertEquals("SUCCEEDED",restarted.path("state").asText());assertTrue(restarted.path("quiescent").asBoolean());assertNotEquals("RESTART_RECONCILIATION_REQUIRED",restarted.path("reasonCode").asText());assertEquals(oldSeal,rawGet("/source-seals").path("items").get(0));
    }
    @Test public void lostStopReplyOnlyQueriesOriginalKeyThenRecoversWithoutRedispatch()throws Exception {
        prepare();loseStop=true;hideReceipt=true;
        assertEquals("SEAL_PENDING",seal().path("pendingReason").asText());assertEquals(1,stops);assertEquals(0,seals);String original=stopKey;
        service=newService();assertEquals("SEAL_PENDING",seal().path("pendingReason").asText());assertEquals(1,stops);assertEquals(0,seals);assertEquals(1,queries);
        denyReceipt=true;source.denied("DEVICE_SCOPE_DENIED",()->seal());assertEquals(1,stops);assertEquals(0,seals);denyReceipt=false;
        changedGeneration=true;source.denied("NATIVE_TARGET_CHANGED",()->seal());assertEquals(1,stops);assertEquals(0,seals);changedGeneration=false;
        hideReceipt=false;assertEquals("FINALIZING_MATERIALS",seal().path("phase").asText());assertEquals(original,stopKey);assertEquals(1,stops);assertEquals(1,seals);assertEquals(3,queries);
    }
    @Test public void committedSealWithLostReplyIsReadAndReusedWithoutAnotherStopOrSeal()throws Exception {
        prepare();loseSeal=true;try{seal();fail("Expected controlled transport loss");}catch(IOException expected){}
        assertEquals(1,stops);assertEquals(1,seals);JsonNode original=rawGet("/source-seals").path("items").get(0);
        service=newService();assertEquals("FINALIZING_MATERIALS",seal().path("phase").asText());assertEquals(1,stops);assertEquals(1,seals);assertEquals(original,rawGet("/source-seals").path("items").get(0));
    }
}
