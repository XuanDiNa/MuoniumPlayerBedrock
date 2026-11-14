package com.muoniumplayer.bedrock;

/** Visibility only. Losing focus must not pause or invent a playback clock. */
public final class OverlayPolicy {
    private OverlayPolicy() {}
    public static boolean visible(boolean enabled, boolean minimized, boolean gameFocused, boolean playerFocused) {
        return enabled && !minimized && (gameFocused || playerFocused);
    }
}
