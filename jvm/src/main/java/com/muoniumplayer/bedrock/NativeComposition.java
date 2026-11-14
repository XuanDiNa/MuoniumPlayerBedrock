package com.muoniumplayer.bedrock;
import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;
import java.util.*;

/** Private bounded pixel mailbox. No game image/depth ever enters Java.
 * The render context is hidden; the optional display HWND is input-only. */
final class NativeComposition implements AutoCloseable {
    static final int ATLAS=2048,SCREEN_BYTES=16777216*4,BYTES=4096+SCREEN_BYTES+ATLAS*ATLAS*4;
    private interface Api extends StdCallLibrary {
        Api I=Native.load("kernel32",Api.class);
        HANDLE OpenFileMappingW(int access,boolean inherit,WString name);HANDLE OpenMutexW(int access,boolean inherit,WString name);
        Pointer MapViewOfFile(HANDLE mapping,int access,int hi,int lo,long bytes);int WaitForSingleObject(HANDLE h,int millis);
        boolean ReleaseMutex(HANDLE h);boolean UnmapViewOfFile(Pointer view);boolean CloseHandle(HANDLE h);long GetTickCount64();
    }
    record Plane(double[] position,float[] right,float[] down){}
    private record Tile(Plane plane,float x,float y,float width,float height,int left,int top,int w,int h){}
    private HANDLE mapping,lock;private Pointer view;
    private final List<Tile> tiles=new ArrayList<>();
    private ByteBuffer atlasBytes,screenBytes;private Surface atlas;private int shelfX,shelfY,shelfHeight,usedHeight;
    private long sequence;private boolean collecting;private WorldFrame world;
    private long readNanos,copyNanos,readPixels,packedPixels;
    long readNanos(){return readNanos;}long copyNanos(){return copyNanos;}
    long readbackPixels(){return readPixels;}long packedPixels(){return packedPixels;}
    record Packed(int width,int height,List<AlphaBounds.Region> bands){
        static Packed of(int width,int height,List<AlphaBounds.Region> bands){
            if(width<1||height<1||width>8192||height>8192||(long)width*height>16777216||bands.size()>4)
                throw new IllegalArgumentException("Composition bands budget");
            int w=0,h=0;
            for(int i=0;i<bands.size();i++){
                var b=bands.get(i);
                if(b.width()<1||b.height()<1||b.left()<0||b.bottom()<0||b.right()>width||b.top()>height)
                    throw new IllegalArgumentException("Composition band outside viewport");
                for(int j=0;j<i;j++){var a=bands.get(j);if(b.bottom()<a.top()&&a.bottom()<b.top())
                    throw new IllegalArgumentException("Composition bands overlap");}
                w=Math.max(w,b.width());h=Math.addExact(h,b.height());
            }
            return new Packed(w,h,List.copyOf(bands));
        }
    }
    static final class ProbeGate {
        private long next;
        boolean ready(long now){if(now<next)return false;next=now+1000;return true;}
        void reset(){next=0;}
    }
    static final class Route {
        private boolean selected,failed;
        boolean nativeOutput(boolean available,boolean acknowledged){
            if(selected&&(!available||!acknowledged)){selected=false;failed=true;}
            if(!failed&&available&&acknowledged)selected=true;
            return selected;
        }
        void suspend(){selected=false;}
    }
    private final Route route=new Route();
    private final ProbeGate probeGate=new ProbeGate();
    boolean probeReady(){return available()&&!route.failed&&probeGate.ready(Api.I.GetTickCount64());}
    static void copyTopLeft(ByteBuffer source,int offset,int stride,int width,int height,ByteBuffer destination){
        var src=source.duplicate();
        for(int row=0;row<height;row++){
            int start=Math.addExact(offset,Math.multiplyExact(height-1-row,stride));
            src.clear().position(start).limit(Math.addExact(start,Math.multiplyExact(width,4)));
            destination.put(src);
        }
    }
    private PictureRecorder recorder;private Canvas saved;private Plane recordingPlane;
    private float left,top,right,bottom;private boolean hasBounds;
    static Plane plane(double ex,double ey,double ez,double x,double y,double z,double yaw,double pitch,float scale){
        var m=new Matrix4f().rotateY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch)).scale(-scale,-scale,scale);
        var r=m.transformDirection(new Vector3f(1,0,0));var d=m.transformDirection(new Vector3f(0,1,0));
        return new Plane(new double[]{ex+x,ey+y,ez+z},new float[]{r.x,r.y,r.z},new float[]{d.x,d.y,d.z});
    }
    NativeComposition(String prefix){
        if(!prefix.matches("Local\\\\MuoniumBedrock-[0-9a-f]{32}"))throw new IllegalArgumentException("Composition identity");
        mapping=Api.I.OpenFileMappingW(6,false,new WString(prefix+"-composition"));lock=Api.I.OpenMutexW(0x00100001,false,new WString(prefix+"-composition-lock"));
        if(mapping!=null&&lock!=null)view=Api.I.MapViewOfFile(mapping,6,0,0,BYTES);
        if(view==null)close();
    }
    boolean available(){return view!=null&&view.getInt(3072)==1;}
    boolean acknowledged(boolean visible){
        if(!visible){route.suspend();probeGate.reset();if(view!=null){view.setInt(3076,0);view.setLong(3080,0);}return false;}
        // Owner resize/recreation is intentional renegotiation, not a failed lease.
        if(view!=null&&view.getInt(3096)==1){route.suspend();probeGate.reset();return false;}
        long at=view==null?0:view.getLong(3080),now=Api.I.GetTickCount64();
        boolean selected=route.nativeOutput(available(),at>0&&at<=now&&now-at<1000);
        if(route.failed&&view!=null)view.setInt(3076,0);
        if(lastRoute==null||lastRoute!=selected){lastRoute=selected;
            System.out.println("COMPOSITION_ROUTE native="+selected+" ackAgeMs="+(at>0&&at<=now?now-at:-1)+" fallbackLatched="+route.failed+" lyrics=screen-raster nativeWorldDepth=false");}
        return selected;
    }
    private Boolean lastRoute;
    void beginFrame(WorldFrame frame,boolean enabled){
        tiles.clear();shelfX=shelfY=shelfHeight=usedHeight=0;world=frame;collecting=enabled&&frame!=null&&available();
        if(atlas!=null)atlas.getCanvas().clear(0);
    }
    boolean begin(double x,double y,double z,double yaw,double pitch,float scale){
        if(!collecting||tiles.size()>=32)return false;
        if(recorder!=null)throw new IllegalStateException("Nested native lyric plane");
        recordingPlane=plane(world.eyeX(),world.eyeY(),world.eyeZ(),x,y,z,yaw,pitch,scale);
        recorder=new PictureRecorder();saved=SkijaHost.redirectCanvas(recorder.beginRecording(Rect.makeLTRB(-32768,-32768,32768,32768)));hasBounds=false;return true;
    }
    boolean recording(){return recorder!=null;}
    void include(float x,float y,float w,float h){
        if(!recording()||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0)return;
        if(!hasBounds){left=x;top=y;right=x+w;bottom=y+h;hasBounds=true;}else{left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+w);bottom=Math.max(bottom,y+h);}
    }
    void end(){
        if(recorder==null)return;var r=recorder;recorder=null;SkijaHost.redirectCanvas(saved);saved=null;
        try(r;var picture=r.finishRecordingAsPicture()){
            if(!hasBounds)return;float x=(float)Math.floor(left)-2,y=(float)Math.floor(top)-2,w=(float)Math.ceil(right)-x+2,h=(float)Math.ceil(bottom)-y+2;
            if(w<=0||h<=0||w>65536||h>65536)return;
            float density=Math.min(2f,Math.min(2044/w,2044/h));int tw=Math.max(1,(int)Math.ceil(w*density)),th=Math.max(1,(int)Math.ceil(h*density));
            if(shelfX+tw>ATLAS){shelfX=0;shelfY+=shelfHeight;shelfHeight=0;}if(shelfY+th>ATLAS)return;
            if(atlas==null){atlasBytes=MemoryUtil.memAlloc(ATLAS*ATLAS*4);atlas=Surface.makeRenderTarget(SkijaHost.context(),false,ImageInfo.makeN32Premul(ATLAS,ATLAS));if(atlas==null)throw new IllegalStateException("Native lyric atlas allocation");atlas.getCanvas().clear(0);}
            var c=atlas.getCanvas();int save=c.save();try{c.clipRect(Rect.makeXYWH(shelfX,shelfY,tw,th));c.translate(shelfX,shelfY);c.scale(tw/w,th/h);c.translate(-x,-y);c.drawPicture(picture);}finally{c.restoreToCount(save);}
            tiles.add(new Tile(recordingPlane,x,y,w,h,shelfX,shelfY,tw,th));shelfX+=tw;shelfHeight=Math.max(shelfHeight,th);usedHeight=Math.max(usedHeight,shelfY+th);
        }
    }
    boolean publish(int framebuffer,int width,int height,boolean visible,boolean occlusion,AlphaBounds.Region crop){
        return publish(framebuffer,width,height,visible,occlusion,crop,null,0,0);
    }
    boolean publish(int framebuffer,int width,int height,boolean visible,boolean occlusion,AlphaBounds.Region crop,
                    ByteBuffer borrowedPixels,int borrowedOffset,int borrowedStride){
        return publish(framebuffer,width,height,visible,occlusion,crop,borrowedPixels,borrowedOffset,borrowedStride,false,null);
    }
    boolean probe(int framebuffer,int width,int height,AlphaBounds.Region crop,ByteBuffer pixels,int offset,int stride){
        return publish(framebuffer,width,height,true,false,crop,pixels,offset,stride,true,null);
    }
    boolean publishBands(int framebuffer,int width,int height,boolean visible,boolean occlusion,
                         AlphaBounds.Region crop,List<AlphaBounds.Region> bands){
        // Old/dormant consumers retain the v1 union; only D3D12 advertises v2.
        var packed=view!=null&&view.getInt(3100)==2?Packed.of(width,height,visible?bands:List.of()):null;
        return publish(framebuffer,width,height,visible,occlusion,crop,null,0,0,false,packed);
    }
    private boolean publish(int framebuffer,int width,int height,boolean visible,boolean occlusion,AlphaBounds.Region crop,
                    ByteBuffer borrowedPixels,int borrowedOffset,int borrowedStride,boolean probeOnly,Packed packed){
        readNanos=copyNanos=readPixels=packedPixels=0;
        if(!available())return false;
        long readStarted=System.nanoTime();
        int x=visible?crop.left():0,y=visible?height-crop.top():0,w=visible?crop.width():0,h=visible?crop.height():0;
        if(packed!=null){x=y=0;w=packed.width();h=packed.height();}
        packedPixels=(long)w*h;
        int bytes=Math.multiplyExact(Math.multiplyExact(w,h),4);if(bytes>SCREEN_BYTES)throw new IllegalArgumentException("Composition screen budget");
        if(bytes>0&&borrowedPixels==null){if(screenBytes==null||screenBytes.capacity()<bytes){if(screenBytes!=null)MemoryUtil.memFree(screenBytes);screenBytes=MemoryUtil.memAlloc(bytes);}
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,framebuffer);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,4);GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,0);GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,0);
            if(packed==null){
                GL11.nglReadPixels(x,height-y-h,w,h,GL12.GL_BGRA,GL11.GL_UNSIGNED_BYTE,MemoryUtil.memAddress(screenBytes));
                readPixels=(long)w*h;
            }else{
                // Pack bottom-up once so the existing full-image flip produces
                // top-left bands in header order. Padding is always initialized.
                if(packed.bands().stream().anyMatch(band->band.width()<packed.width()))
                    MemoryUtil.memSet(MemoryUtil.memAddress(screenBytes),0,bytes);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,w);int offsetY=0;
                try{for(var band:packed.bands()){
                    long offset=(long)(h-offsetY-band.height())*w*4;
                    GL11.nglReadPixels(band.left(),band.bottom(),band.width(),band.height(),GL12.GL_BGRA,GL11.GL_UNSIGNED_BYTE,MemoryUtil.memAddress(screenBytes)+offset);
                    offsetY+=band.height();readPixels+=band.area();
                }}finally{GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,0);}
            }
            if(GL11.glGetError()!=0)throw new IllegalStateException("Composition GL readback");}
        if(visible&&usedHeight>0)try(var pixmap=Pixmap.make(new ImageInfo(ATLAS,usedHeight,ColorType.BGRA_8888,ColorAlphaType.PREMUL),MemoryUtil.memAddress(atlasBytes),ATLAS*4)){
            if(!atlas.readPixels(pixmap,0,0))throw new IllegalStateException("Native lyric atlas readback");
        }
        readNanos=System.nanoTime()-readStarted;
        var header=ByteBuffer.allocate(2728).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x5043504d).putInt(packed==null?1:2).putLong(++sequence).putLong(Api.I.GetTickCount64()).putLong(world==null?0:world.epoch())
            .putInt(world==null?0:world.dimension()).putInt(visible?1:0).putInt(width).putInt(height).putInt(x).putInt(y).putInt(w).putInt(h)
            .putInt(visible?tiles.size():0).putInt(usedHeight).putInt(occlusion?1:0).putInt(probeOnly?1:0);
        for(var tile:tiles){for(double v:tile.plane.position)header.putDouble(v);for(float v:tile.plane.right)header.putFloat(v);for(float v:tile.plane.down)header.putFloat(v);
            header.putFloat(tile.x).putFloat(tile.y).putFloat(tile.width).putFloat(tile.height).putInt(tile.left).putInt(tile.top).putInt(tile.w).putInt(tile.h);}
        if(packed!=null){
            header.position(2640);header.putInt(packed.bands().size());int offsetY=0;
            for(var band:packed.bands()){
                header.putInt(band.left()).putInt(height-band.top()).putInt(band.width()).putInt(band.height()).putInt(offsetY);
                offsetY+=band.height();
            }
        }
        long copyStarted=System.nanoTime();
        int wait=Api.I.WaitForSingleObject(lock,0);if(wait!=0&&wait!=0x80)return false;
        try{
            // GL is bottom-up; the wire image and atlas are top-left BGRA.
            if(bytes>0){var dest=view.getByteBuffer(4096,bytes);
                copyTopLeft(borrowedPixels==null?screenBytes:borrowedPixels,borrowedPixels==null?0:borrowedOffset,
                    borrowedPixels==null?w*4:borrowedStride,w,h,dest);}
            if(visible&&usedHeight>0){var src=atlasBytes.duplicate();src.limit(usedHeight*ATLAS*4);view.getByteBuffer(4096L+SCREEN_BYTES,usedHeight*(long)ATLAS*4).put(src);}
            view.write(0,header.array(),0,header.capacity());
            view.setInt(3076,visible&&!route.failed?1:0);return true;
        }finally{copyNanos=System.nanoTime()-copyStarted;Api.I.ReleaseMutex(lock);}
    }
    public void close(){
        if(recorder!=null){SkijaHost.redirectCanvas(saved);recorder.close();recorder=null;saved=null;}
        if(atlas!=null)atlas.close();atlas=null;if(atlasBytes!=null)MemoryUtil.memFree(atlasBytes);atlasBytes=null;if(screenBytes!=null)MemoryUtil.memFree(screenBytes);screenBytes=null;
        if(view!=null)Api.I.UnmapViewOfFile(view);view=null;for(var h:new HANDLE[]{lock,mapping})if(h!=null)Api.I.CloseHandle(h);lock=mapping=null;
    }
}
