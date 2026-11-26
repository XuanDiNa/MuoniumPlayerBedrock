package com.muoniumplayer.bedrock;
import com.sun.jna.platform.win32.Kernel32;import java.nio.file.*;import java.util.UUID;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceKeysTest {
 @TempDir Path root;
 @Test void actualNativeEventsToggleOnceWithoutOpeningDevicesOrRestartingMusic()throws Exception{
  String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");var k=Kernel32.INSTANCE;
  var mic=k.CreateEvent(null,false,false,prefix+"-14");var deaf=k.CreateEvent(null,false,false,prefix+"-15");
  RoomVoice.Devices fake=(m,c)->{throw new AssertionError("No hardware without enabled room");};
  try(var v=new RoomVoice(root,fake,()->"",()->false);var keys=new RoomVoiceKeys(prefix,v)){
   k.SetEvent(mic);keys.poll(true);assertTrue(v.microphone());assertFalse(v.enabled());keys.poll(true);assertTrue(v.microphone());
   k.SetEvent(deaf);keys.poll(true);assertTrue(v.deafened());assertTrue(v.microphone());
   k.SetEvent(deaf);keys.poll(false);assertTrue(v.deafened());keys.poll(true);assertTrue(v.deafened());
   k.SetEvent(deaf);keys.poll(true);assertFalse(v.deafened());
  }finally{k.CloseHandle(mic);k.CloseHandle(deaf);}
 }
}
