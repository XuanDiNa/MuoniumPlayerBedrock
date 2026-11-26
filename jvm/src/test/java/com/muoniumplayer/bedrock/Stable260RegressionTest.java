package com.muoniumplayer.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.muoniumplayer.core.ncm.music.BilibiliMusicService;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Stable260RegressionTest {
    @Test void bundledNotoFontsDoNotFetchUpstreamProprietaryAssets() throws Exception {
        var type = com.muoniumplayer.core.management.FontAssetDownload.class;
        var enabled = type.getDeclaredMethod("enabled");
        enabled.setAccessible(true);
        assertEquals(false, enabled.invoke(null), "Bedrock already bundles complete public fonts");
    }
    @Test void downloadInventoryDoesNotStartDownloadsOrOfferBundledDependencies() {
        var before = com.muoniumplayer.core.utils.network.HttpDownloadRace.networkState();
        int active = before.activeTasks(), waiting = before.waitingTasks();
        assertTrue(com.muoniumplayer.core.download.DownloadCatalog.shouldShowEntry());
        var items = com.muoniumplayer.core.download.DownloadCatalog.snapshot();
        for (String id : new String[] {"skija", "fonts"}) {
            var bundled = items.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
            assertEquals(com.muoniumplayer.core.download.DownloadCatalog.Status.INSTALLED, bundled.status());
            assertFalse(bundled.canStart());
        }
        assertFalse(items.stream().anyMatch(i -> i.id().equals("muoniumconfig")));
        assertEquals(active, com.muoniumplayer.core.utils.network.HttpDownloadRace.networkState().activeTasks());
        assertEquals(waiting, com.muoniumplayer.core.utils.network.HttpDownloadRace.networkState().waitingTasks());
    }

    @Test void togglingDynamicEqInvalidatesPendingStreamingAnalysis() throws Exception {
        var type = Class.forName("com.muoniumplayer.core.ncm.music.StreamingMixPreparation");
        var key = type.getDeclaredMethod("settingsKey");
        key.setAccessible(true);
        boolean old = com.muoniumplayer.core.ncm.music.AutomixSettings.isLearnedEqEnabled();
        try {
            com.muoniumplayer.core.ncm.music.AutomixSettings.setLearnedEqEnabled(false);
            String disabled = (String) key.invoke(null);
            com.muoniumplayer.core.ncm.music.AutomixSettings.setLearnedEqEnabled(true);
            assertNotEquals(disabled, key.invoke(null));
        } finally {
            com.muoniumplayer.core.ncm.music.AutomixSettings.setLearnedEqEnabled(old);
        }
    }

    @Test void resolvedBilibiliPageDurationIsIdentityBoundAndNeverPersisted() throws Exception {
        var constructor = BilibiliMusicService.Track.class.getDeclaredConstructor(
                String.class, int.class, String.class, String.class, long.class,
                String.class, String.class, String.class);
        constructor.setAccessible(true);
        Music song = Music.fromBilibiliTrack(constructor.newInstance(
                "BV1xx411c7mD", 2, "Page two", "Artist", 900_000L, "", "123", "456"));
        Method apply = assertDoesNotThrow(() -> Music.class.getMethod(
                "applyBilibiliPlaybackDuration", String.class, long.class));
        apply.invoke(song, "BV1xx411c7mD", 5_000L);
        assertEquals(900_000L, song.getDuration(), "A late response for P1 cannot change P2");
        apply.invoke(song, song.getSourceId(), 123_000L);
        assertEquals(123_000L, song.getDuration());
        apply.invoke(song, song.getSourceId(), 0L);
        assertEquals(123_000L, song.getDuration());
        Music restored = new Gson().fromJson(new Gson().toJson(song), Music.class);
        assertEquals(900_000L, restored.getDuration(), "Runtime duration must not rewrite stored playlist metadata");
    }

    @Test void bilibiliViewUsesSelectedPageDurationRatherThanWholeVideo() throws Exception {
        Method parse = assertDoesNotThrow(() -> BilibiliMusicService.class.getDeclaredMethod(
                "trackFromViewData", com.google.gson.JsonObject.class, String.class, int.class));
        parse.setAccessible(true);
        var view = JsonParser.parseString("""
                {"aid":"123","cid":"1","bvid":"BV1xx411c7mD","title":"Video","duration":900,
                 "pages":[{"page":1,"cid":"11","duration":60,"part":"First"},
                          {"page":2,"cid":"22","duration":123,"part":"Second"}]}
                """).getAsJsonObject();
        var track = (BilibiliMusicService.Track) parse.invoke(null, view, "BV1xx411c7mD", 2);
        assertNotNull(track);
        assertEquals(123_000L, track.durationMillis);
        assertEquals("22", track.cid);
        var invalid = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> parse.invoke(null, view, "BV1xx411c7mD", 3));
        assertInstanceOf(IllegalArgumentException.class, invalid.getCause());
    }
}
