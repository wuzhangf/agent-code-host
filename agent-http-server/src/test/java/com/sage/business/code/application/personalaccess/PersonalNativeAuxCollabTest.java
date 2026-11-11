package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sun.net.httpserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * §5 native routing family: context packages, auxiliary sessions,
 * collaborations/preflight/previews/adoptions/children and handoff export.
 * Contract-shaped loopback stubs only; grant mapping, strict whitelists,
 * durable idempotency and reverse binding are asserted at the PAC boundary.
 */
public class PersonalNativeAuxCollabTest extends PersonalLocalTaskAccessTest {
    AtomicInteger collabPosts=new AtomicInteger(),preflightPosts=new AtomicInteger(),auxPosts=new AtomicInteger(),previewPosts=new AtomicInteger(),adoptPosts=new AtomicInteger(),handoffReads=new AtomicInteger();
    volatile boolean wrongAuxBinding,wrongChildBinding;

    @Override void handle(HttpExchange x){try{
        String path=x.getRequestURI().getPath().substring("/local/v1".length()),method=x.getRequestMethod();
        // New-family paths only; every other path delegates before the body stream is consumed.
        if(!(path.startsWith("/tasks/native-one/context-packages")||path.startsWith("/tasks/native-one/auxiliary-sessions")
                ||path.startsWith("/tasks/native-one/collaborations")||path.startsWith("/tasks/native-one/handoff-export"))){super.handle(x);return;}
        if(!("Bearer "+secret).equals(x.getRequestHeaders().getFirst("Authorization"))){send(x,401,json.createObjectNode());return;}
        JsonNode body=(method.equals("POST")||method.equals("PUT"))?json.readTree(x.getRequestBody()):null;
        if(path.equals("/tasks/native-one/context-packages")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.startsWith("/tasks/native-one/context-packages/")){
            if(method.equals("GET")){send(x,200,json.createObjectNode().put("packageId",path.substring("/tasks/native-one/context-packages/".length())).put("taskId","native-one").put("state","FROZEN"));return;}
            send(x,404,json.createObjectNode().put("code","NOT_FOUND"));return;
        }
        if(path.equals("/tasks/native-one/context-packages")&&method.equals("POST")){
            ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("packageId","pkg-1").put("taskId","native-one").put("state","FROZEN");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/auxiliary-sessions")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.equals("/tasks/native-one/auxiliary-sessions")&&method.equals("POST")){
            auxPosts.incrementAndGet();ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("auxiliarySessionId","aux-1").put("taskId","native-one").put("state","ACTIVE");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/auxiliary-sessions/aux-1")&&method.equals("GET")){
            send(x,200,json.createObjectNode().put("auxiliarySessionId","aux-1").put("taskId",wrongAuxBinding?"other-task":"native-one").put("state","ACTIVE"));return;
        }
        if(path.equals("/tasks/native-one/auxiliary-sessions/aux-1/messages")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if((path.equals("/tasks/native-one/auxiliary-sessions/aux-1/stop")||path.equals("/tasks/native-one/auxiliary-sessions/aux-1/deliver"))&&method.equals("POST")){
            ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("auxiliarySessionId","aux-1").put("taskId","native-one").put("state",path.endsWith("stop")?"STOPPED":"DELIVERED");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/collaborations/preflight")&&method.equals("POST")){
            preflightPosts.incrementAndGet();ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("preflightId","pf-1").put("digest","aa22").put("expiresAt",999999);
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/collaborations")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.equals("/tasks/native-one/collaborations")&&method.equals("POST")){
            collabPosts.incrementAndGet();ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("collaborationId","grp-1").put("taskId","native-one").put("state","ACTIVE");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/collaborations/grp-1")&&method.equals("GET")){
            send(x,200,json.createObjectNode().put("collaborationId","grp-1").put("taskId","native-one").put("state","ACTIVE"));return;
        }
        if(path.equals("/tasks/native-one/collaborations/grp-1/stop")&&method.equals("POST")){
            ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("collaborationId","grp-1").put("taskId","native-one").put("state","STOPPED");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/collaborations/grp-1/previews")&&method.equals("POST")){
            previewPosts.incrementAndGet();ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("previewId","pv-1").put("collaborationId","grp-1").put("taskId","native-one").put("state","READY");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/collaborations/grp-1/previews/pv-1")&&method.equals("GET")){
            send(x,200,json.createObjectNode().put("previewId","pv-1").put("taskId","native-one").put("groupId","grp-1").put("state","READY"));return;
        }
        if(path.equals("/tasks/native-one/collaborations/grp-1/adoptions")&&method.equals("POST")){
            adoptPosts.incrementAndGet();ObjectNode result=json.createObjectNode().put("requestKey",body.path("requestKey").asText()).put("adoptionId","ad-1").put("collaborationId","grp-1").put("taskId","native-one").put("state","APPLIED");
            inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
        }
        if(path.equals("/tasks/native-one/collaborations/grp-1/adoptions")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        if(path.equals("/tasks/native-one/collaborations/grp-1/adoptions/ad-1")&&method.equals("GET")){
            send(x,200,json.createObjectNode().put("adoptionId","ad-1").put("taskId","native-one").put("groupId","grp-1").put("state","APPLIED"));return;
        }
        if(path.startsWith("/tasks/native-one/collaborations/grp-1/children/")){
            String[] seg=path.split("/");String child=seg[6];
            if(seg.length==7&&method.equals("GET")){send(x,200,json.createObjectNode().put("childId",child).put("groupId",wrongChildBinding?"grp-other":"grp-1").put("taskId","native-one"));return;}
            if(seg.length==8&&seg[7].equals("messages")&&method.equals("GET")){send(x,200,json.createObjectNode().set("items",json.createArrayNode()));return;}
        }
        if(path.equals("/tasks/native-one/handoff-export")&&method.equals("GET")){
            handoffReads.incrementAndGet();send(x,200,json.createObjectNode().put("taskId","native-one").put("exportId","hx-1").put("state","READY").set("parts",json.createArrayNode()));return;
        }
        if(path.startsWith("/tasks/native-one/handoff-export/")&&path.contains("/chunks/")&&method.equals("GET")){
            handoffReads.incrementAndGet();send(x,200,json.createObjectNode().put("bytes","aGk=").put("nextOffset",1));return;
        }
        super.handle(x);
    }catch(Exception e){e.printStackTrace();send(x,500,json.createObjectNode().put("code","FIXTURE_FAILURE"));}}

    void rescope(String... actions)throws Exception {
        PersonalAccessService.Device d=auth.authenticate(token);
        auth.updateScopeResources(did,"rescope-"+UUID.randomUUID(),Long.toString(d.revision),
            Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",backend,"native-one")),
            Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),
            new TreeSet<String>(Arrays.asList(actions)));
    }
    void full()throws Exception{rescope("OBSERVE","LAUNCH","CONTROL","INTERACT","APPROVE","COLLABORATE","ADOPT","HANDOFF");}
    ObjectNode collab(String key){return json.createObjectNode().put("requestKey",key).put("objective","coordinate").put("expectedPreflightId","pf-1");}
    ObjectNode aux(String key){return json.createObjectNode().put("requestKey",key).put("question","why?");}

    @Test public void auxiliaryCreateDeliverStopUseLedgerAndOriginalKeyRecovery()throws Exception {
        call("POST","/tasks",launch(),"original-launch");full();
        JsonNode created=call("POST","/tasks/native-one/auxiliary-sessions",aux("aux-create"),"aux-create").body;
        assertEquals("OBSERVED",created.path("state").asText());assertEquals("aux-1",created.path("source").path("auxiliarySessionId").asText());
        call("POST","/tasks/native-one/auxiliary-sessions",aux("aux-create"),"aux-create");assertEquals(1,auxPosts.get());
        ObjectNode deliver=json.createObjectNode().put("requestKey","aux-deliver").put("expectedAnswerDigest","a".repeat(64));
        JsonNode delivered=call("POST","/tasks/native-one/auxiliary-sessions/aux-1/deliver",deliver,"aux-deliver").body;
        assertEquals("DELIVERED",delivered.path("source").path("state").asText());
        assertEquals("OBSERVED",call("GET","/tasks/native-one/requests/aux-deliver",null,null).body.path("state").asText());
        JsonNode stopped=call("POST","/tasks/native-one/auxiliary-sessions/aux-1/stop",json.createObjectNode().put("requestKey","aux-stop"),"aux-stop").body;
        assertEquals("STOPPED",stopped.path("source").path("state").asText());
        assertEquals("aux-1",stopped.path("subjectId").asText());
    }
    @Test public void controlGrantRequiredForAuxAndCollabStop()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        rescope("OBSERVE","LAUNCH","INTERACT","COLLABORATE");
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/tasks/native-one/auxiliary-sessions/aux-1/stop",json.createObjectNode().put("requestKey","aux-stop"),"aux-stop"));
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/tasks/native-one/collaborations/grp-1/stop",json.createObjectNode().put("requestKey","grp-stop"),"grp-stop"));
        rescope("OBSERVE","LAUNCH","CONTROL","INTERACT","COLLABORATE");
        assertEquals("OBSERVED",call("POST","/tasks/native-one/collaborations/grp-1/stop",json.createObjectNode().put("requestKey","grp-stop"),"grp-stop").body.path("state").asText());
    }
    @Test public void collaborateAdoptAndHandoffGrantsAreEnforcedSeparately()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        rescope("OBSERVE","LAUNCH","CONTROL","INTERACT","ADOPT","HANDOFF");
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/tasks/native-one/collaborations",collab("c1"),"c1"));
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/tasks/native-one/collaborations/preflight",json.createObjectNode().put("requestKey","pf"),"pf"));
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/tasks/native-one/collaborations/grp-1/previews",json.createObjectNode().put("requestKey","pv"),"pv"));
        assertEquals(0,collabPosts.get());assertEquals(0,preflightPosts.get());assertEquals(0,previewPosts.get());
        full();
        JsonNode preflight=call("POST","/tasks/native-one/collaborations/preflight",json.createObjectNode().put("requestKey","pf"),"pf").body;
        assertEquals("pf-1",preflight.path("source").path("preflightId").asText());assertEquals(1,preflightPosts.get());assertEquals(0,collabPosts.get());
        call("POST","/tasks/native-one/collaborations",collab("c1"),"c1");assertEquals(1,collabPosts.get());
        rescope("OBSERVE","LAUNCH","CONTROL","INTERACT","COLLABORATE","HANDOFF");
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/tasks/native-one/collaborations/grp-1/adoptions",json.createObjectNode().put("requestKey","ad").put("expectedPreviewId","pv-1").put("expectedPreviewDigest","b".repeat(64)),"ad"));
        full();assertEquals("OBSERVED",call("POST","/tasks/native-one/collaborations/grp-1/adoptions",json.createObjectNode().put("requestKey","ad").put("expectedPreviewId","pv-1").put("expectedPreviewDigest","b".repeat(64)),"ad").body.path("state").asText());
        rescope("OBSERVE","LAUNCH","CONTROL","INTERACT","COLLABORATE","ADOPT");
        denied("DEVICE_SCOPE_DENIED",()->callq("/tasks/native-one/handoff-export",null));
        denied("DEVICE_SCOPE_DENIED",()->callq("/tasks/native-one/handoff-export/part-1/chunks/"+"c".repeat(64)+"/0",null));
        full();assertEquals("hx-1",callq("/tasks/native-one/handoff-export",null).body.path("exportId").asText());
        assertEquals(200,callq("/tasks/native-one/handoff-export/part-1/chunks/"+"c".repeat(64)+"/0",null).status);
    }
    @Test public void childReadsProveReverseParentBinding()throws Exception {
        call("POST","/tasks",launch(),"original-launch");full();
        assertEquals(200,callq("/tasks/native-one/collaborations/grp-1/children/child-1/messages","limit=10").status);
        wrongChildBinding=true;
        denied("LOCAL_BINDING_MISMATCH",()->callq("/tasks/native-one/collaborations/grp-1/children/child-1/messages",null));
        wrongAuxBinding=true;
        denied("LOCAL_BINDING_MISMATCH",()->callq("/tasks/native-one/auxiliary-sessions/aux-1/messages",null));
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/auxiliary-sessions/aux-1/messages","before=5&cursor=6"));
        assertEquals(200,callq("/tasks/native-one/handoff-export","limit=1").status);
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/handoff-export","bogus=1"));
    }
    @Test public void strictWhitelistsAndIdempotentDigestsAreEnforced()throws Exception {
        call("POST","/tasks",launch(),"original-launch");full();
        denied("INVALID_REQUEST",()->call("POST","/tasks/native-one/collaborations",collab("bad").put("url","http://x"),"bad"));
        denied("INVALID_REQUEST",()->call("POST","/tasks/native-one/auxiliary-sessions",aux("bad2").put("command","rm"),"bad2"));
        denied("IDEMPOTENCY_KEY_MISMATCH",()->call("POST","/tasks/native-one/collaborations",collab("c2").put("requestKey","other"),"c2"));
        denied("INVALID_REQUEST",()->call("POST","/tasks/native-one/collaborations/grp-1/adoptions",json.createObjectNode().put("requestKey","ad2").put("expectedPreviewId","pv-1"),"ad2"));
        call("POST","/tasks/native-one/collaborations",collab("c2"),"c2");
        denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->call("POST","/tasks/native-one/collaborations",collab("c2").put("objective","changed"),"c2"));
    }
    @Test public void contextPackageCreateUsesLedgerAndReadsStayObserve()throws Exception {
        call("POST","/tasks",launch(),"original-launch");full();
        assertEquals("OBSERVED",call("POST","/tasks/native-one/context-packages",json.createObjectNode().put("requestKey","pkg"),"pkg").body.path("state").asText());
        assertEquals("pkg-1",callq("/tasks/native-one/context-packages/pkg-1",null).body.path("packageId").asText());
        assertEquals(200,callq("/tasks/native-one/context-packages","limit=10").status);
        assertEquals(200,callq("/tasks/native-one/auxiliary-sessions/aux-1/messages","before=5&limit=10").status);
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/auxiliary-sessions/aux-1/messages","before=5&cursor=6"));
        assertEquals(200,callq("/tasks/native-one/handoff-export","limit=1").status);
        denied("INVALID_REQUEST",()->callq("/tasks/native-one/handoff-export","bogus=1"));
    }
}
