package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.screens.ncm.ModelInstallNoticeOverlay;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;

class InstallNoticeLifecycleTest {
    @Test void reopeningOriginalPlayerRetainsAnUnansweredInstallationNotice() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");
        assertTrue(GLFW.glfwInit()); long window=0;
        try {
            GLFW.glfwDefaultWindowHints(); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Disposable notice fixture",0,0);
            assertNotEquals(0,window); GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(1280,720,1);
            MuoniumPlayerClient.getInstance().init();
            var screen=NCMScreen.getInstance();var session=new OverlaySession(client,()->screen);
            session.toggle();
            var notice=new ModelInstallNoticeOverlay();notice.onInit();
            screen.modelInstallNoticeOverlay=notice;FoliaModelNotice.markShownThisSession();
            assertFalse(FoliaModelNotice.shouldShow(),"Once-shown gate reproduces the missed-notice condition");
            session.close();
            session.toggle();
            assertSame(notice,screen.modelInstallNoticeOverlay,
                    "Closing/reopening must retain an unanswered original install notice despite the once-shown gate");
            client.getWindow().size(960,600,1);screen.resize(960,600);
            assertSame(notice,screen.modelInstallNoticeOverlay,"Resize must retain the pending installer");
            for(int i=0;i<12;i++) {
                SkijaHost.beginFrame();try{screen.drawHostFrame(0);}finally{SkijaHost.endFrame();}
                GLFW.glfwSwapBuffers(window);Thread.sleep(16);
            }
            assertSame(notice,screen.modelInstallNoticeOverlay);
            notice.handleEscape();
            for(int i=0;i<40 && screen.modelInstallNoticeOverlay!=null;i++) {
                SkijaHost.beginFrame();try{screen.drawHostFrame(0);}finally{SkijaHost.endFrame();}
                GLFW.glfwSwapBuffers(window);Thread.sleep(16);
            }
            assertNull(screen.modelInstallNoticeOverlay,"An explicit Later/Escape choice must still dismiss the notice");
            session.close();session.toggle();
            assertNull(screen.modelInstallNoticeOverlay,"An explicitly deferred notice must not nag on every reopen");
            session.close();
        } finally {
            SkijaHost.shutdown();
            if(window!=0){org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);GLFW.glfwDestroyWindow(window);}
            GLFW.glfwTerminate();SessionMedia.close();
        }
    }
}
