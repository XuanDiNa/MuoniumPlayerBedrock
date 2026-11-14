package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentReaderTest {
    interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("kernel32",Api.class);
        HANDLE CreateFileMappingW(HANDLE file,Pointer attrs,int protection,int high,int bytes,WString name);
        HANDLE CreateMutexW(Pointer attrs,boolean owned,WString name);
        HANDLE CreateEventW(Pointer attrs,boolean manual,boolean initial,WString name);
        Pointer MapViewOfFile(HANDLE map,int access,int high,int low,long bytes);
        long GetTickCount64();int WaitForSingleObject(HANDLE handle,int millis);
        boolean CloseHandle(HANDLE h);boolean UnmapViewOfFile(Pointer p);
    }
    @Test void realPrivateTransportHasExplicitDemandEpochFreshnessAndCleanup() {
        String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
        var api=Api.INSTANCE;
        var invalid=new HANDLE(Pointer.createConstant(-1L));
        var map=api.CreateFileMappingW(invalid,null,4,0,EnvironmentFrame.BYTES,new WString(prefix+"-environment"));
        var gate=api.CreateMutexW(null,false,new WString(prefix+"-environment-lock"));
        var event=api.CreateEventW(null,true,false,new WString(prefix+"-environment-active"));
        Pointer view=api.MapViewOfFile(map,2,0,0,EnvironmentFrame.BYTES);assertNotNull(view);
        try {
            var raw=java.util.Arrays.copyOf(EnvironmentFrameTest.packet(),EnvironmentFrame.BYTES);ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(16,api.GetTickCount64());
            view.write(0,raw,0,raw.length);
            byte[] world=WorldFrameTest.packet(api.GetTickCount64());ByteBuffer.wrap(world).order(ByteOrder.LITTLE_ENDIAN).putLong(24,7);
            var frame=WorldFrame.decode(world,api.GetTickCount64());assertNotNull(frame);
            try(var reader=new EnvironmentReader(prefix)) {
                assertNull(reader.read(frame));reader.request(true);assertEquals(0,api.WaitForSingleObject(event,0));
                var first=reader.read(frame);assertNotNull(first);assertSame(first,reader.read(frame));assertNull(reader.read(null));
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(24,8);view.write(0,raw,0,raw.length);
                assertNull(reader.read(frame));
                reader.request(false);assertEquals(258,api.WaitForSingleObject(event,0));assertNull(reader.read(frame));
                reader.request(true);
            }
            assertEquals(258,api.WaitForSingleObject(event,0),"Closing the host must stop native environment scanning");
        }finally{api.UnmapViewOfFile(view);api.CloseHandle(event);api.CloseHandle(gate);api.CloseHandle(map);}
    }
}
