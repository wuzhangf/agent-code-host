package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;

/** Production fixed-origin proxy and device scope; source stores private partial bytes. */
public class PersonalMacMaterialUploadTest {
    PersonalLocalTaskAccessTest f;
    final Map<String,ObjectNode> uploads=new ConcurrentHashMap<>();
    volatile int chunks;volatile boolean corrupt,changeGrant;
    final String hash=ClientAccessWire.digest("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @Before public void setup()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();grant("2");
        f.host.createContext("/native/v1/workspaces/",x->{try{
            assertEquals("Bearer "+f.secret,x.getRequestHeaders().getFirst("Authorization"));assertEquals(f.backend,x.getRequestHeaders().getFirst("X-Worker-Local-Store"));
            String[] p=x.getRequestURI().getPath().split("/");String key=p[6];String tail=p.length>7?p[7]:"";
            JsonNode body=x.getRequestMethod().equals("POST")?f.json.readTree(x.getRequestBody()):null;
            ObjectNode row=uploads.get(key);
            if(x.getRequestMethod().equals("GET")){if(row==null){f.send(x,404,f.json.createObjectNode().put("code","NOT_FOUND"));return;}}
            else if(tail.isEmpty()) {
                assertTrue(body.path("requestKey").asText().startsWith("upload-"));
                if(row==null){row=f.json.createObjectNode().put("schemaVersion","mac-material-upload/1").put("uploadKey",key).put("workspaceRef","project-one").put("name",body.path("name").asText()).put("sizeBytes",body.path("sizeBytes").asLong()).put("sha256",body.path("sha256").asText()).put("nextOffset",0).put("state","OPEN");uploads.put(key,row);}
            } else if(tail.equals("chunks")){chunks++;row.put("nextOffset",5);}
            else if(tail.equals("seal")){row.put("state","SEALED");}
            if(changeGrant){changeGrant=false;grant("3");}
            ObjectNode out=row.deepCopy();if(corrupt)out.put("workspaceRef","sibling");out.put("privatePath","/owner/only/material");f.send(x,200,out);
        }catch(Throwable error){x.close();}});
    }
    void grant(String revision){f.auth.updateScopeResources(f.did,"upload-scope-"+revision,revision,Collections.emptyList(),Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one","macos")),new TreeSet<>(Arrays.asList("LAUNCH","OBSERVE")));}
    @After public void close()throws Exception{f.cleanup();}
    ObjectNode begin(){return f.json.createObjectNode().put("requestKey","client-one-begin").put("name","report.txt").put("sizeBytes",5).put("sha256",hash);}
    JsonNode beginCall()throws Exception{return f.call("POST","/workspaces/project-one/uploads/client-one",begin(),"client-one-begin").body;}
    @Test public void uploadKeyIsPrivateScopeBoundAndOnlySealedReceiptPublished()throws Exception{
        JsonNode row=beginCall();assertEquals("client-one",row.path("uploadKey").asText());assertFalse(row.has("privatePath"));
        assertEquals(row,beginCall());assertEquals(1,uploads.size());
        ObjectNode part=f.json.createObjectNode().put("requestKey","client-one-chunk-0").put("offset",0).put("sha256",hash).put("dataBase64",Base64.getEncoder().encodeToString("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(5,f.call("POST","/workspaces/project-one/uploads/client-one/chunks",part,"client-one-chunk-0").body.path("nextOffset").asInt());
        assertEquals("SEALED",f.call("POST","/workspaces/project-one/uploads/client-one/seal",f.json.createObjectNode().put("requestKey","client-one-seal"),"client-one-seal").body.path("state").asText());
    }
    @Test public void optionsPublishesActualPacStoreOnlyAfterScopeCheck()throws Exception{JsonNode options=f.call("GET","/options",null,null).body;assertEquals(f.auth.ownerStatus().path("storeId"),options.path("storeId"));assertEquals(f.backend,options.path("backendStoreId").asText());assertFalse(options.has("ownerToken"));}
    @Test public void tamperedChunkNeverForwarded()throws Exception{beginCall();ObjectNode part=f.json.createObjectNode().put("requestKey","client-one-chunk-0").put("offset",0).put("sha256",hash).put("dataBase64",Base64.getEncoder().encodeToString(new byte[]{1}));f.denied("INVALID_MATERIAL_CHUNK",()->f.call("POST","/workspaces/project-one/uploads/client-one/chunks",part,"client-one-chunk-0"));assertEquals(0,chunks);}
    @Test public void grantChangeCannotReadPreviousUploadLocator()throws Exception{beginCall();grant("3");f.denied("NOT_FOUND",()->f.call("GET","/workspaces/project-one/uploads/client-one",null,null));assertEquals(1,uploads.size());}
    @Test public void unauthorizedProjectNeverForwarded()throws Exception{f.denied("DEVICE_SCOPE_DENIED",()->f.call("POST","/workspaces/sibling/uploads/client-one",begin(),"client-one-begin"));assertTrue(uploads.isEmpty());}
    @Test public void foreignReceiptRejected()throws Exception{corrupt=true;f.denied("LOCAL_REPLY_INVALID",()->beginCall());}
    @Test public void revisionChangeDuringSourceRequestRejected()throws Exception{changeGrant=true;f.denied("GRANT_REVISION_CONFLICT",()->beginCall());}
}
