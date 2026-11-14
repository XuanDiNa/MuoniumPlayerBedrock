package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.lyrics3d.WorldLyricsSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudLayoutSettingsTest {
    @Test void independentControlsReachOriginalModulesAndGeometryWithoutAudioChanges() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");
        var client=BedrockClient.getInstance();client.bindWindow(0);
        if(!MuoniumPlayerClient.getInstance().isReady())MuoniumPlayerClient.getInstance().init();
        var extension=MuoniumPlayerExtension.getInstance();
        var initial=HostSettings.parse("{}");
        var edited=HostSettings.parse("""
                {"musicInfoEnabled":false,"musicLyricsEnabled":true,"spectrumEnabled":false,
                 "infoXPercent":75,"infoYPercent":20,"infoScalePercent":60,
                 "desktopLyricsXPercent":20,"desktopLyricsYPercent":70,"desktopLyricsScalePercent":130,
                 "islandOffsetX":-160,"islandOffsetY":80,"islandScalePercent":70,
                 "spectrumOffsetX":25,"spectrumOffsetY":-80,"spectrumScalePercent":65}
                """);
        var apply=BedrockOverlay.class.getDeclaredMethod("applySettings",HostSettings.class);apply.setAccessible(true);
        apply.invoke(null,initial);
        var music=com.muoniumplayer.core.ncm.music.CloudMusic.player;
        com.muoniumplayer.core.ncm.music.CloudMusic.setVolume(.37f,false);
        apply.invoke(null,edited);
        assertFalse(extension.musicInfo.isEnabled());assertTrue(extension.musicLyrics.isEnabled());
        assertFalse(extension.musicInfo.widget.renderPredicate());assertTrue(extension.musicLyrics.widget.renderPredicate());
        assertFalse(HudConfig.spectrumInHud);assertTrue(HudConfig.spectrumEnabled,"HUD-only toggle must not disable player spectrum");
        assertEquals(.75f,HudConfig.infoX,.0001);assertEquals(.2f,HudConfig.infoY,.0001);assertEquals(.6f,HudConfig.infoScale,.0001);
        assertEquals(.2f,HudConfig.lyricX,.0001);assertEquals(.7f,HudConfig.lyricY,.0001);assertEquals(1.3f,HudConfig.lyricScale,.0001);
        assertEquals(.7f,HudConfig.dynamicIslandScale,.0001);
        assertSame(music,com.muoniumplayer.core.ncm.music.CloudMusic.player);
        assertEquals(.37f,com.muoniumplayer.core.ncm.music.CloudMusic.getVolume(),.001);
        HudConfig.infoX=.13f;extension.musicInfo.setEnabled(true);
        apply.invoke(null,HostSettings.parse("""
                {"musicInfoEnabled":false,"musicLyricsEnabled":true,"spectrumEnabled":false,
                 "infoXPercent":75,"infoYPercent":20,"infoScalePercent":60,
                 "desktopLyricsXPercent":20,"desktopLyricsYPercent":70,"desktopLyricsScalePercent":130,
                 "islandOffsetX":-160,"islandOffsetY":80,"islandScalePercent":70,
                 "spectrumOffsetX":25,"spectrumOffsetY":-80,"spectrumScalePercent":65,"lyricsOffsetY":40}
                """));
        assertEquals(.13f,HudConfig.infoX,.0001,"Unrelated edit must preserve original editor positioning");
        assertTrue(extension.musicInfo.isEnabled(),"Unrelated edit must preserve original editor visibility");
    }
    @Test void newMissingKeysPreserveTheOriginalSavedLayoutAndRangesAreStrict() {
        HudConfig.infoX=.123f;HudConfig.lyricScale=1.234f;
        BedrockOverlay.applyHudSettings(HostSettings.parse("{}"),null);
        assertEquals(.123f,HudConfig.infoX,.0001);assertEquals(1.234f,HudConfig.lyricScale,.0001);
        for(String invalid:new String[]{"{\"infoScalePercent\":49}","{\"infoXPercent\":101}",
                "{\"spectrumOffsetY\":-1001}","{\"islandScalePercent\":136}","{\"musicInfoEnabled\":1}",
                "{\"lyricColorRed\":256}","{\"lyricColorGreen\":1.5}","{\"lyricColorBlue\":\"20\"}"})
            assertThrows(IllegalArgumentException.class,()->HostSettings.parse(invalid),invalid);
    }
    @Test void explicitSpectrumSwitchDoesNotChangeInsidePlayerPreferences() {
        var original=HostSettings.parse("{}");
        HudElements.apply(original,null);assertFalse(HudElements.spectrumAllowed(false,true));
        boolean global=HudConfig.spectrumEnabled,inside=HudConfig.spectrumInPlayer;
        var on=HostSettings.parse("{\"spectrumEnabled\":true}");HudElements.apply(on,original);
        assertTrue(HudElements.spectrumAllowed(false,HudConfig.spectrumInHud));
        assertEquals(global,HudConfig.spectrumEnabled);assertEquals(inside,HudConfig.spectrumInPlayer);
        HudElements.apply(HostSettings.parse("{\"spectrumEnabled\":false}"),on);
        assertFalse(HudElements.spectrumAllowed(true,HudConfig.spectrumInHud));
        HudElements.apply(original,null);
    }
    @Test void customSubtitleColorAppliesToBoth3DModesAndCanReturnToOriginalPalette() throws Exception {
        var custom=HostSettings.parse("{\"lyricColorCustom\":true,\"lyricColorRed\":32,\"lyricColorGreen\":180,\"lyricColorBlue\":240}");
        var settings=WorldLyricsSettings.defaults();settings.coverColorMode=2;settings.keywordColors="test:FF0000";
        HudElements.applyLyricColor(settings,custom);
        assertTrue(settings.colorCustom);assertEquals(0x20B4F0,settings.colorRgb);
        assertEquals(0,settings.coverColorMode);assertEquals("",settings.keywordColors);
        assertEquals(0xFF20B4F0,custom.lyricArgb());
        var untouched=WorldLyricsSettings.defaults();untouched.colorRgb=0x7F2E15;
        HudElements.applyLyricColor(untouched,HostSettings.parse("{}"));assertEquals(0x7F2E15,untouched.colorRgb);
        int previous=HudConfig.lyricColorRgb,current=HudConfig.currentLyricColorRgb;
        boolean follow=HudConfig.osdLyricColorFollowCover;
        HudElements.apply(custom,null);
        assertEquals(previous,HudConfig.lyricColorRgb,"Host color must not become a persisted original-player preference");
        assertEquals(follow,HudConfig.osdLyricColorFollowCover);
        var resolve=com.muoniumplayer.core.widget.impl.MusicLyricsWidget.class.getDeclaredMethod("followCoverColor",int.class,boolean.class);
        resolve.setAccessible(true);
        assertEquals(0xFF20B4F0,(int)resolve.invoke(null,previous,false));
        assertEquals(0xFF20B4F0,HudElements.subtitleColor(current));
        HudElements.apply(HostSettings.parse("{}"),custom);
        assertEquals(previous,HudConfig.lyricColorRgb);assertEquals(current,HudConfig.currentLyricColorRgb);
        assertEquals(follow,HudConfig.osdLyricColorFollowCover);
        assertEquals(current,HudElements.subtitleColor(current));
    }
}
