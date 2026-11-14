package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostSettingsTest {
    @Test void hudAndIslandCanBeDisabledIndependentlyOfPlaybackAndLyrics() {
        var defaults=HostSettings.parse("{}");
        assertTrue(defaults.hudEnabled()); assertTrue(defaults.dynamicIslandEnabled());
        var value=HostSettings.parse("{\"hudEnabled\":false,\"dynamicIslandEnabled\":false}");
        assertFalse(value.hudEnabled()); assertFalse(value.dynamicIslandEnabled());
        assertTrue(value.enabled()); assertTrue(value.lyricsEnabled());
        assertThrows(IllegalArgumentException.class,()->HostSettings.parse("{\"hudEnabled\":1}"));
    }
    @Test void missingSettingsKeepKeysUnboundAndDoNotStartPlayback() {
        var value = HostSettings.parse("{}");
        assertTrue(value.enabled());
        assertEquals(70, value.volume());
        assertEquals(0, value.stageStyle());
    }
    @Test void invalidRangesAndDuplicateKeysAreRefusedAtomically() {
        for (String json : new String[]{"{\"volume\":101}", "{\"enabled\":true,\"enabled\":false}",
                "{\"volume\":\"70\"}", "{\"stageScalePercent\":0}", "{\"lyricsMode\":3}", "[]"})
            assertThrows(IllegalArgumentException.class, () -> HostSettings.parse(json), json);
    }
    @Test void stageFieldsAreIndependentOfTheUpstreamWorldCamera() {
        var value = HostSettings.parse("{\"stageStyle\":2,\"stageScalePercent\":150,\"stageSpreadDeg\":25,\"lyricsEnabled\":false}");
        assertEquals(2, value.stageStyle()); assertEquals(150, value.stageScalePercent());
        assertEquals(25, value.stageSpreadDeg()); assertFalse(value.lyricsEnabled());
    }
}
