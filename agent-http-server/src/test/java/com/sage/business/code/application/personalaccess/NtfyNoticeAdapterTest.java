package com.sage.business.code.application.personalaccess;

import com.sage.business.code.bootstrap.PersonalNoticeSettings;
import com.sage.business.code.domain.ServiceVersion;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import static org.junit.Assert.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;

/** Real outbound HTTP headers/body, with a test-only loopback receiver.
 * Production PersonalNoticeSettings.from still requires an HTTPS server. */
public class NtfyNoticeAdapterTest {
    HttpServer receiver;
    AtomicInteger posts=new AtomicInteger(),redirected=new AtomicInteger();
    AtomicInteger status=new AtomicInteger(200);
    volatile String userAgent,authorization,contentType,title,click,tags,path;
    volatile byte[] payload;
    NtfyNoticeAdapter adapter;
    @Before public void setup()throws Exception {
        receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        receiver.createContext("/notice/pac-fixture-topic",x->{
            posts.incrementAndGet();path=x.getRequestURI().getRawPath();
            userAgent=x.getRequestHeaders().getFirst("User-Agent");
            authorization=x.getRequestHeaders().getFirst("Authorization");
            contentType=x.getRequestHeaders().getFirst("Content-Type");
            title=x.getRequestHeaders().getFirst("Title");click=x.getRequestHeaders().getFirst("Click");tags=x.getRequestHeaders().getFirst("Tags");
            payload=x.getRequestBody().readAllBytes();
            if(status.get()==307)x.getResponseHeaders().add("Location","/redirected");
            byte[] response="{}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status.get(),response.length);x.getResponseBody().write(response);x.close();
        });
        receiver.createContext("/redirected",x->{redirected.incrementAndGet();x.sendResponseHeaders(200,-1);x.close();});
        receiver.start();
        PersonalNoticeSettings settings=new PersonalNoticeSettings();settings.enabled=true;
        settings.server=URI.create("http://127.0.0.1:"+receiver.getAddress().getPort()+"/notice");
        adapter=new NtfyNoticeAdapter(settings,name->{assertEquals("NTFY_PUBLISH_TOKEN",name);return "fixture-publish-credential";},1000,1000);
    }
    @After public void cleanup(){if(receiver!=null)receiver.stop(0);}
    @Test public void outboundProductIdentityPreservesFixedPublishContract(){
        NtfyNoticeAdapter.Result result=adapter.publish("pac-fixture-topic","agentcontrol://inbox?host=fixture","test");
        assertTrue(result.accepted);assertEquals(200,result.statusCode);assertEquals(1,posts.get());
        assertEquals("Control-Computer/"+ServiceVersion.SERVICE_VERSION+" (Personal Agent)",userAgent);
        assertEquals("/notice/pac-fixture-topic",path);assertEquals("Bearer fixture-publish-credential",authorization);
        assertEquals("text/plain; charset=utf-8",contentType);assertEquals("Agent Control",title);
        assertEquals("agentcontrol://inbox?host=fixture",click);assertEquals("test",tags);
        assertArrayEquals(NtfyNoticeAdapter.FIXED_MESSAGE.getBytes(StandardCharsets.UTF_8),payload);
    }
    @Test public void rejectionAndRedirectNeverTriggerAnotherPublish(){
        status.set(307);NtfyNoticeAdapter.Result redirect=adapter.publish("pac-fixture-topic","agentcontrol://inbox?host=fixture");
        assertFalse(redirect.accepted);assertEquals(307,redirect.statusCode);assertEquals(1,posts.get());assertEquals(0,redirected.get());
        status.set(503);NtfyNoticeAdapter.Result unavailable=adapter.publish("pac-fixture-topic","agentcontrol://inbox?host=fixture");
        assertEquals("NTFY_HTTP_503",unavailable.error);assertEquals(2,posts.get());
        status.set(403);NtfyNoticeAdapter.Result forbidden=adapter.publish("pac-fixture-topic","agentcontrol://inbox?host=fixture");
        assertFalse(forbidden.accepted);assertTrue(forbidden.authenticationFailed);assertEquals(3,posts.get());assertEquals(0,redirected.get());
    }
}
