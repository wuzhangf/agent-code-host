package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real loopback HTTP and durable PAC store; host facts are controlled, no model is substituted into PAC. */
public class PersonalLocalSourcesTest {
    final ObjectMapper json=new ObjectMapper();
    final List<HttpServer> hosts=new ArrayList<>();
    Path root;ControlPlaneDatabase db;PersonalAccessStore store;PersonalAccessService auth;
    final String secretA=UUID.randomUUID().toString(),secretB=UUID.randomUUID().toString();
    volatile String identityB="store-b";
    PersonalLocalTaskAccess access;
    @Before public void setup()throws Exception {
        root=Files.createTempDirectory("pac-multiple-sources-");db=new ControlPlaneDatabase(root,false);store=new PersonalAccessStore(db,json);
        byte[] seal=new byte[32];new java.security.SecureRandom().nextBytes(seal);auth=new PersonalAccessService(store,json,seal,"acs-store","https://localhost");
        ArrayNode config=json.createArrayNode();
        config.addObject().put("backendStoreId","store-a").put("origin",host("store-a",secretA)).put("tokenEnv","SOURCE_A_TOKEN");
        config.addObject().put("backendStoreId","store-b").put("origin",host("store-b",secretB)).put("tokenEnv","SOURCE_B_TOKEN");
        access=PersonalLocalTaskAccess.registered(config,store,auth,json,name->name.equals("SOURCE_A_TOKEN")?secretA:secretB);
    }
    String host(String backend,String token)throws Exception {
        HttpServer h=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);hosts.add(h);
        h.createContext("/local/v1/",x->{
            String id=backend.equals("store-b")?identityB:backend;
            ObjectNode body=json.createObjectNode();int status=200;
            String guard=x.getRequestHeaders().getFirst("X-Worker-Local-Store");
            if(!("Bearer "+token).equals(x.getRequestHeaders().getFirst("Authorization")))status=401;
            else if(guard!=null&&!guard.equals(id))status=409;
            else if(x.getRequestURI().getPath().endsWith("/identity"))body.put("storeId",id).put("storeGuard","HEADER_V1");
            else if(x.getRequestURI().getPath().endsWith("/projects"))body.putArray("items").addObject().put("projectId","project-"+backend);
            else if(x.getRequestURI().getPath().endsWith("/engines"))body.putArray("items").addObject().put("engineId",backend.equals("store-a")?"pi":"codex");
            else if(x.getRequestURI().getPath().endsWith("/task-preflights"))body.put("projectId","project-"+backend).put("engineId",backend.equals("store-a")?"pi":"codex").put("profileId","USER_PROFILE");
            else body.put("servedBy",backend);
            byte[] bytes=json.writeValueAsBytes(body);x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();
        });h.start();return "http://127.0.0.1:"+h.getAddress().getPort();
    }
    @After public void cleanup()throws Exception {for(HttpServer h:hosts)h.stop(0);db.close();try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
    PersonalAccessService.Device device(){PersonalAccessService.Device d=new PersonalAccessService.Device();d.id="internal-authorized-device";return d;}
    @Test public void routesStoreGuardAndCredentialsAndCatalogIndependently()throws Exception {
        assertEquals("store-a",access.call(device(),"store-a","GET","/tasks/one",null,null).body.path("servedBy").asText());
        assertEquals("store-b",access.call(device(),"store-b","GET","/tasks/one",null,null).body.path("servedBy").asText());
        JsonNode rows=access.ownerCatalog().path("sources");assertEquals(2,rows.size());
        assertEquals("pi",rows.get(0).path("engines").get(0).path("engineId").asText());
        assertEquals("codex",rows.get(1).path("engines").get(0).path("engineId").asText());
        assertFalse(rows.toString().contains(secretA));assertFalse(rows.toString().contains("tokenEnv"));
    }
    @Test public void changedIdentityFailsClosedWhileOtherSourceSurvives()throws Exception {
        identityB="replaced-store";
        try{access.call(device(),"store-b","GET","/tasks/one",null,null);fail();}catch(BusinessException expected){assertEquals("LOCAL_SOURCE_IDENTITY_CHANGED",expected.getCode());}
        assertEquals("store-a",access.call(device(),"store-a","GET","/tasks/one",null,null).body.path("servedBy").asText());
        assertFalse(access.ownerCatalog().path("sources").get(1).path("available").asBoolean());
        try{access.call(device(),"unknown-store","GET","/tasks/one",null,null);fail();}catch(BusinessException expected){assertEquals("LOCAL_SOURCE_NOT_CONFIGURED",expected.getCode());}
    }
    @Test public void offlineSourceDoesNotHideHealthyCatalog()throws Exception {
        hosts.get(1).stop(0);JsonNode rows=access.ownerCatalog().path("sources");
        assertTrue(rows.get(0).path("available").asBoolean());assertFalse(rows.get(1).path("available").asBoolean());
    }
    @Test public void grantedDeviceOptionsAreSelectedByBackendAndNeverLeakOtherSource()throws Exception {
        List<PersonalResourceScope.LaunchScope> launches=Arrays.asList(new PersonalResourceScope.LaunchScope("store-a","project-store-a","pi"),new PersonalResourceScope.LaunchScope("store-b","project-store-b","codex"));
        access.validateOwnerResources(Collections.emptyList(),launches);
        Set<String> actions=new TreeSet<>(Arrays.asList("OBSERVE","LAUNCH"));
        ObjectNode invitation=auth.invitationResources("two-source-invite",Collections.emptyList(),launches,actions,"test-cert");
        String request=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();
        auth.request(json.createObjectNode().put("inviteId","two-source-invite").put("inviteSecret",invitation.path("inviteSecret").asText()).put("pairingRequestId",request).put("installationId","two-source-phone").put("label","phone").put("pollingSecretHash",PersonalAccessService.hash(poll)));
        auth.decide(request,true);String token=auth.redeem(request,poll,"two-source-redeem").path("accessToken").asText();
        JsonNode a=access.remote(token,"GET","/personal/v1/local-tasks/store-a/options",null,null,null).body;
        JsonNode b=access.remote(token,"GET","/personal/v1/local-tasks/store-b/options",null,null,null).body;
        assertEquals("store-a",a.path("backendStoreId").asText());assertEquals("pi",a.path("engines").get(0).path("engineId").asText());
        assertEquals("store-b",b.path("backendStoreId").asText());assertEquals("codex",b.path("engines").get(0).path("engineId").asText());
        hosts.get(1).stop(0);
        assertEquals(200,access.remote(token,"GET","/personal/v1/local-tasks/store-a/options",null,null,null).status);
    }
}
