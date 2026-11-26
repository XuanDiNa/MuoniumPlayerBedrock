package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;

/** Read-only owned values plus an explicit demand event; no game addresses. */
public final class EnvironmentReader implements AutoCloseable {
    private interface Api extends StdCallLibrary {
        Api INSTANCE=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);
        HANDLE OpenMutexW(int access,boolean inherit,WString name);
        HANDLE OpenEventW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(HANDLE map,int access,int high,int low,long bytes);
        int WaitForSingleObject(HANDLE handle,int millis);
        boolean ReleaseMutex(HANDLE handle);boolean SetEvent(HANDLE h);boolean ResetEvent(HANDLE h);
        boolean UnmapViewOfFile(Pointer view);boolean CloseHandle(HANDLE handle);long GetTickCount64();
    }
    private HANDLE mapping,lock,request;private Pointer view;private boolean requested;
    private final EnvironmentSnapshotCache cache=new EnvironmentSnapshotCache();
    public EnvironmentReader(String prefix) {
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("Environment identity");
        mapping=Api.INSTANCE.OpenFileMappingW(4,false,new WString(prefix+"-environment"));
        lock=Api.INSTANCE.OpenMutexW(0x00100001,false,new WString(prefix+"-environment-lock"));
        request=Api.INSTANCE.OpenEventW(2,false,new WString(prefix+"-environment-active"));
        if(mapping!=null&&lock!=null&&request!=null)view=Api.INSTANCE.MapViewOfFile(mapping,4,0,0,EnvironmentFrame.BYTES);
        if(view==null){close();throw new IllegalStateException("Private environment transport unavailable");}
    }
    public void request(boolean value) {
        if(request!=null&&value!=requested){if(value)Api.INSTANCE.SetEvent(request);else Api.INSTANCE.ResetEvent(request);requested=value;}
        if(!value)cache.clear();
    }
    public EnvironmentFrame read(WorldFrame world) {
        return readEpoch(world==null?0:world.epoch());
    }
    EnvironmentFrame readEpoch(long epoch) {
        if(view==null||epoch<=0||!requested){cache.clear();return null;}
        int status=Api.INSTANCE.WaitForSingleObject(lock,0);
        if(status==0||status==0x80)try{
            return cache.read(view.getLong(8),view.getLong(16),view.getLong(24),view.getInt(32),
                Api.INSTANCE.GetTickCount64(),epoch,()->view.getByteArray(0,EnvironmentFrame.BYTES));
        }finally{Api.INSTANCE.ReleaseMutex(lock);}
        // A scan's short publication lock is not a scene transition. Reuse
        // immutable values for at most 100ms; a successful invalid read revokes.
        return cache.contended(Api.INSTANCE.GetTickCount64(),epoch);
    }
    public void close(){
        request(false);if(view!=null)Api.INSTANCE.UnmapViewOfFile(view);view=null;
        for(var handle:new HANDLE[]{request,lock,mapping})if(handle!=null)Api.INSTANCE.CloseHandle(handle);
        request=lock=mapping=null;cache.clear();
    }
}
