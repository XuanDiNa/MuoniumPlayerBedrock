package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.ncm.music.CloudMusic;

class UpstreamBatchTest {
    @Test void capsuleProgressUsesTheExistingPlaybackClock() throws Exception {
        assertTrue(HudConfig.DYNAMIC_ISLAND_STYLE_MAX>=8,"The upstream capsule style is not integrated");
        Class<?> card=assertDoesNotThrow(()->Class.forName("com.muoniumplayer.core.rendering.MusicCapsuleCard"));
        var snapshot=new CloudMusic.PlaybackSnapshot(1,1,2500,10000,true,null,null);
        assertEquals(.25,(double)card.getMethod("progressFraction",CloudMusic.PlaybackSnapshot.class).invoke(null,snapshot),1e-9);
    }
    @Test void originalPresetShareCodecIsAvailable() {
        assertDoesNotThrow(()->Class.forName("com.muoniumplayer.core.preset.PresetService"));
    }
    @Test void actualPresetServiceExcludesUnimplementedNativeDomains() {
        var ids=com.muoniumplayer.core.preset.PresetService.get().engine().domains().stream().map(d->d.id()).toList();
        assertTrue(ids.contains("cine")); assertFalse(ids.contains("spin")); assertFalse(ids.contains("transparent"));
        assertTrue(ids.contains("hud")); assertTrue(ids.contains("acoustics"));
        assertTrue(com.muoniumplayer.core.preset.PresetService.get().engine().availableFacets().contains(com.muoniumplayer.core.preset.model.Facet.CAMERA));
        for(var category:com.muoniumplayer.core.screens.hud.HudSettingsModel.categories())
            assertNotEquals("fun",category.id);
    }
    @Test void newObservationsAndWindowsPublishingAreOptIn() {
        var defaults=HostSettings.parse("{}");
        assertFalse(defaults.worldLyricsOcclusion());assertFalse(defaults.smtcPublishEnabled());
        var enabled=HostSettings.parse("{\"worldLyricsOcclusion\":true,\"smtcPublishEnabled\":true}");
        assertTrue(enabled.worldLyricsOcclusion());assertTrue(enabled.smtcPublishEnabled());
        assertThrows(IllegalArgumentException.class,()->HostSettings.parse("{\"worldLyricsOcclusion\":1}"));
    }
    @Test void shippedDefaultPresetDoesNotAdvertiseNativeCamera() throws Exception {
        try(var in=getClass().getResourceAsStream("/muonium/presets/builtin/default.mpreset.json")) {
            assertNotNull(in);
            String text=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(text.contains("运镜"));
        }
    }
    @Test void windowsPublicationHasItsOwnIdentity() throws Exception {
        Class<?> publisher=assertDoesNotThrow(()->Class.forName("com.muoniumplayer.core.ncm.music.SmtcPublisher"));
        assertEquals("MuoniumPlayer.SMTC",publisher.getField("PUBLISH_AUMID").get(null));
    }
    @Test void externalHelperAndArtworkUseMatchingSessionDirectory() throws Exception {
        try(var in=getClass().getResourceAsStream("/muonium/external/SmtcWatcher.cs")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("MUONIUM_SMTC_ART_DIR"));
        }
        for(String entry:new String[]{"ExternalMediaService","ExternalArtwork"})try(var in=getClass().getResourceAsStream(
            "/com/muoniumplayer/core/ncm/music/"+entry+".class")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("SessionMedia"));
        }
    }
}
