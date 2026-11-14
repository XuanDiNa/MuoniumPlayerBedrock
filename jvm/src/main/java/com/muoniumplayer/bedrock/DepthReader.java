package com.muoniumplayer.bedrock;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.*;

/** Optional bidirectional owned-value transport; never blocks a game frame. */
final class DepthReader implements AutoCloseable {
    private interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);
        HANDLE OpenMutexW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(HANDLE mapping,int access,int hi,int lo,long bytes);
        int WaitForSingleObject(HANDLE handle,int ms);boolean ReleaseMutex(HANDLE handle);
        boolean UnmapViewOfFile(Pointer view);boolean CloseHandle(HANDLE handle);long GetTickCount64();
    }
    private HANDLE mapping,lock;private Pointer view;private DepthFrame cached;private long now;
    DepthReader(String prefix){
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("Depth identity");
        mapping=Api.I.OpenFileMappingW(6,false,new WString(prefix+"-depth"));lock=Api.I.OpenMutexW(0x00100001,false,new WString(prefix+"-depth-lock"));
        if(mapping!=null&&lock!=null)view=Api.I.MapViewOfFile(mapping,6,0,0,DepthFrame.HEADER+(long)DepthFrame.MAX_PIXELS*4);
        if(view==null){close();System.out.println("DEPTH transport unavailable; voxel clipping retained");}
    }
    long now(){return now;}
    DepthFrame read(){
        now=Api.I.GetTickCount64();if(view==null)return null;int wait=Api.I.WaitForSingleObject(lock,0);
        if(wait==0||wait==0x80)try{
            long sequence=view.getLong(56);
            if(cached==null||cached.sequence!=sequence){var header=view.getByteArray(0,DepthFrame.HEADER);int bytes=DepthFrame.pixelBytes(header);
                cached=bytes==0?null:DepthFrame.decode(view.getByteArray(0,DepthFrame.HEADER+bytes),now);}
        }finally{Api.I.ReleaseMutex(lock);}
        return cached!=null&&now>=cached.at&&now-cached.at<=100?cached:null;
    }
    void request(WorldFrame frame,int width,int height,int[] crop,boolean enabled){
        if(view==null)return;int wait=Api.I.WaitForSingleObject(lock,0);if(wait!=0&&wait!=0x80)return;
        try{
            var b=ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0,0x5153504d).putInt(4,2);
            if(enabled&&frame!=null&&crop!=null&&crop.length==4&&width>0&&height>0&&width<=8192&&height<=8192&&(long)width*height<=16777216
                &&crop[0]>=0&&crop[1]>=0&&crop[2]>0&&crop[3]>0&&crop[2]<=width&&crop[3]<=height&&crop[0]<=width-crop[2]&&crop[1]<=height-crop[3]){
                b.putLong(8,Api.I.GetTickCount64()).putLong(16,frame.epoch()).putInt(24,frame.dimension()).putInt(28,1)
                    .putInt(32,width).putInt(36,height).putInt(40,crop[0]).putInt(44,crop[1]).putInt(48,crop[2]).putInt(52,crop[3]);
            }
            view.write(0,b.array(),0,56);
        }finally{Api.I.ReleaseMutex(lock);}
    }
    public void close(){request(null,0,0,null,false);if(view!=null)Api.I.UnmapViewOfFile(view);view=null;cached=null;
        if(lock!=null)Api.I.CloseHandle(lock);lock=null;if(mapping!=null)Api.I.CloseHandle(mapping);mapping=null;}
}
