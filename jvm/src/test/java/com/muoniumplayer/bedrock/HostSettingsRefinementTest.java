package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostSettingsRefinementTest {
    static List<HudSettingsModel.Entry> entries() {
        return Arrays.stream(HudSettingsModel.categories()).flatMap(c->Arrays.stream(c.groups))
            .flatMap(g->Arrays.stream(g.entries)).toList();
    }
    @Test void hudDoesNotOfferUnavailableJavaUiProvidersOrClaimADefaultHKey() {
        assertTrue(entries().stream().noneMatch(e->e.getLabel().contains("设置界面由谁渲染")),
            "Java settings-mod providers cannot be selected in the native Bedrock host");
    }
    @Test void resetActionRestoresAutomaticCalibrationWithoutChangingVolumeOrLyrics() throws Exception {
        float delay=HudSetting.SMTC_DELAY.getValue(),window=HudSetting.SMTC_WINDOW.getValue();
        float volume=com.muoniumplayer.core.ncm.music.CloudMusic.getVolume();boolean lyrics=HudConfig.worldLyricsEnabled;
        try {
            HudSetting.SMTC_DELAY.setValue(730);HudSetting.SMTC_WINDOW.setValue(6500);
            var reset=entries().stream().filter(e->e.getLabel().equals("恢复自动时间轴校准")).findFirst();
            assertTrue(reset.isPresent(),"Calibration needs a clear return-to-automatic action");
            reset.get().activate();
            assertEquals(-1,HudConfig.smtcTimelineDelayMs);assertEquals(-1,HudConfig.smtcTimelineWindowMs);
            assertEquals(-1,HudSetting.SMTC_DELAY.getValue());assertEquals(-1,HudSetting.SMTC_WINDOW.getValue());
            var saved=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                com.muoniumplayer.core.settings.ConfigPaths.HUD.toPath())).getAsJsonObject();
            assertEquals(-1,saved.get("smtcTimelineDelayMs").getAsInt());
            assertEquals(-1,saved.get("smtcTimelineWindowMs").getAsInt());
            assertEquals(volume,com.muoniumplayer.core.ncm.music.CloudMusic.getVolume());
            assertEquals(lyrics,HudConfig.worldLyricsEnabled);
        } finally {HudSetting.SMTC_DELAY.setValue(delay);HudSetting.SMTC_WINDOW.setValue(window);}
    }
}
