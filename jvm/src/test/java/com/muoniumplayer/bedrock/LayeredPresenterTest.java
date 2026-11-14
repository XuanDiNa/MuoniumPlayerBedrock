package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Pointer;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import static org.junit.jupiter.api.Assertions.*;

/** Actual GPU -> premultiplied DIB -> Win32 ULW; owns both hidden windows. */
class LayeredPresenterTest {
    interface GdiProbe extends StdCallLibrary {
        GdiProbe INSTANCE=Native.load("gdi32",GdiProbe.class);
        int GetPixelFormat(WinDef.HDC dc);
    }
    interface ResourceProbe extends StdCallLibrary {
        ResourceProbe INSTANCE=Native.load("user32",ResourceProbe.class);
        int GetGuiResources(WinNT.HANDLE process,int flag);
    }
    @Test void splitWindowsKeepAlphaOrientationInputAndResourcesAcrossResize() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");
        assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create()) {
            assertEquals(GLFW.GLFW_FALSE,GLFW.glfwGetWindowAttrib(windows.render(),GLFW.GLFW_VISIBLE));
            assertEquals(GLFW.GLFW_NO_API,GLFW.glfwGetWindowAttrib(windows.display(),GLFW.GLFW_CLIENT_API));
            assertEquals(windows.render(),GLFW.glfwGetCurrentContext());
            var displayDc=User32.INSTANCE.GetDC(windows.handle());
            try {assertEquals(0,GdiProbe.INSTANCE.GetPixelFormat(displayDc),"Visible HWND must never acquire an OpenGL pixel format");}
            finally {User32.INSTANCE.ReleaseDC(windows.handle(),displayDc);}
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());
            com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
            var mode=new OverlayWindowMode(windows.display());mode.update(false);
            int fbo=0;
            int initialGdi=ResourceProbe.INSTANCE.GetGuiResources(Kernel32.INSTANCE.GetCurrentProcess(),0);
            try(var presenter=new LayeredPresenter(windows.handle())) {
              try {
                for(int[] size:new int[][]{{320,200},{640,480},{321,211},{320,200}}) {
                    int w=size[0],h=size[1];presenter.resize(w,h);fbo=presenter.framebuffer();
                    assertEquals(1,presenter.resourceSets());
                    assertTrue(GL30.glIsFramebuffer(fbo));
                    client.getWindow().size(w,h,1);SkijaHost.renderTarget(fbo);
                    SkijaHost.beginFrame(0);
                    try(var paint=new Paint()) {
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(0,0,40,40),paint.setColor(0x80804020));
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(0,h-40,40,40),paint.setColor(0xFF00FF00));
                    } finally {SkijaHost.endFrame();}
                    presenter.present(20,20);
                    assertEquals(0,presenter.pixel(w-2,2),"Clear areas must remain alpha zero");
                    int top=presenter.pixel(10,10);
                    assertEquals(128,top>>>24,1);assertEquals(64,(top>>>16)&255,1);
                    assertEquals(32,(top>>>8)&255,1);assertEquals(16,top&255,1);
                    assertEquals(0xFF00FF00,presenter.pixel(10,h-10),"DIB must not invert the image");
                    assertFalse(mode.state().attributes(),"ULW must not use constant-alpha attributes");
                    mode.update(true);presenter.present(20,20);
                    mode.update(false);presenter.present(20,20);
                    SkijaHost.beginFrame(0xEE101015);SkijaHost.endFrame();presenter.present(20,20);
                    int dark=presenter.pixel(w/2,h/2);
                    assertEquals(238,dark>>>24);assertTrue(((dark>>>16)&255)<30);
                    SkijaHost.beginFrame(0);SkijaHost.endFrame();presenter.present(20,20);
                    assertEquals(0,presenter.pixel(w/2,h/2),"Post-Escape clear must replace the dark bitmap");
                }
                assertThrows(IllegalArgumentException.class,()->presenter.resize(0,1));
                assertThrows(IllegalArgumentException.class,()->presenter.resize(16384,16384));
                assertEquals(1,presenter.resourceSets(),"Rejected sizes must not discard current buffers");
                testInput(windows.display(),windows.handle());
                presenter.resize(2560,1600);client.getWindow().size(2560,1600,2);
                SkijaHost.renderTarget(presenter.framebuffer());
                long total=0,max=0;
                for(int frame=0;frame<40;frame++) {
                    SkijaHost.beginFrame(0);SkijaHost.endFrame();presenter.present(20,20);
                    if(frame>=10){long ns=presenter.lastPresentNanos();total+=ns;max=Math.max(max,ns);}
                }
                System.out.println("ULW_LOCAL_BENCH size=2560x1600 samples=30 readbackAndSubmitAvgUs="+total/30/1000+" maxUs="+max/1000+
                    " renderer="+org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
                java.lang.reflect.Method passive;
                try {passive=LayeredPresenter.class.getDeclaredMethod("presentPassive",int.class,int.class);}
                catch(NoSuchMethodException e){fail("Passive world lyrics still read back the entire screen every frame");return;}
                total=0;max=0;
                for(int frame=0;frame<40;frame++) {
                    SkijaHost.beginFrame(0);
                    try(var paint=new Paint().setColor(0x80FFFFFF)) {
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(250+frame%3,170,180,50),paint);
                        // A single alpha=1 pixel at the far edge must survive max reduction.
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(1,1,.5f,.5f),paint.setColor(0x01010101));
                    }finally{SkijaHost.endFrame();}
                    passive.invoke(presenter,20,20);
                    assertEquals(128,presenter.pixel(550,350)>>>24,1);
                    assertEquals(1,presenter.pixel(2,2)>>>24);
                    if(frame>=10){long ns=presenter.lastPresentNanos();total+=ns;max=Math.max(max,ns);}
                }
                System.out.println("ULW_PASSIVE_BENCH size=2560x1600 samples=30 readbackAndSubmitAvgUs="+total/30/1000+" maxUs="+max/1000);
                SkijaHost.beginFrame(0);SkijaHost.endFrame();passive.invoke(presenter,20,20);
                assertEquals(0,presenter.pixel(550,350),"Empty passive frame must not leave the previous lyric image visible");
                SkijaHost.beginFrame(0xEE101015);SkijaHost.endFrame();presenter.present(20,20);
                assertEquals(238,presenter.pixel(1200,700)>>>24,"Opening player must restore the full interactive window");
                assertEquals(GLFW.GLFW_FALSE,GLFW.glfwGetWindowAttrib(windows.render(),GLFW.GLFW_VISIBLE));
                // Cinematic bars and lyric islands must not force a full-screen transfer.
                SkijaHost.beginFrame(0);
                try(var paint=new Paint().setColor(0xFF000000)) {
                    SkijaHost.canvas().drawRect(Rect.makeXYWH(0,0,1280,100),paint);
                    SkijaHost.canvas().drawRect(Rect.makeXYWH(0,700,1280,100),paint);
                    SkijaHost.canvas().drawRect(Rect.makeXYWH(500,380,180,40),paint.setColor(0x80FFFFFF));
                }finally{SkijaHost.endFrame();}
                presenter.presentPassive(20,20);
                assertTrue(presenter.readbackPixels()<presenter.fullPixels()*0.6,
                    "Cinematic sparse bars/lyrics still transfer the transparent full-screen gap");
                assertEquals(0xFF000000,presenter.pixel(20,20));
                assertEquals(0xFF000000,presenter.pixel(20,1500));
                assertEquals(128,presenter.pixel(1100,800)>>>24,1);
                assertEquals(0,presenter.pixel(1200,600));
                var cpu=java.lang.management.ManagementFactory.getThreadMXBean();
                for(boolean sparse:new boolean[]{false,true,true,false}) {
                    long work=0,bytes=0,cpuStart=cpu.getCurrentThreadCpuTime();
                    for(int i=0;i<100;i++) {
                        long started=System.nanoTime();SkijaHost.beginFrame(0);
                        try(var paint=new Paint().setColor(0xFF000000)) {
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(0,0,1280,100),paint);
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(0,700,1280,100),paint);
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(500+i%3,380,180,40),paint.setColor(0x80FFFFFF));
                        }finally{SkijaHost.endFrame();}
                        if(sparse)presenter.presentPassive(20,20);else presenter.present(20,20);
                        work+=System.nanoTime()-started;bytes+=presenter.readbackPixels()*4;
                        long remaining=8_333_333L-(System.nanoTime()-started);
                        if(remaining>0)java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                    }
                    System.out.println("SPARSE_ULW_BENCH sparse="+sparse+" size=2560x1600 targetHz=120 samples=100 workAvgUs="+work/100/1000+
                        " cpuMs="+(cpu.getCurrentThreadCpuTime()-cpuStart)/1_000_000+" bytesPerFrame="+bytes/100+" regions="+presenter.regionCount());
                }
                SkijaHost.shutdown();SkijaHost.renderTarget(0);
              } finally {SkijaHost.shutdown();}
            }
            assertFalse(GL30.glIsFramebuffer(fbo),"Presenter close must delete its FBO");
            assertTrue(ResourceProbe.INSTANCE.GetGuiResources(Kernel32.INSTANCE.GetCurrentProcess(),0)<=initialGdi+1,"Resize/close must release GDI surfaces");
        } finally {SkijaHost.shutdown();GLFW.glfwTerminate();SessionMedia.close();}
    }
    private static void testInput(long window,WinDef.HWND hwnd) throws Exception {
        var client=BedrockClient.getInstance();client.getWindow().size(320,200,1);
        class Screen extends com.muoniumplayer.compat.mc.GuiScreen {
            int click=-1,wheel,esc;
            protected void mouseClicked(int x,int y,int button){click=x;}
            protected void keyTyped(char ch,int key){if(key==1)esc++;}
        }
        var screen=new Screen();client.gui.setScreen(screen);
        var input=BedrockOverlay.class.getDeclaredMethod("installInput",long.class);input.setAccessible(true);input.invoke(null,window);
        User32.INSTANCE.PostMessage(hwnd,0x200,new WinDef.WPARAM(0),new WinDef.LPARAM(30|(40<<16)));
        User32.INSTANCE.PostMessage(hwnd,0x201,new WinDef.WPARAM(1),new WinDef.LPARAM(30|(40<<16)));
        User32.INSTANCE.PostMessage(hwnd,0x202,new WinDef.WPARAM(0),new WinDef.LPARAM(30|(40<<16)));
        User32.INSTANCE.PostMessage(hwnd,0x20A,new WinDef.WPARAM(120L<<16),new WinDef.LPARAM(0));
        User32.INSTANCE.PostMessage(hwnd,0x100,new WinDef.WPARAM(27),new WinDef.LPARAM(1));
        GLFW.glfwPollEvents();
        assertEquals(30,screen.click,"Visible NO_API window must receive player clicks");
        assertEquals(120,com.muoniumplayer.compat.lwjgl2.Mouse.getDWheel());assertEquals(1,screen.esc);
        client.gui.setScreen(null);
    }
}
