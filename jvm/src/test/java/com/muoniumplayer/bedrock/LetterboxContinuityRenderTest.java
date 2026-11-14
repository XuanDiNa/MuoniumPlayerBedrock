package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** An unrelated lyric island must not hide/reassign a continuously drawn bar. */
class LetterboxContinuityRenderTest {
    interface Windows extends StdCallLibrary {
        Windows I=Native.load("user32",Windows.class);
        Pointer SetWindowLongPtrW(WinDef.HWND hwnd,int index,Pointer value);
    }
    @Test void changingLyricBandsNeverHideTheUnchangedTopLetterbox()throws Exception{
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        var hidden=new AtomicInteger();WinDef.HWND watched=null;Pointer original=null;
        WinUser.WindowProc procedure=null;
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())){
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());
            com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
            new OverlayWindowMode(windows.display()).update(false);
            presenter.resize(640,480);client.getWindow().size(640,480,1);SkijaHost.renderTarget(presenter.framebuffer());
            draw(presenter,true);presenter.visible(true);
            var f=LayeredPresenter.class.getDeclaredField("bands");f.setAccessible(true);
            var bands=(PassiveLayerWindows)f.get(presenter);
            watched=bands.window(2); // Initial sparse frame: bottom bar, lyric, top bar.
            var hwnd=watched;Pointer[] forward={null};
            procedure=(h,msg,w,l)->{
                if(msg==0x18&&w.longValue()==0)hidden.incrementAndGet();
                return User32.INSTANCE.CallWindowProc(forward[0],h,msg,w,l);
            };
            original=Windows.I.SetWindowLongPtrW(watched,-4,CallbackReference.getFunctionPointer(procedure));
            assertNotNull(original);forward[0]=original;
            var before=new WinDef.RECT();assertTrue(User32.INSTANCE.GetWindowRect(watched,before));
            for(int i=0;i<20;i++){
                draw(presenter,i%2!=0);GLFW.glfwPollEvents();
                assertEquals(0,hidden.get(),"Unchanged top bar was hidden while an unrelated lyric appeared/disappeared");
                assertTrue(User32.INSTANCE.IsWindowVisible(hwnd));
                var after=new WinDef.RECT();assertTrue(User32.INSTANCE.GetWindowRect(hwnd,after));
                assertEquals(before.toRectangle(),after.toRectangle(),"Continuous bar must keep its displayed rectangle");
                assertEquals(0xFF000000,presenter.pixel(4,4));assertEquals(0xFF000000,presenter.pixel(4,476));
                assertTrue(presenter.readbackPixels()<presenter.fullPixels()/2,"Retain sparse transfers");
            }
            System.out.println("LETTERBOX_CONTINUITY frames=20 hidden=0 sparse=true");
          }finally{SkijaHost.shutdown();}
        }finally{
            if(watched!=null&&original!=null&&User32.INSTANCE.IsWindow(watched))Windows.I.SetWindowLongPtrW(watched,-4,original);
            java.lang.ref.Reference.reachabilityFence(procedure);SkijaHost.shutdown();SessionMedia.close();GLFW.glfwTerminate();
        }
    }
    private static void draw(LayeredPresenter presenter,boolean lyric){
        SkijaHost.beginFrame(0);
        try(var paint=new Paint().setColor(0xFF000000)){
            SkijaHost.canvas().drawRect(Rect.makeXYWH(0,0,640,32),paint);
            SkijaHost.canvas().drawRect(Rect.makeXYWH(0,448,640,32),paint);
            if(lyric)SkijaHost.canvas().drawRect(Rect.makeXYWH(250,220,100,20),paint.setColor(0x80FFFFFF));
        }finally{SkijaHost.endFrame();}
        presenter.presentPassive(40,40);
    }
}
