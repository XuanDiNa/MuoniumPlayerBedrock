package com.muoniumplayer.bedrock;
import com.google.gson.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceSpatialLifecycleTest {
 @TempDir Path root;
 static Object call(RoomVoice v,String method,Class<?>[] types,Object...args)throws Exception{
  try{return RoomVoice.class.getDeclaredMethod(method,types).invoke(v,args);}catch(NoSuchMethodException e){fail("Missing spatial lifecycle: "+method);return null;}
 }
 static RoomVoiceWorld pose(WorldFrame f){return f==null?null:new RoomVoiceWorld(f.epoch(),f.dimension(),f.eyeX(),f.eyeY(),f.eyeZ(),f.lookX(),f.lookY(),f.lookZ(),f.epoch());}
 static void observe(RoomVoice v,WorldFrame f){v.updatePose(pose(f),null);}
 @Test void explicitSpatialConsentRevokesOnWorldLossAndRequiresCapableHost()throws Exception{
  var capture=new AtomicReference<java.util.function.Consumer<float[]>>();var sent=new CopyOnWriteArrayList<JsonObject>();Object owner=new Object();
  RoomVoice.Devices fake=(mic,sink)->{capture.set(sink);return new RoomVoice.Device(){public void play(float[] pcm){}public void close(){}};};
  try(var v=new RoomVoice(root,fake,()->"a",()->false)){
   assertEquals(false,call(v,"spatial",new Class<?>[]{}));observe(v,WorldSpeakerTest.camera(7,0));
   call(v,"setSpatial",new Class<?>[]{boolean.class},true);assertFalse(v.enabled());assertFalse(v.microphone());
   var handshake=RoomVoiceTest.session("a");v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,handshake);v.setEnabled(true);v.setMicrophone(true);
   Thread.sleep(100);assertFalse(v.ready(),"Spatial audio cannot pass through an old host as ordinary room audio");
   handshake.addProperty("spatialVersion",1);v.receive(owner,handshake);RoomVoiceTest.until(v::ready);
   float[] pcm=new float[960];Arrays.fill(pcm,.1f);capture.get().accept(pcm);RoomVoiceTest.until(()->!sent.isEmpty());
   assertTrue(sent.getLast().has("spatial"));String oldWorld=sent.getLast().getAsJsonObject("spatial").get("world").getAsString();
   observe(v,null);RoomVoiceTest.until(()->!v.ready());int before=sent.size();capture.get().accept(pcm);Thread.sleep(50);assertEquals(before,sent.size());
   observe(v,WorldSpeakerTest.camera(8,0));Thread.sleep(50);assertFalse(v.ready());call(v,"confirmSpatialWorld",new Class<?>[]{});RoomVoiceTest.until(v::ready);
   capture.get().accept(pcm);RoomVoiceTest.until(()->sent.size()>before);assertNotEquals(oldWorld,sent.getLast().getAsJsonObject("spatial").get("world").getAsString());
   v.toggleDeafened();RoomVoiceTest.until(()->!v.ready());assertTrue(v.microphone());
  }
 }
 @Test void receivedOpusRendersStereoAndSenderUsesIndependentPlayerHead()throws Exception{
  var heard=new CopyOnWriteArrayList<float[]>();var capture=new AtomicReference<java.util.function.Consumer<float[]>>();var sent=new CopyOnWriteArrayList<JsonObject>();
  RoomVoice.Devices fake=(mic,sink)->{capture.set(sink);return new RoomVoice.Device(){public void play(float[] pcm){heard.add(pcm);}public void close(){}};};Object owner=new Object();
  try(var v=new RoomVoice(root,fake,()->"a",()->false);var codec=new RoomVoiceCodec()){
   var world=WorldSpeakerTest.camera(7,0);var environment=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
   var player=new CinematicState(1,1000,9,0,true,2,3,4,2,4.6,4,0,0,70,1);
   v.updatePose(new RoomVoiceWorld(7,0,2,4.6,4,0,0,1,7),environment);v.setSpatial(true);var handshake=RoomVoiceTest.session("a");handshake.addProperty("spatialVersion",1);
   v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,handshake);v.setEnabled(true);v.setMicrophone(true);RoomVoiceTest.until(v::ready);
   float[] pcm=new float[960];for(int i=0;i<960;i++)pcm[i]=(float)(.2*Math.sin(i*.08));capture.get().accept(pcm);RoomVoiceTest.until(()->!sent.isEmpty());
   var source=sent.getLast().getAsJsonObject("spatial");assertEquals(2,source.get("x").getAsDouble());assertEquals(4.6,source.get("y").getAsDouble(),1e-6);
   var sender=UUID.randomUUID();var position=RoomVoiceSpatialTest.source(-7.5,.5,.5,0,32);
   for(int i=0;i<8;i++){v.updatePose(pose(world),environment);var frame=RoomVoiceTransportTest.frame(handshake.get("epoch").getAsString(),i);
    frame.addProperty("sender",sender.toString());frame.add("spatial",position);frame.addProperty("data",Base64.getEncoder().encodeToString(codec.encode(pcm)));v.receive(owner,frame);Thread.sleep(20);}
   RoomVoiceTest.until(()->heard.stream().anyMatch(f->{double left=0,right=0;for(int i=0;i<960;i++){left+=f[i*2]*f[i*2];right+=f[i*2+1]*f[i*2+1];}return right>.01&&left<right*.01;}));
  }
 }
 @Test void uiOpenAndCameraEpochChangesKeepExplicitDistanceConsent()throws Exception{
  var sent=new CopyOnWriteArrayList<JsonObject>();var capture=new AtomicReference<java.util.function.Consumer<float[]>>();
  var closes=new AtomicInteger();
  RoomVoice.Devices fake=(mic,sink)->{capture.set(sink);return new RoomVoice.Device(){public void play(float[] f){}public void close(){closes.incrementAndGet();}};};
  try(var v=new RoomVoice(root,fake,()->"a",()->false)){
   Object owner=new Object();var h=RoomVoiceTest.session("a");h.addProperty("spatialVersion",1);
   // The HUD camera is absent while this control is clicked. Only player pose exists.
   v.updatePose(new RoomVoiceWorld(7,0,0,65,0,0,0,1,13),null);v.setSpatial(true);
   v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,h);v.setEnabled(true);v.setMicrophone(true);RoomVoiceTest.until(v::ready);
   float[] pcm=new float[960];Arrays.fill(pcm,.15f);
   for(int i=0;i<5;i++){v.updatePose(new RoomVoiceWorld(7,0,i,65,0,0,0,1,14+i),null);capture.get().accept(pcm);Thread.sleep(20);}
   RoomVoiceTest.until(()->sent.size()>=3);assertTrue(v.ready());assertEquals(0,closes.get(),"camera/menu transitions must not reopen audio devices");
   String token=sent.getFirst().getAsJsonObject("spatial").get("world").getAsString();
   assertTrue(sent.stream().allMatch(f->token.equals(f.getAsJsonObject("spatial").get("world").getAsString())));
   v.updatePose(new RoomVoiceWorld(8,0,0,65,0,0,0,1,20),null);RoomVoiceTest.until(()->!v.ready());
   v.confirmSpatialWorld();RoomVoiceTest.until(v::ready);
   Thread.sleep(400);RoomVoiceTest.until(()->!v.ready()); // No fresh player pose must never keep sending.
  }
 }
}
