package com.muoniumplayer.bedrock;
/** Packaging fixture: fresh process, shipped JAR layout and no codec-path override. */
public final class RoomVoiceCodecProbe {
 public static void main(String[] args){
  if(System.getProperty("muonium.voice.codecPath")!=null)throw new AssertionError("Must exercise bundled resolution");
  try(var codec=new RoomVoiceCodec()){
   var pcm=new float[960];for(int i=0;i<pcm.length;i++)pcm[i]=(float)(.2*Math.sin(i*.08));
   var packet=codec.encode(pcm);var decoded=codec.decode(packet);double energy=0;for(float x:decoded){if(!Float.isFinite(x))throw new AssertionError("Invalid PCM");energy+=x*x;}
   if(energy<.01)throw new AssertionError("Silent codec");
   System.out.println("ROOM_VOICE bundled JAR codec resolution and audible Opus roundtrip passed");
  }
 }
}
