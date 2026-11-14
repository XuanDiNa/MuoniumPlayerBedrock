package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Own disposable hidden GL and NO_API layered windows. Never launches or sends input to Minecraft. */
class OverlayCloseRenderTest {
    private static int presentedAlpha;
    @Test void escapeReleasesOriginalPlayerAndClearsLayeredBitmapAcrossFocusCycles() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");
        assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
            long window=windows.display();
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(960,600,1);
            presenter.resize(960,600);SkijaHost.renderTarget(presenter.framebuffer());
            var player=MuoniumPlayerClient.getInstance();player.init();FoliaModelNotice.markShownThisSession();
            var session=new OverlaySession(client,NCMScreen::getInstance);
            var mode=new OverlayWindowMode(window);
            mode.update(true);
            session.open(new GuiTransparentFixture());
            frame(session,presenter);
            assertEquals(1,presentedAlpha,"Transparent interactive pages need the minimal input shield, without the opaque player backdrop");
            session.close();
            mode.update(false);
            for(int cycle=0;cycle<2;cycle++) {
                session.toggle();
                assertTrue(mode.update(true));
                for(int frame=0;frame<65;frame++) {
                    frame(session,presenter);Thread.sleep(16);
                }
                assertNotNull(client.gui.screen());
                client.gui.screen().keyEvent('\0',Keyboard.KEY_ESCAPE);
                for(int frame=0;frame<80&&session.isOpen();frame++) {
                    frame(session,presenter);Thread.sleep(16);
                }
                assertFalse(session.isOpen(),"Original Escape animation must eventually end interaction");
                assertNull(client.gui.screen());
                assertEquals(0,presentedAlpha,"The exact frame closing the original player must not submit its opaque backdrop before returning focus");
                assertTrue(mode.update(false));
                assertFalse(mode.state().attributes());
                StringBuilder alphaTrace=new StringBuilder();boolean cleared=true;
                for(int frame=0;frame<6;frame++) {
                    if(frame==2){assertFalse(session.visible(true,false,false,false));}
                    assertTrue(session.visible(true,false,true,false));
                    frame(session,presenter);
                    alphaTrace.append(" frame=").append(frame).append(" presented=").append(presentedAlpha);
                    cleared &= presentedAlpha==0;
                    Path output=Path.of("../stage-captures/close-"+cycle+"-"+frame+".png");
                    Files.createDirectories(output.getParent());SkijaHost.savePng(output,true);
                    var image=ImageIO.read(output.toFile());
                    assertEquals(0,image.getRGB(0,0)>>>24);
                }
                System.out.println("CLOSE_ALPHA cycle="+cycle+alphaTrace);
                assertTrue(cleared,"Frames submitted after close must be transparent: "+alphaTrace);
            }
            // The reported folder dialog belongs to this original player page.
            // Closing it must invalidate a late selection before any import.
            session.toggle();
            var entered=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var imported=new java.util.concurrent.atomic.AtomicBoolean();
            try {
                assertTrue(HostFileDialogs.request(".",file->imported.set(true),(initial,owner)->{
                    entered.countDown();release.await(3,java.util.concurrent.TimeUnit.SECONDS);
                    return new java.io.File(".");
                }));
                assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                session.close();
                assertFalse(HostFileDialogs.active(),"Closing the original player must release its owned picker");
            }finally{
                release.countDown();HostFileDialogs.cancel();
                java.awt.EventQueue.invokeAndWait(()->{});client.drainTasks();
            }
            assertFalse(imported.get(),"A selection after closing the player cannot import a folder");
            SkijaHost.shutdown();
        } finally {
            GLFW.glfwTerminate();SessionMedia.close();
        }
    }
    private static class GuiTransparentFixture extends com.muoniumplayer.compat.mc.GuiScreen {
        public boolean transparentHost(){return true;}
    }
    private static void frame(OverlaySession session,LayeredPresenter presenter) {
        session.finishScreenClose();
        BedrockOverlay.drawFrame(session,()->{});
        presenter.present(20,20);
        presentedAlpha=presenter.pixel(0,0)>>>24;
        session.finishScreenClose();
    }
}
