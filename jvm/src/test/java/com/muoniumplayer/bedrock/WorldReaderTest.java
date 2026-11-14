package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldReaderTest {
    interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("kernel32",Api.class);
        HANDLE CreateFileMappingW(HANDLE file,Pointer security,int protect,int high,int low,WString name);
        HANDLE CreateMutexW(Pointer security,boolean owned,WString name);
        HANDLE CreateEventW(Pointer security,boolean manual,boolean initial,WString name);
        boolean SetEvent(HANDLE handle);
        Pointer MapViewOfFile(HANDLE mapping,int access,int high,int low,long bytes);
        int WaitForSingleObject(HANDLE handle,int millis);
        boolean ReleaseMutex(HANDLE handle);
        boolean UnmapViewOfFile(Pointer view);
        boolean CloseHandle(HANDLE handle);
        long GetTickCount64();
    }
    @Test void actualReadOnlyMappingRoundTripRejectsTornAndStaleDataWithoutWaiting() throws Exception {
        String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
        HANDLE map=Api.INSTANCE.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,256,new WString(prefix+"-world"));
        assertNotNull(map,"CreateFileMappingW error="+Native.getLastError());
        HANDLE gate=Api.INSTANCE.CreateMutexW(null,false,new WString(prefix+"-world-lock"));
        assertNotNull(map);assertNotNull(gate);
        Pointer view=Api.INSTANCE.MapViewOfFile(map,2,0,0,256);assertNotNull(view);
        var release=new CountDownLatch(1);var locked=new CountDownLatch(1);
        Thread holder=null;
        try(WorldReader reader=new WorldReader(prefix)) {
            assertNull(reader.read());
            long tick=Api.INSTANCE.GetTickCount64();byte[] raw=WorldFrameTest.packet(tick);
            view.write(0,raw,0,raw.length);
            var f=reader.read();assertNotNull(f);assertEquals(100,f.eyeX());assertEquals(7,f.epoch());
            holder=new Thread(()->{
                assertEquals(0,Api.INSTANCE.WaitForSingleObject(gate,1000));locked.countDown();
                try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally{Api.INSTANCE.ReleaseMutex(gate);}
            });holder.start();assertTrue(locked.await(2,TimeUnit.SECONDS));
            // A torn writer is never observed while its mutex is held.
            view.setInt(0,0);assertNotNull(reader.read());
            while(Api.INSTANCE.GetTickCount64()-tick<=251)Thread.sleep(5);
            long start=System.nanoTime();assertNull(reader.read());
            assertTrue(System.nanoTime()-start<100_000_000L,"Render reader must never wait for writer");
            release.countDown();holder.join();assertNull(reader.read());
            raw=WorldFrameTest.packet(Api.INSTANCE.GetTickCount64());
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(24,8).putInt(36,1);
            view.write(0,raw,0,raw.length);assertEquals(8,reader.read().epoch());assertEquals(1,reader.read().dimension());
            long metricsAt=Api.INSTANCE.GetTickCount64();
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(4,2).putLong(16,metricsAt)
                    .putInt(224,1).putInt(228,120).putInt(232,63).putInt(236,2).putLong(240,metricsAt).putLong(248,9);
            view.write(0,raw,0,raw.length);var metrics=reader.read();assertNotNull(metrics);
            assertEquals(120,metrics.scene(false).fps());assertEquals(63,metrics.scene(false).ping());
        } finally {
            release.countDown();if(holder!=null)holder.join();
            Api.INSTANCE.UnmapViewOfFile(view);Api.INSTANCE.CloseHandle(gate);Api.INSTANCE.CloseHandle(map);
        }
        assertThrows(IllegalStateException.class,()->new WorldReader(prefix));
    }
    @Test void actualFrameSignalFollowsEveryPublishedSequenceAtHighTargetRates() throws Exception {
        String prefix="Local\\MuoniumBedrock-"+UUID.randomUUID().toString().replace("-","");
        var api=Api.INSTANCE;
        var map=api.CreateFileMappingW(new HANDLE(Pointer.createConstant(-1L)),null,4,0,256,new WString(prefix+"-world"));
        var gate=api.CreateMutexW(null,false,new WString(prefix+"-world-lock"));
        var signal=api.CreateEventW(null,false,false,new WString(prefix+"-world-frame"));
        var view=api.MapViewOfFile(map,2,0,0,256);assertNotNull(view);assertNotNull(signal);
        try(var reader=new WorldReader(prefix)){
            assertTrue(reader.frameSignaled());
            WorldFrame prior=null;
            for(int seq=1;seq<=360;seq++){
                long tick=api.GetTickCount64();var raw=WorldFrameTest.packet(tick);
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(8,seq).putInt(4,2).putInt(224,1).putInt(228,360);
                view.write(0,raw,0,raw.length);assertTrue(api.SetEvent(signal));
                if(prior!=null)reader.awaitNext(prior,8_000_000L);
                var next=reader.read();assertNotNull(next);assertEquals(seq,next.sequence());assertEquals(360,next.fps());
                assertFalse(next.sameFrame(prior));assertTrue(next.sameFrame(reader.read()));prior=next;
            }
            var finalFrame=prior;
            // Consume a possibly old notification, then wait for an actual new sequence.
            reader.awaitNext(finalFrame,1_000_000L);
            assertTrue(reader.read().sameFrame(finalFrame));
            var writer=new Thread(()->{
                try{Thread.sleep(2);}catch(InterruptedException e){throw new RuntimeException(e);}
                var raw=WorldFrameTest.packet(api.GetTickCount64());ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(8,361);
                view.write(0,raw,0,raw.length);api.SetEvent(signal);
            });writer.start();reader.awaitNext(finalFrame,8_000_000L);writer.join();
            assertEquals(361,reader.read().sequence());
        }finally{api.UnmapViewOfFile(view);api.CloseHandle(signal);api.CloseHandle(gate);api.CloseHandle(map);}
    }
}
