package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.PointerByReference;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** Reusable GPU FBO -> premultiplied bottom-up DIB -> UpdateLayeredWindow.
 * The visible HWND must have no OpenGL pixel format/context. No media is cached. */
final class LayeredPresenter implements AutoCloseable {
    private final WinDef.HWND window;
    private final PassiveLayerWindows bands;
    private java.util.List<AlphaBounds.Region> regions=java.util.List.of();
    private long copiedPixels,transferPixels;
    private final WinUser.BLENDFUNCTION blend=new WinUser.BLENDFUNCTION();
    private final WinDef.POINT source=new WinDef.POINT(),destination=new WinDef.POINT();
    private final WinUser.SIZE size=new WinUser.SIZE();
    private WinDef.HDC dc;
    private WinDef.HBITMAP bitmap;
    private WinNT.HANDLE previousBitmap;
    private Pointer bits;
    private ByteBuffer pixels;
    private int fbo,color,stencil,width,height;
    private AlphaBounds bounds;
    private boolean boundsFailed;
    private AlphaBounds.Region region;
    private boolean closed;
    private boolean shieldReady;private int shieldX,shieldY;
    LayeredPresenter(WinDef.HWND window) {
        this.window=window;
        bands=new PassiveLayerWindows(window);
        blend.BlendOp=0;blend.BlendFlags=0;blend.SourceConstantAlpha=(byte)255;blend.AlphaFormat=1;
    }
    void resize(int w,int h) {
        if(closed)throw new IllegalStateException("Presenter closed");
        if(w<1||h<1||w>16384||h>16384||(long)w*h>16_777_216L)
            throw new IllegalArgumentException("Overlay dimensions exceed bounded surface allocation");
        if(width==w&&height==h&&fbo!=0)return;
        if(w>GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE)||h>GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE))
            throw new IllegalArgumentException("Overlay dimensions exceed GPU limit");
        releaseSurfaces();
        try {
            width=w;height=h;
            fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
            color=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,color);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
            stencil=GL30.glGenRenderbuffers();GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,stencil);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL30.GL_DEPTH24_STENCIL8,w,h);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_STENCIL_ATTACHMENT,GL30.GL_RENDERBUFFER,stencil);
            if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Offscreen framebuffer incomplete");
            dc=GDI32.INSTANCE.CreateCompatibleDC(null);if(dc==null)throw win32("CreateCompatibleDC");
            var info=new WinGDI.BITMAPINFO();
            info.bmiHeader.biSize=info.bmiHeader.size();info.bmiHeader.biWidth=w;
            // Positive height matches GL's bottom row first: no per-frame flip/copy.
            info.bmiHeader.biHeight=h;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;
            info.bmiHeader.biCompression=WinGDI.BI_RGB;
            var pointer=new PointerByReference();
            bitmap=GDI32.INSTANCE.CreateDIBSection(dc,info,WinGDI.DIB_RGB_COLORS,pointer,null,0);
            bits=pointer.getValue();if(bitmap==null||bits==null)throw win32("CreateDIBSection");
            previousBitmap=GDI32.INSTANCE.SelectObject(dc,bitmap);
            if(previousBitmap==null||Pointer.nativeValue(previousBitmap.getPointer())==-1)throw win32("SelectObject");
            pixels=MemoryUtil.memByteBuffer(Pointer.nativeValue(bits),Math.multiplyExact(Math.multiplyExact(w,h),4));
            size.cx=w;size.cy=h;
        } catch(Throwable failure) {releaseSurfaces();throw failure;}
    }
    int framebuffer(){return fbo;}
    void compose(NativeComposition target,boolean visible,boolean occlusion,boolean passive){
        long start=System.nanoTime();region=new AlphaBounds.Region(0,0,width,height);
        if(passive){if(bounds==null)bounds=new AlphaBounds(width,height);region=bounds.find(color,fbo,width,height);}
        regions=passive?bounds.regions():java.util.List.of(region);
        boundsNanos=System.nanoTime()-start;
        target.publishBands(fbo,width,height,visible,occlusion,region,regions);
        readNanos=target.readNanos();submitNanos=target.copyNanos();copiedPixels=target.readbackPixels();transferPixels=target.packedPixels();
        transport="native-d3d12";lastPresentNanos=System.nanoTime()-start;presentations++;
    }
    void probe(NativeComposition target){
        if(!target.probeReady()||region==null||pixels==null)return;
        // The fallback has just read this crop for ULW. Borrow its DIB rather
        // than forcing a second GL readback for every playing lyric frame.
        // Sparse DIB bands do not contain their transparent gaps. Read the
        // union only for this bounded negotiation probe, never borrow stale gaps.
        target.probe(fbo,width,height,region,regions.size()>1?null:pixels,
            Math.multiplyExact(region.bottom()*width+region.left(),4),width*4);
    }
    void inputShield(int x,int y){
        bands.count(1);
        if(shieldReady&&x==shieldX&&y==shieldY)return;
        var values=pixels.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for(int at=0;at<values.capacity();at++)values.put(at,0x01000000);
        destination.x=x;destination.y=y;source.x=source.y=0;size.cx=width;size.cy=height;
        if(!User32.INSTANCE.UpdateLayeredWindow(window,null,destination,size,dc,source,0,blend,2))throw win32("Input shield");
        shieldReady=true;shieldX=x;shieldY=y;
    }
    int resourceSets(){return fbo!=0&&bitmap!=null?1:0;}
    void present(int x,int y) {
        present(x,y,false);
    }
    void presentPassive(int x,int y){present(x,y,true);}
    void visible(boolean value){bands.visibility(value);}
    private void present(int x,int y,boolean passive) {
        shieldReady=false;transport="layered-window";
        if(closed||fbo==0||pixels==null)throw new IllegalStateException("Presenter not sized");
        long start=System.nanoTime();
        region=new AlphaBounds.Region(0,0,width,height);
        regions=java.util.List.of(region);
        if(passive&&!boundsFailed)try{
            if(bounds==null)bounds=new AlphaBounds(width,height);
            region=bounds.find(color,fbo,width,height);
            regions=bounds.regions();
        }catch(RuntimeException failure){
            if(bounds!=null)bounds.close();bounds=null;boundsFailed=true;
            System.out.println("OVERLAY_CROP unavailable; retaining full ULW: "+failure.getClass().getSimpleName());
        }
        boundsNanos=System.nanoTime()-start;long readStarted=System.nanoTime();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,fbo);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,4);GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,width);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,0);
        // glReadPixels waits for this FBO's writes and writes directly into the
        // reused DIB allocation. No Java image, PNG, row conversion or extra copy.
        // Disconnected alpha bands avoid downloading/uploading the transparent
        // gap between cinematic bars, lyrics and HUD. At most four rectangles.
        copiedPixels=0;
        for(var crop:regions){
            long offset=((long)crop.bottom()*width+crop.left())*4;
            GL11.nglReadPixels(crop.left(),crop.bottom(),crop.width(),crop.height(),GL12.GL_BGRA,GL11.GL_UNSIGNED_BYTE,Pointer.nativeValue(bits)+offset);
            copiedPixels+=crop.area();
        }
        transferPixels=copiedPixels;
        int error=GL11.glGetError();if(error!=GL11.GL_NO_ERROR)throw new IllegalStateException("Overlay readback GL error "+error);
        readNanos=System.nanoTime()-readStarted;long submitStarted=System.nanoTime();
        // The passive HWND covers the alpha crop. Windows consumes this source
        // rectangle from the reusable full-size DIB; moving/resizing the HWND
        // removes old pixels without a full-frame clear/readback. Interactive
        // frames retain the full game client rect for exact mouse coordinates.
        var placements=bands.plan(regions);
        for(var placement:placements){
            var crop=placement.region();
            destination.x=x+crop.left();destination.y=y+height-crop.top();
            source.x=crop.left();source.y=height-crop.top();
            size.cx=crop.width();size.cy=crop.height();
            if(!User32.INSTANCE.UpdateLayeredWindow(bands.window(placement.slot()),null,destination,size,dc,source,0,blend,2))throw win32("UpdateLayeredWindow");
            bands.presented(placement);
        }
        bands.commit(placements);bands.refresh();
        submitNanos=System.nanoTime()-submitStarted;
        lastPresentNanos=System.nanoTime()-start;presentations++;
    }
    private String transport="none";
    String transport(){return transport;}
    private long lastPresentNanos,presentations;
    private long boundsNanos,readNanos,submitNanos;
    long boundsNanos(){return boundsNanos;}long readNanos(){return readNanos;}long submitNanos(){return submitNanos;}
    long lastPresentNanos(){return lastPresentNanos;}
    long presentations(){return presentations;}
    long readbackPixels(){return copiedPixels;}
    long transferPixels(){return transferPixels;}
    int regionCount(){return regions.size();}
    long fullPixels(){return (long)width*height;}
    int pixel(int x,int y) {
        if(bits==null||x<0||y<0||x>=width||y>=height)throw new IllegalArgumentException("Pixel outside surface");
        int bottomY=height-1-y;
        if(regions.stream().noneMatch(r->x>=r.left()&&x<r.right()&&bottomY>=r.bottom()&&bottomY<r.top()))return 0;
        return bits.getInt(((long)(height-1-y)*width+x)*4);
    }
    private static IllegalStateException win32(String call){return new IllegalStateException(call+" failed win32="+Native.getLastError());}
    private void releaseSurfaces() {
        shieldReady=false;
        bands.close();regions=java.util.List.of();copiedPixels=0;
        SkijaHost.renderTarget(0);
        if(bounds!=null)bounds.close();bounds=null;region=null;boundsFailed=false;
        pixels=null;bits=null;
        if(dc!=null&&previousBitmap!=null&&Pointer.nativeValue(previousBitmap.getPointer())!=-1)GDI32.INSTANCE.SelectObject(dc,previousBitmap);
        previousBitmap=null;
        if(bitmap!=null){GDI32.INSTANCE.DeleteObject(bitmap);bitmap=null;}
        if(dc!=null){GDI32.INSTANCE.DeleteDC(dc);dc=null;}
        if(stencil!=0){GL30.glDeleteRenderbuffers(stencil);stencil=0;}
        if(color!=0){GL11.glDeleteTextures(color);color=0;}
        if(fbo!=0){GL30.glDeleteFramebuffers(fbo);fbo=0;}
        width=height=0;
    }
    public void close(){if(!closed){releaseSurfaces();closed=true;}}
}
