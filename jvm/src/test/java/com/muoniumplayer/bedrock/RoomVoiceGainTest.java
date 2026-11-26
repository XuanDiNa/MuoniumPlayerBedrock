package com.muoniumplayer.bedrock;
import com.google.gson.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceGainTest {
 @TempDir Path root;
 static void gain(RoomVoice v,float n)throws Exception{
  try{RoomVoice.class.getMethod("setMicrophoneGain",float.class).invoke(v,n);}
  catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException x)throw x;throw e;}
 }
 static float gain(RoomVoice v)throws Exception{return (float)RoomVoice.class.getMethod("microphoneGain").invoke(v);}
 static final class Audio implements RoomVoice.Devices {
  final AtomicReference<java.util.function.Consumer<float[]>> capture=new AtomicReference<>();final AtomicInteger opens=new AtomicInteger();
  public RoomVoice.Device open(boolean mic,java.util.function.Consumer<float[]> sink){capture.set(sink);opens.incrementAndGet();return new RoomVoice.Device(){public void play(float[] p){}public void close(){}};}
 }
 @Test void savedGainDefaultsSafelyAndNeverEnablesMicrophone()throws Exception{
  var audio=new Audio();
  try(var v=new RoomVoice(root,audio,()->"",()->false)){
   assertEquals(1,gain(v));gain(v,3);assertFalse(v.microphone());assertFalse(v.enabled());assertFalse(v.ready());assertEquals(0,audio.opens.get());
   for(float bad:new float[]{-1,4.01f,Float.NaN,Float.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->gain(v,bad));
  }
  try(var v=new RoomVoice(root,audio,()->"",()->false)){assertEquals(3,gain(v));assertFalse(v.microphone());assertFalse(v.enabled());}
  var preferences=root.resolve("config/muonium/room-voice.json");var json=JsonParser.parseString(Files.readString(preferences)).getAsJsonObject();json.addProperty("microphoneGain",999);Files.writeString(preferences,json.toString());
  try(var v=new RoomVoice(root,audio,()->"",()->false)){assertEquals(1,gain(v),"Invalid persisted amplification resets safely");}
  json.remove("microphoneGain");json.addProperty("sendGain",.5);Files.writeString(preferences,json.toString());
  try(var v=new RoomVoice(root,audio,()->"",()->false)){assertEquals(1,gain(v));assertEquals(.5f,v.sendGain(),"Old volume preference survives");}
 }
 static float[] tone(float level){var f=new float[960];for(int i=0;i<f.length;i++)f[i]=(float)(level*Math.sin(i*.08));return f;}
 static double energy(float[] pcm){double sum=0;for(float x:pcm){assertTrue(Float.isFinite(x));sum+=x*x;}return sum/pcm.length;}
 static List<float[]> captureFrames(RoomVoice v,Audio audio,List<JsonObject> sent,RoomVoiceCodec decoder,boolean music,float level)throws Exception{
  int start=sent.size();var f=tone(level);var stereo=new double[1920];for(int i=0;i<960;i++)stereo[2*i]=stereo[2*i+1]=f[i];
  for(int i=0;i<9;i++){if(music)v.onOutput(stereo,2,48000);else audio.capture.get().accept(f);Thread.sleep(25);}
  RoomVoiceTest.until(()->sent.size()>=start+6);var output=new ArrayList<float[]>();
  for(int i=start;i<sent.size();i++)output.add(decoder.decode(Base64.getDecoder().decode(sent.get(i).get("data").getAsString())));return output;
 }
 static double tailEnergy(List<float[]> frames){return frames.subList(frames.size()-3,frames.size()).stream().mapToDouble(RoomVoiceGainTest::energy).average().orElseThrow();}
 @Test void amplificationChangesActualEncodedMicrophoneAudioWithoutReopeningDevice()throws Exception{
  var audio=new Audio();var sent=new CopyOnWriteArrayList<JsonObject>();Object owner=new Object();
  try(var v=new RoomVoice(root,audio,()->"a",()->false);var decoder=new RoomVoiceCodec()){
   v.setMicrophone(true);v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,RoomVoiceTest.session("a"));v.setEnabled(true);RoomVoiceTest.until(v::ready);
   double baseline=tailEnergy(captureFrames(v,audio,sent,decoder,false,.05f));gain(v,4);
   double louder=tailEnergy(captureFrames(v,audio,sent,decoder,false,.05f));assertTrue(louder>baseline*12&&louder<baseline*20,"400% must boost real decoded voice about fourfold");
   gain(v,0);assertTrue(tailEnergy(captureFrames(v,audio,sent,decoder,false,.05f))<baseline*.005);assertEquals(1,audio.opens.get(),"Live adjustment must not reset capture");
  }
 }
 @Test void microphoneBoostLeavesBroadcastMusicVolumeUnchanged()throws Exception{
  var audio=new Audio();var sent=new CopyOnWriteArrayList<JsonObject>();Object owner=new Object();
  try(var v=new RoomVoice(root,audio,()->"a",()->true);var decoder=new RoomVoiceCodec()){
   v.setMusic(true);v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,RoomVoiceTest.session("a"));v.setEnabled(true);RoomVoiceTest.until(v::ready);Thread.sleep(50);
   double first=tailEnergy(captureFrames(v,audio,sent,decoder,true,.12f));gain(v,4);
   double second=tailEnergy(captureFrames(v,audio,sent,decoder,true,.12f));assertEquals(first,second,first*.1);assertFalse(v.microphone());assertEquals(1,audio.opens.get());
  }
 }
 @Test void loudMicrophoneIsLimitedAsAWaveformInsteadOfHardClipped()throws Exception{
  var audio=new Audio();var sent=new CopyOnWriteArrayList<JsonObject>();Object owner=new Object();
  try(var v=new RoomVoice(root,audio,()->"a",()->false);var decoder=new RoomVoiceCodec()){
   gain(v,4);v.setMicrophone(true);v.bind(owner,UUID.randomUUID(),sent::add);v.receive(owner,RoomVoiceTest.session("a"));v.setEnabled(true);RoomVoiceTest.until(v::ready);
   var frames=captureFrames(v,audio,sent,decoder,false,.8f);var f=frames.getLast();double fundamental=0,harmonic=0;
   for(int i=0;i<f.length;i++){fundamental+=f[i]*Math.sin(i*.08);harmonic+=f[i]*Math.sin(i*.24);assertTrue(Math.abs(f[i])<1.2);}
   assertTrue(energy(f)>.1);assertTrue(Math.abs(harmonic/fundamental)<.1,"Peak limiter must preserve voice waveform rather than flatten peaks");
  }
 }
}
