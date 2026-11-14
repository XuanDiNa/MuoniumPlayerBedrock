package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaSettingsTest {
    @Test void originalSettingsDoNotOfferPersistentCacheQuota() {
        boolean foundSessionExplanation = false;
        for (var category : HudSettingsModel.categories()) for (var group : category.groups) for (var entry : group.entries) {
            assertNotEquals("缓存上限", entry.getLabel(), "A persistent GB cache preference is misleading under session-only storage");
            if (entry.description.contains("退出后清理")) foundSessionExplanation = true;
        }
        assertTrue(foundSessionExplanation, "The original settings must explain temporary playback files");
    }
}
