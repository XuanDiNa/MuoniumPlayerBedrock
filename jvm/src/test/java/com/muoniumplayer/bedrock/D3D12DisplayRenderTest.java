package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

/** Real D3D12 hardware/WARP flip swapchains + production ULW, final DWM pixels. */
class D3D12DisplayRenderTest {
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
    @Test void hardwareAndWarpD3D12AlphaTransitions() throws Exception {
        exerciseD3D12(false);exerciseD3D12(true);
    }
    private void exerciseD3D12(boolean warp) throws Exception {
        var dpi=Desktop.I.SetThreadDpiAwarenessContext(com.sun.jna.Pointer.createConstant(-4L));
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        var command=new java.util.ArrayList<String>();
        command.add(Path.of("../../../build/windows/x64/release/D3D12DisplayFixture.exe").toAbsolutePath().normalize().toString());
        if(warp)command.add("--warp");
        var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var response=new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
        var input=new java.io.PrintWriter(process.getOutputStream(),true);
        String ready=response.readLine();assertNotNull(ready);assertTrue(ready.startsWith("READY "),ready);
        var background=new WinDef.HWND(com.sun.jna.Pointer.createConstant(Long.parseUnsignedLong(ready.substring(6))));
        int samples=0;
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());
            if(!com.muoniumplayer.MuoniumPlayerClient.getInstance().isReady())
                com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
            var mode=new OverlayWindowMode(windows.display());
            var topmost=new WinDef.HWND(com.sun.jna.Pointer.createConstant(-1L));
            assertTrue(User32.INSTANCE.SetWindowPos(background,topmost,40,40,400,300,0x10|0x40));
            for(int frame=0;frame<40;frame++) {
                GLFW.glfwPollEvents();
                int w=frame<20?256:320,h=frame<20?192:240;
                input.println(frame==20?"RESIZE 440 340":"DRAW");assertEquals("OK",response.readLine());
                presenter.resize(w,h);client.getWindow().size(w,h,1);SkijaHost.renderTarget(presenter.framebuffer());
                boolean empty=frame%5==4,interactive=frame%2==0;mode.update(interactive);
                SkijaHost.beginFrame(0);
                try(var paint=new Paint().setColor(0x80FF0000)) {
                    if(!empty){
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(4,8,32,32),paint);
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(4,h-40,32,32),paint);
                    }
                }finally{SkijaHost.endFrame();}
                // Keep the owned background directly below our bands. Other
                // desktop windows can be raised during font/module startup;
                // sampling their pixels is not a composition failure.
                input.println("RAISE");assertEquals("OK",response.readLine());
                assertNotEquals(0,User32.INSTANCE.GetWindowLong(background,-20)&8,"owned background must actually be topmost before desktop sampling");
                if(interactive)presenter.present(40,40);else presenter.presentPassive(40,40);
                presenter.visible(true);
                if(!interactive&&!empty)assertEquals(2,presenter.regionCount());
                assertTrue(User32.INSTANCE.SetWindowPos(windows.handle(),topmost,0,0,0,0,0x1|0x2|0x10|0x40));
                GLFW.glfwPollEvents();
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
                if(Math.abs(red-(empty?0:128))>3||green>2||Math.abs(blue-(empty?255:127))>3){
                    System.out.println("DWM_DIAGNOSTIC raster="+Integer.toHexString(presenter.pixel(12,h-16))
                        +" background="+background+" overlay="+windows.handle());
                    User32.INSTANCE.EnumWindows((hwnd,data)->{
                        var rect=new WinDef.RECT();User32.INSTANCE.GetWindowRect(hwnd,rect);
                        if(User32.INSTANCE.IsWindowVisible(hwnd)&&rect.left<=point.x&&rect.right>point.x&&rect.top<=point.y&&rect.bottom>point.y){
                            char[] name=new char[128];User32.INSTANCE.GetClassName(hwnd,name,name.length);
                            System.out.println("DWM_WINDOW hwnd="+hwnd+" class="+Native.toString(name)+" exstyle="+Integer.toHexString(User32.INSTANCE.GetWindowLong(hwnd,-20))+" rect="+rect.toRectangle());
                        }return true;
                    },null);
                }
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
            System.out.println("D3D12_ULW_DWM_DISPLAY warp="+warp+" samples="+samples+" transitions=interactive,passive,empty,resize");
          } finally {SkijaHost.shutdown();}
        } finally {
            input.println("STOP");input.close();
            try {assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());}
            finally {process.destroyForcibly();}
            SessionMedia.close();GLFW.glfwTerminate();
            Desktop.I.SetThreadDpiAwarenessContext(dpi);
        }
    }
}
