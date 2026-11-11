package com.sage.business.code.application.personalaccess;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sage.business.code.domain.BusinessException;
import com.sage.business.code.infrastructure.controlplane.ControlPlaneDatabase;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
public class PersonalTranscriptionServiceTest {
 private final ObjectMapper mapper=new ObjectMapper(); private Path root; private ControlPlaneDatabase db;
 private PersonalAccessStore store;private PersonalAccessService auth;private PersonalTranscriptionService service;
 private String token,did;private final Fake provider=new Fake();
 static class Fake implements PersonalTranscriptionService.Provider {
  final AtomicInteger calls=new AtomicInteger();final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  public String binding(){return "test-offline";}
  public String transcribe(String id,byte[] pcm,String lang,BooleanSupplier cancelled)throws Exception{calls.incrementAndGet();entered.countDown();while(!release.await(20,TimeUnit.MILLISECONDS)){if(cancelled.getAsBoolean())throw new CancellationException();}return "手机转写结果";}
  public void close(){release.countDown();}
 }
 @Before public void setup()throws Exception {
  root=Files.createTempDirectory("pac-voice-");db=new ControlPlaneDatabase(root,false);store=new PersonalAccessStore(db,mapper);
  auth=new PersonalAccessService(store,mapper,new byte[32],"store-voice","https://localhost:9443");
  String iid=UUID.randomUUID().toString(),rid=UUID.randomUUID().toString(),poll=UUID.randomUUID().toString();
  ObjectNode invite=auth.invitation(iid,Arrays.asList("session-one"),new HashSet<String>(Arrays.asList("OBSERVE","INTERACT")),"cert");
  auth.request(mapper.createObjectNode().put("inviteId",iid).put("inviteSecret",invite.path("inviteSecret").asText()).put("pairingRequestId",rid).put("pollingSecretHash",PersonalAccessService.hash(poll)).put("installationId",UUID.randomUUID().toString()).put("label","phone"));
  auth.decide(rid,true);ObjectNode credential=auth.redeem(rid,poll,"redeem");token=credential.path("accessToken").asText();did=credential.path("deviceGrantId").asText();
  service=new PersonalTranscriptionService(store,auth,mapper,provider);
 }
 @After public void cleanup()throws Exception{service.close();db.close();try(java.util.stream.Stream<Path> walk=Files.walk(root)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
 static byte[] wav(){ByteBuffer b=ByteBuffer.allocate(6444).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(6436).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(6400);return b.array();}
 private ObjectNode submit(String key){byte[] wav=wav();return service.submit(token,key,"session-one",PersonalTranscriptionService.digest(wav),wav,"auto");}
 private void denied(String code,Runnable call){try{call.run();fail("Expected "+code);}catch(BusinessException e){assertEquals(code,e.getCode());}}
 private ObjectNode completed(String key)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);ObjectNode result;do{result=service.get(token,key);if(!Arrays.asList("ACCEPTED","DISPATCHED").contains(result.path("state").asText()))return result;Thread.sleep(10);}while(System.nanoTime()<deadline);throw new AssertionError("request not completed");}
 @Test public void originalKeyIsQueriedAndReplayedWithoutSecondProviderCall()throws Exception{
  submit("voice-one");assertTrue(provider.entered.await(2,TimeUnit.SECONDS));submit("voice-one");provider.release.countDown();assertEquals("手机转写结果",completed("voice-one").path("text").asText());submit("voice-one");assertEquals(1,provider.calls.get());
  byte[] different=wav();different[44]=1;denied("IDEMPOTENCY_PAYLOAD_MISMATCH",()->service.submit(token,"voice-one","session-one",PersonalTranscriptionService.digest(different),different,"auto"));
 }
 @Test public void cancellationNeverPublishesLateText()throws Exception{submit("cancel-one");assertTrue(provider.entered.await(2,TimeUnit.SECONDS));assertEquals("CANCELLED",service.cancel(token,"cancel-one").path("state").asText());provider.release.countDown();Thread.sleep(100);assertFalse(service.get(token,"cancel-one").has("text"));assertEquals(1,provider.calls.get());}
 @Test public void revocationHidesPendingAndCompletedTranscription()throws Exception{submit("revoke-one");assertTrue(provider.entered.await(2,TimeUnit.SECONDS));auth.revoke(did);provider.release.countDown();denied("DEVICE_REVOKED",()->service.get(token,"revoke-one"));}
 @Test public void restartMarksUnresolvedUnknownAndDoesNotRedispatch(){PersonalTranscriptionService.Record record=new PersonalTranscriptionService.Record();record.id="interrupted";record.deviceId=did;record.sessionId="session-one";record.audioHash="digest";record.providerBinding="test-offline";record.state="DISPATCHED";record.dispatched=true;record.createdAt=System.currentTimeMillis();store.transaction(c->{store.put(c,"transcription",record.id,record);return null;});service.close();service=new PersonalTranscriptionService(store,auth,mapper,provider);assertEquals("UNKNOWN",service.get(token,"interrupted").path("state").asText());assertEquals(0,provider.calls.get());}
 @Test public void wavAndDigestAreValidatedBeforeDispatch(){byte[] broken=wav();broken[24]=1;denied("AUDIO_FORMAT_INVALID",()->service.submit(token,"bad-format","session-one",PersonalTranscriptionService.digest(broken),broken,"auto"));denied("AUDIO_DIGEST_MISMATCH",()->service.submit(token,"bad-digest","session-one","wrong",wav(),"auto"));assertEquals(0,provider.calls.get());}
}
