package com.muoniumplayer.bedrock;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.muoniumplayer.core.cine.CinePose;
/** Private owned-value command lease. No game address enters the Java host. */
final class CinematicReader implements AutoCloseable {
    private interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);HANDLE OpenMutexW(int access,boolean inherit,WString name);
        HANDLE OpenEventW(int access,boolean inherit,WString name);Pointer MapViewOfFile(HANDLE map,int access,int hi,int lo,long bytes);
        int WaitForSingleObject(HANDLE handle,int millis);boolean ReleaseMutex(HANDLE handle);boolean SetEvent(HANDLE h);boolean ResetEvent(HANDLE h);
        boolean UnmapViewOfFile(Pointer view);boolean CloseHandle(HANDLE handle);long GetTickCount64();
    }
    private HANDLE mapping,lock,request;private Pointer view;private long sequence;private boolean wanted;
    CinematicReader(String prefix){
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("Cinematic identity");
        mapping=Api.I.OpenFileMappingW(6,false,new WString(prefix+"-cinematic"));lock=Api.I.OpenMutexW(0x00100001,false,new WString(prefix+"-cinematic-lock"));
        request=Api.I.OpenEventW(2,false,new WString(prefix+"-cinematic-active"));
        if(mapping!=null&&lock!=null&&request!=null)view=Api.I.MapViewOfFile(mapping,6,0,0,200);
        if(view==null){close();System.out.println("CINEMATIC transport unavailable; ordinary camera retained");}
    }
    void request(boolean value){if(request!=null&&wanted!=value){if(value)Api.I.SetEvent(request);else Api.I.ResetEvent(request);wanted=value;}}
    CinematicState read(){
        if(view==null||!wanted)return null;int wait=Api.I.WaitForSingleObject(lock,0);if(wait!=0&&wait!=0x80)return null;
        try{return CinematicState.decode(view.getByteArray(88,112),Api.I.GetTickCount64());}finally{Api.I.ReleaseMutex(lock);}
    }
    void write(CinematicState state,CinePose pose,boolean hideHud,boolean allowed){
        if(view==null)return;var bytes=CinematicCommand.encode(state,pose,++sequence,Api.I.GetTickCount64(),hideHud,allowed);
        int wait=Api.I.WaitForSingleObject(lock,0);if(wait!=0&&wait!=0x80)return;
        try{view.write(0,bytes,0,88);}finally{Api.I.ReleaseMutex(lock);}
    }
    public void close(){
        request(false);write(null,null,false,false);if(view!=null)Api.I.UnmapViewOfFile(view);view=null;
        for(var h:new HANDLE[]{request,lock,mapping})if(h!=null)Api.I.CloseHandle(h);request=lock=mapping=null;
    }
}
