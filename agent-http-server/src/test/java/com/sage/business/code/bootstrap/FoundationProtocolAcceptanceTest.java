package com.sage.business.code.bootstrap;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.adapter.http.BusinessHttpServer;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/** Real ACS composition, real Product and OpenAI HTTP adapter, two registered clients; no paid service. */
public class FoundationProtocolAcceptanceTest {
    private static String previousObservation;
    @BeforeClass public static void isolateRuntimeOptionalStorage(){previousObservation=System.getProperty("observation.mode");System.setProperty("observation.mode","DISABLED");}
    @AfterClass public static void restoreRuntimeOptionalStorage(){if(previousObservation==null)System.clearProperty("observation.mode");else System.setProperty("observation.mode",previousObservation);}
    protected final ObjectMapper mapper=new ObjectMapper();
    protected final String bearer=UUID.randomUUID().toString();
    protected Path root,workspace;
    private BusinessServiceFactory.GuiServices services;
    private ServiceConfig config;
    private BusinessHttpServer server;
    private HttpServer model;
    private ExecutorService modelPool;
    protected int port;
    protected String a,b;
    private final Map<String,String> clientIds=new HashMap<>();
    private final List<HttpURLConnection> slowStreams=new ArrayList<>();
    protected final AtomicInteger modelCalls=new AtomicInteger();
    protected final Semaphore releaseModel=new Semaphore(0);
    protected Path createFixtureRoot()throws Exception{return Files.createTempDirectory("foundation-protocol-");}
    protected boolean deleteFixtureRoot(){return true;}
    protected BusinessServiceFactory.GuiServices fixtureServices(){return services;}
    /** The ServiceConfig built for this lane; assertions read the frozen
     * write-approval wait from here, the same value the factory hands to the
     * provider bridge. */
    protected final ServiceConfig fixtureConfig(){return config;}
    static final String WRITE_APPROVAL_TIMEOUT_ENV="PAC_WRITE_APPROVAL_TIMEOUT_MS";
    static final long DEFAULT_WRITE_APPROVAL_TIMEOUT_MILLIS=3_000L;
    static final long LIVE_LANE_WRITE_APPROVAL_TIMEOUT_MILLIS=300_000L;
    /** Write-approval bridge wait for this fixture lane.  The deterministic
     * default stays 3s; {@code PAC_WRITE_APPROVAL_TIMEOUT_MS} (environment
     * variable, then the same-named system property) overrides it, mirroring
     * the product's {@code gui.write-approval.timeout-millis} contract.
     * Human-paced lanes override this method for their floor. */
    protected long writeApprovalTimeoutMillis(){
        return resolveWriteApprovalTimeoutMillis(writeApprovalTimeoutOverride(),false);
    }
    /** Raw override value, or null when neither source is set.  The
     * environment wins over the system property. */
    String writeApprovalTimeoutOverride(){
        String value=System.getenv(WRITE_APPROVAL_TIMEOUT_ENV);
        if(value==null||value.trim().isEmpty())value=System.getProperty(WRITE_APPROVAL_TIMEOUT_ENV);
        return value==null||value.trim().isEmpty()?null:value.trim();
    }
    /** Resolution shared by every lane: an explicit override always wins,
     * otherwise a live approval lane gets the human-paced floor and all
     * other lanes keep the deterministic default. */
    static long resolveWriteApprovalTimeoutMillis(String override,boolean liveApprovalLane){
        if(override!=null&&!override.trim().isEmpty()){
            final long value;
            try{value=Long.parseLong(override.trim());}
            catch(NumberFormatException invalid){throw new IllegalArgumentException(WRITE_APPROVAL_TIMEOUT_ENV+" must be a positive millisecond value: "+override);}
            if(value<=0L)throw new IllegalArgumentException(WRITE_APPROVAL_TIMEOUT_ENV+" must be positive: "+override);
            return value;
        }
        return liveApprovalLane?LIVE_LANE_WRITE_APPROVAL_TIMEOUT_MILLIS:DEFAULT_WRITE_APPROVAL_TIMEOUT_MILLIS;
    }
    @Before public void start()throws Exception{
        root=createFixtureRoot();workspace=Files.createDirectories(root.resolve("workspace"));
        if(!Files.exists(workspace.resolve(".git"))){Files.write(workspace.resolve("README.md"),Collections.singletonList("acceptance workspace"));
            git("init");git("config","user.email","fixture@example.invalid");git("config","user.name","fixture");git("add",".");git("commit","-m","baseline");}
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);modelPool=Executors.newCachedThreadPool();model.setExecutor(modelPool);
        model.createContext("/v1/chat/completions",x->{
            JsonNode request=mapper.readTree(x.getRequestBody());modelCalls.incrementAndGet();observeModelRequest(request);
            awaitModelPermit(request);
            ObjectNode message=replyForModel(request);
            ObjectNode choice=mapper.createObjectNode().put("index",0).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop");
            boolean stream=request.path("stream").asBoolean();choice.set(stream?"delta":"message",message);
            ObjectNode reply=mapper.createObjectNode().put("id","foundation-response").put("model","fixture-model");reply.putArray("choices").add(choice);
            String text=stream?"data: "+reply+"\n\ndata: [DONE]\n\n":reply.toString();
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
            x.sendResponseHeaders(200,bytes.length);try(OutputStream out=x.getResponseBody()){out.write(bytes);}finally{x.close();}
        });model.start();
        config=new ServiceConfig("127.0.0.1",1,bearer,root.resolve("state"),root.resolve("absent-model.properties"),
                Collections.singletonMap("sample",workspace.toString()),root.resolve("logs"),"WARN",false,false,4000,com.sage.business.code.adapter.product.ProcessExecutionSettings.disabled(),com.sage.business.code.adapter.product.TraceExportSettings.disabled(),"10MB",2,"20MB",
                "sample",Collections.singletonList("."),false,1,10,null,null,null,null,false,null,null,null,writeApprovalTimeoutMillis(),null,null);
        config.sessionFoundationEnabled=true;config.agentProfilesDirectory=root.resolve("agents");
        Properties catalog=new Properties();catalog.setProperty("PROVIDER_1_ID","fixture");catalog.setProperty("PROVIDER_1_DIALECT","OPENAI_CHAT_COMPLETIONS");
        catalog.setProperty("PROVIDER_1_BASE_URL","http://127.0.0.1:"+model.getAddress().getPort()+"/v1");
        // Random loopback protocol material stays in memory; no credential file or external endpoint.
        catalog.setProperty("PROVIDER_1_API_KEY",UUID.randomUUID().toString());catalog.setProperty("PROVIDER_1_MODEL_1_ID","fixture-model");
        catalog.setProperty("PROVIDER_1_MODEL_1_CAPABILITIES","NATIVE_TOOL_CALLING");catalog.setProperty("PROVIDER_1_MODEL_2_ID","fixture-model-target");catalog.setProperty("PROVIDER_1_MODEL_2_CAPABILITIES","NATIVE_TOOL_CALLING");catalog.setProperty("PROVIDER_1_MODEL_3_ID","fixture-tiny");catalog.setProperty("PROVIDER_1_MODEL_3_CAPABILITIES","NATIVE_TOOL_CALLING");catalog.setProperty("PROVIDER_1_MODEL_3_CONTEXT_WINDOW_TOKENS","1024");catalog.setProperty("PROVIDER_1_MODEL_3_MAX_OUTPUT_TOKENS","256");
        catalog.setProperty("MODEL_DEFAULT_MODEL","fixture-model");config.setCatalogFileProperties(catalog);
        configureCollaboration(config);services=BusinessServiceFactory.createGui(config);
        server=new BusinessHttpServer(new InetSocketAddress("127.0.0.1",0),bearer,services.tasks,mapper,null,null,services.v2);server.start();
        java.lang.reflect.Field field=BusinessHttpServer.class.getDeclaredField("server");field.setAccessible(true);port=((HttpServer)field.get(server)).getAddress().getPort();
        a=register("WEB_TAB");b=register("CLI");Assert.assertNotEquals(a,b);
    }
    protected void stopService()throws Exception{if(server!=null){server.close();server=null;}if(services!=null){if(services.catalogSynchronizer!=null)services.catalogSynchronizer.close();services.tasks.close();services=null;}}
    protected void restartService()throws Exception{
        stopService();services=BusinessServiceFactory.createGui(config);server=new BusinessHttpServer(new InetSocketAddress("127.0.0.1",0),bearer,services.tasks,mapper,null,null,services.v2);server.start();
        java.lang.reflect.Field field=BusinessHttpServer.class.getDeclaredField("server");field.setAccessible(true);port=((HttpServer)field.get(server)).getAddress().getPort();a=register("WEB_TAB");b=register("CLI");
    }
    protected void configureCollaboration(ServiceConfig config){}
    protected void observeModelRequest(JsonNode request){}
    protected void awaitModelPermit(JsonNode request)throws IOException {
        try{if(!releaseModel.tryAcquire(25,TimeUnit.SECONDS))throw new IOException("fixture gate timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }

    protected ObjectNode replyForModel(JsonNode request){return mapper.createObjectNode().put("role","assistant").put("content","persisted fixture reply");}
    @After public void close()throws Exception{
        for(HttpURLConnection stream:slowStreams)stream.disconnect();
        releaseModel.release(100);if(server!=null)server.close();if(services!=null){if(services.catalogSynchronizer!=null)services.catalogSynchronizer.close();services.tasks.close();}
        if(model!=null)model.stop(0);if(modelPool!=null)modelPool.shutdownNow();
        if(root!=null&&deleteFixtureRoot())try(java.util.stream.Stream<Path> all=Files.walk(root)){all.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}
    }
    private String register(String kind)throws Exception{
        String id=UUID.randomUUID().toString();ObjectNode body=mapper.createObjectNode().put("registrationKey",id).put("clientId",id).put("processInstanceId",UUID.randomUUID().toString()).put("clientKind",kind);
        body.putArray("protocols").add("v2");JsonNode registered=api("POST","/api/v2/client-sessions",id,null,body,201);String token=registered.path("clientSessionToken").asText();clientIds.put(token,registered.path("clientSessionId").asText());return token;
    }
    /** Test fixture hook for a server composition that refreshes its client lane. */
    protected final String registerClient(String kind)throws Exception{return register(kind);}
    @Test public void twoClientsObserveFreezePauseResumeAndStopActualProduct()throws Exception{
        JsonNode capabilities=api("GET","/api/v2/capabilities",null,a,null,200);Assert.assertTrue(capabilities.toString().contains("session.sharedObserveV1"));
        ObjectNode request=mapper.createObjectNode().put("workspaceAlias","sample").put("interactionMode","ACT").put("intent","EXPLAIN").put("executionMode","INSPECT")
                .put("autonomyLevel","OFF").put("verificationLevel","NONE").put("initialWorkspaceListing",false).put("maxModelTurns",5).put("executionOwnership","WORKER_ATTEMPT");
        request.set("executionBinding",mapper.readTree("{\"schemaVersion\":\"worker-session-binding/1\",\"workerId\":\"worker-one\",\"workerTaskId\":\"task-one\",\"attemptId\":\"attempt-one\",\"attemptEpoch\":\"1\",\"workspaceId\":\"workspace-one\",\"workspaceGeneration\":\"1\",\"provider\":\"agentcode\"}"));
        JsonNode session=api("POST","/api/v2/sessions","create-one",a,request,201);String sid=session.path("sessionId").asText();
        ObjectNode message=mapper.createObjectNode().put("clientMessageId","start-one").put("content","Explain the existing README briefly").put("expectedSessionVersion",session.path("sessionVersion").asLong());message.putArray("attachments");
        JsonNode accepted=api("POST","/api/v2/sessions/"+sid+"/messages","start-one",a,message,202);String rid=accepted.path("runId").asText();Assert.assertFalse(rid.isEmpty());
        await(()->modelCalls.get()>=1,"Product did not call local HTTP model");
        JsonNode foundation=api("GET","/api/v2/sessions/"+sid+"/foundation",null,b,null,200);Assert.assertEquals(rid,foundation.path("firstRunId").asText());Assert.assertEquals("start-one",foundation.path("firstClientMessageId").asText());
        JsonNode run=api("GET","/api/v2/runs/"+rid,null,b,null,200);
        JsonNode pause=command(rid,"pause-one","PAUSE",run.path("stateVersion").asLong(),a);Assert.assertEquals("REQUESTED",pause.path("phase").asText());
        com.sage.business.code.infrastructure.AtomicJsonStore authority=new com.sage.business.code.infrastructure.AtomicJsonStore(root.resolve("state"),mapper);
        com.sage.business.code.domain.RunRecord beforeConsumption=authority.read("runs/"+rid+"/state.json",com.sage.business.code.domain.RunRecord.class);
        String snapshots="/api/v2/sessions/"+sid+"/observation-snapshots";
        int before=modelCalls.get();JsonNode snap=api("POST",snapshots,"snapshot-one",b,mapper.createObjectNode(),201);Assert.assertEquals(before,modelCalls.get());
        String frozenPath=snapshots+"/"+snap.path("snapshotId").asText();JsonNode frozen=api("GET",frozenPath+"/items",null,a,null,200);
        Assert.assertTrue(frozen.toString().contains("Explain the existing README briefly"));Assert.assertFalse(snap.path("resumable").asBoolean());
        releaseModel.release();await(()->"PAUSED_FOR_HUMAN".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Product did not reach human pause");
        JsonNode applied=api("GET","/api/v2/runs/"+rid+"/commands/pause-one",null,b,null,200);Assert.assertEquals("APPLIED",applied.path("phase").asText());
        JsonNode pausedSnapshot=api("POST",snapshots,"paused-stable",b,mapper.createObjectNode(),201);
        Assert.assertEquals(pausedSnapshot.toString(),"STABLE_COMPONENTS",pausedSnapshot.path("consistency").asText());
Assert.assertFalse(applied.path("consumptionProof").path("proofDigest").asText().isEmpty());
        Assert.assertEquals(frozen,api("GET",frozenPath+"/items",null,b,null,200));Assert.assertEquals(before,modelCalls.get());
        // Inject the durable boundary after Product consumed PAUSE but before ACS persisted its result/receipt.
        Assert.assertNotNull(com.agent.product.code.CodeAgent.observeControlByCorrelation(new java.io.File(beforeConsumption.productWorkspaceRoot),rid,"pause-one"));
        Assert.assertNotNull(com.agent.product.code.CodeAgent.observedHumanPauseCheckpoint(new java.io.File(beforeConsumption.productWorkspaceRoot),applied.path("consumptionProof").path("productRunId").asText(),"pause-one"));
        String oldClient=b;String host=factsHost(sid);
        server.close();services.tasks.close();authority.write("runs/"+rid+"/state.json",beforeConsumption);
        com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase recoveryDb=new com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase(root.resolve("state"));
        recoveryDb.inTransaction(c->{try(java.sql.PreparedStatement q=c.prepareStatement("UPDATE governance_command_receipts SET status='PENDING',result_json=?,receipt_json=?,command_revision=? WHERE command_id='pause-one'")){
            q.setString(1,pause.toString());q.setString(2,pause.toString());q.setLong(3,Long.parseLong(pause.path("receiptRevision").asText()));q.executeUpdate();}return null;});recoveryDb.close();
        services=BusinessServiceFactory.createGui(config);server=new BusinessHttpServer(new InetSocketAddress("127.0.0.1",0),bearer,services.tasks,mapper,null,null,services.v2);server.start();
        java.lang.reflect.Field socket=BusinessHttpServer.class.getDeclaredField("server");socket.setAccessible(true);port=((HttpServer)socket.get(server)).getAddress().getPort();
        api("GET","/api/v2/sessions/"+sid+"/foundation",null,oldClient,null,401);a=register("WEB_TAB");b=register("CLI");
        Assert.assertEquals(host,factsHost(sid));Assert.assertEquals("PAUSED_FOR_HUMAN",api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText());
        Assert.assertEquals("APPLIED",api("GET","/api/v2/runs/"+rid+"/commands/pause-one",null,b,null,200).path("phase").asText());
        Assert.assertEquals(before,modelCalls.get());Assert.assertEquals(frozen,api("GET",frozenPath+"/items",null,b,null,200));

        run=api("GET","/api/v2/runs/"+rid,null,b,null,200);
        // SQL ACCEPTED is durable, but the process exits before the Run marker/dispatch window.
        com.sage.business.code.contract.v2.run.RunCommandRequest resumeRequest=new com.sage.business.code.contract.v2.run.RunCommandRequest();resumeRequest.setSchemaVersion("run-command/1");resumeRequest.setCommandId("resume-one");resumeRequest.setIntent("RESUME");resumeRequest.setExpectedStateVersion(run.path("stateVersion").asLong());
        com.sage.business.code.contract.v2.run.RunCommandReceipt intent=new com.sage.business.code.contract.v2.run.RunCommandReceipt();intent.setCommandId("resume-one");intent.setRunId(rid);intent.setSessionId(sid);intent.setIntent("RESUME");intent.setExpectedStateVersion(resumeRequest.getExpectedStateVersion());intent.setAcceptedStateVersion(resumeRequest.getExpectedStateVersion());intent.setObservedStateVersion(resumeRequest.getExpectedStateVersion());intent.setObservedRunState("PAUSED_FOR_HUMAN");intent.setPhase("ACCEPTED");intent.setReceiptRevision("1");intent.setAcceptedAt(java.time.Instant.now().toString());intent.setReceiptId(com.sage.business.code.application.operation.GovernanceCommandReceiptStore.receiptId("local-owner","run.control",rid,"resume-one"));
        Map<String,String> actor=new LinkedHashMap<>();actor.put("principalId","local-owner");actor.put("clientSessionId",clientIds.get(b));intent.setActor(actor);
        server.close();services.tasks.close();
        recoveryDb=new com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase(root.resolve("state"));
        new com.sage.business.code.application.run.RunControlCommandStore(recoveryDb,BusinessServiceFactory.mapper()).accept("local-owner",com.sage.business.code.infrastructure.Hashing.sha256(rid+"\n"+BusinessServiceFactory.mapper().writeValueAsString(resumeRequest)),intent);recoveryDb.close();
        services=BusinessServiceFactory.createGui(config);server=new BusinessHttpServer(new InetSocketAddress("127.0.0.1",0),bearer,services.tasks,mapper,null,null,services.v2);server.start();socket.setAccessible(true);port=((HttpServer)socket.get(server)).getAddress().getPort();a=register("WEB_TAB");b=register("CLI");
        await(()->modelCalls.get()>=2,"Known-undispatched RESUME did not enter Product after restart");
        JsonNode resumed=api("GET","/api/v2/runs/"+rid+"/commands/resume-one",null,a,null,200);Assert.assertEquals("APPLIED",resumed.path("phase").asText());
        Assert.assertEquals(applied.path("consumptionProof").path("productRunId"),resumed.path("consumptionProof").path("productRunId"));
        for(String client:Arrays.asList(a,b)){
            HttpURLConnection slow=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/v2/sessions/"+sid+"/events").openConnection();
            slow.setRequestProperty("Authorization","Bearer "+bearer);slow.setRequestProperty("X-CodeAgent-Client-Token",client);slow.setReadTimeout(5000);
            Assert.assertEquals(200,slow.getResponseCode());slowStreams.add(slow);
        }
        long stopStart=System.nanoTime();command(rid,"stop-one","STOP",-1,a);Assert.assertTrue("STOP admission exceeded 1s",System.nanoTime()-stopStart<1000000000L);
        releaseModel.release();await(()->api("GET","/api/v2/runs/"+rid+"/commands/stop-one",null,b,null,200).path("terminal").asBoolean(),"STOP did not settle");
        Assert.assertEquals(applied.path("consumptionProof"),api("GET","/api/v2/runs/"+rid+"/commands/pause-one",null,b,null,200).path("consumptionProof"));
        message.put("clientMessageId","second");JsonNode rejected=api("POST","/api/v2/sessions/"+sid+"/messages","second",b,message,409);Assert.assertEquals("WORKER_SESSION_SINGLE_RUN",rejected.path("code").asText());
        api("GET",frozenPath,null,null,null,428);
        ObjectNode replay=mapper.createObjectNode().put("schemaVersion","run-command/1").put("commandId","pause-one").put("intent","PAUSE").put("expectedStateVersion",pause.path("expectedStateVersion").asLong());
        Assert.assertEquals(applied.path("receiptId"),api("POST","/api/v2/runs/"+rid+"/commands","pause-one",b,replay,200).path("receiptId"));
    }
    @Test public void ordinaryHostSessionKeepsMultiTurnAndPersistedAssistantBodiesAcrossClients()throws Exception{
        ObjectNode req=mapper.createObjectNode().put("workspaceAlias","sample").put("interactionMode","ACT").put("intent","EXPLAIN").put("executionMode","INSPECT")
                .put("autonomyLevel","OFF").put("verificationLevel","NONE").put("initialWorkspaceListing",false);
        JsonNode session=api("POST","/api/v2/sessions","ordinary-session",a,req,201);String sid=session.path("sessionId").asText();
        for(int turn=1;turn<=2;turn++){
            JsonNode current=api("GET","/api/v2/sessions/"+sid,null,b,null,200);
            ObjectNode body=mapper.createObjectNode().put("clientMessageId","ordinary-"+turn).put("content","Explain README turn "+turn).put("expectedSessionVersion",current.path("sessionVersion").asLong());body.putArray("attachments");
            JsonNode accepted=api("POST","/api/v2/sessions/"+sid+"/messages","ordinary-"+turn,a,body,202);String rid=accepted.path("runId").asText();releaseModel.release(10);
            await(()->"SUCCEEDED".equals(api("GET","/api/v2/runs/"+rid,null,b,null,200).path("sourceStatus").asText()),"Ordinary run did not complete");
        }
        JsonNode history=api("GET","/api/v2/sessions/"+sid+"/messages?limit=30",null,b,null,200);
        Assert.assertTrue(history.toString(),history.toString().contains("Explain README turn 1"));Assert.assertTrue(history.toString(),history.toString().contains("persisted fixture reply"));
        Assert.assertEquals("HOST_SESSION",api("GET","/api/v2/sessions/"+sid+"/foundation",null,b,null,200).path("executionOwnership").asText());
    }

    private String factsHost(String sid)throws Exception{return api("GET","/api/v2/sessions/"+sid+"/foundation",null,b,null,200).path("hostId").asText();}
    protected JsonNode command(String rid,String id,String intent,long version,String client)throws Exception{
        ObjectNode req=mapper.createObjectNode().put("schemaVersion","run-command/1").put("commandId",id).put("intent",intent);if(version>=0)req.put("expectedStateVersion",version);
        return api("POST","/api/v2/runs/"+rid+"/commands",id,client,req,0);
    }
    protected interface Check{boolean get()throws Exception;}
    protected void await(Check check,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(check.get())return;Thread.sleep(30);}Assert.fail(message);}
    protected JsonNode api(String method,String path,String key,String client,JsonNode body,int expected)throws Exception{
        if("PATCH".equals(method)){
            java.net.http.HttpRequest.Builder request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+bearer).header("Content-Type","application/json");
            if(client!=null)request.header("X-CodeAgent-Client-Token",client);if(key!=null)request.header("Idempotency-Key",key);
            java.net.http.HttpResponse<String> response=java.net.http.HttpClient.newHttpClient().send(request.method(method,java.net.http.HttpRequest.BodyPublishers.ofString(body==null?"{}":body.toString())).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode value=mapper.readTree(response.body());Assert.assertEquals(value.toString(),expected,response.statusCode());return value;
        }
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(15000);
        c.setRequestProperty("Authorization","Bearer "+bearer);if(client!=null)c.setRequestProperty("X-CodeAgent-Client-Token",client);if(key!=null)c.setRequestProperty("Idempotency-Key",key);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){mapper.writeValue(out,body);}}
        int status=c.getResponseCode();JsonNode value;try(InputStream in=status>=400?c.getErrorStream():c.getInputStream()){value=mapper.readTree(in);}c.disconnect();
        if(expected==0)Assert.assertTrue(value.toString(),status>=200&&status<300);else Assert.assertEquals(value.toString(),expected,status);return value;
    }
    private void git(String... args)throws Exception{List<String> cmd=new ArrayList<>();cmd.add("git");cmd.add("-C");cmd.add(workspace.toString());Collections.addAll(cmd,args);Process process=new ProcessBuilder(cmd).redirectErrorStream(true).start();while(process.getInputStream().read()!=-1){}Assert.assertEquals(0,process.waitFor());}
}
