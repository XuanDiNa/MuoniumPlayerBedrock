package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.screens.hud.*;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.muoniumplayer.bedrock.UiPresentationRenderTest.*;

class VoiceBroadcastRenderTest {
    @Test @Timeout(60) void sharedHudRendersVoiceOptionsWithoutEnablingAnything()throws Exception{
        assertTrue(GLFW.glfwInit());long window=0;
        var voice=RoomVoice.global();boolean enabled=voice.enabled();var decoder=CloudMusic.player;
        var preference=UiPresentation.global();var original=preference.snapshot();
        try{
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Voice controls offscreen fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(1280,720,1);
            MuoniumPlayerClient.getInstance().init();
            preference.selectLayout("新版设置中心");preference.selectMotion("减少动态");
            var screen=new HostHudEditor();client.gui.setScreen(screen);
            var categories=HudSettingsModel.categories();int index=-1;
            for(int i=0;i<categories.length;i++)if(categories[i].id.equals("voice-broadcast"))index=i;
            assertTrue(index>=0);set(screen,"categoryIndex",index);
            for(int i=0;i<40;i++)frame(screen);
            var labels=new java.util.HashSet<String>();
            for(Object row:(java.util.List<?>)field(screen,"rows"))
                if(rowEntry(row) instanceof HudSettingsModel.Entry entry)labels.add(entry.getLabel());
            assertTrue(labels.contains("广播当前音乐"),labels.toString());
            assertTrue(labels.contains("开启房间语音"),labels.toString());
            assertTrue(labels.contains("开启麦克风"),labels.toString());
            assertTrue(labels.contains("麦克风增益"),labels.toString());
            assertTrue(labels.contains("距离音讯"),labels.toString());assertTrue(labels.contains("传送距离"),labels.toString());
            Path png=Path.of("../stage-captures/voice-broadcast-controls.png");Files.createDirectories(png.getParent());SkijaHost.savePng(png,true);
            assertTrue(Files.size(png)>10000);assertEquals(enabled,voice.enabled());assertSame(decoder,CloudMusic.player);
            assertFalse(voice.ready());assertFalse(voice.microphone());assertFalse(voice.music());
            var parent=screen;RoomVoiceSettings.open();var settings=client.gui.screen();assertInstanceOf(HostHudEditor.class,settings);
            settings.suspendHostInput();assertFalse(voice.enabled());assertFalse(voice.ready());
            settings.closeScreen();assertSame(parent,client.gui.screen());
            client.gui.setScreen(null);
        }finally{
            preference.selectLayout(UiPresentation.layouts()[original.layout()]);preference.selectMotion(UiPresentation.motions()[original.motion()]);
            SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
}
