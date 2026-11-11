package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;
public class FunAsrTranscriberTest {
 @Test public void fixedOfflineProtocolUsesPcmAndOriginalName()throws Exception { probe(false); }
 @Test public void legacyTwoPassOfflineCollectsEveryFragmentUntilFinal()throws Exception { probe(true); }
 private void probe(boolean legacy)throws Exception {
  try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
   ExecutorService worker=Executors.newSingleThreadExecutor();
   Future<?> fixture=worker.submit(()->{try(Socket socket=server.accept()) {
    socket.setSoTimeout(3000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int b;
    while((b=in.read())!=-1){headers.write(b);if(headers.size()>8192)throw new IOException("headers");String h=new String(headers.toByteArray(),StandardCharsets.US_ASCII);if(h.endsWith("\r\n\r\n"))break;}
    String key=null;for(String line:new String(headers.toByteArray(),StandardCharsets.US_ASCII).split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))key=line.substring(line.indexOf(':')+1).trim();
    String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
    com.fasterxml.jackson.databind.JsonNode meta=new ObjectMapper().readTree(frame(in,1));assertEquals("offline",meta.path("mode").asText());assertEquals("original-key",meta.path("wav_name").asText());assertEquals(16000,meta.path("audio_fs").asInt());assertEquals("[5,10,5]",meta.path("chunk_size").toString());assertEquals(6400,frame(in,2).length);assertFalse(new ObjectMapper().readTree(frame(in,1)).path("is_speaking").asBoolean());
    reply(out,"{\"mode\":\"offline\",\"wav_name\":\"other\",\"text\":\"wrong\"}");
    if(legacy) {
      reply(out,"{\"mode\":\"2pass-offline\",\"wav_name\":\"original-key\",\"text\":\"test \" ,\"is_final\":false}");
      reply(out,"{\"mode\":\"2pass-online\",\"wav_name\":\"original-key\",\"text\":\"partial wrong\",\"is_final\":false}");
      reply(out,"{\"mode\":\"2pass-offline\",\"wav_name\":\"original-key\",\"text\":\"transcript\",\"is_final\":true}");
    } else reply(out,"{\"mode\":\"offline\",\"wav_name\":\"original-key\",\"text\":\"test transcript\",\"is_final\":false}");
   }catch(Exception e){throw new RuntimeException(e);}});
   try(FunAsrTranscriber provider=new FunAsrTranscriber("ws://127.0.0.1:"+server.getLocalPort(),new ObjectMapper())) {
    assertEquals("test transcript",provider.transcribe("original-key",new byte[6400],"auto",()->false));fixture.get(5,TimeUnit.SECONDS);
   }finally{worker.shutdownNow();}
  }
 }
 private static byte[] frame(InputStream source,int opcode)throws IOException {DataInputStream in=new DataInputStream(source);assertEquals(opcode,in.readUnsignedByte()&15);int second=in.readUnsignedByte();assertTrue((second&128)!=0);long n=second&127;if(n==126)n=in.readUnsignedShort();else if(n==127)n=in.readLong();if(n>1920000)throw new IOException("frame too large");byte[] mask=new byte[4],data=new byte[(int)n];in.readFully(mask);in.readFully(data);for(int i=0;i<data.length;i++)data[i]^=mask[i%4];return data;}
 private static void reply(OutputStream out,String text)throws IOException {byte[] data=text.getBytes(StandardCharsets.UTF_8);out.write(129);out.write(data.length);out.write(data);out.flush();}
 @Test public void rejectsUnencryptedRemoteAndClientCredentialUrls(){for(String url:Arrays.asList("ws://192.0.2.1:10095","wss://user:password@example.test","wss://example.test/?key=value","https://example.test")){try{new FunAsrTranscriber(url,new ObjectMapper());fail("unsafe endpoint");}catch(IllegalArgumentException expected){}}}
}
