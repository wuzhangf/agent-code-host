package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** PAC application service + real loopback Mac Host/file effect; no phone/device assertion. */
public final class NativeOfficeHostRoundTripMain {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode host(String origin,String path,String token,String store,JsonNode body)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(origin+path).openConnection();
        c.setRequestProperty("Authorization","Bearer "+token);if(store!=null)c.setRequestProperty("X-Worker-Local-Store",store);
        if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");if(body.has("requestKey"))c.setRequestProperty("Idempotency-Key",body.path("requestKey").asText());try(OutputStream out=c.getOutputStream()){out.write(JSON.writeValueAsBytes(body));}}
        try{assertTrue("Host request failed: "+path,c.getResponseCode()<300);try(InputStream in=c.getInputStream()){return JSON.readTree(in);}}finally{c.disconnect();}
    }
    static PersonalLocalTaskAccess.Result pac(PersonalLocalTaskAccessTest f,String method,String path,JsonNode body,String key)throws Exception {return f.access.remote(f.token,method,"/personal/v1/local-tasks/"+f.backend+path,body,key,null);}
    static ObjectNode obj(){return JSON.createObjectNode();}
    public static void main(String[] args)throws Exception {
        String origin=args[0],owner=System.getenv("OFFICE_FIXTURE_OWNER");
        PersonalLocalTaskAccessTest f=new PersonalLocalTaskAccessTest();f.setup();
        try{
            f.backend=host(origin,"/local/v1/identity",owner,null,null).path("storeId").asText();
            f.access=new PersonalLocalTaskAccess(origin,owner,f.records,f.auth,f.json);
            PersonalAccessService.Device device=f.auth.authenticate(f.token);
            f.auth.updateScopeResources(f.did,"office-real-host",Long.toString(device.revision),Collections.emptyList(),Collections.singletonList(new PersonalResourceScope.LaunchScope(f.backend,"office","macos")),new TreeSet<>(Arrays.asList("OBSERVE","LAUNCH","CONTROL")));
            JsonNode pre=pac(f,"POST","/task-preflights",obj().put("projectId","office").put("engineId","macos"),null).body;
            assertEquals("TASK_OWNED_OUTPUT",pre.path("workingCopyPolicy").asText());assertEquals(NativeOfficeContract.PROTOCOL,pre.path("macProfile").path("protocolVersion").asText());
            ObjectNode spec=obj().put("schemaVersion","mac-native-work-spec/1").put("recipeID",NativeOfficeContract.RECIPE).put("workspaceRef","office").put("goal","Prepare report").put("engine","EXTERNAL_TOOLS");
            spec.putArray("requiredCapabilities").add("mac.files.write");spec.putArray("acceptance").addObject().put("name","report.txt").put("containsText","verified report");
            ObjectNode body=obj().put("requestKey","real-office-original").put("projectId","office").put("engineId","macos").put("instruction","Prepare report");body.set("optionsRevision",pre.path("optionsRevision"));body.set("nativeWorkSpec",spec);
            JsonNode receipt=pac(f,"POST","/tasks",body,"real-office-original").body;String task=receipt.path("taskId").asText();assertTrue(task.startsWith("native-"));
            assertEquals(task,pac(f,"POST","/tasks",body,"real-office-original").body.path("taskId").asText());
            assertEquals(1,host(origin,"/local/v1/tasks",owner,f.backend,null).path("items").size());
            // Explicit owner-side external-tool fixture, not a phone permission bypass.
            String scoped=host(origin,"/local/v1/tasks/"+task+"/native-access",owner,f.backend,obj().put("scope","NATIVE_TASK_TOOLS")).path("token").asText();
            String base="/native/v1/tasks/"+task;JsonNode context=host(origin,base+"/context",scoped,f.backend,null);
            ObjectNode call=obj().put("schemaVersion","mac-native-call/1").put("requestKey","real-office-write").put("capabilityId","mac.files.write").put("capabilityVersion","1.0.0");call.set("guard",context.path("guard"));call.putObject("arguments").put("name","report.txt").put("text","verified report");
            JsonNode applied=host(origin,base+"/operations",scoped,f.backend,call);assertEquals("PASSED",applied.path("result").path("verification").asText());
            ObjectNode complete=obj().put("requestKey","real-office-finish");complete.set("guard",context.path("guard"));host(origin,base+"/complete",scoped,f.backend,complete);
            JsonNode detail=pac(f,"GET","/tasks/"+task,null,null).body;assertEquals("SUCCEEDED",detail.path("state").asText());assertEquals(spec,detail.path("nativeWorkSpec"));
            JsonNode artifact=pac(f,"GET","/tasks/"+task+"/artifacts",null,null).body.path("items").get(0);
            JsonNode downloaded=pac(f,"GET","/tasks/"+task+"/artifacts/"+artifact.path("artifactId").asText(),null,null).body;
            byte[] bytes=Base64.getDecoder().decode(downloaded.path("bytes").asText());assertEquals("verified report",new String(bytes,StandardCharsets.UTF_8));assertEquals(artifact.path("sha256").asText(),ClientAccessWire.digest(bytes));
            System.out.println(obj().put("schemaVersion","pac-office-real-host/1").put("status","PASS").put("scope","PAC_APPLICATION_SERVICE_REAL_MAC_HOST_FILES").put("taskId",task).put("originalClientKey","real-office-original").put("taskCount",1).put("artifactSha256",artifact.path("sha256").asText()));
        }finally{f.cleanup();}
    }
}
