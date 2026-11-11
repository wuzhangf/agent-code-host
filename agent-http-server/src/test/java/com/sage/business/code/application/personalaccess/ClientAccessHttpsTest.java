package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sun.net.httpserver.HttpServer;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import static org.junit.Assert.*;

/** Actual TLS listener and shared JS consumer, with controlled original source endpoints. */
public class ClientAccessHttpsTest {
    ClientAccessServiceTest fixture;
    HttpServer upstream;
    PersonalAccessServer personal;
    Path certificate;
    SSLContext tls;
    String owner;
    @Before public void setup()throws Exception{
        Assume.assumeNotNull(System.getProperty("uca.client.root"));
        fixture=new ClientAccessServiceTest();fixture.setup();owner=UUID.randomUUID().toString();
        Path key=fixture.fixture.root.resolve("tls.p12");String password=UUID.randomUUID().toString();
        String keytool=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process gen=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",key.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(fixture.fixture.root.resolve("keytool.log").toFile()).start();
        if(!gen.waitFor(20,TimeUnit.SECONDS)||gen.exitValue()!=0)throw new IllegalStateException("Ephemeral fixture TLS generation failed");
        KeyStore ks=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(key)){ks.load(in,password.toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,password.toCharArray());TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ks);tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        X509Certificate cert=(X509Certificate)ks.getCertificate("fixture");certificate=fixture.fixture.root.resolve("public-ca.pem");Files.write(certificate,("-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(cert.getEncoded())+"\n-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII));
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);upstream.createContext("/",x->{try{
            assertEquals("Bearer "+owner,x.getRequestHeaders().getFirst("Authorization"));
            JsonNode body=x.getRequestMethod().equals("POST")?ClientAccessWire.JSON.readTree(x.getRequestBody()):null;
            String path=x.getRequestURI().toString();JsonNode result=fixture.source(fixture.fixture.auth.authenticate(fixture.fixture.token),x.getRequestMethod(),path,body,x.getRequestHeaders().getFirst("Idempotency-Key"));byte[] bytes=ClientAccessWire.JSON.writeValueAsBytes(result);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
        }catch(Throwable failure){byte[] bytes="{\"code\":\"SOURCE_FIXTURE_FAILURE\"}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(500,bytes.length);x.getResponseBody().write(bytes);}finally{x.close();}});upstream.start();
        personal=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,cert,fixture.fixture.auth,new ClientSessionRegistry("acs-store","boot"),ClientAccessWire.JSON,new URI("http://127.0.0.1:"+upstream.getAddress().getPort()),owner).localTasks(fixture.fixture.access);personal.start();
    }
    @After public void close()throws Exception{if(personal!=null)personal.close();if(upstream!=null)upstream.stop(0);if(fixture!=null)fixture.close();}
    void node(String name)throws Exception{
        ProcessBuilder builder=new ProcessBuilder("node",Paths.get(System.getProperty("uca.client.root"),"apps","client-access","trial.mjs").toString());Map<String,String> env=builder.environment();
        env.put("UCA_ORIGIN","https://127.0.0.1:"+personal.port());env.put("UCA_BASE_PATH","/personal/v1/client");env.put("UCA_TEST_TOKEN",fixture.fixture.token);env.put("UCA_CONNECTION_ID",fixture.fixture.did);env.put("UCA_PENDING_PATH",fixture.fixture.root.resolve("pending.json").toString());env.put("UCA_PROMPT","Controlled original source protocol test");env.put("NODE_EXTRA_CA_CERTS",certificate.toString());
        Path output=fixture.fixture.root.resolve("node-result.json"),errors=fixture.fixture.root.resolve("node-error.txt");Process process=builder.redirectOutput(output.toFile()).redirectError(errors.toFile()).start();if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("Reference consumer timed out");}
        assertEquals(new String(Files.readAllBytes(errors),StandardCharsets.UTF_8),0,process.exitValue());JsonNode evidence=ClientAccessWire.JSON.readTree(Files.readAllBytes(output));assertTrue(evidence.path("receipt").path("accepted").asBoolean());assertTrue(evidence.path("lookup").path("consumed").isNull());
        Path destination=Paths.get("target","client-access-evidence");Files.createDirectories(destination);Files.write(destination.resolve(name+".json"),Files.readAllBytes(output));
    }
    @Test public void sharedConsumerRunsThroughActualPersonalHttpsAcsBinding()throws Exception{
        fixture.acsScope();node("acs-https");assertEquals(1,fixture.messages.get());
        HttpsURLConnection denied=(HttpsURLConnection)new URL("https://127.0.0.1:"+personal.port()+"/personal/v1/client/manifest").openConnection();denied.setSSLSocketFactory(tls.getSocketFactory());try{assertEquals(401,denied.getResponseCode());JsonNode error=ClientAccessWire.JSON.readTree(denied.getErrorStream());assertTrue(error.has("error"));}finally{denied.disconnect();}
    }
    void nativeSource(String engine)throws Exception{
        PersonalLocalTaskAccessTest f=fixture.fixture;f.engineName=engine;PersonalAccessService.Device d=f.auth.authenticate(f.token);f.auth.updateScopeResources(f.did,"engine-scope",Long.toString(d.revision),Collections.emptyList(),Arrays.asList(new PersonalResourceScope.LaunchScope(f.backend,"project-one",engine)),new TreeSet<String>(Arrays.asList("OBSERVE","LAUNCH","CONTROL","INTERACT","APPROVE")));
        f.call("POST","/tasks",f.launch().put("engineId",engine),"original-launch");f.nativeState="SUCCEEDED";node(engine+"-https");assertEquals(1,f.inputPosts.get());
    }
    @Test public void sharedConsumerRunsThroughPiHttpsAdapter()throws Exception{nativeSource("pi");}
    @Test public void sharedConsumerRunsThroughCodexHttpsAdapter()throws Exception{nativeSource("codex");}
}
