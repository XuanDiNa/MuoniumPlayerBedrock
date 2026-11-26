package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.settings.HudSetting;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Chat identity and chat mouse dispatch are not part of the native transport. */
class BedrockChatCardSettingsTest {
    private HudSettingsModel.Entry entry(String name) {
        for (var category : HudSettingsModel.categories())
            for (var group : category.groups)
                for (var entry : group.entries)
                    if (name.equals(entry.getLabel())) return entry;
        throw new AssertionError("Missing entry: " + name);
    }

    @Test void chatOnlyControlsNeverAppearForAnyStyleOrImportedPreference() {
        var previous = HudConfig.toJson();
        try {
            HudConfig.dynamicIslandEnabled = true;
            for (int style : new int[]{0, 2, 6, 7, 8}) {
                HudConfig.dynamicIslandStyle = style;
                for (boolean expand : new boolean[]{false, true}) {
                    HudConfig.islandHoverExpand = expand;
                    for (int trigger : new int[]{0, 1}) {
                        HudConfig.islandExpandTrigger = trigger;
                        assertFalse(entry("可交互展开").isVisible());
                        assertFalse(entry("展开方式").isVisible());
                        assertFalse(entry("音乐卡缩放").isVisible());
                    }
                }
            }
        } finally { HudConfig.applyJson(previous); }
    }

    @Test void supportedCardAppearanceAndExplicitMiniPlayerEntryRemainAvailable() {
        var previous = HudConfig.toJson();
        try {
            HudConfig.dynamicIslandEnabled = true;
            HudConfig.dynamicIslandStyle = 8;
            assertTrue(entry("音乐卡 · 显示歌词").isVisible());
            assertTrue(entry(HudSetting.ISLAND_MUSIC_CAPSULE_MAX_WIDTH.getLabel()).isVisible());
            assertTrue(entry("迷你播放器").isVisible());
            assertEquals(HudSettingsModel.Kind.ACTION, entry("迷你播放器").kind);
            assertFalse(entry("岛内搜索").description.contains("Ctrl+F"),
                    "The host has no default island-search binding");
        } finally { HudConfig.applyJson(previous); }
    }

    @Test void supportedWidthVisibilityStillFollowsIslandAndCapsuleStyle() {
        var previous=HudConfig.toJson();
        try {
            for(boolean enabled:new boolean[]{false,true}) for(int style:new int[]{0,2,6,7,8}) {
                HudConfig.dynamicIslandEnabled=enabled;HudConfig.dynamicIslandStyle=style;
                for(boolean interaction:new boolean[]{false,true}) for(int trigger:new int[]{0,1}) {
                    HudConfig.islandHoverExpand=interaction;HudConfig.islandExpandTrigger=trigger;
                    assertEquals(enabled&&style==8,entry("音乐胶囊最大长度").isVisible());
                    assertEquals(enabled,entry("灵动岛基础宽度").isVisible());
                    assertFalse(entry("音乐卡缩放").isVisible());
                }
            }
        }finally{HudConfig.applyJson(previous);}
    }
    @Test void hidingControlsDoesNotRewriteStoredValuesOrShareKeys() {
        var previous = HudConfig.toJson();
        try {
            HudConfig.islandHoverExpand = false;
            HudConfig.islandExpandTrigger = 1;
            HudConfig.islandMiniCardScale = 1.6f;
            var serialized = HudConfig.toJson();
            for (var category : HudSettingsModel.categories())
                for (var group : category.groups)
                    for (var entry : group.entries) entry.isVisible();
            assertFalse(HudConfig.islandHoverExpand);
            assertEquals(1, HudConfig.islandExpandTrigger);
            assertEquals(1.6f, HudConfig.islandMiniCardScale);
            HudConfig.applyJson(serialized);
            assertEquals(serialized.get("islandExpandTrigger"), HudConfig.toJson().get("islandExpandTrigger"));
            assertEquals(serialized.get("islandHoverExpand"), HudConfig.toJson().get("islandHoverExpand"));
            assertEquals(serialized.get("islandMiniCardScale"), HudConfig.toJson().get("islandMiniCardScale"));
        } finally { HudConfig.applyJson(previous); }
    }
}
