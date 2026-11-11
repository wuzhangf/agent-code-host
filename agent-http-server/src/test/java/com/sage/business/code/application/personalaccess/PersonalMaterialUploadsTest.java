package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * §11.2 native project material-upload profile: create binding (taskId or
 * launchScope createKey), bounded chunks with verified digests, seal, delete
 * and OBSERVE reads — all through the durable ledger, plus materials[] message
 * reference validation.
 */
public class PersonalMaterialUploadsTest extends PersonalLocalTaskAccessTest {
    AtomicInteger uploadPosts=new AtomicInteger(),chunkPuts=new AtomicInteger();
    ConcurrentMap<String,ObjectNode> uploads=new ConcurrentHashMap<String,ObjectNode>();

    @Override void handle(HttpExchange x){try{
        String path=x.getRequestURI().getPath().substring("/local/v1".length()),method=x.getRequestMethod();
        if(!(path.equals("/tasks/native-one")&&method.equals("GET")||path.startsWith("/projects/"))){super.handle(x);return;}
        if(!("Bearer "+secret).equals(x.getRequestHeaders().getFirst("Authorization"))){send(x,401,json.createObjectNode());return;}
        JsonNode body=(method.equals("POST")||method.equals("PUT"))&&!path.contains("/chunks/")?json.readTree(x.getRequestBody()):null;
        if(path.equals("/tasks/native-one")&&method.equals("GET")){
            ObjectNode out=json.createObjectNode().put("taskId","native-one").put("projectId","project-one").put("engineId",engineName).put("storeId",backend).put("state",nativeState).put("generation","g1").put("nativeSessionId","native-session").put("currentCommandId","command-one").put("workspace","private-workspace");
            out.putObject("capabilities").put("stop",true).put("pause",pauseAvailable).put("resume",resumeAvailable).put("steer","RUNNING".equals(nativeState)).put("followUp","SUCCEEDED".equals(nativeState));send(x,200,out);return;
        }
        if(path.equals("/projects/project-one/material-uploads")&&method.equals("POST")){
            uploadPosts.incrementAndGet();
            ObjectNode upload=json.createObjectNode().put("uploadKey","mat-"+uploadPosts.get()).put("requestKey",body.path("requestKey").asText()).set("target",body.path("target"));
            upload.put("state","OPEN").put("nextOffset",0).put("displayName",body.path("displayName").asText()).put("mediaType",body.path("mediaType").asText()).put("sizeBytes",body.path("sizeBytes").asLong()).put("sha256",body.path("sha256").asText());
            uploads.put(upload.path("uploadKey").asText(),upload);
            inputs.put(body.path("requestKey").asText(),upload);send(x,202,upload);return;
        }
        if(path.startsWith("/projects/project-one/material-uploads/")){
            String[] seg=path.split("/");String uploadKey=seg[4];
            if(seg.length==5&&method.equals("GET")){
                if(uploadKey.equals("mat-foreign")){send(x,200,json.createObjectNode().put("uploadKey","mat-foreign").put("state","OPEN").putObject("target").put("taskId","other-task"));return;}
                ObjectNode upload=uploads.get(uploadKey);send(x,upload==null?404:200,upload==null?json.createObjectNode().put("code","NOT_FOUND"):upload);return;
            }
            ObjectNode upload=uploads.get(uploadKey);
            if(upload==null){send(x,404,json.createObjectNode().put("code","NOT_FOUND"));return;}
            if(seg.length==7&&seg[5].equals("chunks")&&method.equals("PUT")){
                // Engine wire: raw chunk bytes; digest via ?digest= (Codex)
                // or the x-chunk-sha256 header (Pi). Verify before accepting.
                chunkPuts.incrementAndGet();
                byte[] raw=x.getRequestBody().readAllBytes();
                String query=x.getRequestURI().getQuery();
                String digest=query!=null&&query.startsWith("digest=")?query.substring(7):x.getRequestHeaders().getFirst("x-chunk-sha256");
                if(digest==null||!PersonalAccessService.hashBytes(raw).equals(digest)){send(x,409,json.createObjectNode().put("code","MATERIAL_CHUNK_DIGEST_MISMATCH"));return;}
                ObjectNode result=upload.deepCopy().put("nextOffset",Long.parseLong(seg[6])+raw.length);inputs.put(x.getRequestHeaders().getFirst("Idempotency-Key"),result);send(x,202,result);return;
            }
            if(seg.length==6&&seg[5].equals("seal")&&method.equals("POST")){
                ObjectNode result=upload.deepCopy().put("state","SEALED").put("materialRef","mat-"+uploadKey);inputs.put(body.path("requestKey").asText(),result);send(x,202,result);return;
            }
            if(seg.length==5&&method.equals("DELETE")){
                ObjectNode result=upload.deepCopy().put("state","CANCELLED");inputs.put(x.getRequestHeaders().getFirst("Idempotency-Key"),result);send(x,202,result);return;
            }
        }
        super.handle(x);
    }catch(Exception e){e.printStackTrace();send(x,500,json.createObjectNode().put("code","FIXTURE_FAILURE"));}}

    ObjectNode create(String key,String targetField,String targetValue){
        ObjectNode body=json.createObjectNode().put("requestKey",key).put("displayName","note.txt").put("mediaType","text/plain").put("sizeBytes",16).put("sha256","a".repeat(64));
        body.putObject("target").put(targetField,targetValue);return body;
    }
    ObjectNode chunk(String key,String data){byte[] bytes=data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return json.createObjectNode().put("requestKey",key).put("chunkDigest",PersonalAccessService.hashBytes(bytes)).put("dataBase64",Base64.getEncoder().encodeToString(bytes));
    }

    @Test public void taskBoundUploadFlowChunksSealReadAndDelete()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        JsonNode created=call("POST","/projects/project-one/material-uploads",create("mat-create","taskId","native-one"),"mat-create").body;
        assertEquals("OBSERVED",created.path("state").asText());
        String uploadKey=created.path("source").path("uploadKey").asText();assertTrue(uploadKey.startsWith("mat-"));
        call("POST","/projects/project-one/material-uploads",create("mat-create","taskId","native-one"),"mat-create");assertEquals(1,uploadPosts.get());
        denied("MATERIAL_CHUNK_DIGEST_MISMATCH",()->call("PUT","/projects/project-one/material-uploads/"+uploadKey+"/chunks/0",chunk("bad","data").put("chunkDigest","f".repeat(64)),"bad"));
        assertEquals("OBSERVED",call("PUT","/projects/project-one/material-uploads/"+uploadKey+"/chunks/0",chunk("c0","chunk-zero"),"c0").body.path("state").asText());
        // Lost-reply recovery replays the stored bytes; upstream dedupes the
        // raw chunk by offset+digest so a re-PUT stays a safe no-op.
        assertEquals("OBSERVED",call("PUT","/projects/project-one/material-uploads/"+uploadKey+"/chunks/0",chunk("c0","chunk-zero"),"c0").body.path("state").asText());
        assertEquals(2,chunkPuts.get());
        JsonNode sealed=call("POST","/projects/project-one/material-uploads/"+uploadKey+"/seal",json.createObjectNode().put("requestKey","seal").put("sha256","a".repeat(64)),"seal").body;
        assertEquals("SEALED",sealed.path("source").path("state").asText());
        JsonNode read=callq("/projects/project-one/material-uploads/"+uploadKey,null).body;
        assertEquals(uploadKey,read.path("uploadKey").asText());assertEquals("native-one",read.path("target").path("taskId").asText());
        denied("LOCAL_BINDING_MISMATCH",()->callq("/projects/project-one/material-uploads/mat-foreign",null));
        JsonNode deleted=call("DELETE","/projects/project-one/material-uploads/"+uploadKey,null,"del-key").body;
        assertEquals("MATERIAL_UPLOAD_CANCEL",deleted.path("operation").asText());
    }
    @Test public void createKeyBindsLaunchScopeAndInteract()throws Exception {
        assertEquals("OBSERVED",call("POST","/projects/project-one/material-uploads",create("ck","createKey","future-task-key"),"ck").body.path("state").asText());
        PersonalAccessService.Device d=auth.authenticate(token);
        auth.updateScopeResources(did,"s1",Long.toString(d.revision),Collections.emptyList(),
            Arrays.asList(new PersonalResourceScope.LaunchScope(backend,"project-one","pi")),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH")));
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/projects/project-one/material-uploads",create("ck2","createKey","another"),"ck2"));
    }
    @Test public void materialShapeAndOwnershipValidation()throws Exception {
        call("POST","/tasks",launch(),"original-launch");
        denied("INVALID_REQUEST",()->call("POST","/projects/project-one/material-uploads",create("m1","taskId","native-one").put("displayName","../escape"),"m1"));
        denied("MATERIAL_MEDIA_UNSUPPORTED",()->call("POST","/projects/project-one/material-uploads",create("m2","taskId","native-one").put("mediaType","application/pdf"),"m2"));
        denied("MATERIAL_SIZE_EXCEEDED",()->call("POST","/projects/project-one/material-uploads",create("m3","taskId","native-one").put("sizeBytes",2097152),"m3"));
        denied("INVALID_REQUEST",()->call("POST","/projects/project-one/material-uploads",create("m4","taskId","native-one").putObject("target").put("taskId","native-one").put("createKey","both"),"m4"));
        denied("DEVICE_SCOPE_DENIED",()->call("POST","/projects/project-one/material-uploads",create("m5","taskId","other-project-task"),"m5"));
        assertEquals(0,uploadPosts.get());
    }
    @Test public void messageMaterialsValidatedAndForwarded()throws Exception {
        call("POST","/tasks",launch(),"original-launch");nativeState="RUNNING";
        ObjectNode body=json.createObjectNode().put("requestKey","msg-mat").put("kind","NATIVE_STEER").put("content","go").put("generation","g1").put("nativeSessionId","native-session").put("targetCommandId","command-one");
        ArrayNode materials=body.putArray("materials");
        materials.addObject().put("materialRef","mat-1").put("sha256","a".repeat(64)).put("role","IMAGE");
        JsonNode sent=call("POST","/tasks/native-one/messages",body,"msg-mat").body;
        assertEquals("OBSERVED",sent.path("state").asText());
        assertTrue(inputs.keySet().iterator().next().startsWith("mobile-"));
        ObjectNode bad=body.deepCopy().put("requestKey","bad-role");bad.putArray("materials").addObject().put("materialRef","mat-1").put("sha256","a".repeat(64)).put("role","VIDEO");
        denied("INVALID_REQUEST",()->call("POST","/tasks/native-one/messages",bad,"bad-role"));
        ObjectNode foreign=body.deepCopy().put("requestKey","foreign-ref");foreign.putArray("materials").addObject().put("materialRef","native-other-task").put("sha256","a".repeat(64)).put("role","IMAGE");
        denied("MATERIAL_OWNERSHIP_MISMATCH",()->call("POST","/tasks/native-one/messages",foreign,"foreign-ref"));
        ObjectNode missing=body.deepCopy().put("requestKey","miss-field");missing.putArray("materials").addObject().put("materialRef","mat-1").put("role","IMAGE");
        denied("INVALID_REQUEST",()->call("POST","/tasks/native-one/messages",missing,"miss-field"));
    }
}
