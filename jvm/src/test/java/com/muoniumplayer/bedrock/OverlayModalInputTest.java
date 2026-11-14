package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.win32.StdCallLibrary;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real desktop hit testing of our own disposable windows. No game, global
 * input injection, cursor movement or foreground-window changes are used. */
class OverlayModalInputTest {
    interface HitProbe extends StdCallLibrary {
        HitProbe INSTANCE=Native.load("user32",HitProbe.class);
        // Windows x64 passes a POINT by value in one 64-bit register.
        WinDef.HWND WindowFromPoint(long point);
    }
    @Test void transparentMiniAndEditorCaptureBackgroundThenReleaseItAfterEscape() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");
        assertTrue(GLFW.glfwInit());long backing=0;
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API,GLFW.GLFW_NO_API);
            backing=GLFW.glfwCreateWindow(960,600,"Disposable background hit-test fixture",0,0);
            assertNotEquals(0,backing);
            var background=new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(backing)));
            var topmost=new WinDef.HWND(Pointer.createConstant(-1L));
            assertTrue(User32.INSTANCE.SetWindowPos(background,topmost,30,30,960,600,0x0010|0x0040));
            assertEquals(background,hit(984,624),"Fixture point must belong to our own background window");
            User32.INSTANCE.SetWindowLongPtr(windows.handle(),-8,background.getPointer());
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(960,600,1);
            MuoniumPlayerClient.getInstance().init();
            var input=BedrockOverlay.class.getDeclaredMethod("installInput",long.class);input.setAccessible(true);input.invoke(null,windows.display());
            var session=new OverlaySession(client,NCMScreen::getInstance);
            var mode=new OverlayWindowMode(windows.display());
            presenter.resize(960,600);SkijaHost.renderTarget(presenter.framebuffer());
            for(int cycle=0;cycle<2;cycle++)for(boolean editor:new boolean[]{false,true}) {
                session.open(editor?new HostHudEditor():new HostMiniPlayer());mode.update(true);
                BedrockOverlay.drawFrame(session,()->{});presenter.present(30,30);
                assertTrue(User32.INSTANCE.SetWindowPos(windows.handle(),topmost,30,30,960,600,0x0010|0x0040));
                GLFW.glfwPollEvents();
                var target=hit(984,624);
                System.out.println("MODAL_HIT editor="+editor+" cycle="+cycle+" alpha="+(presenter.pixel(954,594)>>>24)+" captured="+windows.handle().equals(target));
                assertEquals(windows.handle(),target,"Transparent interactive background must not pass clicks/focus to the game");
                assertTrue((presenter.pixel(954,594)>>>24)<=2,"Input shield must remain visually transparent");
                assertFalse(mode.state().attributes(),"Must retain per-pixel ULW, without constant-alpha APIs");
                // Deliver Escape through the real HWND input callback, not keyEvent.
                User32.INSTANCE.PostMessage(target,0x100,new WinDef.WPARAM(27),new WinDef.LPARAM(1));
                User32.INSTANCE.PostMessage(target,0x101,new WinDef.WPARAM(27),new WinDef.LPARAM(0xC0000001L));
                GLFW.glfwPollEvents();session.finishScreenClose();
                for(int frame=0;frame<90&&session.isOpen();frame++) {
                    BedrockOverlay.drawFrame(session,()->{});presenter.present(30,30);session.finishScreenClose();Thread.sleep(16);
                }
                assertFalse(session.isOpen(),"One Escape must close without reopening the page");
                BedrockOverlay.drawFrame(session,()->{});presenter.present(30,30);mode.update(false);
                assertEquals(0,presenter.pixel(954,594),"Close must remove the input shield");
                assertEquals(background,hit(984,624),"Closed page must release its background");
                BedrockOverlay.drawFrame(session,()->{
                    try(var paint=new Paint().setColor(0xFFFFFFFF)) {
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(950,590,10,10),paint);
                    }
                });presenter.presentPassive(30,30);
                assertEquals(background,hit(984,624),"Visible passive HUD must remain click-through");
                assertFalse(session.visible(true,false,false,false),"Alt-Tab must hide presentation");
            }
            session.open(new HostMiniPlayer());mode.update(true);
            BedrockOverlay.drawFrame(session,()->{});presenter.present(30,30);
            var outside=hit(984,624);
            assertEquals(windows.handle(),outside);
            User32.INSTANCE.PostMessage(outside,0x200,new WinDef.WPARAM(0),new WinDef.LPARAM(954|(594<<16)));
            User32.INSTANCE.PostMessage(outside,0x201,new WinDef.WPARAM(1),new WinDef.LPARAM(954|(594<<16)));
            User32.INSTANCE.PostMessage(outside,0x202,new WinDef.WPARAM(0),new WinDef.LPARAM(954|(594<<16)));
            GLFW.glfwPollEvents();session.finishScreenClose();
            assertFalse(session.isOpen(),"Outside click closes the mini, without reaching the background");
            // Disabling an interactive page must also drop its input shield.
            session.open(new HostHudEditor());mode.update(true);
            assertFalse(session.visible(false,false,true,false));
            BedrockOverlay.drawFrame(session,()->{});presenter.present(30,30);mode.update(false);
            assertEquals(background,hit(984,624));assertEquals(0,presenter.pixel(954,594));
          } finally {SkijaHost.shutdown();}
        } finally {
            if(backing!=0)GLFW.glfwDestroyWindow(backing);
            GLFW.glfwTerminate();SessionMedia.close();
        }
    }
    private static WinDef.HWND hit(int x,int y){return HitProbe.INSTANCE.WindowFromPoint((x&0xFFFFFFFFL)|((long)y<<32));}
}
