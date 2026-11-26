package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;

/** Optional read-only player pose transport. Missing mapping only disables distance voice. */
final class RoomVoiceWorldReader implements AutoCloseable {
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
    private HANDLE mapping,lock;
    private Pointer view;
    private byte[] last;
    public RoomVoiceWorldReader(String prefix) {
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("World identity");
        mapping=Api.INSTANCE.OpenFileMappingW(4,false,new WString(prefix+"-voice-world"));
        lock=Api.INSTANCE.OpenMutexW(0x00100001,false,new WString(prefix+"-voice-world-lock"));
        if(mapping!=null&&lock!=null)view=Api.INSTANCE.MapViewOfFile(mapping,4,0,0,RoomVoiceWorld.BYTES);
        if(view==null){close();System.out.println("ROOM_VOICE poseTransport=unavailable ordinaryRoomVoice=available");}
    }
    public RoomVoiceWorld read() {
        if(view==null)return null;
        int result=Api.INSTANCE.WaitForSingleObject(lock,0);
        if(result==0||result==0x80)try{last=view.getByteArray(0,RoomVoiceWorld.BYTES);}
        finally{Api.INSTANCE.ReleaseMutex(lock);}
        return RoomVoiceWorld.decode(last,Api.INSTANCE.GetTickCount64());
    }
    public void close() {
        if(view!=null)Api.INSTANCE.UnmapViewOfFile(view);view=null;last=null;
        if(lock!=null)Api.INSTANCE.CloseHandle(lock);lock=null;
        if(mapping!=null)Api.INSTANCE.CloseHandle(mapping);mapping=null;
    }
}
