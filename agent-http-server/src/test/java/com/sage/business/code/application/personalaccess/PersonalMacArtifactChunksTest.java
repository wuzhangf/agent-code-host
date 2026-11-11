package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sage.business.code.domain.BusinessException;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;

/** Real fixed loopback source and production PAC authorization; no model or user files. */
public class PersonalMacArtifactChunksTest {
    PersonalLocalTaskAccessTest f;
    String artifact="file-"+String.join("",Collections.nCopies(64,"a")), digest=String.join("",Collections.nCopies(64,"b"));
    volatile boolean changed,wrongTask,undeclared;
    byte[] bytes=new byte[262144];
    @Before public void open()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();
        f.auth.updateScopeResources(f.did,"chunk-scope","2",Arrays.asList(new PersonalResourceScope.Resource("WORKER_LOCAL",f.backend,"native-one")),Collections.emptyList(),new TreeSet<String>(Arrays.asList("OBSERVE")));
        f.host.removeContext("/local/v1/");f.host.createContext("/local/v1/",x->{try{
            String path=x.getRequestURI().getPath();
            if(path.equals("/local/v1/tasks/native-one")){f.send(x,200,f.json.createObjectNode().put("taskId","native-one").put("recipeID",NativeOfficeContract.RECIPE));return;}
            if(path.equals("/local/v1/tasks/native-one/artifacts")){ObjectNode out=f.json.createObjectNode();out.putArray("items").addObject().put("artifactId",artifact).put("status","AVAILABLE_CHUNKED").put("transferMode",undeclared?"UNKNOWN":"CHUNKS_V1").put("sha256",digest).put("sizeBytes","1048576");f.send(x,200,out);return;}
            if(path.contains("/chunks/")){long offset=Long.parseLong(path.substring(path.lastIndexOf('/')+1));ObjectNode out=f.json.createObjectNode().put("schemaVersion","mac-artifact-chunk/1").put("taskId",wrongTask?"sibling":"native-one").put("artifactId",artifact).put("sha256",digest).put("offset",offset).put("sizeBytes","1048576").put("encoding","base64").put("bytes",Base64.getEncoder().encodeToString(bytes)).put("chunkSHA256",changed?digest:ClientAccessWire.digest(bytes));f.send(x,200,out);return;}
            f.handle(x);
        }catch(Exception e){x.close();}});
    }
    @After public void close()throws Exception {f.cleanup();}
    JsonNode chunk()throws Exception {return f.call("GET","/tasks/native-one/artifacts/"+artifact+"/chunks/"+digest+"/0",null,null).body;}
    @Test public void declaredChunkPreservesExactOriginalBindings()throws Exception {JsonNode value=chunk();assertEquals("native-one",value.path("taskId").asText());assertEquals(digest,value.path("sha256").asText());assertEquals(262144,Base64.getDecoder().decode(value.path("bytes").asText()).length);}
    @Test public void modifiedChunkRejected()throws Exception {changed=true;f.denied("LOCAL_ARTIFACT_CHANGED",()->chunk());}
    @Test public void siblingTaskRejected()throws Exception {wrongTask=true;f.denied("LOCAL_ARTIFACT_INVALID",()->chunk());}
    @Test public void undeclaredTransferRejected()throws Exception {undeclared=true;f.denied("LOCAL_ARTIFACT_CHANGED",()->chunk());}
    @Test public void siblingAuthorizationNotExpanded()throws Exception {f.denied("DEVICE_SCOPE_DENIED",()->f.call("GET","/tasks/sibling/artifacts/"+artifact+"/chunks/"+digest+"/0",null,null));}
}
