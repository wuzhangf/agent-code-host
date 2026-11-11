package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Actual production HTTPS adapter against an authenticated original Mac HTTP fixture.
 * Ephemeral certificate is trusted explicitly; hostname verification remains enabled. */
public class PersonalMacAdoptionHttpsTest {
    PersonalMacAdoptionTest mac;PersonalAccessServer server;SSLContext tls;
    @Before public void setup()throws Exception {
        mac=new PersonalMacAdoptionTest();mac.setup();
        String password=UUID.randomUUID().toString();Path file=mac.f.root.resolve("fixture-only-tls.p12");
        String keytool=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process process=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(mac.f.root.resolve("fixture-only-keytool.log").toFile()).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(file)){keys.load(input,password.toCharArray());}
        KeyManagerFactory keyManager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keyManager.init(keys,password.toCharArray());
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(keyManager.getKeyManagers(),trust.getTrustManagers(),new SecureRandom());
        X509Certificate certificate=(X509Certificate)keys.getCertificate("fixture");
        server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,certificate,mac.f.auth,new ClientSessionRegistry("acs-store","mac-tls-fixture-boot"),mac.f.json,new URI("http://127.0.0.1:"+mac.f.host.getAddress().getPort()),UUID.randomUUID().toString()).localTasks(mac.f.access);server.start();
    }
    @After public void close()throws Exception {if(server!=null)server.close();if(mac!=null)mac.close();}
    JsonNode call(String method,String path,JsonNode body,String key,int expected)throws Exception {
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.port()+path).openConnection();
        connection.setSSLSocketFactory(tls.getSocketFactory());connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);connection.setConnectTimeout(3000);connection.setReadTimeout(12000);connection.setRequestProperty("Authorization","Bearer "+mac.f.token);connection.setRequestProperty("X-Client-Access-Version","client-access/1");if(key!=null)connection.setRequestProperty("Idempotency-Key",key);
        try {
            if(body!=null){byte[] bytes=mac.f.json.writeValueAsBytes(body);connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();JsonNode result=mac.f.json.readTree(input);assertEquals(result.toString(),expected,status);return result;
        }finally{connection.disconnect();}
    }
    @Test public void fiveFieldLaunchProjectionArtifactsAndStopUseActualTlsAndOriginalTask()throws Exception {
        String root="/personal/v1/local-tasks/"+mac.f.backend;
        JsonNode options=call("GET",root+"/options",null,null,200);assertEquals("macos",options.path("engines").get(0).path("engineId").asText());assertEquals("MAC_MANAGED",options.path("engines").get(0).path("profileId").asText());
        call("POST",root+"/task-preflights",mac.obj().put("projectId","project-one").put("engineId","macos"),null,200);
        JsonNode receipt=call("POST",root+"/tasks",mac.launch(),"original-launch",200);assertEquals("OBSERVED",receipt.path("state").asText());assertEquals(1,mac.f.launchPosts.get());
        JsonNode detail=call("GET",root+"/tasks/native-one",null,null,200),projection=detail.path("clientAccess");String ref=projection.path("conversationRef").asText();assertTrue(ref.startsWith("c1."));
        JsonNode view=call("GET","/personal/v1/client/conversations/"+ref,null,null,200);assertEquals("RECOVERY_REQUIRED",view.path("sourceState").asText());assertEquals(projection.path("agentRef"),view.path("agentRef"));
        String conversation="/personal/v1/client/conversations/"+ref;
        JsonNode replies=call("GET",conversation+"/replies",null,null,200),snapshot=replies.path("snapshot");
        assertEquals(mac.publicSummary,snapshot.path("text").asText());
        JsonNode content=call("GET",conversation+"/replies/"+snapshot.path("replyRef").asText()+"/content",null,null,200);
        assertEquals(mac.publicSummary,content.path("text").asText());assertEquals(snapshot.path("contentRevision"),content.path("contentRevision"));
        boolean pauseUnsupported=false;for(JsonNode action:view.path("actions"))if("execution.pause".equals(action.path("actionId").asText()))pauseUnsupported="UNSUPPORTED".equals(action.path("availability").asText());assertTrue(pauseUnsupported);
        JsonNode artifact=call("GET",root+"/tasks/native-one/artifacts/"+mac.artifactId,null,null,200);assertArrayEquals(mac.artifact,Base64.getDecoder().decode(artifact.path("bytes").asText()));
        ObjectNode stop=mac.obj().put("requestKey","tls-stop").put("intent","STOP").put("generation","g1").put("nativeSessionId","mac-session-one").put("nativeTurnId","turn-one").put("targetCommandId","command-one");
        JsonNode stopped=call("POST",root+"/tasks/native-one/commands",stop,"tls-stop",200);assertEquals("REQUESTED",stopped.path("source").path("state").asText());
        call("POST",root+"/tasks/native-one/commands",stop,"tls-stop",200);assertEquals(1,mac.f.inputPosts.get());
        call("GET",root+"/tasks/sibling/artifacts/"+mac.artifactId,null,null,403);
        mac.f.auth.revoke(mac.f.did);call("GET",root+"/tasks/native-one",null,null,401);
    }
}
