package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.contract.v2.session.*;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
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

/** Actual PAC HTTPS adapter + real auth scope producers + actual material HTTP
 * handler/SessionService. Native source and final model gateway are controlled. */
public class PersonalHandoffReauthorizationHttpsTest {
    PersonalAcsHandoffMaterialContractTest t;PersonalWorkHandoffTest f;
    PersonalAccessServer server;SSLContext tls;String tlsPassword;
    @Before public void setup()throws Exception {
        t=new PersonalAcsHandoffMaterialContractTest();t.setup();f=t.f;
        String password=UUID.randomUUID().toString();tlsPassword=password;Path file=f.root.resolve("test-only-tls.p12");
        String keytool=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process p=new ProcessBuilder(keytool,"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(f.root.resolve("keytool.log").toFile()).start();
        assertTrue(p.waitFor(20,TimeUnit.SECONDS));assertEquals(0,p.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(file)){keys.load(in,password.toCharArray());}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,(X509Certificate)keys.getCertificate("test"),f.auth,
                new ClientSessionRegistry("acs-store","reauth-test-boot"),f.json,new URI(t.origin),PersonalAcsHandoffMaterialContractTest.OWNER)
                .localTasks(f.access).workHandoffs(f.materialRoot,f.signingRoot);server.start();
    }
    @After public void close()throws Exception {if(server!=null)server.close();if(t!=null)t.cleanup();}
    @Test public void canonicalMaterialGetNeedsNoMutationKeyButWritesStillDo()throws Exception {
        ObjectNode create=f.json.createObjectNode().put("schemaVersion","business-session/2.0").put("title","material read contract")
                .put("workspaceAlias","control").put("intent","IMPLEMENT").put("interactionMode","ACT").put("executionMode","MODIFY")
                .put("autonomyLevel","LOW").put("verificationLevel","VERIFY");
        String sid=t.sessions.create(f.json.treeToValue(create,SessionCreateRequest.class),"material-read-session").getSessionId();
        PersonalAccessService.Device device=f.auth.authenticate(f.token);f.auth.appendCreatedSession(f.did,device.revision,sid);
        String path="/personal/v1/agent/projects/control/material-uploads";
        byte[] bytes="public material".getBytes(StandardCharsets.UTF_8);
        ObjectNode body=f.json.createObjectNode().put("requestKey","material-begin").put("displayName","proof.txt")
                .put("mediaType","text/plain").put("sizeBytes",bytes.length).put("sha256",PersonalAccessService.hashBytes(bytes));
        body.putObject("target").put("sessionId",sid);
        assertEquals("INVALID_REQUEST",materialCall("POST",path,body,null,400).path("code").asText());
        JsonNode begin=materialCall("POST",path,body,"material-begin",200);
        String uploadKey=begin.path("source").path("uploadKey").asText();assertFalse(uploadKey.isEmpty());
        JsonNode canonical=materialCall("GET",path+"/"+uploadKey,null,null,200);
        assertEquals(uploadKey,canonical.path("uploadKey").asText());assertEquals(sid,canonical.path("target").path("sessionId").asText());
        assertEquals("material-upload/1",canonical.path("schemaVersion").asText());
        assertEquals("REFERENCE_TEXT",canonical.path("mediaRole").asText());
        assertEquals("text/plain",canonical.path("mediaType").asText());
        assertEquals(bytes.length,canonical.path("sizeBytes").asLong());
        assertEquals(PersonalAccessService.hashBytes(bytes),canonical.path("sha256").asText());
        assertEquals("SELECTED",canonical.path("state").asText());
        ObjectNode chunk=f.json.createObjectNode().put("requestKey","material-chunk").put("chunkDigest",PersonalAccessService.hashBytes(bytes))
                .put("dataBase64",Base64.getEncoder().encodeToString(bytes));
        assertEquals("INVALID_REQUEST",materialCall("PUT",path+"/"+uploadKey+"/chunks/0",chunk,null,400).path("code").asText());
        assertEquals("INVALID_REQUEST",materialCall("POST",path+"/"+uploadKey+"/seal",f.json.createObjectNode().put("requestKey","material-seal").put("sha256",PersonalAccessService.hashBytes(bytes)),null,400).path("code").asText());
        assertEquals("INVALID_REQUEST",materialCall("DELETE",path+"/"+uploadKey,null,null,400).path("code").asText());
        assertEquals(0,t.rawPuts);assertEquals(0,t.messagePosts);assertEquals(0,t.gatewayCalls);
    }
    private JsonNode materialCall(String method,String path,JsonNode body,String key,int expected)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.port()+path).openConnection(Proxy.NO_PROXY);
        c.setSSLSocketFactory(tls.getSocketFactory());c.setRequestMethod(method);c.setConnectTimeout(3000);c.setReadTimeout(15000);
        c.setRequestProperty("Authorization","Bearer "+f.token);if(key!=null)c.setRequestProperty("Idempotency-Key",key);
        try {if(body!=null){byte[] bytes=f.json.writeValueAsBytes(body);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();JsonNode reply=in==null?null:f.json.readTree(in);
            assertEquals(reply==null?"empty reply":reply.path("code").asText(),expected,status);return reply;
        } finally {c.disconnect();}
    }
    JsonNode call(String method,String tail,JsonNode body,String key,int expected)throws Exception {
        return callAs(f.token,method,tail,body,key,expected);
    }
    JsonNode callAs(String token,String method,String tail,JsonNode body,String key,int expected)throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)new URL("https://127.0.0.1:"+server.port()+"/personal/v1/work-handoffs"+tail).openConnection(Proxy.NO_PROXY);
        c.setSSLSocketFactory(tls.getSocketFactory());c.setRequestMethod(method);c.setConnectTimeout(3000);c.setReadTimeout(15000);
        c.setRequestProperty("Authorization","Bearer "+token);if(key!=null)c.setRequestProperty("Idempotency-Key",key);
        try {if(body!=null){byte[] bytes=f.json.writeValueAsBytes(body);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status=c.getResponseCode();InputStream in=status>=400?c.getErrorStream():c.getInputStream();JsonNode reply=in==null?null:f.json.readTree(in);
            assertEquals(reply==null?"empty reply":reply.path("code").asText(),expected,status);return reply;
        } finally {c.disconnect();}
    }
    JsonNode cont(String id,String phase,int status)throws Exception {String key="handoff:reauth-flow:"+phase;return call("POST","/"+id+"/continue",f.json.createObjectNode().put("requestKey",key),key,status);}
    PersonalWorkHandoffService.Handoff row(String id){return f.records.transaction(c->f.records.get(c,"local-handoff",f.did+":"+id,PersonalWorkHandoffService.Handoff.class));}
    String prepare(boolean acs)throws Exception {
        f.exportBytes=("Public complete material\n"+"保留原文。".repeat(1200)+"\nCONTROL_REAUTH_完整材料末尾").getBytes(StandardCharsets.UTF_8);
        ObjectNode body=f.createBody("reauth-flow");if(acs)body.putObject("targetAgentRef").put("hostKey","local").put("kind","ACS").put("projectRef","control");
        return call("POST","",body,"reauth-flow",201).path("handoffId").asText();
    }
    String legacyRejectedTarget()throws Exception {
        String id=prepare(true);cont(id,"PREPARING",200);JsonNode candidate=cont(id,"PREPARING",200);
        String key="handoff:reauth-flow:CONFIRM";call("POST","/"+id+"/confirm",f.json.createObjectNode().put("requestKey",key).put("expectedMaterialRevision",1).put("expectedMaterialDigest",candidate.path("candidateMaterialDigest").asText()),key,200);
        cont(id,"SOURCE_SEALING",200);cont(id,"FINALIZING_MATERIALS",200);cont(id,"TARGET_STAGING",200);
        t.rejectFirstInput=true;assertEquals("INPUT_LIMIT_EXCEEDED",cont(id,"TARGET_STARTING",422).path("code").asText());
        // Pre-material-channel legacy row, preserving the real refusal and sid.
        f.records.transaction(c->{PersonalWorkHandoffService.Handoff h=f.records.get(c,"local-handoff",f.did+":"+id,PersonalWorkHandoffService.Handoff.class);h.acsMaterialRef=null;h.acsMaterialSha256=null;h.acsPromptState=null;h.materialBinding="INLINE_REFERENCES";h.stagedRefs.clear();f.records.put(c,"local-handoff",f.did+":"+id,h);return null;});
        return id;
    }
    void change(List<PersonalResourceScope.Resource> resources,List<PersonalResourceScope.LaunchScope> launches,Set<String> actions){
        PersonalAccessService.Device d=f.auth.authenticate(f.token);f.auth.updateScopeResources(f.did,UUID.randomUUID().toString(),Long.toString(d.revision),resources,launches,actions);
    }
    @Test public void additiveRealSessionGrantReauthorizesOriginalLegacyTargetWithoutRecreation()throws Exception {
        String id=legacyRejectedTarget(),sid=t.sid;PersonalWorkHandoffService.Handoff original=row(id);String originalWireKey=t.lastWire.path("clientMessageId").asText();
        ObjectNode create=f.json.createObjectNode().put("schemaVersion","business-session/2.0").put("title","unrelated e6").put("workspaceAlias","control").put("intent","IMPLEMENT").put("interactionMode","ACT").put("executionMode","MODIFY").put("autonomyLevel","LOW").put("verificationLevel","VERIFY");
        String extra=t.sessions.create(f.json.treeToValue(create,SessionCreateRequest.class),"unrelated-e6").getSessionId();
        PersonalAccessService.Device d=f.auth.authenticate(f.token);f.auth.appendCreatedSession(f.did,d.revision,extra);
        String before=f.json.writeValueAsString(row(id));JsonNode hint=call("GET","/"+id,null,null,200);
        assertEquals(before,f.json.writeValueAsString(row(id)));assertTrue(hint.path("authorization").path("canResume").asBoolean());assertTrue(hint.path("authorization").path("reauthorizationRequired").asBoolean());
        assertEquals(Long.toString(original.grantRevision),hint.path("authorization").path("expectedGrantRevision").asText());
        assertEquals("INPUT_LIMIT_EXCEEDED",row(id).failureCode);
        JsonNode ack=cont(id,"TARGET_STARTING",200);PersonalWorkHandoffService.Handoff after=row(id);
        assertEquals(sid,t.sid);assertEquals(sid,after.targetTaskRef);assertEquals(1,t.creates);assertEquals(originalWireKey,t.clientKey);assertEquals(1,t.gatewayCalls);assertTrue(t.content.length()<4000);
        assertTrue(t.received.get(0).getTextContent().contains(new String(f.exportBytes,StandardCharsets.UTF_8)));
        assertEquals(original.manifestJson,after.manifestJson);assertEquals(original.materialDigest,after.materialDigest);assertEquals(original.sealId,after.sealId);assertEquals(original.sourceSealRef,after.sourceSealRef);assertEquals(original.spentKeys,after.spentKeys);assertEquals(1,f.sealPosts.get());
        JsonNode proof=ack.path("reauthorizations").get(0);assertEquals(f.did,proof.path("deviceGrantId").asText());assertEquals(Long.toString(original.grantRevision),proof.path("fromGrantRevision").asText());assertEquals(Long.toString(after.grantRevision),proof.path("toGrantRevision").asText());
        assertEquals("handoff:reauth-flow:TARGET_STARTING",proof.path("phaseKey").asText());assertEquals(sid,proof.path("targetTaskRef").asText());assertEquals(original.materialDigest,proof.path("materialDigest").asText());assertEquals(original.sourceSealRef,proof.path("sourceSealRef").asText());assertEquals(1,after.reauthorizations.size());
        JsonNode afterGet=call("GET","/"+id,null,null,200);assertFalse(afterGet.path("authorization").path("reauthorizationRequired").asBoolean());
        JsonNode secondAck=cont(id,"TARGET_STARTING",200);
        assertEquals(1,secondAck.path("reauthorizations").size());assertEquals(proof.path("authorizationId"),secondAck.path("reauthorizations").get(0).path("authorizationId"));assertEquals(2,t.messagePosts);assertEquals(1,t.gatewayCalls);assertEquals(1,t.creates);assertEquals(1,f.sealPosts.get());
        String capture=System.getProperty("handoff.reauth.capture");if(capture!=null){Path out=Path.of(capture);Files.createDirectories(out);f.json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("get-before-reauthorization.json").toFile(),hint);f.json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("post-explicit-continue.json").toFile(),ack);f.json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("get-after-reauthorization.json").toFile(),afterGet);f.json.writerWithDefaultPrettyPrinter().writeValue(out.resolve("post-same-key-recovery.json").toFile(),secondAck);}
    }
    @Test public void freshPostRejectsSourceTargetAndHandoffRevocationAfterAnAllowedGet()throws Exception {
        String id=legacyRejectedTarget();PersonalWorkHandoffService.Handoff original=row(id);PersonalAccessService.Device base=f.auth.authenticate(f.token);
        for(String removed:List.of("source","target","action")){
            change(base.resources,base.launchScopes,base.actions);
            assertTrue(call("GET","/"+id,null,null,200).path("authorization").path("canResume").asBoolean());
            List<PersonalResourceScope.Resource> resources=new ArrayList<>(base.resources);Set<String> actions=new TreeSet<>(base.actions);
            if(removed.equals("source"))resources.removeIf(r->"WORKER_LOCAL".equals(r.kind)&&original.sourceTaskId.equals(r.taskId));
            if(removed.equals("target"))resources.removeIf(r->t.sid.equals(r.sessionId));
            if(removed.equals("action"))actions.remove("HANDOFF");change(resources,base.launchScopes,actions);
            assertEquals("DEVICE_SCOPE_DENIED",cont(id,"TARGET_STARTING",403).path("code").asText());
            if(!removed.equals("source"))assertFalse(call("GET","/"+id,null,null,200).path("authorization").path("canResume").asBoolean());
            else call("GET","/"+id,null,null,403);
            assertEquals(0,t.gatewayCalls);assertEquals(1,t.messagePosts);assertEquals(1,t.creates);assertEquals(original.grantRevision,row(id).grantRevision);assertTrue(row(id).reauthorizations.isEmpty());assertEquals("INPUT_LIMIT_EXCEEDED",row(id).failureCode);
        }
    }
    @Test public void missingLaunchOrExactLaunchScopeCannotAdvanceTheUncreatedTarget()throws Exception {
        String id=prepare(false);PersonalAccessService.Device base=f.auth.authenticate(f.token);
        assertTrue(call("GET","/"+id,null,null,200).path("authorization").path("canResume").asBoolean());
        change(base.resources,List.of(new PersonalResourceScope.LaunchScope(f.backend,"different-project","pi")),base.actions);
        assertFalse(call("GET","/"+id,null,null,200).path("authorization").path("canResume").asBoolean());cont(id,"PREPARING",403);
        Set<String> actions=new TreeSet<>(base.actions);actions.remove("LAUNCH");change(base.resources,Collections.emptyList(),actions);cont(id,"PREPARING",403);
        assertEquals(0,f.targetLaunches.get());assertEquals("COLLECT",row(id).pendingStep);assertTrue(row(id).reauthorizations.isEmpty());
    }
    @Test public void removedCreateBeforeTargetCreationCannotAdvance()throws Exception {
        String id=prepare(true);PersonalAccessService.Device d=f.auth.authenticate(f.token);
        assertTrue(call("GET","/"+id,null,null,200).path("authorization").path("canResume").asBoolean());
        Set<String> actions=new TreeSet<>(d.actions);actions.remove("CREATE");change(d.resources,d.launchScopes,actions);
        cont(id,"PREPARING",403);assertEquals(0,t.creates);assertEquals("COLLECT",row(id).pendingStep);assertTrue(row(id).reauthorizations.isEmpty());
    }
    @Test public void anotherActorAndARevokedOriginalDeviceCannotResume()throws Exception {
        String id=prepare(false);PersonalAccessService.Device d=f.auth.authenticate(f.token);
        String request=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();
        ObjectNode invite=f.auth.invitationResources("other-actor",d.resources,d.launchScopes,d.actions,"public-test-cert");
        f.auth.request(f.json.createObjectNode().put("inviteId","other-actor").put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",request).put("installationId","other-installation").put("label","other actor").put("pollingSecretHash",PersonalAccessService.hash(poll)));
        f.auth.decide(request,true);String other=f.auth.redeem(request,poll,"other-redemption").path("accessToken").asText();
        callAs(other,"GET","/"+id,null,null,404);
        String key="handoff:reauth-flow:PREPARING";
        callAs(other,"POST","/"+id+"/continue",f.json.createObjectNode().put("requestKey",key),key,404);
        f.auth.revoke(f.did);call("POST","/"+id+"/continue",f.json.createObjectNode().put("requestKey",key),key,401);
        assertEquals("COLLECT",row(id).pendingStep);assertTrue(row(id).reauthorizations.isEmpty());assertEquals(0,f.targetLaunches.get());
    }
    JsonNode reopenedContinue(String id)throws Exception {
        return reopened(id,null);
    }
    JsonNode reopenedRetryLookup(String id,String retryKey)throws Exception {return reopened(id,retryKey);}
    private JsonNode reopened(String id,String retryKey)throws Exception {
        if(server!=null)server.close();server=null;
        ObjectNode config=f.json.createObjectNode().put("root",f.root.toString()).put("token",f.token)
                .put("sealKey",Base64.getEncoder().encodeToString(f.fixtureSealKey)).put("tlsPassword",tlsPassword)
                .put("upstream",t.origin).put("nativeOrigin","http://127.0.0.1:"+f.host.getAddress().getPort())
                .put("nativeToken",f.secret).put("materials",f.materialRoot.toString()).put("signing",f.signingRoot.toString()).put("handoffId",id);
        if(retryKey!=null)config.put("retryKey",retryKey);
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),ReopenedPac.class.getName())
                .redirectOutput(f.root.resolve("reopened-public-reply.json").toFile()).redirectError(f.root.resolve("reopened-error.log").toFile()).start();
        // Ephemeral test credentials cross only the private stdin pipe, never
        // command arguments, logs, an env file or the persisted public packet.
        try(OutputStream in=child.getOutputStream()){in.write(f.json.writeValueAsBytes(config));}
        if(!child.waitFor(40,TimeUnit.SECONDS)){child.destroyForcibly();fail("Reopened owned PAC timed out");}
        assertEquals(Files.readString(f.root.resolve("reopened-error.log")),0,child.exitValue());
        JsonNode result=f.json.readTree(Files.readAllBytes(f.root.resolve("reopened-public-reply.json")));
        assertNotEquals(ProcessHandle.current().pid(),result.path("processId").asLong());return result.path("reply");
    }
    /** A separate JVM loads the same persisted authority and handoff, then
     * exposes the real PAC HTTPS adapter. The receiver/source remain the
     * original controlled HTTP services outside this restarted PAC process. */
    public static class ReopenedPac {
        public static void main(String[] args)throws Exception {
            ObjectMapper json=new ObjectMapper();JsonNode c=json.readTree(System.in);
            Path root=Path.of(c.path("root").asText());String password=c.path("tlsPassword").asText();
            KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(root.resolve("test-only-tls.p12"))){keys.load(in,password.toCharArray());}
            KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
            TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);SSLContext tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
            try(ControlPlaneDatabase db=new ControlPlaneDatabase(root,false)){
                PersonalAccessStore records=new PersonalAccessStore(db,json);PersonalAccessService auth=new PersonalAccessService(records,json,Base64.getDecoder().decode(c.path("sealKey").asText()),"acs-store","https://localhost");
                PersonalLocalTaskAccess local=new PersonalLocalTaskAccess(c.path("nativeOrigin").asText(),c.path("nativeToken").asText(),records,auth,json);
                try(PersonalAccessServer server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,(X509Certificate)keys.getCertificate("test"),auth,new ClientSessionRegistry("acs-store","reopened-pac-boot"),json,new URI(c.path("upstream").asText()),PersonalAcsHandoffMaterialContractTest.OWNER)
                        .localTasks(local).workHandoffs(Path.of(c.path("materials").asText()),Path.of(c.path("signing").asText()))){
                    server.start();PersonalHandoffReauthorizationHttpsTest client=new PersonalHandoffReauthorizationHttpsTest();client.f=new PersonalWorkHandoffTest();client.server=server;client.tls=tls;
                    String key="handoff:reauth-flow:TARGET_STARTING";
                    JsonNode reply=c.has("retryKey")?client.callAs(c.path("token").asText(),"GET","/"+c.path("handoffId").asText()+"/target-retries/"+c.path("retryKey").asText(),null,null,200)
                            :client.callAs(c.path("token").asText(),"POST","/"+c.path("handoffId").asText()+"/continue",json.createObjectNode().put("requestKey",key),key,200);
                    System.out.println(json.createObjectNode().put("processId",ProcessHandle.current().pid()).set("reply",reply));
                }
            }
        }
    }
    @Test public void provenLegacyRejectionSurvivesStageBusinessFailureAndFreshPacProcess()throws Exception {
        String id=legacyRejectedTarget(),sid=t.sid;PersonalWorkHandoffService.Handoff original=row(id);
        t.materialFailure="PROJECT_NOT_FOUND";
        assertEquals("PROJECT_NOT_FOUND",cont(id,"TARGET_STARTING",404).path("code").asText());
        assertEquals("PROJECT_NOT_FOUND",row(id).failureCode);assertEquals("REJECTED_INPUT_LIMIT",row(id).acsPromptState);
        assertEquals(1,t.messagePosts);assertEquals(0,t.gatewayCalls);
        t.materialFailure=null;JsonNode ack=reopenedContinue(id);
        assertEquals(sid,ack.path("targetTaskRef").asText());assertEquals(1,t.creates);assertEquals(2,t.messagePosts);assertEquals(1,t.gatewayCalls);
        assertEquals("ACCEPTED",row(id).acsPromptState);assertEquals(original.materialDigest,row(id).materialDigest);assertEquals(original.sourceSealRef,row(id).sourceSealRef);assertEquals(1,f.sealPosts.get());
        assertTrue(t.received.get(0).getTextContent().contains(new String(f.exportBytes,StandardCharsets.UTF_8)));
    }
    @Test public void uncertainDispatchCannotAcquireRejectionProofAfterFreshPacProcess()throws Exception {
        String id=legacyRejectedTarget();t.dropBeforeAcceptance=true;
        cont(id,"TARGET_STARTING",503);
        assertEquals("DISPATCHED",row(id).acsPromptState);assertNull(row(id).failureCode);assertEquals(2,t.messagePosts);assertEquals(0,t.gatewayCalls);
        JsonNode ack=reopenedContinue(id);
        assertEquals("TARGET_PROMPT_OUTCOME_UNKNOWN",ack.path("pendingReason").asText());assertEquals("DISPATCHED",row(id).acsPromptState);
        assertEquals(2,t.messagePosts);assertEquals(0,t.gatewayCalls);assertEquals(1,t.creates);assertEquals(1,f.sealPosts.get());
        // An unrelated stale generic failure must not overrule the durable
        // DISPATCHED uncertainty marker, including after another fresh JVM.
        f.records.transaction(c->{PersonalWorkHandoffService.Handoff h=f.records.get(c,"local-handoff",f.did+":"+id,PersonalWorkHandoffService.Handoff.class);h.failureCode="INPUT_LIMIT_EXCEEDED";h.failurePhase="TARGET_STARTING";f.records.put(c,"local-handoff",f.did+":"+id,h);return null;});
        JsonNode stillUnknown=reopenedContinue(id);
        assertEquals("TARGET_PROMPT_OUTCOME_UNKNOWN",stillUnknown.path("pendingReason").asText());assertEquals("DISPATCHED",row(id).acsPromptState);assertEquals(2,t.messagePosts);assertEquals(0,t.gatewayCalls);
    }

}
