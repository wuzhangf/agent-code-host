package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * TLS-level coverage for the /personal/v1/agent/ allowlist: only upstream-
 * verified /api/v2 surfaces forward, write bodies carry strict field
 * whitelists plus required guards, and each hop re-checks the durable grant.
 * The upstream stub records exactly what PAC forwarded.
 */
public class PersonalAgentRouteHttpsTest {
    PersonalLocalTaskAccessTest f;PersonalAccessServer server;HttpServer upstream;SSLContext tls;
    ConcurrentMap<String,ObjectNode> seen=new ConcurrentHashMap<String,ObjectNode>();
    @Before public void setup()throws Exception {
        f=new PersonalLocalTaskAccessTest();f.setup();
        grant(new TreeSet<String>(Arrays.asList("OBSERVE","INTERACT","CONTROL","APPROVE","CREATE","LAUNCH")),true);
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);upstream.createContext("/api/v2/",this::handle);upstream.start();
        String password=UUID.randomUUID().toString();Path file=f.root.resolve("fixture-only-tls.p12");
        String keytool=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process process=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(f.root.resolve("fixture-only-keytool.log").toFile()).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(file)){keys.load(input,password.toCharArray());}
        KeyManagerFactory keyManager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keyManager.init(keys,password.toCharArray());
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(keyManager.getKeyManagers(),trust.getTrustManagers(),new SecureRandom());
        X509Certificate certificate=(X509Certificate)keys.getCertificate("fixture");
        server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,certificate,f.auth,new ClientSessionRegistry("acs-store","route-test-boot"),f.json,new URI("http://127.0.0.1:"+upstream.getAddress().getPort()),UUID.randomUUID().toString());server.start();
    }
    @After public void close()throws Exception{if(server!=null)server.close();if(upstream!=null)upstream.stop(0);if(f!=null)f.cleanup();}

    /** Scope invariant: LAUNCH is present exactly when launchScopes is non-empty. */
    void grant(Set<String> actions,boolean session)throws Exception {
        PersonalAccessService.Device d=f.auth.authenticate(f.token);
        List<PersonalResourceScope.Resource> resources=new ArrayList<PersonalResourceScope.Resource>();
        if(session)resources.add(new PersonalResourceScope.Resource("ACS_SESSION","acs-store","session-one"));
        List<PersonalResourceScope.LaunchScope> launches=actions.contains("LAUNCH")?Arrays.asList(new PersonalResourceScope.LaunchScope("native-store","project-one","pi")):Collections.<PersonalResourceScope.LaunchScope>emptyList();
        if(resources.isEmpty()&&launches.isEmpty())launches=Arrays.asList(new PersonalResourceScope.LaunchScope("native-store","project-one","pi"));
        if(!launches.isEmpty()){actions=new TreeSet<String>(actions);actions.add("LAUNCH");}
        f.auth.updateScopeResources(f.did,UUID.randomUUID().toString(),Long.toString(d.revision),resources,launches,actions);
    }
    void handle(HttpExchange x)throws IOException {
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
        byte[] raw=method.equals("POST")||method.equals("PATCH")||method.equals("PUT")?x.getRequestBody().readAllBytes():null;
        ObjectNode record=f.json.createObjectNode().put("method",method).put("path",path);
        if(x.getRequestURI().getRawQuery()!=null)record.put("query",x.getRequestURI().getRawQuery());
        if(x.getRequestHeaders().getFirst("Idempotency-Key")!=null)record.put("key",x.getRequestHeaders().getFirst("Idempotency-Key"));
        if(raw!=null&&raw.length>0)record.set("body",f.json.readTree(raw));
        seen.put(method+" "+path,record);
        ObjectNode reply=f.json.createObjectNode().put("sessionId","session-one").put("ok",true);
        if(path.startsWith("/api/v2/runs/"))reply.put("runId",path.split("/")[4]);
        if(path.startsWith("/api/v2/approvals/"))reply.put("approvalId",path.split("/")[4]).put("kind","COMMAND");
        if(path.endsWith("/model-settings"))reply.put("settingsRevision",2).put("transitionState","READY");
        if(path.endsWith("/automation-controls"))reply.put("paused",true).put("version",1L);
        byte[] bytes=f.json.writeValueAsBytes(reply);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
    }
    /** Raw HTTPS request over SSLSocket — HttpURLConnection cannot emit PATCH. */
    JsonNode call(String method,String path,JsonNode body,String key,int expected)throws Exception {
        byte[] payload=body==null?null:f.json.writeValueAsBytes(body);
        SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket("127.0.0.1",server.port());socket.setSoTimeout(10000);
        try{
            socket.startHandshake();
            StringBuilder head=new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1:").append(server.port()).append("\r\nAuthorization: Bearer ").append(f.token).append("\r\n");
            if(key!=null)head.append("Idempotency-Key: ").append(key).append("\r\n");
            if(payload!=null)head.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream out=socket.getOutputStream();out.write(head.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));if(payload!=null)out.write(payload);out.flush();
            InputStream in=socket.getInputStream();
            String status=readLine(in);assertTrue(status,status.startsWith("HTTP/"));int code=Integer.parseInt(status.split(" ",3)[1]);
            int length=-1;String line;while(!(line=readLine(in)).isEmpty()){int colon=line.indexOf(':');if(colon>0&&"Content-Length".equalsIgnoreCase(line.substring(0,colon).trim()))length=Integer.parseInt(line.substring(colon+1).trim());}
            byte[] reply;if(length>0){reply=new byte[length];int off=0;while(off<length){int n=in.read(reply,off,length-off);if(n<0)break;off+=n;}}
            else{ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))>=0)buffer.write(chunk,0,n);reply=buffer.toByteArray();}
            JsonNode result=reply.length==0?f.json.createObjectNode():f.json.readTree(reply);
            assertEquals(result.toString(),expected,code);return result;
        }finally{socket.close();}
    }
    private static String readLine(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();int c;while((c=in.read())>=0&&c!='\n')if(c!='\r')out.write(c);return out.toString("UTF-8");}

    @Test public void verifiedRoutesForwardUnderTheirGrantAction()throws Exception {
        assertEquals("session-one",call("GET","/personal/v1/agent/sessions/session-one",null,null,200).path("sessionId").asText());
        assertEquals("GET",seen.get("GET /api/v2/sessions/session-one").path("method").asText());
        call("PATCH","/personal/v1/agent/sessions/session-one",f.json.createObjectNode().put("expectedSessionVersion",3).put("title","renamed"),"k-patch-1",200);
        assertEquals("renamed",seen.get("PATCH /api/v2/sessions/session-one").path("body").path("title").asText());
        assertEquals("k-patch-1",seen.get("PATCH /api/v2/sessions/session-one").path("key").asText());
        call("POST","/personal/v1/agent/sessions/session-one/archive",f.json.createObjectNode().put("expectedSessionVersion",3).put("reason","done"),"k-arch-1",200);
        call("POST","/personal/v1/agent/runs/run-one/continue",f.json.createObjectNode().put("expectedStateVersion",1).put("message","proceed"),"k-cont-1",200);
        assertEquals("proceed",seen.get("POST /api/v2/runs/run-one/continue").path("body").path("message").asText());
        assertEquals("k-cont-1",seen.get("POST /api/v2/runs/run-one/continue").path("key").asText());
        // RunContinueRequest permits an absent body upstream.
        call("POST","/personal/v1/agent/runs/run-one/continue",null,"k-cont-2",200);
        ObjectNode advance=f.json.createObjectNode().put("expectedVersion",1).put("fingerprint","fp-1");
        call("POST","/personal/v1/agent/approvals/approval-one/advance",advance,"k-adv-1",200);
        call("POST","/personal/v1/agent/approvals/approval-one/controls",f.json.createObjectNode().put("expectedVersion",1).put("requestFingerprint","fp-2").put("control","CANCEL"),"k-ctl-1",200);
        for(String leaf:new String[]{"activities","reasoning","output","diff","review","workspace-conflicts"})
            call("GET","/personal/v1/agent/runs/run-one/"+leaf,null,null,200);
        call("GET","/personal/v1/agent/runs/run-one/activities?afterCursor=7&limit=10",null,null,200);
        assertEquals("afterCursor=7&limit=10",seen.get("GET /api/v2/runs/run-one/activities").path("query").asText());
        call("GET","/personal/v1/agent/runs/run-one/diff?page=2&pageSize=40",null,null,200);
        assertEquals("page=2&pageSize=40",seen.get("GET /api/v2/runs/run-one/diff").path("query").asText());
        call("GET","/personal/v1/agent/sessions/session-one/model-settings",null,null,200);
        ObjectNode patch=f.json.createObjectNode().put("expectedSettingsRevision",2);patch.putObject("settings").put("modelAlias","fixture-model");
        call("PATCH","/personal/v1/agent/sessions/session-one/model-settings",patch,"k-ms-1",200);
        assertEquals("fixture-model",seen.get("PATCH /api/v2/sessions/session-one/model-settings").path("body").path("settings").path("modelAlias").asText());
        call("GET","/personal/v1/agent/sessions/session-one/automation-controls",null,null,200);
        call("POST","/personal/v1/agent/sessions/session-one/automation-controls",f.json.createObjectNode().put("expectedVersion",1).put("control","PAUSE_AUTOMATION"),"k-ac-1",200);
        assertEquals("PAUSE_AUTOMATION",seen.get("POST /api/v2/sessions/session-one/automation-controls").path("body").path("control").asText());
    }

    @Test public void strictBodiesRejectUnknownFieldsAndMissingGuards()throws Exception {
        // Unknown field on the session patch — permission fields are unreachable.
        call("PATCH","/personal/v1/agent/sessions/session-one",f.json.createObjectNode().put("expectedSessionVersion",3).put("permissionMode","FULL_ACCESS"),"k-bad-1",400);
        call("PATCH","/personal/v1/agent/sessions/session-one",f.json.createObjectNode().put("title","x"),"k-bad-2",400);
        ObjectNode extra=f.json.createObjectNode().put("expectedSettingsRevision",2);extra.putObject("settings");extra.put("extra",1);
        call("PATCH","/personal/v1/agent/sessions/session-one/model-settings",extra,"k-bad-3",400);
        ObjectNode nested=f.json.createObjectNode().put("expectedSettingsRevision",2);nested.putObject("settings").put("unknownSetting",true);
        call("PATCH","/personal/v1/agent/sessions/session-one/model-settings",nested,"k-bad-3b",400);
        call("PATCH","/personal/v1/agent/sessions/session-one/model-settings",f.json.createObjectNode().put("expectedSettingsRevision",2),"k-bad-4",400);
        call("POST","/personal/v1/agent/sessions/session-one/automation-controls",f.json.createObjectNode().put("expectedVersion",1).put("control","DELETE_EVERYTHING"),"k-bad-5",400);
        call("POST","/personal/v1/agent/approvals/approval-one/advance",f.json.createObjectNode().put("expectedVersion",0).put("fingerprint","fp"),"k-bad-6",400);
        call("POST","/personal/v1/agent/approvals/approval-one/advance",f.json.createObjectNode().put("expectedVersion",1),"k-bad-7",400);
        call("POST","/personal/v1/agent/approvals/approval-one/decisions",f.json.createObjectNode().put("expectedVersion",1).put("fingerprint","fp"),"k-bad-8",400);
        call("POST","/personal/v1/agent/runs/run-one/continue",f.json.createObjectNode().put("expectedStateVersion","two"),"k-bad-9",400);
        call("POST","/personal/v1/agent/runs/run-one/continue",f.json.createObjectNode().put("bogus",1),"k-bad-10",400);
        // Idempotency-Key is required on every forwarded write.
        call("POST","/personal/v1/agent/runs/run-one/continue",f.json.createObjectNode().put("expectedStateVersion",1),null,400);
        // Query names are limited to what upstream actually reads per route.
        call("GET","/personal/v1/agent/runs/run-one/activities?cursor=1",null,null,400);
        call("GET","/personal/v1/agent/runs/run-one/reasoning?limit=10",null,null,400);
        call("GET","/personal/v1/agent/sessions/session-one/model-settings?limit=10",null,null,400);
        // Nothing upstream-verified means OPERATION_NOT_ALLOWED, never a proxy.
        call("GET","/personal/v1/agent/sessions/session-one/events",null,null,403);
        call("POST","/personal/v1/agent/runs/run-one/cancel",f.json.createObjectNode(),"k-x-1",403);
        call("PATCH","/personal/v1/agent/approvals/approval-one",f.json.createObjectNode(),"k-x-2",403);
    }

    @Test public void permissionModePathsNeverLeaveTheLocalPlane()throws Exception {
        // permissionMode is session state, not a route: no upstream surface
        // exists, so every spelling must be refused locally without a forward.
        call("GET","/personal/v1/agent/sessions/session-one/permission-mode",null,null,403);
        call("PATCH","/personal/v1/agent/sessions/session-one/permission-mode",
                f.json.createObjectNode().put("expectedSessionVersion",3).put("mode","FULL_ACCESS"),"k-pm-1",403);
        call("POST","/personal/v1/agent/sessions/session-one/permission-mode",
                f.json.createObjectNode().put("mode","READ_ONLY"),"k-pm-2",403);
        call("GET","/personal/v1/agent/sessions/session-one/permissionMode",null,null,403);
        call("DELETE","/personal/v1/agent/sessions/session-one/permission-mode",null,"k-pm-3",403);
        assertTrue("permissionMode forwarded upstream: " + seen,seen.isEmpty());
    }

    @Test public void grantActionsGateEachRouteFamily()throws Exception {
        grant(new TreeSet<String>(Arrays.asList("OBSERVE")),true);
        call("GET","/personal/v1/agent/sessions/session-one",null,null,200);
        call("GET","/personal/v1/agent/runs/run-one/activities",null,null,200);
        call("GET","/personal/v1/agent/sessions/session-one/model-settings",null,null,200);
        call("PATCH","/personal/v1/agent/sessions/session-one",f.json.createObjectNode().put("expectedSessionVersion",3).put("title","x"),"k-denied-1",403);
        ObjectNode settingsOnly=f.json.createObjectNode().put("expectedSettingsRevision",2);settingsOnly.putObject("settings");
        call("PATCH","/personal/v1/agent/sessions/session-one/model-settings",settingsOnly,"k-denied-2",403);
        call("POST","/personal/v1/agent/runs/run-one/continue",null,"k-denied-3",403);
        call("POST","/personal/v1/agent/approvals/approval-one/advance",f.json.createObjectNode().put("expectedVersion",1).put("fingerprint","fp"),"k-denied-4",403);
        call("POST","/personal/v1/agent/sessions/session-one/automation-controls",f.json.createObjectNode().put("expectedVersion",1).put("control","PAUSE_AUTOMATION"),"k-denied-5",403);
        call("POST","/personal/v1/agent/sessions/session-one/archive",f.json.createObjectNode().put("expectedSessionVersion",3),"k-denied-6",403);
        // Shrinking the session grant out entirely closes every route on it.
        grant(new TreeSet<String>(Arrays.asList("OBSERVE")),false);
        call("GET","/personal/v1/agent/sessions/session-one",null,null,403);
        call("GET","/personal/v1/agent/runs/run-one/activities",null,null,403);
        call("GET","/personal/v1/agent/sessions/session-one/model-settings",null,null,403);
    }

    @Test public void revokedDeviceFailsClosedOnEveryNewRoute()throws Exception {
        call("GET","/personal/v1/agent/sessions/session-one",null,null,200);
        call("GET","/personal/v1/agent/authorized-scope",null,null,200);
        f.auth.revoke(f.did);
        call("GET","/personal/v1/agent/sessions/session-one",null,null,401);
        call("GET","/personal/v1/agent/authorized-scope",null,null,401);
        call("POST","/personal/v1/agent/runs/run-one/continue",null,"k-revoked-1",401);
        ObjectNode settingsOnly=f.json.createObjectNode().put("expectedSettingsRevision",2);settingsOnly.putObject("settings");
        call("PATCH","/personal/v1/agent/sessions/session-one/model-settings",settingsOnly,"k-revoked-2",401);
    }

    @Test public void authorizedScopeProjectsOnlyGrantedRecords()throws Exception {
        JsonNode scope=call("GET","/personal/v1/agent/authorized-scope",null,null,200);
        assertEquals("personal-authorized-scope/1",scope.path("schemaVersion").asText());
        assertTrue(scope.path("sessions").isArray()&&scope.path("sessions").size()==1&&"session-one".equals(scope.path("sessions").get(0).asText()));
        assertEquals(1,scope.path("resources").size());
        assertEquals("ACS_SESSION",scope.path("resources").get(0).path("kind").asText());
        assertEquals("session-one",scope.path("resources").get(0).path("sessionId").asText());
        assertEquals(1,scope.path("launchScopes").size());
        JsonNode launch=scope.path("launchScopes").get(0);
        assertEquals("native-store",launch.path("backendStoreId").asText());
        assertEquals("project-one",launch.path("projectId").asText());
        assertEquals("pi",launch.path("engineId").asText());
        // Shrink the durable grant: the surface reports only what remains.
        // A scope can never be fully empty (INVALID_SCOPE), so the helper
        // keeps the launch grant — sessions/resources are gone, launch
        // stays, and the action set mirrors exactly that.
        grant(new TreeSet<String>(Arrays.asList("OBSERVE")),false);
        JsonNode shrunk=call("GET","/personal/v1/agent/authorized-scope",null,null,200);
        assertEquals(0,shrunk.path("sessions").size());
        assertEquals(0,shrunk.path("resources").size());
        assertEquals(1,shrunk.path("launchScopes").size());
        assertEquals(2,shrunk.path("actions").size());
    }

    /** Builds one approval response body; null omits the field entirely. */
    private ObjectNode approvalBody(Long expectedVersion,String fingerprint,String decision){
        ObjectNode body=f.json.createObjectNode();
        if(expectedVersion!=null)body.put("expectedVersion",expectedVersion.longValue());
        if(fingerprint!=null)body.put("fingerprint",fingerprint);
        if(decision!=null)body.put("decision",decision);
        return body;
    }

    /**
     * L1-RT-06 / F18: a response to an input request is bound to that request —
     * the approvalId in the route, the live {@code expectedVersion} and the
     * request fingerprint. Missing or stale bindings are refused (400 at the
     * edge, upstream 4xx/409 relayed verbatim); a request already settled by
     * another end refuses a contradicting resubmit instead of auto-approving
     * the new revision. PAC relays each admitted attempt exactly once and
     * never rewrites the binding fields itself — resubmission policy belongs
     * to the client, never to this relay.
     */
    @Test public void approvalDecisionsBindRequestIdAndRevision()throws Exception {
        final AtomicLong version=new AtomicLong(7);
        final AtomicReference<String> settled=new AtomicReference<String>();
        final AtomicInteger decisionPosts=new AtomicInteger(),advancePosts=new AtomicInteger();
        // Stateful slice of the upstream approval authority for this test,
        // mirroring ExecutionPermissionApiHandler + ExecutionPermissionCoordinator
        // check order: fingerprint, then live revision, then terminal state.
        // An identical resubmit replays the recorded decision (idempotent); a
        // contradicting one conflicts. Longest-prefix context shadows the
        // generic /api/v2/ stub for approvals only.
        upstream.createContext("/api/v2/approvals/",x->{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            byte[] raw=x.getRequestBody().readAllBytes();
            JsonNode body=raw.length==0?null:f.json.readTree(raw);
            int status=404;ObjectNode reply=f.json.createObjectNode().put("code","APPROVAL_NOT_FOUND");
            if(method.equals("GET")&&path.equals("/api/v2/approvals/approval-one")){
                status=200;reply=f.json.createObjectNode().put("approvalId","approval-one").put("sessionId","session-one").put("kind","COMMAND")
                        .put("requestFingerprint","fp-req").put("version",version.get());
                if(settled.get()!=null)reply.put("status","DECIDED").put("decision",settled.get());
            }else if(method.equals("POST")&&(path.equals("/api/v2/approvals/approval-one/decisions")||path.equals("/api/v2/approvals/approval-one/advance"))){
                if(path.endsWith("/decisions"))decisionPosts.incrementAndGet();else advancePosts.incrementAndGet();
                ObjectNode record=f.json.createObjectNode().put("method","POST").put("path",path);
                if(x.getRequestHeaders().getFirst("Idempotency-Key")!=null)record.put("key",x.getRequestHeaders().getFirst("Idempotency-Key"));
                if(body!=null)record.set("body",body);seen.put("POST "+path,record);
                long expected=body==null?-1L:body.path("expectedVersion").asLong(-1L);
                String fingerprint=body==null?"":body.path("fingerprint").asText("");
                if(!"fp-req".equals(fingerprint)){status=409;reply=f.json.createObjectNode().put("code","APPROVAL_FINGERPRINT_MISMATCH");}
                else if(expected!=version.get()){status=409;reply=f.json.createObjectNode().put("code","STATE_VERSION_CONFLICT").put("version",version.get());}
                else if(settled.get()!=null){
                    if(path.endsWith("/decisions")&&settled.get().equals(body.path("decision").asText())){status=200;reply=f.json.createObjectNode().put("approvalId","approval-one").put("sessionId","session-one").put("status","DECIDED").put("decision",settled.get()).put("version",version.get());}
                    else{status=409;reply=f.json.createObjectNode().put("code","APPROVAL_NOT_PENDING");}
                }else if(path.endsWith("/decisions")){settled.set(body.path("decision").asText());version.incrementAndGet();
                    status=200;reply=f.json.createObjectNode().put("approvalId","approval-one").put("sessionId","session-one").put("status","DECIDED").put("decision",settled.get()).put("version",version.get());
                }else{status=200;reply=f.json.createObjectNode().put("approvalId","approval-one").put("sessionId","session-one").put("version",version.get());}
            }
            byte[] bytes=f.json.writeValueAsBytes(reply);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
        });
        // The device learns the live binding tuple from the approval read.
        JsonNode pending=call("GET","/personal/v1/agent/approvals/approval-one",null,null,200);
        assertEquals(7,pending.path("version").asLong());
        assertEquals("fp-req",pending.path("requestFingerprint").asText());
        // (a) A response missing the request id in the route is not even an
        // approval write; a wrong request id fails the upstream lookup closed.
        call("POST","/personal/v1/agent/approvals//decisions",approvalBody(7L,"fp-req","APPROVE"),"k-ap-noid",400);
        JsonNode ghost=call("POST","/personal/v1/agent/approvals/approval-ghost/decisions",approvalBody(7L,"fp-req","APPROVE"),"k-ap-ghost",404);
        assertEquals("NOT_FOUND",ghost.path("code").asText());
        // A response missing the revision or the fingerprint binding is
        // refused at the edge — the authority never sees it.
        call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(null,"fp-req","APPROVE"),"k-ap-nov",400);
        call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(0L,"fp-req","APPROVE"),"k-ap-zerov",400);
        call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(7L,null,"APPROVE"),"k-ap-nofp",400);
        call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(7L,"","APPROVE"),"k-ap-emptyfp",400);
        assertEquals(0,decisionPosts.get());
        assertFalse("ghost write forwarded: "+seen,seen.containsKey("POST /api/v2/approvals/approval-ghost/decisions"));
        // A fingerprint from another request conflicts instead of deciding.
        JsonNode wrongFingerprint=call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(7L,"fp-other-request","APPROVE"),"k-ap-wrongfp",409);
        assertEquals("APPROVAL_FINGERPRINT_MISMATCH",wrongFingerprint.path("code").asText());
        // A stale revision conflicts; the forwarded body is byte-faithful —
        // expectedVersion stays 6, nothing is rebased to the live revision.
        JsonNode stale=call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(6L,"fp-req","APPROVE"),"k-ap-stale",409);
        assertEquals("STATE_VERSION_CONFLICT",stale.path("code").asText());
        assertEquals(6,seen.get("POST /api/v2/approvals/approval-one/decisions").path("body").path("expectedVersion").asLong());
        assertNull(settled.get());
        // (b) One end settles the request at revision 7 (e.g. the desktop
        // answered upstream): the approval moves to version 8.
        JsonNode decided=call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(7L,"fp-req","DENY"),"k-ap-deny",200);
        assertEquals("DENY",decided.path("decision").asText());
        assertEquals("DENY",settled.get());
        assertEquals("k-ap-deny",seen.get("POST /api/v2/approvals/approval-one/decisions").path("key").asText());
        // Re-submitting the identical decision replays the recorded fact —
        // no second application; the recorded decision is never displaced.
        JsonNode replay=call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(8L,"fp-req","DENY"),"k-ap-replay",200);
        assertEquals("DENY",replay.path("decision").asText());
        assertEquals("DENY",settled.get());
        // A contradicting resubmit on the stale revision drifts-conflicts...
        JsonNode drift=call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(7L,"fp-req","APPROVE"),"k-ap-drift",409);
        assertEquals("STATE_VERSION_CONFLICT",drift.path("code").asText());
        // ...and on the live revision hits the already-settled wall.
        JsonNode late=call("POST","/personal/v1/agent/approvals/approval-one/decisions",approvalBody(8L,"fp-req","APPROVE"),"k-ap-late",409);
        assertEquals("APPROVAL_NOT_PENDING",late.path("code").asText());
        assertEquals("DENY",settled.get());
        // The sibling advance route enforces the same binding contract.
        JsonNode advance=call("POST","/personal/v1/agent/approvals/approval-one/advance",approvalBody(7L,"fp-req",null),"k-ap-adv",409);
        assertEquals("STATE_VERSION_CONFLICT",advance.path("code").asText());
        // (c) Every admitted attempt produced exactly one upstream write —
        // the relay issued no corrective resend and no auto-approval.
        assertEquals(6,decisionPosts.get());
        assertEquals(1,advancePosts.get());
    }
}
