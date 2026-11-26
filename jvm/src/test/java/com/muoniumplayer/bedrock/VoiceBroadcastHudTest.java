package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceBroadcastHudTest {
    @Test void openingSharedSettingsCreatesOnlyPassiveControls(){
        var voice=RoomVoice.global();var player=CloudMusic.player;
        boolean enabled=voice.enabled();
        var categories=HudSettingsModel.categories();
        var category=java.util.Arrays.stream(categories).filter(c->c.id.equals("voice-broadcast")).findFirst().orElseThrow();
        assertEquals("语音与音乐广播",category.title);
        var status=java.util.Arrays.stream(category.groups[0].entries).filter(e->e.getLabel().startsWith("状态：")).findFirst().orElseThrow();
        assertEquals(HudSettingsModel.Kind.INFO,status.kind);status.activate();
        assertSame(player,CloudMusic.player);assertEquals(enabled,voice.enabled());
        assertFalse(voice.microphone());assertFalse(voice.music());assertFalse(voice.ready());
        assertTrue(java.util.Arrays.stream(category.groups[0].entries).anyMatch(e->e.getLabel().equals("开启麦克风")));
        for(var entry:category.groups[0].entries)if(entry.getLabel().equals("麦克风输入装置")||entry.getLabel().equals("耳机／喇叭输出装置"))assertTrue(java.util.List.of(entry.getChoices()).contains("系统默认"));
        assertTrue(java.util.Arrays.stream(category.groups[0].entries).anyMatch(e->e.getLabel().equals("拒听")));
        var boost=java.util.Arrays.stream(category.groups[0].entries).filter(e->e.getLabel().equals("麦克风增益")).findFirst().orElseThrow();
        var options=java.util.List.of(boost.getChoices());assertTrue(options.contains("100%")&&options.contains("200%")&&options.contains("400%"));
        boost.setChoice("200%");
        try{assertEquals(2f,RoomVoice.class.getMethod("microphoneGain").invoke(voice));assertFalse(voice.microphone());assertFalse(voice.ready());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}finally{boost.setChoice("100%");}
        assertFalse(voice.spatial());assertEquals(32,voice.spatialRange());
        for(String name:new String[]{"距离音讯","重新启用当前世界","传送距离"})assertTrue(java.util.Arrays.stream(category.groups[0].entries).anyMatch(e->e.getLabel().equals(name)));
    }
}
