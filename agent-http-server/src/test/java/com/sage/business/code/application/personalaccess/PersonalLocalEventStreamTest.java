package com.sage.business.code.application.personalaccess;

import com.fasterxml.jackson.databind.*;
import com.sage.business.code.adapter.personalaccess.PersonalAccessServer;
import com.sage.business.code.application.client.ClientSessionRegistry;
import com.sage.business.code.domain.BusinessException;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

/**
 * worker-events/1 relay: the events GET streams real SSE blocks from the
 * fixed loopback source, every data payload crossing the same public-view
 * rules as the JSON page. A pre-stream source keeps the paged reply, and the
 * adapter bounds live relays so its fixed executor never starves polling.
 */
public class PersonalLocalEventStreamTest {
    static final String ROUTE="/personal/v1/local-tasks/native-store/tasks/native-one/events";
    PersonalLocalTaskAccessTest f;
    @Before public void setup()throws Exception{f=new PersonalLocalTaskAccessTest();f.setup();f.call("POST","/tasks",f.launch(),"original-launch");}
    @After public void cleanup()throws Exception{if(f.sseHold!=null)f.sseHold.countDown();f.cleanup();}

    /** Fake adapter sink: records whether the SSE head committed and the emitted bytes. */
    static final class Sink implements PersonalLocalTaskAccess.EventSink{
        boolean begun;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        public void begin(){begun=true;}
        public void write(byte[] frame){bytes.write(frame,0,frame.length);}
        String text(){return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
    }
    PersonalLocalTaskAccess.Result stream(String query,String lastEventId,Sink sink)throws Exception{
        return f.access.streamEvents(f.token,"GET",ROUTE,query,lastEventId,sink);
    }
    void denied(String code,Callable<?> task)throws Exception{try{task.call();fail("Expected "+code);}catch(BusinessException expected){assertEquals(code,expected.getCode());}}

    @Test public void dataPayloadsAreRewrittenThroughThePublicView()throws Exception {
        f.sseFrames=Arrays.asList(
            ": source-heartbeat\n\n".getBytes(StandardCharsets.UTF_8),
            "id: 7\nevent: delta\ndata: {\"kind\":\"ASSISTANT_DELTA\",\"delta\":\"visible text\",\"workspace\":\"/private/dir\",\"env\":{\"KEY\":\"secret\"},\"profileHome\":\"/home/u\",\"command\":\"argv-secret\",\"executablePath\":\"/opt/tool\"}\n\n".getBytes(StandardCharsets.UTF_8),
            "id: 8\nevent: done\ndata: {\"kind\":\"TASK_STATE\",\"state\":\"SUCCEEDED\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Sink sink=new Sink();assertNull(stream("cursor=6",null,sink));assertTrue(sink.begun);
        String out=sink.text();
        assertTrue(out.contains(": source-heartbeat\n\n"));
        assertTrue(out.contains("id: 7\nevent: delta\n"));
        assertTrue(out.contains("\"kind\":\"ASSISTANT_DELTA\""));
        assertTrue(out.contains("\"delta\":\"visible text\""));
        assertTrue(out.contains("id: 8\nevent: done\n"));
        for(String leaked:Arrays.asList("workspace","/private/dir","env","profileHome","/home/u","command","argv-secret","executablePath","/opt/tool"))
            assertFalse("leaked "+leaked,out.contains(leaked));
        assertEquals("cursor=6",f.eventsQuery);
        assertTrue("PAC must negotiate SSE with the source itself",f.eventsAccept.contains("text/event-stream"));
    }
    @Test public void eventKindAndRetryLinesPassThroughVerbatim()throws Exception {
        f.sseFrames=Arrays.asList(("retry: 3000\nid: 9\nevent: custom\ndata: {\"kind\":\"PING\"}\n\n").getBytes(StandardCharsets.UTF_8),
            "id: 10\nevent: marker\n\n".getBytes(StandardCharsets.UTF_8));
        Sink sink=new Sink();assertNull(stream(null,null,sink));
        String out=sink.text();
        assertTrue(out.contains("retry: 3000\nid: 9\nevent: custom\n"));
        // A field-only block (no data payload) is relayed unmodified.
        assertTrue(out.contains("id: 10\nevent: marker\n\n"));
    }
    @Test public void preStreamSourceKeepsThePagedJsonReply()throws Exception {
        // sseFrames unset: the source ignores the negotiation and answers the page.
        Sink sink=new Sink();
        PersonalLocalTaskAccess.Result page=stream("follow=1",null,sink);
        assertNotNull(page);assertEquals(200,page.status);assertFalse(sink.begun);
        assertTrue(page.body.has("items"));assertEquals("native-store",page.body.path("backendStoreId").asText());
        assertEquals("follow=1",f.eventsQuery);
        assertTrue(f.eventsAccept.contains("text/event-stream"));
    }
    @Test public void lastEventIdHeaderForwardsVerbatim()throws Exception {
        f.sseFrames=Arrays.asList("id: 42\nevent: ping\ndata: {\"kind\":\"PING\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Sink sink=new Sink();assertNull(stream(null,"41",sink));
        assertEquals("41",f.eventsLastEventId);
        assertTrue(sink.text().contains("id: 42\n"));
    }
    @Test public void sourceErrorBeforeTheHeadKeepsProblemShape()throws Exception {
        f.eventsErrorStatus=409;f.eventsErrorCode="EVENT_CURSOR_EXPIRED";
        denied("EVENT_CURSOR_EXPIRED",()->stream("cursor=9",null,new Sink()));
        f.eventsErrorStatus=400;f.eventsErrorCode="CURSOR_ARGUMENT_CONFLICT";
        Sink sink=new Sink();denied("CURSOR_ARGUMENT_CONFLICT",()->stream("cursor=9",null,sink));assertFalse(sink.begun);
        assertEquals(2,f.eventReads.get());
    }
    @Test public void malformedStreamInputsFailBeforeAnySourceCall()throws Exception {
        for(String bad:Arrays.asList("follow=0","follow=true","follow=1&follow=1","cursor=abc","cursor=1&cursor=2","limit=0","limit=101","page=1","cursor=1&bogus=2"))
            denied("INVALID_REQUEST",()->stream(bad,null,new Sink()));
        denied("INVALID_REQUEST",()->stream(null,"abc",new Sink()));
        denied("INVALID_REQUEST",()->stream(null,"-1",new Sink()));
        assertEquals(0,f.eventReads.get());
    }
    @Test public void graphicsTaskFramesUseThePublicItemWhitelist()throws Exception {
        f.taskRecipeId="mac.app-workflow/1";
        f.sseFrames=Arrays.asList(("id: 3\nevent: GRAPHICS_RESULT\n"
            +"data: {\"kind\":\"GRAPHICS_RESULT\",\"taskId\":\"native-one\",\"outcome\":\"CONFIRMED\",\"sequence\":\"3\",\"cursor\":\"3\",\"delta\":\"drop\",\"observation\":{\"elements\":[{\"value\":\"AX-secret\"}]},\"artifact\":{\"bytes\":\"PNG-bytes\"}}\n\n").getBytes(StandardCharsets.UTF_8));
        Sink sink=new Sink();assertNull(stream(null,null,sink));
        String out=sink.text();
        assertTrue(out.contains("id: 3\nevent: GRAPHICS_RESULT\n"));
        for(String kept:Arrays.asList("\"kind\":\"GRAPHICS_RESULT\"","\"taskId\":\"native-one\"","\"outcome\":\"CONFIRMED\"","\"sequence\":\"3\"","\"cursor\":\"3\""))assertTrue("kept "+kept,out.contains(kept));
        for(String dropped:Arrays.asList("delta","observation","artifact","AX-secret","PNG-bytes"))assertFalse("leaked "+dropped,out.contains(dropped));
    }
    @Test public void unparseableDataBlocksAreDroppedNotRelayed()throws Exception {
        f.sseFrames=Arrays.asList(
            "id: 1\nevent: weird\ndata: this-is-not-json\n\n".getBytes(StandardCharsets.UTF_8),
            "id: 2\nevent: array\ndata: [1,2,3]\n\n".getBytes(StandardCharsets.UTF_8),
            "id: 3\nevent: ok\ndata: {\"kind\":\"PING\"}\n\n".getBytes(StandardCharsets.UTF_8));
        Sink sink=new Sink();assertNull(stream(null,null,sink));
        String out=sink.text();
        assertFalse(out.contains("this-is-not-json"));assertFalse(out.contains("[1,2,3]"));
        assertFalse(out.contains("id: 1"));assertFalse(out.contains("id: 2"));
        assertTrue(out.contains("id: 3"));
    }
    @Test public void unterminatedTailBlockDiesWithTheConnection()throws Exception {
        f.sseFrames=Arrays.asList(
            "id: 1\nevent: ok\ndata: {\"kind\":\"PING\"}\n\n".getBytes(StandardCharsets.UTF_8),
            "id: 2\nevent: tail\ndata: {\"kind\":\"LEAK\"}\n".getBytes(StandardCharsets.UTF_8));
        Sink sink=new Sink();assertNull(stream(null,null,sink));
        assertTrue(sink.text().contains("id: 1"));assertFalse(sink.text().contains("LEAK"));
    }
    @Test public void scopeAndMethodBoundariesApplyToStreams()throws Exception {
        denied("DEVICE_SCOPE_DENIED",()->f.access.streamEvents(f.token,"GET","/personal/v1/local-tasks/native-store/tasks/sibling/events",null,null,new Sink()));
        denied("OPERATION_NOT_ALLOWED",()->f.access.streamEvents(f.token,"POST",ROUTE,null,null,new Sink()));
        denied("OPERATION_NOT_ALLOWED",()->f.access.streamEvents(f.token,"GET","/personal/v1/local-tasks/native-store/tasks/native-one/output",null,null,new Sink()));
        assertEquals(0,f.eventReads.get());
    }

    // ---- TLS adapter level: real negotiation, admission bound, JSON stability ----

    PersonalAccessServer server;SSLContext tls;
    void tlsServer()throws Exception {
        String password=UUID.randomUUID().toString();Path file=f.root.resolve("fixture-stream-tls.p12");
        String keytool=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process process=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass",password,"-keypass",password,"-noprompt").redirectErrorStream(true).redirectOutput(f.root.resolve("fixture-stream-keytool.log").toFile()).start();
        assertTrue(process.waitFor(20,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(file)){keys.load(input,password.toCharArray());}
        KeyManagerFactory keyManager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keyManager.init(keys,password.toCharArray());
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(keyManager.getKeyManagers(),trust.getTrustManagers(),new SecureRandom());
        X509Certificate certificate=(X509Certificate)keys.getCertificate("fixture");
        server=new PersonalAccessServer(new InetSocketAddress("127.0.0.1",0),tls,certificate,f.auth,new ClientSessionRegistry("acs-store","stream-test-boot"),f.json,new URI("http://127.0.0.1:"+f.host.getAddress().getPort()),UUID.randomUUID().toString()).localTasks(f.access);server.start();
    }
    static final class Head{SSLSocket socket;int status;final Map<String,String> headers=new HashMap<String,String>();InputStream in;}
    Head request(String path,String accept,String lastEventId)throws Exception {
        SSLSocket socket=(SSLSocket)tls.getSocketFactory().createSocket("127.0.0.1",server.port());
        socket.setSoTimeout(15000);socket.startHandshake();
        StringBuilder head=new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1:").append(server.port())
            .append("\r\nAuthorization: Bearer ").append(f.token).append("\r\n");
        if(accept!=null)head.append("Accept: ").append(accept).append("\r\n");
        if(lastEventId!=null)head.append("Last-Event-ID: ").append(lastEventId).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();
        InputStream in=socket.getInputStream();String status=readLine(in);assertTrue(status,status.startsWith("HTTP/"));
        Head r=new Head();r.socket=socket;r.in=in;r.status=Integer.parseInt(status.split(" ",3)[1]);
        String line;while(!(line=readLine(in)).isEmpty()){int colon=line.indexOf(':');if(colon>0)r.headers.put(line.substring(0,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
        return r;
    }
    JsonNode jsonBody(String path,int expected)throws Exception {
        Head r=request(path,"application/json",null);assertEquals(expected,r.status);
        byte[] body;try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=r.in.read(buffer))>=0)out.write(buffer,0,n);body=out.toByteArray();}
        r.socket.close();return body.length==0?f.json.createObjectNode():f.json.readTree(body);
    }
    private static String readLine(InputStream in)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();int c;while((c=in.read())>=0&&c!='\n')if(c!='\r')out.write(c);return out.toString("UTF-8");}
    /** Chunked-decoding reader for the SSE wire: sendResponseHeaders(200,0) frames every block as one chunk. */
    private static String chunkedBody(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(true){
            String size=readLine(in);int n=Integer.parseInt(size.trim().split(";",2)[0],16);if(n==0){readLine(in);break;}
            byte[] b=new byte[n];int off=0;while(off<n){int m=in.read(b,off,n-off);if(m<0)break;off+=m;}out.write(b,0,off);readLine(in);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }

    @Test public void liveRelaysAreBoundedAndJsonPollingIsUntouched()throws Exception {
        tlsServer();
        try{
            f.sseFrames=Arrays.asList("id: 1\nevent: open\ndata: {\"kind\":\"OPEN\"}\n\n".getBytes(StandardCharsets.UTF_8));
            f.sseHold=new CountDownLatch(1);
            List<SSLSocket> held=new ArrayList<SSLSocket>();
            try{
                Head first=request(ROUTE+"?cursor=1&follow=1","text/event-stream","41");
                assertEquals(200,first.status);assertTrue(first.headers.get("content-type").startsWith("text/event-stream"));
                held.add(first.socket);
                // The resume header reaches the source verbatim on the relayed request.
                assertEquals("41",f.eventsLastEventId);
                for(int i=1;i<4;i++){
                    Head r=request(ROUTE+"?cursor=1&follow=1","text/event-stream",null);
                    assertEquals(200,r.status);assertTrue(r.headers.get("content-type").startsWith("text/event-stream"));
                    held.add(r.socket);
                }
                // Fifth concurrent relay: bounded admission answers a real
                // problem before any source call — it must never queue.
                JsonNode problem=jsonBody(ROUTE+"?follow=1",429);
                assertEquals("STREAM_LIMIT_REACHED",problem.path("code").asText());
                assertEquals(4,f.eventReads.get());
                // The bounded JSON page is byte-stable while relays are held.
                JsonNode page=jsonBody(ROUTE+"?cursor=1&limit=5",200);
                assertTrue(page.has("items"));assertEquals("native-store",page.path("backendStoreId").asText());
                assertEquals("cursor=1&limit=5",f.eventsQuery);
            }finally{for(SSLSocket s:held)try{s.close();}catch(Exception ignored){}f.sseHold.countDown();}
            // Source EOF releases every slot; the next relay is admitted.
            Head reopened=null;
            for(int i=0;i<50;i++){reopened=request(ROUTE+"?follow=1","text/event-stream",null);if(reopened.status==429){reopened.socket.close();Thread.sleep(100);}else break;}
            assertNotNull(reopened);assertEquals(200,reopened.status);reopened.socket.close();
        }finally{if(server!=null)server.close();}
    }
    @Test public void hostCapabilitiesDeclareTheLocalEventsStream()throws Exception {
        tlsServer();
        try{
            JsonNode host=jsonBody("/personal/v1/host",200);
            assertTrue(host.path("capabilities").path("localTasks").asBoolean());
            assertTrue(host.path("capabilities").path("localTaskEventsSse").asBoolean());
            // The ACS-level sse flag stays honest: no ACS stream is proxied here.
            assertFalse(host.path("capabilities").path("sse").asBoolean());
        }finally{if(server!=null)server.close();}
    }
    @Test public void followEqualsOneAloneNegotiatesTheRelay()throws Exception {
        tlsServer();
        try{
            f.sseFrames=Arrays.asList("id: 5\nevent: ping\ndata: {\"kind\":\"PING\"}\n\n".getBytes(StandardCharsets.UTF_8));
            Head r=request(ROUTE+"?follow=1",null,null);
            assertEquals(200,r.status);assertTrue(r.headers.get("content-type").startsWith("text/event-stream"));
            assertEquals("follow=1",f.eventsQuery);assertTrue(f.eventsAccept.contains("text/event-stream"));
            String body=chunkedBody(r.in);
            assertTrue(body,body.contains("id: 5\nevent: ping\ndata: {\"kind\":\"PING\"}\n\n"));
            r.socket.close();
        }finally{if(server!=null)server.close();}
    }
}
