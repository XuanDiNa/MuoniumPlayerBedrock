package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import java.util.function.Supplier;

/** Interactive page lifetime, separate from the overlay window's visibility. */
final class OverlaySession {
    private final BedrockClient client;
    private final Supplier<GuiScreen> playerScreen;
    private boolean open;
    private boolean inputFocused;

    OverlaySession(BedrockClient client, Supplier<GuiScreen> playerScreen) {
        this.client = client;
        this.playerScreen = playerScreen;
    }
    boolean isOpen() {
        return open;
    }
    void open(GuiScreen screen){client.gui.setScreen(screen);open=true;}
    void show(){if(!open)open(playerScreen.get());}
    boolean finishScreenClose() {
        if (!open || client.gui.screen() != null) return false;
        suspendInput();
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
        suspendInput();
        open = false;
        client.gui.setScreen(null);
    }
    boolean visible(boolean enabled, boolean minimized, boolean gameFocused, boolean playerFocused) {
        boolean focused=enabled&&!minimized&&playerFocused;
        if(inputFocused&&!focused)suspendInput();
        inputFocused=focused;
        if (!enabled) close();
        finishScreenClose();
        // Alt-Tab/minimize suspends presentation, not the original page lifetime.
        return OverlayPolicy.visible(enabled, minimized, gameFocused, playerFocused);
    }
    private void suspendInput(){
        HostInputState.suspend();
        if(client.gui.screen()!=null)client.gui.screen().suspendHostInput();
        inputFocused=false;
    }
}
