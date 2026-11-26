package com.muoniumplayer.bedrock;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
/** Two appended native controls; existing control indices remain unchanged. */
final class RoomVoiceKeys implements AutoCloseable {
 private final HANDLE microphone,deafen;private final RoomVoice voice;
 RoomVoiceKeys(String prefix){this(prefix,RoomVoice.global());}
 RoomVoiceKeys(String prefix,RoomVoice voice){
  if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("Control identity");this.voice=voice;
  microphone=Kernel32.INSTANCE.OpenEvent(0x00100000,false,prefix+"-14");
  deafen=Kernel32.INSTANCE.OpenEvent(0x00100000,false,prefix+"-15");
  if(microphone==null||deafen==null){close();throw new IllegalStateException("Native voice controls unavailable");}
 }
 void poll(boolean enabled){boolean mic=Kernel32.INSTANCE.WaitForSingleObject(microphone,0)==0;boolean deaf=Kernel32.INSTANCE.WaitForSingleObject(deafen,0)==0;if(enabled){if(mic)voice.toggleMicrophone();if(deaf)voice.toggleDeafened();}}
 public void close(){if(microphone!=null)Kernel32.INSTANCE.CloseHandle(microphone);if(deafen!=null)Kernel32.INSTANCE.CloseHandle(deafen);}
}
