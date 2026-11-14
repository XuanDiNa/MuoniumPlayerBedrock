package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DepthReaderTest {
    interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE CreateFileMappingW(HANDLE file,Pointer security,int protect,int hi,int bytes,WString name);
        HANDLE CreateMutexW(Pointer security,boolean owned,WString name);
        Pointer MapViewOfFile(HANDLE mapping,int access,int hi,int lo,long bytes);
        boolean CloseHandle(HANDLE handle);boolean UnmapViewOfFile(Pointer view);long GetTickCount64();
    }
    @Test void actualTransportReadsOwnedPixelsAndCancelsItsRequestOnClose(){
        String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
        long size=DepthFrame.HEADER+(long)DepthFrame.MAX_PIXELS*4;
        var map=Api.I.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,(int)size,new WString(prefix+"-depth"));
        var gate=Api.I.CreateMutexW(null,false,new WString(prefix+"-depth-lock"));
        assertNotNull(map);assertNotNull(gate);var view=Api.I.MapViewOfFile(map,6,0,0,size);assertNotNull(view);
        try{
            var raw=DepthFrameTest.packet();var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);long now=Api.I.GetTickCount64();
            b.putLong(320,now).putLong(64+16,now);view.write(0,raw,0,raw.length);
            try(var reader=new DepthReader(prefix)){
                var frame=reader.read();assertNotNull(frame);assertEquals(64*64*4,frame.rgba.length);
                view.setByte(360,(byte)0);assertEquals((byte)255,frame.rgba[0],"Published pixels have independent ownership");
                reader.request(frame.camera,800,600,new int[]{368,268,64,64},true);
                assertEquals(1,view.getInt(28));assertEquals(7,view.getLong(16));
                reader.request(frame.camera,1920,1080,new int[]{0,0,1920,1080},true);
                assertEquals(1,view.getInt(28),"Over-budget logical ROI remains requested");
                assertEquals(2,view.getInt(4),"Matched depth transport version");
                assertEquals(1920,view.getInt(48));assertEquals(1080,view.getInt(52));
                reader.request(frame.camera,800,600,new int[]{799,0,64,64},true);
                assertEquals(0,view.getInt(28),"Out-of-viewport request is refused");
            }
            assertEquals(0,view.getInt(28),"Closing the host cancels game capture immediately");
        }finally{Api.I.UnmapViewOfFile(view);Api.I.CloseHandle(gate);Api.I.CloseHandle(map);}
    }
}
