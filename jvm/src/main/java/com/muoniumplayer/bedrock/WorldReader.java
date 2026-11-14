package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;

/** Read-only bounded camera transport. No native pointer enters a WorldFrame. */
public final class WorldReader implements AutoCloseable {
    private interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);
        HANDLE OpenMutexW(int access,boolean inherit,WString name);
        HANDLE OpenEventW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(HANDLE mapping,int access,int high,int low,long bytes);
        int WaitForSingleObject(HANDLE handle,int millis);
        boolean ReleaseMutex(HANDLE handle);
        boolean UnmapViewOfFile(Pointer view);
        boolean CloseHandle(HANDLE handle);
        long GetTickCount64();
    }
    private HANDLE mapping,lock,frameReady;
    private Pointer view;
    private byte[] last;
    public WorldReader(String prefix) {
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("World identity");
        mapping=Api.INSTANCE.OpenFileMappingW(4,false,new WString(prefix+"-world"));
        lock=Api.INSTANCE.OpenMutexW(0x00100001,false,new WString(prefix+"-world-lock"));
        if(mapping!=null&&lock!=null)view=Api.INSTANCE.MapViewOfFile(mapping,4,0,0,WorldFrame.BYTES);
        if(view==null){close();throw new IllegalStateException("Private camera transport unavailable");}
        frameReady=Api.INSTANCE.OpenEventW(0x00100000,false,new WString(prefix+"-world-frame"));
    }
    public boolean frameSignaled(){return frameReady!=null;}
    /** Wake on camera publication, bounded to 8ms so controls stay responsive.
     * A notification may be coalesced; the owned sequence is authoritative. */
    public void awaitNext(WorldFrame rendered,long maxNanos){
        if(frameReady==null||rendered==null||maxNanos<=0)return;
        long deadline=System.nanoTime()+Math.min(maxNanos,8_000_000L);
        while(true){
            var next=read();if(next==null||!next.sameFrame(rendered))return;
            long left=deadline-System.nanoTime();if(left<=0)return;
            int wait=Api.INSTANCE.WaitForSingleObject(frameReady,(int)Math.max(1,(left+999_999L)/1_000_000L));
            if(wait==0x102)return;
            if(wait!=0){Api.INSTANCE.CloseHandle(frameReady);frameReady=null;return;}
        }
    }
    public WorldFrame read() {
        if(view==null)return null;
        int result=Api.INSTANCE.WaitForSingleObject(lock,0);
        if(result==0||result==0x80)try{last=view.getByteArray(0,WorldFrame.BYTES);}
        finally{Api.INSTANCE.ReleaseMutex(lock);}
        return WorldFrame.decode(last,Api.INSTANCE.GetTickCount64());
    }
    public void close() {
        if(view!=null)Api.INSTANCE.UnmapViewOfFile(view);view=null;last=null;
        if(lock!=null)Api.INSTANCE.CloseHandle(lock);lock=null;
        if(mapping!=null)Api.INSTANCE.CloseHandle(mapping);mapping=null;
        if(frameReady!=null)Api.INSTANCE.CloseHandle(frameReady);frameReady=null;
    }
}
