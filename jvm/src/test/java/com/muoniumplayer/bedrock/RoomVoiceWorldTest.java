package com.muoniumplayer.bedrock;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceWorldTest {
 static byte[] packet(){var b=ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0,0x5657504d).putInt(4,1).putLong(8,1).putLong(16,1000).putLong(24,7).putInt(32,1).putInt(36,0);
  b.putDouble(40,.5).putDouble(48,.5).putDouble(56,.5).putDouble(80,1).putLong(88,13);return b.array();}
 static Object decode(byte[] b,long now)throws Exception{return Class.forName("com.muoniumplayer.bedrock.RoomVoiceWorld").getDeclaredMethod("decode",byte[].class,long.class).invoke(null,b,now);}
 @Test void privatePlayerPoseDoesNotRequireAHudCamera()throws Exception{assertNotNull(decode(packet(),1001));}
 @Test void staleInvalidAndMalformedPoseFailClosed()throws Exception{
  assertNull(decode(packet(),1351));var p=packet();ByteBuffer b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(32,0);assertNull(decode(p,1001));b.putInt(32,1).putDouble(40,Double.NaN);assertNull(decode(p,1001));
  b.putDouble(40,.5).putInt(36,9);assertNull(decode(p,1001));b.putInt(36,0).putDouble(80,0);assertNull(decode(p,1001));
 }
 @Test void actualPrivateMappingRemainsReadableWithNoCameraMappingAndRevokesOnInvalidation()throws Exception{
  String prefix="Local\\MuoniumBedrock-"+java.util.UUID.randomUUID().toString().replace("-","");
  var api=WorldReaderTest.Api.INSTANCE;
  var map=api.CreateFileMappingW(new com.sun.jna.platform.win32.WinNT.HANDLE(com.sun.jna.Pointer.createConstant(-1L)),null,4,0,96,new com.sun.jna.WString(prefix+"-voice-world"));
  var lock=api.CreateMutexW(null,false,new com.sun.jna.WString(prefix+"-voice-world-lock"));
  var view=api.MapViewOfFile(map,2,0,0,96);assertNotNull(view);
  try(var reader=new RoomVoiceWorldReader(prefix)){
   assertNull(reader.read());var raw=packet();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(16,api.GetTickCount64());view.write(0,raw,0,96);
   var p=reader.read();assertNotNull(p);assertEquals(7,p.epoch());assertEquals(.5,p.y());assertEquals(13,p.geometryEpoch());
   view.setInt(32,0);assertNull(reader.read()); // Successful invalid read must discard its previous snapshot.
   ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(16,api.GetTickCount64()-351);view.write(0,raw,0,96);assertNull(reader.read());
  }finally{api.UnmapViewOfFile(view);api.CloseHandle(lock);api.CloseHandle(map);}
  try(var missing=new RoomVoiceWorldReader(prefix)){assertNull(missing.read());} // Ordinary room voice stays available.
 }
}
