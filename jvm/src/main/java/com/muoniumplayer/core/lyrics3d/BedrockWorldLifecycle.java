package com.muoniumplayer.core.lyrics3d;

/** Small host boundary; the original reset clears active/fading anchors and caches. */
public final class BedrockWorldLifecycle {
    private BedrockWorldLifecycle() {}
    public static void reset() {
        WorldLyricsState.get().resetForTest();
        WorldLyricsRenderer.clearBrokenLatch();
    }
}
