package com.muoniumplayer.bedrock;

import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;

/** Independently authored IPC client. Opens only the game-owned BSChat mapping. */
final class VoiceMailbox implements AutoCloseable {
    static final int BYTES=30976,FRAME=960,SLOTS=8,MAGIC=0x5342554d,VERSION=2;
    interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);
        HANDLE OpenMutexW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(HANDLE map,int access,int hi,int lo,long bytes);
        int WaitForSingleObject(HANDLE handle,int millis);boolean ReleaseMutex(HANDLE handle);
        boolean UnmapViewOfFile(Pointer view);boolean CloseHandle(HANDLE handle);long GetTickCount64();
    }
    record Snapshot(long session,boolean ready){}
    private final long pid;
    private HANDLE mapping,lock;
    private Pointer view;
    private boolean contended;
    VoiceMailbox(long gamePid){
        if(gamePid<=0||gamePid>0xFFFFFFFFL)throw new IllegalArgumentException("Game PID");
        pid=gamePid;var name="Local\\Muonium-BSChat-"+pid;
        mapping=Api.I.OpenFileMappingW(6,false,new WString(name));
        lock=Api.I.OpenMutexW(0x00100001,false,new WString(name+"-lock"));
        if(mapping!=null&&lock!=null)view=Api.I.MapViewOfFile(mapping,6,0,0,BYTES);
        if(view==null)close();
    }
    boolean available(){return view!=null;}
    boolean contended(){return contended;}
    private boolean enter(){if(view==null)return false;int r=Api.I.WaitForSingleObject(lock,0);return r==0||r==0x80;}
    private boolean valid(long now){
        if(view.getInt(0)!=MAGIC||view.getInt(4)!=VERSION||view.getInt(8)!=BYTES||view.getInt(12)!=48000
           ||view.getInt(16)!=1||view.getInt(20)!=FRAME||view.getInt(24)!=SLOTS
           ||Integer.toUnsignedLong(view.getInt(28))!=pid)return false;
        long heartbeat=view.getLong(64);return heartbeat>0&&now>=heartbeat&&now-heartbeat<=250;
    }
    Snapshot snapshot(){
        contended=false;
        if(view==null)return null;
        int acquired=Api.I.WaitForSingleObject(lock,0);
        if(acquired==0x102){contended=true;return null;}
        if(acquired!=0&&acquired!=0x80)return null;
        try{if(!valid(Api.I.GetTickCount64()))return null;return new Snapshot(view.getLong(32),view.getInt(40)==1);}
        finally{Api.I.ReleaseMutex(lock);}
    }
    boolean publish(long session,float[] samples,boolean enabled){
        if(samples!=null&&samples.length!=FRAME)throw new IllegalArgumentException("20ms mono frame");
        if(!enter())return false;
        try{
            long now=Api.I.GetTickCount64();
            if(!valid(now)||view.getLong(32)!=session||view.getInt(40)!=1)return false;
            int publisher=view.getInt(88),self=(int)ProcessHandle.current().pid();
            long heartbeat=view.getLong(56);
            if(publisher!=0&&publisher!=self&&heartbeat>0&&now>=heartbeat&&now-heartbeat<250)return false;
            view.setInt(88,self);view.setLong(80,session);view.setLong(56,now);
            view.setInt(44,enabled?1:0);
            if(enabled&&samples!=null){
                long written=view.getLong(48);if(written<0||written==Long.MAX_VALUE)return false;
                long slot=128+(written%SLOTS)*3856;
                view.setLong(slot,written+1);view.setLong(slot+8,now);view.write(slot+16,samples,0,FRAME);
                view.setLong(48,written+1);
            }
            return true;
        }finally{Api.I.ReleaseMutex(lock);}
    }
    boolean requestSettings(){
        if(!enter())return false;
        try{if(!valid(Api.I.GetTickCount64())||view.getInt(40)!=1)return false;view.setInt(72,view.getInt(72)+1);return true;}
        finally{Api.I.ReleaseMutex(lock);}
    }
    public void close(){
        if(view!=null)Api.I.UnmapViewOfFile(view);view=null;
        if(mapping!=null)Api.I.CloseHandle(mapping);mapping=null;
        if(lock!=null)Api.I.CloseHandle(lock);lock=null;
    }
}
