package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.adapter.http.*;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.application.executionpolicy.*;
import com.sage.business.code.infrastructure.AtomicJsonStore;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Actual TLS -> existing durable policy authority, not a mobile policy implementation. */
public class PersonalExecutionPolicyHttpsTest {
 PersonalLocalTaskAccessTest f;PersonalAccessServer server;HttpServer upstream;SSLContext tls;
 ExecutionPermissionCoordinator coordinator;ExecutionPermissionPolicyService policies;String owner,principal="policy-owner";
 @Before public void setup()throws Exception {
  f=new PersonalLocalTaskAccessTest();f.setup();grant(false);
  AtomicJsonStore store=new AtomicJsonStore(f.root.resolve("policies"),f.json);
  policies=new ExecutionPermissionPolicyService(store,f.json,PermissionMode.FULL_ACCESS,null,f.db);
  policies.seedForNewSession(principal,"session-one","project-one",null,principal);
  owner=UUID.randomUUID().toString();
  coordinator=new ExecutionPermissionCoordinator(store,f.json,policies,f.db);
  FullAccessPendingReconciler reconciler=new FullAccessPendingReconciler(coordinator,f.db,f.json,principal);
  ExecutionPermissionApiHandler handler=new ExecutionPermissionApiHandler(f.json,owner,policies,coordinator,principal,true,sid->"project-one",null,null,null,null,reconciler);
  upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/api/v2",new V2ApiDispatcher(f.json,Arrays.<V2RouteGroup>asList(handler)));upstream.start();
        String password=UUID.randomUUID().toString();Path file=f.root.resolve("fixture-only-tls.p12");
        String keytool=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process process=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(f.root.resolve("fixture-only-keytool.log").toFile()).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(file)){keys.load(input,password.toCharArray());}
        KeyManagerFactory keyManager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keyManager.init(keys,password.toCharArray());
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(keyManager.getKeyManagers(),trust.getTrustManagers(),new SecureRandom());
        X509Certificate certificate=(X509Certificate)keys.getCertificate("fixture");

  server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,certificate,f.auth,new ClientSessionRegistry("acs-store","policy-test-boot"),f.json,new URI("http://127.0.0.1:"+upstream.getAddress().getPort()),owner);server.start();
 }
 @After public void close()throws Exception{if(server!=null)server.close();if(upstream!=null)upstream.stop(0);if(f!=null)f.cleanup();}
 void grant(boolean manage){PersonalAccessService.Device d=f.auth.authenticate(f.token);Set<String> actions=new TreeSet<String>(Arrays.asList("OBSERVE","APPROVE","CONTROL"));if(manage)actions.add("POLICY");f.auth.updateScopeResources(f.did,UUID.randomUUID().toString(),Long.toString(d.revision),Arrays.asList(new PersonalResourceScope.Resource("ACS_SESSION","acs-store","session-one")),Collections.emptyList(),actions);}
 String path(String scope){return "/personal/v1/agent/sessions/session-one/execution-policy"+(scope.equals("PROJECT")?"-defaults":"");}
 ObjectNode update(String mode,long revision){return f.json.createObjectNode().put("expectedRevision",revision).put("requestedMode",mode).put("permissionSemanticsVersion",2).put("pendingApprovalAction","RETAIN");}
 JsonNode call(String method,String path,JsonNode body,String key,int expected)throws Exception{
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.port()+path).openConnection();c.setSSLSocketFactory(tls.getSocketFactory());c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+f.token);c.setConnectTimeout(5000);c.setReadTimeout(10000);
  if(key!=null)c.setRequestProperty("Idempotency-Key",key);
  try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(f.json.writeValueAsBytes(body));}}
   int status=c.getResponseCode();JsonNode result=f.json.readTree(status>=400?c.getErrorStream():c.getInputStream());assertEquals(result.toString(),expected,status);return result;
  }finally{c.disconnect();}
 }
 @Test public void approvalDoesNotImplyPersistentPolicyAndRevokedGrantStopsMutations()throws Exception{
  call("GET",path("SESSION"),null,null,200);
  call("PUT",path("SESSION"),update("FULL_ACCESS",1),"not-granted",403);
  grant(true);call("PUT",path("SESSION"),update("FULL_ACCESS",1),"session-allow",200);
  grant(false);call("PUT",path("SESSION"),update("STANDARD",2),"revoked-device-policy",403);
  assertEquals(PermissionMode.FULL_ACCESS,policies.getSession(principal,"session-one").getMode());
 }
 @Test public void sessionSaveReadRevokeAndProjectDefaultUseOneExistingAuthority()throws Exception{
  grant(true);JsonNode granted=call("PUT",path("SESSION"),update("FULL_ACCESS",1),"session-allow",200);
  assertEquals("FULL_ACCESS",granted.path("mode").asText());assertEquals(2,granted.path("permissionSemanticsVersion").asInt());
  assertEquals("FULL_ACCESS",call("GET",path("SESSION"),null,null,200).path("mode").asText());
  call("PUT",path("SESSION"),update("STANDARD",granted.path("revision").asLong()),"session-revoke",200);
  JsonNode project=call("GET",path("PROJECT"),null,null,200);
  call("PUT",path("PROJECT"),update("AUTO_APPROVE",project.path("revision").asLong()).put("projectId","project-one"),"project-save",200);
  assertEquals(PermissionMode.STANDARD,policies.getSession(principal,"session-one").getMode());
  assertEquals(PermissionMode.AUTO_APPROVE,policies.seedForNewSession(principal,"session-two","project-one",null,principal).getMode());
  assertEquals(PermissionMode.STANDARD,policies.seedForNewSession(principal,"session-other","other-project",null,principal).getMode());
  JsonNode savedProject=call("GET",path("PROJECT"),null,null,200);
  call("PUT",path("PROJECT"),update("STANDARD",savedProject.path("revision").asLong()).put("projectId","project-one"),"project-revoke",200);
  assertEquals(PermissionMode.AUTO_APPROVE,policies.getSession(principal,"session-two").getMode());
  assertEquals(PermissionMode.STANDARD,policies.seedForNewSession(principal,"session-after-revoke","project-one",null,principal).getMode());
 }
 @Test public void explicitSessionGrantRechecksPendingApprovalThroughExistingAuthority()throws Exception{
  grant(true);ExecutionOperationFacts facts=new ExecutionOperationFacts();facts.setRequiresApproval(true);facts.setEffectKnown(false);
  PermissionApprovalRecord pending=coordinator.prepare(ExecutionPermissionRequest.builder().principalId(principal).projectId("project-one").sessionId("session-one").runId("run-policy").workflowId("policy-regression").capabilityId("process.python").summary("real authority pending protocol test").effectFingerprint("policy-original-effect").facts(facts).build());
  assertEquals("PENDING",pending.getStatus());
  List<String> later=new ArrayList<String>();
  for(int i=0;i<101;i++)later.add(coordinator.prepare(ExecutionPermissionRequest.builder().principalId(principal).projectId("project-one").sessionId("session-one").runId("run-policy-"+i).workflowId("policy-regression").capabilityId("process.python").summary("bounded recheck "+i).effectFingerprint("policy-effect-"+i).facts(facts).build()).getApprovalId());
  call("PUT",path("SESSION"),update("FULL_ACCESS",1).put("pendingApprovalAction","RECONCILE_SESSION"),"grant-and-recheck",200);
  assertEquals("APPROVED",coordinator.get(pending.getApprovalId()).getStatus());
  assertEquals("DOMAIN_RESUME",coordinator.get(pending.getApprovalId()).getDispatchRoute());
  assertEquals(102,new com.sage.business.code.infrastructure.executionpolicy.SqliteExecutionPermissionStore(f.db,f.json).countOutbox("READY"));
  for(String id:later)assertEquals("APPROVED",coordinator.get(id).getStatus());
 }
 @Test public void staleVersionAndCrossScopeTargetsCannotCreateAnotherGrant()throws Exception{
  grant(true);call("PUT",path("SESSION"),update("AUTO_APPROVE",1),"one",200);
  call("PUT",path("SESSION"),update("FULL_ACCESS",1),"stale",409);
  call("PUT",path("PROJECT"),update("FULL_ACCESS",1).put("projectId","other-project"),"wrong-project",409);
  call("PUT",path("SESSION").replace("session-one","other-session"),update("FULL_ACCESS",1),"wrong-session",403);
  call("PUT",path("PROJECT"),update("FULL_ACCESS",1).put("projectId","project-one").put("pendingApprovalAction","RECONCILE_SESSION"),"hidden-project-reconcile",400);
  assertEquals(PermissionMode.AUTO_APPROVE,policies.getSession(principal,"session-one").getMode());
 }
 @Test public void resolvedApprovalNoLongerRequiresUserAction()throws Exception{
  ExecutionOperationFacts facts=new ExecutionOperationFacts();facts.setRequiresApproval(true);
  PermissionApprovalRecord pending=coordinator.prepare(ExecutionPermissionRequest.builder().principalId(principal).projectId("project-one").sessionId("session-one").runId("run-resolved").workflowId("resolved-regression").capabilityId("process.python").summary("resolved request").effectFingerprint("resolved-effect").facts(facts).build());
  assertTrue(pending.isRequiresUserAction());
  coordinator.decide(pending.getApprovalId(),pending.getVersion(),PermissionDecisionIntent.APPROVE,"operator-resolved");
  assertFalse(coordinator.get(pending.getApprovalId()).isRequiresUserAction());
 }
}
