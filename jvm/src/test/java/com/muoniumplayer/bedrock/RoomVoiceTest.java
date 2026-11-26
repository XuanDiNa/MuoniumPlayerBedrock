package com.muoniumplayer.bedrock;
import com.google.gson.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceTest {
 @TempDir Path root;
 static void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.nanoTime()+3_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);assertTrue(condition.getAsBoolean());}
 @Test void passiveDefaultsNeverOpenDevicesAndMicRequiresExplicitConsent()throws Exception{
  AtomicInteger opens=new AtomicInteger(),mics=new AtomicInteger(),closes=new AtomicInteger();
  RoomVoice.Devices factory=(mic,capture)->{opens.incrementAndGet();if(mic)mics.incrementAndGet();return new RoomVoice.Device(){public void play(float[] pcm){}public void close(){closes.incrementAndGet();}};};
  String[] room={"a"};Object owner=new Object();
  try(var v=new RoomVoice(root,factory,()->room[0],()->true)){
   assertFalse(v.enabled());assertFalse(v.microphone());assertFalse(v.music());
   v.bind(owner,UUID.randomUUID(),m->{});v.receive(owner,session("a"));Thread.sleep(80);assertEquals(0,opens.get());
   v.setEnabled(true);until(()->opens.get()==1);assertEquals(0,mics.get());
   v.setMicrophone(true);until(()->mics.get()==1);assertTrue(closes.get()>=1);
   v.unbind(owner);until(()->closes.get()>=2);assertFalse(v.ready());
   v.bind(new Object(),UUID.randomUUID(),m->{});v.receive(owner,session("a"));Thread.sleep(50);assertEquals(2,opens.get());
  }
 }
 @Test void actualMusicTapQueuesOnlyActiveRoomAndStopsOnPauseAndDisconnect()throws Exception{
  var sent=new java.util.concurrent.CopyOnWriteArrayList<JsonObject>();AtomicBoolean playing=new AtomicBoolean(true);Object owner=new Object();
  RoomVoice.Devices noHardware=(mic,capture)->new RoomVoice.Device(){public void play(float[] pcm){}public void close(){}};
  try(var v=new RoomVoice(root,noHardware,()->"a",playing::get)){
   v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,session("a"));v.setEnabled(true);v.setMusic(true);until(v::ready);
   double[] pcm=new double[1920];for(int i=0;i<pcm.length;i++)pcm[i]=.1*Math.sin(i*.08);
   for(int i=0;i<10;i++){v.onOutput(pcm,2,48000);Thread.sleep(20);}until(()->!sent.isEmpty());
   assertEquals(RoomVoiceRouter.AUDIO,sent.getFirst().get("op").getAsString());
   playing.set(false);Thread.sleep(80);int count=sent.size();v.onOutput(pcm,2,48000);Thread.sleep(60);assertEquals(count,sent.size());
   v.unbind(owner);Thread.sleep(60);playing.set(true);v.onOutput(pcm,2,48000);Thread.sleep(60);assertEquals(count,sent.size());
  }
 }
 static JsonObject session(String room){var m=new JsonObject();m.addProperty("op",RoomVoiceRouter.SESSION);m.addProperty("version",1);m.addProperty("room",room);m.addProperty("epoch",UUID.randomUUID().toString());return m;}
 @Test void captureMixReceiveAndMicReopenKeepSequenceWithoutLeakingOldSession()throws Exception{
  var capture=new AtomicReference<java.util.function.Consumer<float[]>>();var heard=new java.util.concurrent.CopyOnWriteArrayList<float[]>();var sent=new java.util.concurrent.CopyOnWriteArrayList<JsonObject>();
  RoomVoice.Devices fake=(mic,sink)->{if(mic)capture.set(sink);return new RoomVoice.Device(){public void play(float[] pcm){heard.add(pcm);}public void close(){}};};Object owner=new Object();
  try(var v=new RoomVoice(root,fake,()->"a",()->true);var codec=new RoomVoiceCodec()){
   var handshake=session("a");v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,handshake);v.setEnabled(true);v.setMicrophone(true);until(v::ready);
   float[] pcm=new float[960];for(int i=0;i<960;i++)pcm[i]=(float)(.2*Math.sin(i*.1));
   for(int i=0;i<5;i++){capture.get().accept(pcm);Thread.sleep(25);}until(()->sent.size()>=3);long previous=sent.getLast().get("seq").getAsLong();
   var remote=RoomVoiceTransportTest.frame(handshake.get("epoch").getAsString(),0);remote.addProperty("sender",UUID.randomUUID().toString());remote.addProperty("data",Base64.getEncoder().encodeToString(codec.encode(pcm)));v.receive(owner,remote);
   until(()->heard.stream().anyMatch(frame->{double e=0;for(float x:frame)e+=x*x;return e>.01;}));
   var oldCapture=capture.get();v.setMicrophone(false);until(v::ready);v.setMicrophone(true);until(()->v.ready()&&capture.get()!=oldCapture);
   int before=sent.size();oldCapture.accept(pcm);Thread.sleep(50);assertEquals(before,sent.size());capture.get().accept(pcm);until(()->sent.size()>before);
   assertTrue(sent.getLast().get("seq").getAsLong()>previous,"Device reopen must not replay old packet sequence numbers");
  }
 }
 @Test void deafeningStopsReceiveAndSendAndRestoresChoicesWithoutImplicitConsent()throws Exception{
  var opens=new AtomicInteger();var closes=new AtomicInteger();var mic=new AtomicBoolean();var choices=new AtomicReference<String>();
  RoomVoice.Devices fake=new RoomVoice.Devices(){
   public RoomVoice.Device open(boolean m,java.util.function.Consumer<float[]> c){throw new AssertionError("Selected device overload required");}
   public RoomVoice.Device open(boolean m,String input,String output,java.util.function.Consumer<float[]> c){opens.incrementAndGet();mic.set(m);choices.set(input+"/"+output);return new RoomVoice.Device(){public void play(float[] pcm){}public void close(){closes.incrementAndGet();}};}
  };
  Object owner=new Object();
  try(var v=new RoomVoice(root,fake,()->"a",()->true)){
   v.toggleMicrophone();assertTrue(v.microphone());assertFalse(v.enabled());assertEquals(0,opens.get());
   v.setInputDevice("fixture-mic");v.setOutputDevice("fixture-headphones");assertEquals(0,opens.get());
   v.bind(owner,UUID.randomUUID(),m->{});v.receive(owner,session("a"));v.setEnabled(true);v.setMusic(true);until(v::ready);assertTrue(mic.get());assertEquals("fixture-mic/fixture-headphones",choices.get());
   v.toggleDeafened();until(()->closes.get()==1);assertFalse(v.ready());assertTrue(v.deafened());assertTrue(v.microphone());assertTrue(v.music());
   v.toggleDeafened();until(v::ready);assertTrue(mic.get());assertEquals(2,opens.get());
  }
  try(var restored=new RoomVoice(root,fake,()->"",()->false)){assertTrue(restored.microphone());assertEquals("fixture-mic",restored.inputDevice());assertEquals("fixture-headphones",restored.outputDevice());}
 }
 @Test void deviceCatalogListsAndSelectsWithoutOpeningCapture()throws Exception{
  RoomVoice.Devices fake=new RoomVoice.Devices(){
   public RoomVoice.Device open(boolean mic,java.util.function.Consumer<float[]> sink){throw new AssertionError("Listing is passive");}
   public RoomVoice.DeviceList catalog(){return new RoomVoice.DeviceList(List.of(new RoomVoice.DeviceChoice("","系统默认"),new RoomVoice.DeviceChoice("mic1","Fixture microphone")),List.of(new RoomVoice.DeviceChoice("","系统默认"),new RoomVoice.DeviceChoice("out1","Fixture headphones")));}
  };
  try(var v=new RoomVoice(root,fake,()->"",()->false)){
   v.refreshDevices();until(()->v.deviceOptions(true).length==2);v.selectDevice(true,"Fixture microphone");v.selectDevice(false,"Fixture headphones");
   assertEquals("mic1",v.inputDevice());assertEquals("out1",v.outputDevice());assertFalse(v.microphone());assertFalse(v.enabled());assertFalse(v.ready());
   v.setInputDevice("missing");assertTrue(v.deviceLabel(true).contains("不可用"));assertEquals("missing",v.inputDevice());v.nextDevice(true);assertEquals("",v.inputDevice());
  }
 }
 @Test void audioWorkerRecoveryDoesNotReplayPacketSequence()throws Exception{
  var capture=new AtomicReference<java.util.function.Consumer<float[]>>();var failOutput=new AtomicBoolean();
  var sent=new java.util.concurrent.CopyOnWriteArrayList<JsonObject>();Object owner=new Object();
  RoomVoice.Devices fake=(mic,sink)->{capture.set(sink);return new RoomVoice.Device(){
   public void play(float[] pcm){if(failOutput.getAndSet(false))throw new IllegalStateException("Fixture device disconnected");}
   public void close(){}
  };};
  try(var v=new RoomVoice(root,fake,()->"a",()->false)){
   v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,session("a"));v.setEnabled(true);v.setMicrophone(true);until(v::ready);
   float[] pcm=new float[960];Arrays.fill(pcm,.1f);capture.get().accept(pcm);until(()->!sent.isEmpty());
   long previous=sent.getLast().get("seq").getAsLong();failOutput.set(true);until(()->v.statusText().contains("已停止"));
   var oldCapture=capture.get();v.setEnabled(false);v.setEnabled(true);until(()->v.ready()&&capture.get()!=oldCapture);
   int before=sent.size();capture.get().accept(pcm);until(()->sent.size()>before);
   assertTrue(sent.getLast().get("seq").getAsLong()>previous,"Same host epoch must keep increasing sequence after worker recovery");
  }
 }
}
