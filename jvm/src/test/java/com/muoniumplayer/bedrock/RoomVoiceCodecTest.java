package com.muoniumplayer.bedrock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceCodecTest {
 @Test void bundledCodecResolvesBesidePluginJarsNotProcessWorkingDirectory(){
  var jar=java.nio.file.Path.of("codec-path-fixture","jvm","lib","MuoniumBedrockHost.jar").toAbsolutePath();
  assertEquals(jar.getParent().getParent().resolve("voice/MuoniumVoice.dll"),RoomVoiceCodec.bundledPath(jar.toUri()));
 }
 @Test void realOpusRoundtripIsFiniteAudibleAndBounded(){
  try(var codec=new RoomVoiceCodec()){
   float[] pcm=new float[960];for(int i=0;i<pcm.length;i++)pcm[i]=.15f*(float)Math.sin(i*.08);
   byte[] packet=codec.encode(pcm);assertTrue(packet.length>0&&packet.length<=400);
   float[] decoded=codec.decode(packet);assertEquals(960,decoded.length);double energy=0;
   for(float x:decoded){assertTrue(Float.isFinite(x));energy+=x*x;}assertTrue(energy>.01);
   assertThrows(IllegalArgumentException.class,()->codec.encode(new float[959]));
   assertThrows(IllegalArgumentException.class,()->codec.decode(new byte[401]));
  }
 }
}
