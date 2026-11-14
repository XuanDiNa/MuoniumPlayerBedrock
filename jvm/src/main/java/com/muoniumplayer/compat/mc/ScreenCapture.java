package com.muoniumplayer.compat.mc;
import com.muoniumplayer.bedrock.BedrockClient;
import com.muoniumplayer.render.skija.SkijaHost;
import java.io.File;
import java.nio.file.Files;
public final class ScreenCapture {
    public static final class Frame {
        public final File png;
        public final int width, height;
        public Frame(File png, int w, int h) { this.png = png; width = w; height = h; }
    }
    public interface Callback { void onFrame(Frame frame); }
    public static void capture(File file, Callback callback) {
        BedrockClient.getInstance().execute(() -> {
            Frame result = null;
            try {
                SkijaHost.savePng(file.toPath());
                var win = BedrockClient.getInstance().getWindow(); result = new Frame(file, win.getWidth(), win.getHeight());
            } catch (Exception e) { System.err.println("[Muonium/Capture] " + e); }
            callback.onFrame(result);
        });
    }
    public static void release(File file) { try { Files.deleteIfExists(file.toPath()); } catch (Exception e) { System.err.println("[Muonium/Capture] " + e); } }
}
