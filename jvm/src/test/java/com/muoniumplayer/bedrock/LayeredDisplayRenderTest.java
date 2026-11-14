package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

/** Final DWM pixels from two owned windows; no Minecraft or foreground input. */
class LayeredDisplayRenderTest {
    interface Desktop extends StdCallLibrary {
        Desktop I=Native.load("user32",Desktop.class);
        WinDef.HWND CreateWindowExW(int ex,com.sun.jna.WString type,com.sun.jna.WString title,int style,int x,int y,int w,int h,
            WinDef.HWND parent,WinDef.HMENU menu,WinDef.HINSTANCE instance,com.sun.jna.Pointer param);
        int FillRect(WinDef.HDC dc,WinDef.RECT rect,WinDef.HBRUSH brush);
        boolean ClientToScreen(WinDef.HWND window,WinDef.POINT point);
        com.sun.jna.Pointer SetThreadDpiAwarenessContext(com.sun.jna.Pointer context);
    }
    interface Gdi extends StdCallLibrary {
        Gdi I=Native.load("gdi32",Gdi.class);
        WinDef.HBRUSH CreateSolidBrush(int color);
        int GetPixel(WinDef.HDC dc,int x,int y);
    }
    interface Dwm extends StdCallLibrary {Dwm I=Native.load("dwmapi",Dwm.class);int DwmFlush();}
    @Test void displayedAlphaSurvivesInteractivePassiveEmptyAndResizeTransitions() throws Exception {
        var dpi=Desktop.I.SetThreadDpiAwarenessContext(com.sun.jna.Pointer.createConstant(-4L));
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        var background=Desktop.I.CreateWindowExW(0x08000000,new com.sun.jna.WString("STATIC"),new com.sun.jna.WString("Muonium owned display fixture"),0x80000000,
            40,40,400,300,null,null,null,null);
        assertNotNull(background);var brush=Gdi.I.CreateSolidBrush(0x00FF0000);
        int samples=0;
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());
            com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
            var mode=new OverlayWindowMode(windows.display());
            var topmost=new WinDef.HWND(com.sun.jna.Pointer.createConstant(-1L));
            assertTrue(User32.INSTANCE.SetWindowPos(background,topmost,40,40,400,300,0x10|0x40));
            for(int frame=0;frame<40;frame++) {
                GLFW.glfwPollEvents();
                var backgroundDc=User32.INSTANCE.GetDC(background);
                var rect=new WinDef.RECT();rect.right=400;rect.bottom=300;
                Desktop.I.FillRect(backgroundDc,rect,brush);User32.INSTANCE.ReleaseDC(background,backgroundDc);
                int w=frame<20?256:320,h=frame<20?192:240;
                presenter.resize(w,h);client.getWindow().size(w,h,1);SkijaHost.renderTarget(presenter.framebuffer());
                boolean empty=frame%5==4,interactive=frame%2==0;mode.update(interactive);
                SkijaHost.beginFrame(0);
                try(var paint=new Paint().setColor(0x80FF0000)) {
                    if(!empty){
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(4,8,32,32),paint);
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(4,h-40,32,32),paint);
                    }
                }finally{SkijaHost.endFrame();}
                if(interactive)presenter.present(40,40);else presenter.presentPassive(40,40);
                presenter.visible(true);
                if(!interactive&&!empty)assertEquals(2,presenter.regionCount());
                assertTrue(User32.INSTANCE.SetWindowPos(windows.handle(),topmost,0,0,0,0,0x1|0x2|0x10|0x40));
                GLFW.glfwPollEvents();
                backgroundDc=User32.INSTANCE.GetDC(background);
                Desktop.I.FillRect(backgroundDc,rect,brush);User32.INSTANCE.ReleaseDC(background,backgroundDc);
                var point=new WinDef.POINT();point.x=12;point.y=16;Desktop.I.ClientToScreen(background,point);
                int red=0,green=0,blue=0;long deadline=System.nanoTime()+500_000_000L;
                do {
                    GLFW.glfwPollEvents();assertEquals(0,Dwm.I.DwmFlush());
                    var dc=User32.INSTANCE.GetDC(null);int color=Gdi.I.GetPixel(dc,point.x,point.y);User32.INSTANCE.ReleaseDC(null,dc);
                    red=color&255;green=(color>>>8)&255;blue=(color>>>16)&255;
                    if(Math.abs(red-(empty?0:128))<=3&&green<=2&&Math.abs(blue-(empty?255:127))<=3)break;
                    Thread.sleep(5);
                }while(System.nanoTime()<deadline);
                System.out.println("ULW_DWM_SAMPLE frame="+frame+" interactive="+interactive+" empty="+empty+" point="+point.x+","+point.y+" rgb="+red+","+green+","+blue);
                if(empty){assertEquals(0,red,2);assertEquals(255,blue,2);}
                else {assertEquals(128,red,3);assertEquals(127,blue,3);}
                assertEquals(0,green,2);samples++;
                // Probe the owned auxiliary band's actual DWM output too.
                point.x=12;point.y=h-24;Desktop.I.ClientToScreen(background,point);
                int color=0;deadline=System.nanoTime()+500_000_000L;
                do {
                    assertEquals(0,Dwm.I.DwmFlush());var screen=User32.INSTANCE.GetDC(null);
                    color=Gdi.I.GetPixel(screen,point.x,point.y);User32.INSTANCE.ReleaseDC(null,screen);
                    if(Math.abs((color&255)-(empty?0:128))<=3)break;
                    Thread.sleep(5);
                }while(System.nanoTime()<deadline);
                assertEquals(empty?0:128,color&255,3);assertEquals(empty?255:127,(color>>>16)&255,3);samples++;
                presenter.visible(false); // Hide/show must not leave passive bands visible.
                assertEquals(0,Dwm.I.DwmFlush());
                presenter.visible(true);
            }
            System.out.println("ULW_DWM_DISPLAY samples="+samples+" transitions=interactive,passive,empty,resize");
          } finally {SkijaHost.shutdown();}
        } finally {
            GDI32.INSTANCE.DeleteObject(brush);User32.INSTANCE.DestroyWindow(background);
            SessionMedia.close();GLFW.glfwTerminate();
            Desktop.I.SetThreadDpiAwarenessContext(dpi);
        }
    }
}
