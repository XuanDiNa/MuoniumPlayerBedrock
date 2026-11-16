package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class UiPresentationTest {
    @TempDir Path root;
    @Test void acceptedLegacyRemainsDefaultAndBothChoicesPersistIndependently() throws Exception {
        var preferences=new UiPresentation(root);
        assertEquals(0,preferences.snapshot().layout());assertEquals(0,preferences.snapshot().motion());
        preferences.selectLayout("新版设置中心");preferences.selectMotion("新版分层动画");
        var loaded=new UiPresentation(root);assertEquals(1,loaded.snapshot().layout());assertEquals(1,loaded.snapshot().motion());
        loaded.selectLayout("旧版浮动面板");assertEquals(1,loaded.snapshot().motion());
        loaded.selectMotion("减少动态");assertEquals(2,new UiPresentation(root).snapshot().motion());
    }
    @Test void openEditorSnapshotsDoNotChangeUnderSavedPreferences() {
        var preferences=new UiPresentation(root);var open=preferences.snapshot();
        preferences.selectLayout("新版设置中心");preferences.selectMotion("减少动态");
        assertFalse(open.modern());assertFalse(open.reduced());
        assertTrue(preferences.snapshot().modern());assertTrue(preferences.snapshot().reduced());
    }
    @Test void malformedOrOversizePreferencesFallBackAsAWhole() throws Exception {
        var path=root.resolve("config/muonium/ui-presentation.json");Files.createDirectories(path.getParent());
        for(String bad:new String[]{"{\"layout\":1,\"motion\":99}","{\"layout\":1.5}","{\"layout\":\"1\"}","{\"layout\":1,\"layout\":0}","[]"," ".repeat(65537)}) {
            Files.writeString(path,bad);assertEquals(new UiPresentation.Selection(0,0),new UiPresentation(root).snapshot());
        }
        assertThrows(IllegalArgumentException.class,()->new UiPresentation(root).selectLayout("missing"));
    }
    @Test void settingsModelOffersRealBedrockChoicesAndStatesActivationBoundary() {
        var entries=Arrays.stream(HudSettingsModel.categories()).flatMap(c->Arrays.stream(c.groups))
            .flatMap(g->Arrays.stream(g.entries)).toList();
        assertTrue(entries.stream().anyMatch(e->e.getLabel().equals("设置界面样式")));
        assertTrue(entries.stream().anyMatch(e->e.getLabel().equals("设置界面动画")));
    }
    @Test void explicitScreenActionsAreLastAndBuildingSettingsIsPassive() {
        var client=BedrockClient.getInstance();
        var before=client.gui.screen();
        var categories=HudSettingsModel.categories();
        var last=categories[categories.length-1];
        assertEquals("host-tools",last.id,"Operation entries must follow every settings category");
        var entries=Arrays.stream(last.groups).flatMap(g->Arrays.stream(g.entries)).toList();
        assertEquals(java.util.List.of("打开播放器","HUD 编辑","预设管理","迷你播放器","岛内搜索"),
                entries.stream().map(HudSettingsModel.Entry::getLabel).toList());
        assertTrue(entries.stream().allMatch(e->e.kind==HudSettingsModel.Kind.ACTION));
        assertSame(before,client.gui.screen(),"Opening settings must not execute any entry");
    }
    @Test void modernAndReducedMotionHaveBoundedDeterministicEndpoints() {
        var original=new UiPresentation.Selection(0,0);var modern=new UiPresentation.Selection(1,1);
        assertEquals(.5,original.progress(.5),1e-6);
        assertEquals(0,modern.progress(0));assertEquals(1,modern.progress(1));
        assertTrue(modern.progress(.6)>modern.progress(.2));
        assertEquals(1,new UiPresentation.Selection(1,2).progress(0));
    }
}
