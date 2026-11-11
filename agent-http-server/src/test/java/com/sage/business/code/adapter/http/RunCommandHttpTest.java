package com.sage.business.code.adapter.http;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.application.*;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.application.operation.GovernanceCommandReceiptStore;
import com.sage.business.code.application.run.*;
import com.sage.business.code.domain.*;
import com.sage.business.code.infrastructure.*;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

/** Two independent registered clients through production dispatcher and HTTP handlers. */
public class RunCommandHttpTest {
    private Path root;
    private TaskApplicationService tasks;
    private ControlPlaneDatabase database;
    private HttpServer server;
    private ExecutorService pool;
    private ClientSessionRegistry clients;
    private RunControlCoordinator commands;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String bearer = UUID.randomUUID().toString();
    private final AtomicInteger dispatches = new AtomicInteger();
    private JsonNode a,b;

    @Before public void setup() throws Exception {
        root=Files.createTempDirectory("run-command-http-");
        database=new ControlPlaneDatabase(root.resolve("control"));
        AtomicJsonStore json=new AtomicJsonStore(root.resolve("state"),mapper);
        WorkspaceRegistry workspaces=new WorkspaceRegistry(Collections.<String,String>emptyMap());
        tasks=new TaskApplicationService(json,new TaskCanonicalizer(workspaces,mapper),workspaces,
            new BusinessCodeExecutor() {
                public BusinessExecutionResult execute(ApprovedBusinessTask t,BusinessRunContext c) { throw new AssertionError(); }
                public boolean control(String r,boolean pause,String commandId) { dispatches.incrementAndGet(); return true; }
            },mapper,root.resolve("locks"));
        RunRecord run=new RunRecord(); run.runId="run-one"; run.sessionId="session-one"; run.status=RunStatus.RUNNING; run.stateVersion=17;
        json.write("runs/run-one/state.json",run);
        commands=new RunControlCoordinator(tasks,new RunControlCommandStore(database,mapper),mapper);
        tasks.setControlCoordinator(commands);
        clients=new ClientSessionRegistry("test-store","boot-one");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        pool=Executors.newFixedThreadPool(4); server.setExecutor(pool);
        server.createContext("/api/v2",new V2ApiDispatcher(mapper,Arrays.<V2RouteGroup>asList(
            new ClientSessionApiHandler(mapper,bearer,clients),
            new RunCommandApiHandler(mapper,bearer,commands,true),
            new OperationReceiptApiHandler(mapper,bearer,new GovernanceCommandReceiptStore(database,mapper))),clients,false));
        server.start(); a=register("a"); b=register("b");
        Assert.assertNotEquals(a.path("clientSessionId").asText(),b.path("clientSessionId").asText());
    }
    @After public void cleanup() throws Exception {
        if(server!=null)server.stop(0); if(pool!=null)pool.shutdownNow(); if(tasks!=null)tasks.close(); if(database!=null)database.close();
        if(root!=null)try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});
        }
    }
    private JsonNode register(String label) throws Exception {
        String key=UUID.randomUUID().toString();
        return http("POST","/api/v2/client-sessions",key,null,bearer,
            "{\"registrationKey\":\""+key+"\",\"clientId\":\""+UUID.randomUUID().toString()+"\",\"processInstanceId\":\""+UUID.randomUUID().toString()
            +"\",\"clientKind\":\"CLI\",\"protocols\":[\"v2\"],\"displayLabel\":\""+label+"\"}",201);
    }
    private String body(String id) { return "{\"schemaVersion\":\"run-command/1\",\"commandId\":\""+id+"\",\"intent\":\"PAUSE\",\"expectedStateVersion\":17}"; }
    private String token(JsonNode client) { return client.path("clientSessionToken").asText(); }

    @Test public void otherClientReplaysAndReadsSameDurableCommandAndOperation() throws Exception {
        JsonNode first=http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one"),202);
        JsonNode replay=http("POST","/api/v2/runs/run-one/commands","pause-one",token(b),bearer,body("pause-one"),202);
        Assert.assertEquals(first.path("receiptId"),replay.path("receiptId"));
        Assert.assertEquals(a.path("clientSessionId"),replay.path("actor").path("clientSessionId"));
        JsonNode get=http("GET","/api/v2/runs/run-one/commands/pause-one",null,token(b),bearer,null,200);
        Assert.assertEquals("REQUESTED",get.path("phase").asText()); Assert.assertFalse(get.path("terminal").asBoolean());
        JsonNode op=http("GET","/api/v2/operations/"+first.path("receiptId").asText(),null,token(b),bearer,null,200);
        Assert.assertEquals("run.control",op.path("operation").asText());
        Assert.assertEquals(get,op.path("result")); Assert.assertEquals(1,dispatches.get());
    }
    @Test public void wrongBearerMissingRegistrationAndRevokedTokenCannotReadCommand() throws Exception {
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one"),202);
        http("GET","/api/v2/runs/run-one/commands/pause-one",null,token(b),"invalid",null,401);
        http("GET","/api/v2/runs/run-one/commands/pause-one",null,null,bearer,null,428);
        clients.revoke("local-owner",b.path("clientSessionId").asText(),token(b));
        JsonNode failure=http("GET","/api/v2/runs/run-one/commands/pause-one",null,token(b),bearer,null,401);
        Assert.assertFalse(failure.has("receiptId"));
    }
    @Test public void strictBodyAndSafeIntegerBoundaryRejectBeforeDispatch() throws Exception {
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one").replace("17}","17.5}"),400);
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one").replace("17}","9007199254740992}"),400);
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one").replace("17}","17,\"actor\":\"spoof\"}"),400);
        http("POST","/api/v2/runs/run-one/commands","42",token(a),bearer,body("42").replace("\"commandId\":\"42\"","\"commandId\":42"),400);
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one").replace("17}","17,\"reasonCode\":123}"),400);
        Assert.assertEquals(0,dispatches.get());
    }
    @Test public void disablingAdmissionRetainsQueriesAndStopForExistingIntent() throws Exception {
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(a),bearer,body("pause-one"),202);
        server.removeContext("/api/v2");
        server.createContext("/api/v2",new V2ApiDispatcher(mapper,Collections.<V2RouteGroup>singletonList(
                new RunCommandApiHandler(mapper,bearer,commands,false)),clients,false));
        http("GET","/api/v2/runs/run-one/commands/pause-one",null,token(b),bearer,null,200);
        http("POST","/api/v2/runs/run-one/commands","pause-one",token(b),bearer,body("pause-one"),202);
        http("POST","/api/v2/runs/run-one/commands","new-pause",token(a),bearer,body("new-pause"),409);
        String stop=body("stop-one").replace("PAUSE","STOP");
        http("POST","/api/v2/runs/run-one/commands","stop-one",token(b),bearer,stop,202);
        Assert.assertEquals(2,dispatches.get());
    }

    @Test public void disablingAdmissionStillStopsRunWithoutAnyEarlierFoundationCommand()throws Exception{
        server.removeContext("/api/v2");server.createContext("/api/v2",new V2ApiDispatcher(mapper,Collections.<V2RouteGroup>singletonList(
                new RunCommandApiHandler(mapper,bearer,commands,false)),clients,false));
        http("POST","/api/v2/runs/run-one/commands","first-stop",token(b),bearer,body("first-stop").replace("PAUSE","STOP"),202);
        Assert.assertEquals(1,dispatches.get());
    }

    private JsonNode http(String method,String path,String key,String client,String auth,String body,int expected) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getAddress().getPort()+path).openConnection();
        connection.setRequestMethod(method); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        if(auth!=null)connection.setRequestProperty("Authorization","Bearer "+auth);
        if(client!=null)connection.setRequestProperty(ClientSessionApiHandler.CLIENT_TOKEN_HEADER,client);
        if(key!=null)connection.setRequestProperty("Idempotency-Key",key);
        if(body!=null) {connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");try(OutputStream out=connection.getOutputStream()){out.write(body.getBytes("UTF-8"));}}
        int status=connection.getResponseCode();
        JsonNode value; try(InputStream input=status>=400?connection.getErrorStream():connection.getInputStream()){value=mapper.readTree(input);}
        connection.disconnect(); Assert.assertEquals(value==null?"empty response":value.toString(),expected,status); return value;
    }
}
