package com.muoniumplayer.core.ncm.music;

import java.util.List;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.ConfigPaths;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryLyricPolicyTest {
    @Test void lyricsRoundTripWithoutPersistentFilesAndCanBeInvalidated() {
        var lines = List.of(new LyricLine(1000, "Memory only"));
        LyricCacheStore.store("memory-policy-fixture", lines);
        assertEquals("Memory only", LyricCacheStore.load("memory-policy-fixture").getFirst().lyric);
        assertFalse(ConfigPaths.LYRIC_CACHE.exists());
        LyricCacheStore.invalidate("memory-policy-fixture");
        assertTrue(LyricCacheStore.load("memory-policy-fixture").isEmpty());
    }
}
