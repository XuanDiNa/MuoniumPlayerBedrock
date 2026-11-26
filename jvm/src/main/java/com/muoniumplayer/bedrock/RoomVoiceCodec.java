package com.muoniumplayer.bedrock;
import com.sun.jna.*;
import java.nio.file.Path;
import java.util.Arrays;
/** Independently authored JNA boundary; owns its codec on one audio worker. */
final class RoomVoiceCodec implements AutoCloseable {
 interface Api extends Library {Pointer MV_Create();void MV_Destroy(Pointer p);int MV_Encode(Pointer p,float[] pcm,int count,byte[] out,int cap);int MV_Decode(Pointer p,byte[] in,int bytes,float[] out,int cap);}
 static Path bundledPath(java.net.URI jar){return Path.of(jar).toAbsolutePath().getParent().getParent().resolve("voice/MuoniumVoice.dll");}
 private static String codecPath(){
  String override=System.getProperty("muonium.voice.codecPath");if(override!=null)return Path.of(override).toAbsolutePath().toString();
  try{return bundledPath(RoomVoiceCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}
  catch(java.net.URISyntaxException invalid){throw new IllegalStateException("Voice codec location unavailable",invalid);}
 }
 private static final class Loader {static final Api API=Native.load(codecPath(),Api.class);}
 private final Api api=Loader.API;private Pointer handle=api.MV_Create();
 RoomVoiceCodec(){if(handle==null)throw new IllegalStateException("Opus codec unavailable");}
 byte[] encode(float[] pcm){
  if(handle==null||pcm.length!=960)throw new IllegalArgumentException("20ms PCM required");
  byte[] bytes=new byte[400];int n=api.MV_Encode(handle,pcm,960,bytes,400);
  if(n<1)throw new IllegalArgumentException("Opus encode failed");return Arrays.copyOf(bytes,n);
 }
 float[] decode(byte[] packet){
  if(handle==null||packet.length<1||packet.length>400)throw new IllegalArgumentException("Opus packet size");
  float[] pcm=new float[960];if(api.MV_Decode(handle,packet,packet.length,pcm,960)!=960)throw new IllegalArgumentException("Invalid20ms Opus packet");return pcm;
 }
 public void close(){if(handle!=null){api.MV_Destroy(handle);handle=null;}}
}
