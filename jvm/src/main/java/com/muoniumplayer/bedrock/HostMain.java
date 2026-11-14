package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.ExternalMediaService;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.render.skija.SkijaHost;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import java.nio.file.Path;
import java.util.Arrays;

/** Owned JVM/Skija UI runtime. This preview entry does not launch Minecraft. */
public final class HostMain {
    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--bedrock-host")) {
            int result = 0;
            try { BedrockOverlay.run(args); }
            catch (Throwable failure) { failure.printStackTrace(System.err); result = 1; }
            finally { SessionMedia.close(); }
            // This is the owned companion JVM, never Minecraft's process. Some
            // original optional executors are non-daemon; do not orphan them.
            System.exit(result); return;
        }
        boolean smoke = Arrays.asList(args).contains("--render-smoke");
        boolean standalone = Arrays.asList(args).contains("--standalone");
        if (!smoke && !standalone) throw new IllegalArgumentException("Host mode required");
        int frameLimit = Integer.parseInt(option(args, "--frames", smoke ? "120" : "0"));
        if (frameLimit < 0 || frameLimit > 36000) throw new IllegalArgumentException("Frame limit");
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        long window = 0;
        try {
            if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, smoke ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            window = GLFW.glfwCreateWindow(1280, 720, "MuoniumPlayer Bedrock — upstream UI", 0, 0);
            if (window == 0) throw new IllegalStateException("GLFW window creation failed");
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities(); GLFW.glfwSwapInterval(smoke ? 0 : 1);
            var client = BedrockClient.getInstance(); client.bindWindow(window);
            var player = MuoniumPlayerClient.getInstance(); player.init();
            client.gui.setScreen(NCMScreen.getInstance());
            GLFW.glfwSetCursorPosCallback(window, (w, x, y) -> Mouse.position(x, y));
            GLFW.glfwSetScrollCallback(window, (w, x, y) -> Mouse.pushScrollNotches(y));
            GLFW.glfwSetKeyCallback(window, (w, key, scan, action, mods) -> {
                if (action != GLFW.GLFW_RELEASE && client.gui.screen() != null)
                    dispatch(() -> client.gui.screen().keyEvent('\0', Keyboard.fromGlfw(key)));
            });
            GLFW.glfwSetCharCallback(window, (w, codepoint) -> {
                if (client.gui.screen() != null) for (char ch : Character.toChars(codepoint))
                    dispatch(() -> client.gui.screen().keyEvent(ch, 0));
            });
            GLFW.glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
                var screen = client.gui.screen(); if (screen == null) return;
                int x = (int) (Mouse.getX() / client.getWindow().getGuiScale());
                int y = (int) ((client.getWindow().getHeight() - Mouse.getY() - 1) / client.getWindow().getGuiScale());
                if (action == GLFW.GLFW_PRESS) dispatch(() -> screen.clickEvent(x, y, button));
                else screen.releaseEvent(x, y, button);
            });
            int frames = 0;
            int[] pw = new int[1], ph = new int[1];
            long lastTick = 0;
            while (!GLFW.glfwWindowShouldClose(window) && (frameLimit == 0 || frames < frameLimit)) {
                GLFW.glfwPollEvents(); client.drainTasks();
                GLFW.glfwGetFramebufferSize(window, pw, ph);
                if (pw[0] < 1 || ph[0] < 1) { Thread.sleep(20); continue; }
                var size = client.getWindow();
                if (pw[0] != size.getWidth() || ph[0] != size.getHeight()) {
                    size.size(pw[0], ph[0], 1);
                    if (client.gui.screen() != null) client.gui.screen().resize(size.getGuiScaledWidth(), size.getGuiScaledHeight());
                }
                long now = System.nanoTime();
                if (now - lastTick >= 50_000_000) {
                    player.tick(); lastTick = now;
                    if (client.gui.screen() != null) client.gui.screen().updateScreen();
                }
                SkijaHost.beginFrame();
                try {
                    if (client.gui.screen() != null) client.gui.screen().drawHostFrame(0f);
                } finally { SkijaHost.endFrame(); }
                frames++;
                if (frameLimit > 0 && frames == frameLimit) {
                    String capture = option(args, "--capture", "");
                    if (!capture.isEmpty()) SkijaHost.savePng(Path.of(capture));
                }
                GLFW.glfwSwapBuffers(window);
            }
            System.out.println("UPSTREAM_UI renderedFrames=" + frames + " screen=" + NCMScreen.class.getName());
        } finally {
            ExternalMediaService.stop(); CloudMusic.onStop(); SkijaHost.shutdown();
            if (window != 0) { org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window); GLFW.glfwDestroyWindow(window); }
            GLFW.glfwTerminate(); errors.close();
            SessionMedia.close();
        }
    }
    private static String option(String[] args, String key, String fallback) {
        for (int i = 0; i < args.length; i++) if (key.equals(args[i])) {
            if (i + 1 == args.length) throw new IllegalArgumentException("Missing " + key);
            return args[i + 1];
        }
        return fallback;
    }
    private interface Action { void run() throws Exception; }
    private static void dispatch(Action action) { try { action.run(); } catch (Exception e) { throw new RuntimeException(e); } }
}
