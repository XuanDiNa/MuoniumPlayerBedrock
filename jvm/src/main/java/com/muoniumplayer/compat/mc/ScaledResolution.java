package com.muoniumplayer.compat.mc;
import com.muoniumplayer.bedrock.BedrockClient;
public final class ScaledResolution {
    private final BedrockClient.Window window = BedrockClient.getInstance().getWindow();
    public ScaledResolution() {}
    public ScaledResolution(Object ignored) {}
    public int getScaleFactor() { return (int) window.getGuiScale(); }
    public int getScaledWidth() { return window.getGuiScaledWidth(); }
    public int getScaledHeight() { return window.getGuiScaledHeight(); }
}
