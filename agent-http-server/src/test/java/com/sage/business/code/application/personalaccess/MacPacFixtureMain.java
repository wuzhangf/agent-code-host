package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Disposable integration harness. The source is the registered actual native host;
 * authorization, owner routes, pairing, grants, HTTPS, UCA and proxy are production code.
 * /fixture is ONLY a separate loopback test owner helper, never a production route. */
public final class MacPacFixtureMain implements AutoCloseable {
    private final ObjectMapper json=ClientAccessWire.JSON;
    private final Path root;
    private final ControlPlaneDatabase database;
    private final PersonalAccessService auth;
    private final PersonalAccessServer personal;
    private final PersonalLocalTaskAccess nativeAccess;
    private final HttpServer owner,control;
    private final ExecutorService ownerThreads=Executors.newFixedThreadPool(2),controlThreads=Executors.newFixedThreadPool(2);
    private final ClientSessionRegistry registry;
    private final String ownerToken=UUID.randomUUID().toString()+UUID.randomUUID();
    private final List<String> projects;
    private final String backend,origin;
    private String ownerClientSession,ownerClientToken;
    private boolean enabled;
    private ObjectNode invitation;
    private String seedTask;
    public static final class TlsMaterial {
        public final SSLContext context;public final X509Certificate certificate;
        TlsMaterial(SSLContext context,X509Certificate certificate){this.context=context;this.certificate=certificate;}
    }
    public static TlsMaterial ephemeralTls(Path root)throws Exception {
        Path file=root.resolve("fixture-only-tls.p12");String password=UUID.randomUUID().toString();
        String binary=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process process=new ProcessBuilder(binary,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(root.resolve("fixture-only-keytool.log").toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IllegalStateException("EPHEMERAL_TLS_UNAVAILABLE");
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(file)){keys.load(input,password.toCharArray());}finally{Files.deleteIfExists(file);}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,password.toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        SSLContext context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
        return new TlsMaterial(context,(X509Certificate)keys.getCertificate("fixture"));
    }
    public MacPacFixtureMain(Path root,String source,String token,List<String> projects,int tlsPort)throws Exception {this(root,source,token,projects,tlsPort,0,null);}
    public MacPacFixtureMain(Path root,String source,String token,List<String> projects,int tlsPort,int controlPort)throws Exception {this(root,source,token,projects,tlsPort,controlPort,null);}
    public MacPacFixtureMain(Path root,String source,String token,List<String> projects,int tlsPort,int controlPort,String existingSeed)throws Exception {
        if(root==null||!root.isAbsolute()||Files.exists(root.resolve("pac-state")))throw new IllegalArgumentException("FRESH_DISPOSABLE_STATE_REQUIRED");
        if(projects.isEmpty()||projects.size()>4)throw new IllegalArgumentException("EXPLICIT_MAC_PROJECTS_REQUIRED");
        this.root=root;this.projects=new ArrayList<String>(projects);for(String id:projects)PersonalAccessService.id(id);
        Files.createDirectories(root);try{Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
        if(tlsPort==0)try(ServerSocket probe=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){tlsPort=probe.getLocalPort();}
        origin="https://localhost:"+tlsPort;TlsMaterial tls=ephemeralTls(root);
        byte[] seal=new byte[32];new SecureRandom().nextBytes(seal);
        database=new ControlPlaneDatabase(root.resolve("pac-state"),false);
        PersonalAccessStore store=new PersonalAccessStore(database,json);String storeId="mac-pac-"+UUID.randomUUID();
        auth=new PersonalAccessService(store,json,seal,storeId,origin);Arrays.fill(seal,(byte)0);
        registry=new ClientSessionRegistry(storeId,UUID.randomUUID().toString());
        owner=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);owner.setExecutor(ownerThreads);
        nativeAccess=new PersonalLocalTaskAccess(source,token,store,auth,json);
        ObjectNode catalog=nativeAccess.ownerCatalog();backend=catalog.path("backendStoreId").asText();
        boolean mac=false;for(JsonNode engine:catalog.path("engines"))if("macos".equals(engine.path("engineId").asText())&&"MAC_MANAGED".equals(engine.path("profileId").asText()))mac=true;
        if(!mac)throw new IllegalStateException("MAC_ADOPTION_SOURCE_REQUIRED");
        personal=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",tlsPort),tls.context,tls.certificate,auth,registry,json,new URI("http://127.0.0.1:"+owner.getAddress().getPort()),ownerToken).localTasks(nativeAccess);
        owner.createContext("/api/v2/personal-access",personal.ownerHandler());owner.start();personal.start();
        try{seedTask=prepareSeed(existingSeed);}catch(Exception failure){personal.close();owner.stop(0);ownerThreads.shutdownNow();controlThreads.shutdownNow();database.close();throw failure;}
        control=HttpServer.create(new InetSocketAddress("127.0.0.1",controlPort),8);control.setExecutor(controlThreads);control.createContext("/fixture/",this::fixture);control.start();
        String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(tls.certificate.getEncoded())+"\n-----END CERTIFICATE-----\n";
        Files.write(root.resolve("public-fixture-ca.pem"),pem.getBytes(StandardCharsets.US_ASCII));
        ObjectNode ready=json.createObjectNode().put("schemaVersion","mac001-pac-ready/1").put("fixturePort",control.getAddress().getPort()).put("tlsPort",tlsPort).put("origin",origin).put("projectId",projects.get(0));
        if(projects.size()>1)ready.put("stopProjectId",projects.get(1));
        Files.write(root.resolve("ready.json"),json.writeValueAsBytes(ready));
        String readyFile=System.getenv("MAC001_PAC_READY_FILE");if(readyFile!=null){Path target=Paths.get(readyFile),temporary=target.resolveSibling(target.getFileName()+".tmp-"+UUID.randomUUID());Files.write(temporary,json.writeValueAsBytes(ready));try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
        System.out.println("MAC_PAC_READY "+ready);System.out.flush();
    }
    private synchronized String ownerCredential(){
        if(ownerClientToken!=null)try{registry.heartbeat("local-owner",ownerClientSession,ownerClientToken);return ownerClientToken;}catch(RuntimeException expired){ownerClientToken=null;}
        String key=UUID.randomUUID().toString();com.sage.business.code.application.client.ClientRegistrationResult registered=registry.register("local-owner",new ClientSessionRegistry.RegistrationRequest(key,PersonalAccessService.hash(key),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"WEB_TAB",Arrays.asList("business-codeagent/2"),"MAC-001 disposable owner"));
        ownerClientSession=registered.getClientSessionId();ownerClientToken=registered.getToken();return ownerClientToken;
    }
    private JsonNode owner(String method,String path,JsonNode body)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+owner.getAddress().getPort()+path).openConnection();connection.setInstanceFollowRedirects(false);connection.setRequestMethod(method);connection.setRequestProperty("Authorization","Bearer "+ownerToken);connection.setRequestProperty("X-CodeAgent-Client-Token",ownerCredential());connection.setConnectTimeout(3000);connection.setReadTimeout(15000);
        try {
            if(body!=null){connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);try(OutputStream output=connection.getOutputStream()){json.writeValue(output,body);}}
            int status=connection.getResponseCode();InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();JsonNode result=json.readTree(input);if(status>=400)throw new IllegalStateException("OWNER_ROUTE_REJECTED_"+status);return result;
        }finally{connection.disconnect();}
    }
    /** Uses a separate, subsequently revoked controller. No empty scope or invented seed resource. */
    private String prepareSeed(String existing)throws Exception {
        if(existing!=null)PersonalAccessService.id(existing);
        ObjectNode request=json.createObjectNode().put("requestId",UUID.randomUUID().toString());
        ArrayNode resources=request.putArray("resources"),scopes=request.putArray("launchScopes"),actions=request.putArray("actions").add("OBSERVE");
        if(existing!=null)resources.addObject().put("kind","WORKER_LOCAL").put("backendStoreId",backend).put("taskId",existing);
        else {scopes.addObject().put("backendStoreId",backend).put("projectId",projects.get(0)).put("engineId","macos");actions.add("LAUNCH");}
        JsonNode invite=owner("POST","/api/v2/personal-access/pairing-invitations",request);
        String pairing=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();
        auth.request(json.createObjectNode().put("pairingRequestId",pairing).put("inviteId",invite.path("inviteId").asText()).put("inviteSecret",invite.path("inviteSecret").asText()).put("installationId","mac-seed-"+UUID.randomUUID()).put("label","MAC-001 disposable seed controller").put("pollingSecretHash",PersonalAccessService.hash(poll)));
        owner("POST","/api/v2/personal-access/pairing-requests/"+pairing+"/decisions",json.createObjectNode().put("approve",true));
        JsonNode issued=auth.redeem(pairing,poll,UUID.randomUUID().toString());String token=issued.path("accessToken").asText(),device=issued.path("deviceGrantId").asText();
        String task=existing;long deadline=System.currentTimeMillis()+600000L;
        try {
            if(task==null){String base="/personal/v1/local-tasks/"+backend;
                JsonNode preflight=nativeAccess.remote(token,"POST",base+"/task-preflights",json.createObjectNode().put("projectId",projects.get(0)).put("engineId","macos"),null,null).body;
                String key="mac-seed-"+UUID.randomUUID();ObjectNode body=json.createObjectNode().put("requestKey",key).put("projectId",projects.get(0)).put("engineId","macos").put("optionsRevision",preflight.path("optionsRevision").asText()).put("instruction","Run the trusted disposable MAC-001 seed acceptance profile; no production resources.");
                JsonNode accepted=nativeAccess.remote(token,"POST",base+"/tasks",body,key,null).body;
                while(!accepted.hasNonNull("taskId")&&System.currentTimeMillis()<deadline){Thread.sleep(200);accepted=nativeAccess.remote(token,"GET",base+"/task-requests/"+key,null,null,null).body;}
                task=accepted.path("taskId").asText();PersonalAccessService.id(task);
            }
            while(System.currentTimeMillis()<deadline){JsonNode facts=nativeAccess.remote(token,"GET","/personal/v1/local-tasks/"+backend+"/tasks/"+task,null,null,null).body;
                if("true".equals(System.getenv("MAC001_GRAPHICS"))&&"mac.app-workflow/1".equals(facts.path("recipeID").asText()))return task;
                if("SUCCEEDED".equals(facts.path("state").asText())&&facts.path("quiescent").asBoolean(false))return task;
                if(Arrays.asList("FAILED","WAITING_INPUT","WAITING_RESOURCE","RECOVERY_REQUIRED","CANCELLED").contains(facts.path("state").asText()))throw new IllegalStateException("DISPOSABLE_SEED_NOT_SUCCEEDED");
                Thread.sleep(500);
            }throw new IllegalStateException("DISPOSABLE_SEED_DEADLINE");
        }finally{owner("DELETE","/api/v2/personal-access/devices/"+device,null);}
    }
    private synchronized JsonNode operation(String path)throws Exception {
        if(path.equals("/fixture/invite")){
            if(invitation==null){ObjectNode request=json.createObjectNode().put("requestId",UUID.randomUUID().toString());request.putArray("resources").addObject().put("kind","WORKER_LOCAL").put("backendStoreId",backend).put("taskId",seedTask);request.putArray("launchScopes");request.putArray("actions").add("OBSERVE");invitation=(ObjectNode)owner("POST","/api/v2/personal-access/pairing-invitations",request);}return invitation;
        }
        if(path.equals("/fixture/approve")){
            List<JsonNode> pending=new ArrayList<JsonNode>();for(JsonNode pairing:owner("GET","/api/v2/personal-access/pairing-requests",null))if("PENDING".equals(pairing.path("state").asText()))pending.add(pairing);
            if(pending.size()!=1)throw new IllegalStateException("UNIQUE_DISPOSABLE_PAIRING_REQUIRED");
            owner("POST","/api/v2/personal-access/pairing-requests/"+pending.get(0).path("pairingRequestId").asText()+"/decisions",json.createObjectNode().put("approve",true));return json.createObjectNode().put("approved",true);
        }
        if(path.equals("/fixture/enable-native")){
            if(!enabled){List<JsonNode> active=new ArrayList<JsonNode>();for(JsonNode device:owner("GET","/api/v2/personal-access/devices",null))if("ACTIVE".equals(device.path("state").asText()))active.add(device);
                if(active.size()!=1)throw new IllegalStateException("UNIQUE_DISPOSABLE_DEVICE_REQUIRED");JsonNode device=active.get(0);
                ObjectNode update=json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedGrantRevision",device.path("grantRevision").asText());ArrayNode resources=update.putArray("resources");if("true".equals(System.getenv("MAC001_GRAPHICS")))resources.addObject().put("kind","WORKER_LOCAL").put("backendStoreId",backend).put("taskId",seedTask);ArrayNode scopes=update.putArray("launchScopes");for(String project:projects)scopes.addObject().put("backendStoreId",backend).put("projectId",project).put("engineId","macos");ArrayNode actions=update.putArray("actions").add("OBSERVE").add("LAUNCH").add("CONTROL");
                owner("PUT","/api/v2/personal-access/devices/"+device.path("deviceGrantId").asText()+"/scope",update);enabled=true;
            }return json.createObjectNode().put("backendStoreId",backend);
        }
        if("true".equals(System.getenv("MAC001_GRAPHICS"))&&(path.equals("/fixture/enable-graphics")||path.equals("/fixture/revoke-graphics"))){
            if(!enabled)throw new IllegalStateException("ENABLE_NATIVE_SCOPE_FIRST");
            List<JsonNode> active=new ArrayList<JsonNode>();for(JsonNode device:owner("GET","/api/v2/personal-access/devices",null))if("ACTIVE".equals(device.path("state").asText()))active.add(device);
            if(active.size()!=1)throw new IllegalStateException("UNIQUE_DISPOSABLE_DEVICE_REQUIRED");JsonNode device=active.get(0);
            ObjectNode update=json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedGrantRevision",device.path("grantRevision").asText());
            update.set("resources",device.path("resources"));update.set("launchScopes",device.path("launchScopes"));ArrayNode actions=update.putArray("actions");for(JsonNode action:device.path("actions"))if(!action.asText().startsWith("GRAPHICS_"))actions.add(action);
            if(path.equals("/fixture/enable-graphics"))actions.add("GRAPHICS_OBSERVE").add("GRAPHICS_CAPTURE").add("GRAPHICS_ACT");
            owner("PUT","/api/v2/personal-access/devices/"+device.path("deviceGrantId").asText()+"/scope",update);return json.createObjectNode().put("graphicsEnabled",path.equals("/fixture/enable-graphics"));
        }
        if("true".equals(System.getenv("MAC001_GRAPHICS"))&&path.equals("/fixture/graph-task"))return json.createObjectNode().put("taskId",seedTask).put("backendStoreId",backend).put("projectId",projects.get(0));
        if("true".equals(System.getenv("MAC001_GRAPHICS"))&&path.equals("/fixture/fixture-status")){
            String configured=System.getenv("GRAPHICS_FIXTURE_STATUS_PATH");if(configured==null)throw new IllegalStateException("OWNED_FIXTURE_STATUS_REQUIRED");Path file=Paths.get(configured).toRealPath();if(!file.startsWith(Paths.get("/private/tmp/mac-graphics-20261025"))||Files.size(file)>16384)throw new IllegalStateException("OWNED_FIXTURE_STATUS_REQUIRED");JsonNode status=json.readTree(Files.readAllBytes(file));if(status==null||!status.isObject())throw new IllegalStateException("INVALID_FIXTURE_STATUS");return status;
        }
        if(path.equals("/fixture/state")){ObjectNode out=json.createObjectNode().put("mode","ACTUAL_NATIVE_HOST_PRODUCTION_PAC_ADAPTER").put("backendStoreId",backend).put("nativeEnabled",enabled);out.set("devices",auth.devices());return out;}
        throw new IllegalArgumentException("FIXED_FIXTURE_ROUTE_REQUIRED");
    }
    private void fixture(HttpExchange exchange)throws IOException {
        try {
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()||!"GET".equals(exchange.getRequestMethod())||exchange.getRequestURI().getRawQuery()!=null){exchange.sendResponseHeaders(403,-1);return;}
            JsonNode result=operation(exchange.getRequestURI().getRawPath());byte[] bytes=json.writeValueAsBytes(result);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception failure){byte[] bytes="{\"code\":\"FIXTURE_OPERATION_REJECTED\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(409,bytes.length);exchange.getResponseBody().write(bytes);System.err.println("MAC_PAC_FIXTURE_FAILURE "+failure.getClass().getSimpleName());}finally{exchange.close();}
    }
    public void close(){control.stop(0);personal.close();owner.stop(0);controlThreads.shutdownNow();ownerThreads.shutdownNow();database.close();}
    public static void main(String[] args)throws Exception {
        Map<String,String> options=new HashMap<String,String>();for(int i=0;i<args.length;i+=2){if(i+1==args.length)throw new IllegalArgumentException("NAMED_ARGUMENT_VALUE_REQUIRED");options.put(args[i],args[i+1]);}
        String token=System.getenv("WORKER_LOCAL_TOKEN");if(token==null||token.length()<24)throw new IllegalArgumentException("INHERITED_WORKER_LOCAL_TOKEN_REQUIRED");
        String source=options.containsKey("--worker-url")?options.get("--worker-url"):options.get("--native-origin");
        String projectList=options.containsKey("--project")?options.get("--project"):options.get("--projects");
        MacPacFixtureMain fixture=new MacPacFixtureMain(Paths.get(options.get("--state")).toAbsolutePath(),source,token,Arrays.asList(projectList.split(",")),Integer.parseInt(options.getOrDefault("--tls-port",System.getenv("MAC001_PAC_TLS_PORT")==null?"0":System.getenv("MAC001_PAC_TLS_PORT"))),Integer.parseInt(System.getenv("MAC001_PAC_FIXTURE_PORT")==null?"0":System.getenv("MAC001_PAC_FIXTURE_PORT")),options.get("--seed-task"));
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close,"mac-pac-owned-shutdown"));new CountDownLatch(1).await();
    }
}
