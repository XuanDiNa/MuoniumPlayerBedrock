package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.download.DownloadCatalog;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real side-rail hit routing, modal rendering and Esc without starting a download. */
class Stable260RenderTest {
    @Test void downloadButtonOpensAndReturnsWithoutStartingBackgroundActions() throws Exception {
        assertTrue(GLFW.glfwInit());
        long window = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, 0);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            window = GLFW.glfwCreateWindow(1280, 720, "Owned stable download manager fixture", 0, 0);
            assertNotEquals(0, window);
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            var client = BedrockClient.getInstance();
            client.bindWindow(window);
            client.getWindow().size(1280, 720, 1);
            MuoniumPlayerClient.getInstance().init();
            com.muoniumplayer.core.ncm.music.FoliaModelNotice.markShownThisSession();
            var install = BedrockOverlay.class.getDeclaredMethod("installInput", long.class);
            install.setAccessible(true);
            install.invoke(null, window);
            var screen = NCMScreen.getInstance();
            client.gui.setScreen(screen);
            QueueScreenRenderTest.settle(screen);
            int active = DownloadCatalog.activeTasks(), waiting = DownloadCatalog.waitingTasks();
            Object rail = QueueScreenRenderTest.field(screen, "playlistsPanel");
            var button = (AbstractWidget<?>) QueueScreenRenderTest.field(rail, "downloadButton");
            var tools = (AbstractWidget<?>) QueueScreenRenderTest.field(rail, "announcementButton");
            assertNotNull(button);
            assertFalse(tools.isHidden(), "Adding download management must retain the owned player tools entry");
            assertFalse(button.isHidden());
            assertTrue(button.getWidth() > 0 && button.getX() >= 0);
            assertTrue(button.getX() >= tools.getX() + tools.getWidth(), "The new entry cannot overlap player tools");
            var hwnd = new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
            QueueScreenRenderTest.click(screen, hwnd, button, 1);
            QueueScreenRenderTest.settle(screen);
            Path capture = Path.of("../stage-captures/stable260-downloads.png");
            Files.createDirectories(capture.getParent());
            SkijaHost.savePng(capture, true);
            assertNotNull(screen.downloadManagerOverlay, "The sixth side-rail button must open the real modal");
            assertEquals(active, DownloadCatalog.activeTasks());
            assertEquals(waiting, DownloadCatalog.waitingTasks());
            screen.keyEvent('\0', Keyboard.KEY_ESCAPE);
            QueueScreenRenderTest.settle(screen);
            assertNull(screen.downloadManagerOverlay);
            assertSame(screen, client.gui.screen(), "Esc must close this modal before the player");
            assertEquals(active, DownloadCatalog.activeTasks());
            assertEquals(waiting, DownloadCatalog.waitingTasks());
        } finally {
            BedrockClient.getInstance().gui.setScreen(null);
            SkijaHost.shutdown();
            if (window != 0) {
                Callbacks.glfwFreeCallbacks(window);
                GLFW.glfwDestroyWindow(window);
            }
            GLFW.glfwTerminate();
            SessionMedia.close();
        }
    }
}
