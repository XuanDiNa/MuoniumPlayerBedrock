package com.muoniumplayer.bedrock;
import com.muoniumplayer.core.cine.CineSettings;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class CinematicUiTest {
    com.google.gson.JsonObject saved;boolean oldHook;
    @BeforeEach void isolateSettings() throws Exception {
        var f=CineSettings.class.getDeclaredField("testIoHooked");f.setAccessible(true);
        oldHook=f.getBoolean(null);f.setBoolean(null,true);saved=CineSettings.toJson();
        CineSettings.applyJson(CineSettings.defaultsJson());
    }
    @AfterEach void restoreSettings() throws Exception {
        CineSettings.applyJson(saved);var f=CineSettings.class.getDeclaredField("testIoHooked");f.setAccessible(true);f.setBoolean(null,oldHook);
    }
    List<HudSettingsModel.Entry> entries(){
        var c=Arrays.stream(HudSettingsModel.categories()).filter(v->v.id.equals("cine")).findFirst();
        assertTrue(c.isPresent(),"HUD editor needs the implemented cinematic category");
        return Arrays.stream(c.get().groups).flatMap(g->Arrays.stream(g.entries)).toList();
    }
    @Test void realCameraControlsDefaultOffAndDoNotAdvertiseUnsupportedMobTracking(){
        assertFalse(CineSettings.isEnabled());var e=entries();
        assertTrue(e.stream().anyMatch(v->v.getLabel().equals("卡点运镜")));
        assertTrue(e.stream().anyMatch(v->v.getLabel().equals("隐藏 HUD")));
        assertTrue(e.stream().noneMatch(v->v.getLabel().contains("生物")||v.getLabel().contains("通透")));
        e.stream().filter(v->v.getLabel().equals("卡点运镜")).findFirst().orElseThrow().activate();
        assertTrue(CineSettings.isEnabled());
    }
    @Test void exactIdleSelectionKeepsSecondsAndMinutes(){
        CineSettings.setEnabled(true);
        var e=entries().stream().filter(v->v.getLabel().equals("挂机多久启动")).findFirst().orElseThrow();
        for(String choice:new String[]{"30 秒","60 秒","2 分钟","5 分钟","10 分钟"}){
            e.setChoice(choice);int n=Integer.parseInt(choice.split(" ")[0])*(choice.endsWith("分钟")?60:1);
            assertEquals(n,CineSettings.idleSeconds(),choice);
        }
    }
}
