package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.*;
import static org.junit.Assert.*;

/** Production PAC launch contract, real loopback source; no model or workspace mutations. */
public class PersonalContextOnlyLaunchTest {
    PersonalLocalTaskAccessTest f;boolean supported=true,changed=false,nullSession=false;JsonNode dispatched;
    @Before public void open()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();f.host.removeContext("/local/v1/");f.host.createContext("/local/v1/",exchange->{try{
            if(exchange.getRequestURI().getPath().equals("/local/v1/task-preflights")) {
                ObjectNode out=f.json.createObjectNode().put("projectId","project-one").put("engineId","pi").put("optionsRevision",changed?"changed-options":"frozen-options");
                if(supported)out.putArray("executionModes").add("WORKSPACE_TASK").add("CONTEXT_ONLY");f.send(exchange,200,out);return;
            }
            if(exchange.getRequestURI().getPath().equals("/local/v1/tasks")&&exchange.getRequestMethod().equals("POST")){
                dispatched=f.json.readTree(exchange.getRequestBody());f.launchPosts.incrementAndGet();ObjectNode result=f.json.createObjectNode().put("taskId","native-one").put("engineId","pi");f.launches.put(dispatched.path("requestKey").asText(),result);f.send(exchange,202,result);return;
            }
            if(nullSession&&exchange.getRequestURI().getPath().equals("/local/v1/tasks/native-one")&&exchange.getRequestMethod().equals("GET")) {
                ObjectNode out=f.json.createObjectNode().put("taskId","native-one").put("engineId","pi").put("generation","g1").put("currentCommandId","command-one").putNull("nativeSessionId").putNull("nativeTurnId");out.putObject("capabilities").put("stop",true);f.send(exchange,200,out);return;
            }
            f.handle(exchange);
        }catch(Exception failure){exchange.close();}});
    }
    @After public void close()throws Exception{f.cleanup();}
    @Test public void declaredContextOnlyIsForwardedAndBoundToOriginalDigest()throws Exception{
        ObjectNode body=f.launch().put("executionMode","CONTEXT_ONLY");f.call("POST","/tasks",body,"original-launch");assertEquals(1,f.launchPosts.get());assertEquals("CONTEXT_ONLY",dispatched.path("executionMode").asText());assertTrue(dispatched.path("requestKey").asText().startsWith("mobile-"));
        f.denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->f.call("POST","/tasks",f.launch(),"original-launch"));assertEquals(1,f.launchPosts.get());
    }
    @Test public void sourceWithoutExplicitModeDeclarationCannotBeSilentlyWritable()throws Exception{supported=false;f.denied("CONTEXT_ONLY_UNSUPPORTED",()->f.call("POST","/tasks",f.launch().put("executionMode","CONTEXT_ONLY"),"original-launch"));assertEquals(0,f.launchPosts.get());}
    @Test public void changedPreflightCannotDispatchContextOnly()throws Exception{changed=true;f.denied("PREFLIGHT_CHANGED",()->f.call("POST","/tasks",f.launch().put("executionMode","CONTEXT_ONLY"),"original-launch"));assertEquals(0,f.launchPosts.get());}
    @Test public void arbitraryModesRemainOutsidePACContract()throws Exception{f.denied("EXECUTION_MODE_NOT_ALLOWED",()->f.call("POST","/tasks",f.launch().put("executionMode","WORKSPACE_TASK"),"original-launch"));assertEquals(0,f.launchPosts.get());}
    private ObjectNode stop(String key){return f.json.createObjectNode().put("requestKey",key).put("intent","STOP").put("generation","g1").put("nativeSessionId","native-session").putNull("nativeTurnId").put("targetCommandId","command-one");}
    @Test public void explicitNullIdleTurnIsNotMistakenForMissingAuthority()throws Exception{f.call("POST","/tasks",f.launch(),"original-launch");assertEquals("OBSERVED",f.call("POST","/tasks/native-one/commands",stop("original-stop"),"original-stop").body.path("state").asText());assertEquals(1,f.inputPosts.get());}
    @Test public void queuedNullSessionStopStillRequiresExactSourceGuard()throws Exception{f.call("POST","/tasks",f.launch(),"original-launch");f.denied("NATIVE_TARGET_CHANGED",()->f.call("POST","/tasks/native-one/commands",stop("wrong-null-stop").putNull("nativeSessionId"),"wrong-null-stop"));assertEquals(0,f.inputPosts.get());nullSession=true;assertEquals("OBSERVED",f.call("POST","/tasks/native-one/commands",stop("original-queued-stop").putNull("nativeSessionId"),"original-queued-stop").body.path("state").asText());assertEquals(1,f.inputPosts.get());}
}
