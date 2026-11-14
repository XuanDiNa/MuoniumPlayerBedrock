package com.muoniumplayer.bedrock;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.muoniumplayer.core.cine.CinePose;
import java.nio.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CinematicReaderTest {
    interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE CreateFileMappingW(HANDLE file,Pointer attrs,int protection,int high,int bytes,WString name);
        HANDLE CreateMutexW(Pointer attrs,boolean owned,WString name);
        HANDLE CreateEventW(Pointer attrs,boolean manual,boolean initial,WString name);
        Pointer MapViewOfFile(HANDLE map,int access,int high,int low,long bytes);
        long GetTickCount64();int WaitForSingleObject(HANDLE handle,int millis);
        boolean CloseHandle(HANDLE h);boolean UnmapViewOfFile(Pointer p);
    }
    @Test void realTransportSeparatesActorAndRenderCameraAndReleasesDemandOnClose(){
        String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");var api=Api.I;
        var map=api.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,200,new WString(prefix+"-cinematic"));
        var lock=api.CreateMutexW(null,false,new WString(prefix+"-cinematic-lock"));
        var event=api.CreateEventW(null,true,false,new WString(prefix+"-cinematic-active"));
        Pointer view=api.MapViewOfFile(map,6,0,0,200);assertNotNull(view);
        try {
            byte[] raw=CinematicStateTest.packet();ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(16,api.GetTickCount64());view.write(88,raw,0,112);
            try(var reader=new CinematicReader(prefix)){
                assertEquals(258,api.WaitForSingleObject(event,0));assertNull(reader.read());
                reader.request(true);assertEquals(0,api.WaitForSingleObject(event,0));
                var s=reader.read();assertNotNull(s);assertEquals(12,s.x());assertEquals(14,s.eyeX());
                reader.write(s,CinePose.of(16,67,-6,90,30,0),true,true);
                assertEquals(1,view.getInt(40));assertEquals(s.epoch(),view.getLong(24));assertEquals(1,view.getInt(44));
                reader.request(false);assertNull(reader.read());reader.write(null,null,false,false);assertEquals(0,view.getInt(40));
                reader.request(true);
            }
            assertEquals(258,api.WaitForSingleObject(event,0));assertEquals(0,view.getInt(40));
        }finally{api.UnmapViewOfFile(view);api.CloseHandle(event);api.CloseHandle(lock);api.CloseHandle(map);}
    }
}
