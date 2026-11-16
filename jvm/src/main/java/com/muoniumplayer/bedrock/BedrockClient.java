package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.lwjgl.glfw.GLFW;

/** Host-owned services. No Java Minecraft classes or fabricated world objects. */
public final class BedrockClient {
    private static final BedrockClient INSTANCE = new BedrockClient();
    public record Scene(boolean inWorld, boolean playerListVisible, boolean anyScreenOpen,
                        boolean hudHidden, boolean moving, int fps, int ping) {
        public static final Scene UNKNOWN = new Scene(false, false, true, true, false, -1, -1);
    }
    public final Gui gui = new Gui();
    public final KeyboardHandler keyboardHandler = new KeyboardHandler();
    private final Window window = new Window();
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private volatile Scene scene = Scene.UNKNOWN;
    private Thread renderThread;
    public static BedrockClient getInstance() { return INSTANCE; }
    public Window getWindow() { return window; }
    public Scene scene() { return scene; }
    public void setScene(Scene value) { scene = value == null ? Scene.UNKNOWN : value; }
    public boolean atMainMenu() { return !scene.inWorld() && !scene.hudHidden(); }
    public boolean isSameThread() { return Thread.currentThread() == renderThread; }
    public void execute(Runnable task) { pending.add(task); }
    public void bindWindow(long handle) { renderThread = Thread.currentThread(); window.handle = handle; HostInputState.bind(); }
    public void drainTasks() { TaskPump.drain(pending, 8, 1_000_000L, System::nanoTime); }
    public void printMessage(String message) { System.out.println("[Muonium/Bedrock] " + message); }
    public User getUser() { return new User("Player"); }
    public record User(String name) { public String getName() { return name; } }
    public final class Gui {
        private GuiScreen screen;
        public GuiScreen screen() { return screen; }
        public void setScreen(GuiScreen next) {
            if (!isSameThread()) { execute(() -> setScreen(next)); return; }
            if (screen == next) return;
            var previous=screen;
            if (screen != null) screen.onGuiClosed();
            screen = next;
            if (next != null) next.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
            HostBackNavigation.screenChanged(previous,next);
        }
    }
    public final class KeyboardHandler {
        public String getClipboard() { return GLFW.glfwGetClipboardString(window.handle); }
        public void setClipboard(String value) { GLFW.glfwSetClipboardString(window.handle, value); }
    }
    public static final class Window {
        private long handle;
        private int width = 1280, height = 720;
        private double scale = 1.0;
        public long handle() { return handle; }
        public long getWindow() { return handle; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public double getGuiScale() { return scale; }
        public int getGuiScaledWidth() { return (int) Math.ceil(width / scale); }
        public int getGuiScaledHeight() { return (int) Math.ceil(height / scale); }
        public void size(int w, int h, double s) {
            if (w < 1 || h < 1 || !Double.isFinite(s) || s < 1) throw new IllegalArgumentException("window size");
            width = w; height = h; scale = s;
        }
    }
}
