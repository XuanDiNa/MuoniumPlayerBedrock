package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import java.util.function.Supplier;

/** Interactive page lifetime, separate from the overlay window's visibility. */
final class OverlaySession {
    private final BedrockClient client;
    private final Supplier<GuiScreen> playerScreen;
    private boolean open;

    OverlaySession(BedrockClient client, Supplier<GuiScreen> playerScreen) {
        this.client = client;
        this.playerScreen = playerScreen;
    }
    boolean isOpen() {
        return open;
    }
    void open(GuiScreen screen){client.gui.setScreen(screen);open=true;}
    boolean finishScreenClose() {
        if (!open || client.gui.screen() != null) return false;
        open = false;
        return true;
    }
    void toggle() {
        if (isOpen()) close();
        else {
            client.gui.setScreen(playerScreen.get());
            open = true;
        }
    }
    void close() {
        open = false;
        client.gui.setScreen(null);
    }
    boolean visible(boolean enabled, boolean minimized, boolean gameFocused, boolean playerFocused) {
        if (!enabled) close();
        finishScreenClose();
        // Alt-Tab/minimize suspends presentation, not the original page lifetime.
        return OverlayPolicy.visible(enabled, minimized, gameFocused, playerFocused);
    }
}
