package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.screens.hud.GuiHudEditorPro;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual shared editor rendering/scroll/close and no decoder/queue replacement. */
class UiPresentationRenderTest {
    static Object field(Object target,String name)throws Exception {
        var f=GuiHudEditorPro.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    static void set(Object target,String name,Object value)throws Exception {
        var f=GuiHudEditorPro.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static int rowNumber(Object row,String name)throws Exception {
        var f=row.getClass().getDeclaredField(name);f.setAccessible(true);return ((Number)f.get(row)).intValue();
    }
    static Object rowEntry(Object row)throws Exception {
        var f=row.getClass().getDeclaredField("entry");f.setAccessible(true);return f.get(row);
    }
    static int[] physicalPoint(HostHudEditor screen,int x,int y)throws Exception {
        var begin=GuiHudEditorPro.class.getDeclaredMethod("beginAdaptiveLayout");begin.setAccessible(true);
        var end=GuiHudEditorPro.class.getDeclaredMethod("restoreAdaptiveLayout",boolean.class);end.setAccessible(true);
        boolean active=(boolean)begin.invoke(screen);
        try {return new int[]{Math.toIntExact(Math.round(x*com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale.correctionX())),
            Math.toIntExact(Math.round(y*com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale.correctionY()))};}
        finally{end.invoke(screen,active);}
    }
    static void frame(HostHudEditor screen)throws Exception {
        SkijaHost.beginFrame(0);try{screen.drawHostFrame(0);}finally{SkijaHost.endFrame();}
        Thread.sleep(16);
    }
    static int[] bounds(HostHudEditor screen)throws Exception {
        var begin=GuiHudEditorPro.class.getDeclaredMethod("beginAdaptiveLayout");begin.setAccessible(true);
        var end=GuiHudEditorPro.class.getDeclaredMethod("restoreAdaptiveLayout",boolean.class);end.setAccessible(true);
        boolean active=(boolean)begin.invoke(screen);
        try {
            var method=GuiHudEditorPro.class.getDeclaredMethod("panelBounds");method.setAccessible(true);
            int[] bounds=(int[])method.invoke(screen);
            double sx=com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale.correctionX();
            double sy=com.muoniumplayer.core.screens.ncm.WindowAdaptiveScale.correctionY();
            return new int[]{(int)(bounds[0]*sx),(int)(bounds[1]*sy),(int)(bounds[2]*sx),(int)(bounds[3]*sy)};
        }finally{end.invoke(screen,active);}
    }
    @Test void bothLayoutsAndAllMotionModesRenderScrollCloseAndReopenWithoutAudioReset() throws Exception {
        assertTrue(GLFW.glfwInit());long window=0;
        var preference=UiPresentation.global();var original=preference.snapshot();
        com.muoniumplayer.core.ncm.music.AudioPlayer ownedAudio=null;Path wav=null;
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Owned UI presentation fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(1280,720,1);
            MuoniumPlayerClient.getInstance().init();
            wav=Files.createTempFile(SessionMedia.directory().toPath(),"owned-ui-",".wav");
            int bytes=44100*2*4;var data=java.nio.ByteBuffer.allocate(44+bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            data.put("RIFF".getBytes()).putInt(36+bytes).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(bytes);Files.write(wav,data.array());
            ownedAudio=new com.muoniumplayer.core.ncm.music.AudioPlayer(wav.toFile());CloudMusic.player=ownedAudio;
            var decoder=CloudMusic.player;var queue=CloudMusic.playList;
            // A user's old floating placement must not collapse the new centered layout.
            HudConfig.hudEditorPanelScaleW=.35f;HudConfig.hudEditorPanelX=.9f;
            HudConfig.hudEditorPanelY=.2f;HudConfig.hudEditorLastCategory=0;
            int legacyWidth=0;
            for(String layout:UiPresentation.layouts())for(String motion:UiPresentation.motions()) {
                preference.selectLayout(layout);preference.selectMotion(motion);
                var screen=new HostHudEditor();client.gui.setScreen(screen);
                for(int i=0;i<50;i++)frame(screen);
                var rows=(List<?>)field(screen,"rows");assertFalse(rows.isEmpty());
                int modern=preference.snapshot().modern()?1:0;
                var geometry=GuiHudEditorPro.class.getDeclaredMethod("panelWidth");geometry.setAccessible(true);
                int width=(int)geometry.invoke(screen);if(modern==0)legacyWidth=width;else assertTrue(width>legacyWidth);
                // Force only scroll ownership/category, never values in the common settings model.
                set(screen,"categoryIndex",0);
                int contentHeight=(int)field(screen,"contentHeight");assertTrue(contentHeight>0);
                int[] b=bounds(screen);Mouse.position(b[0]+b[2]-70,b[1]+b[3]/2);
                Mouse.pushScrollNotches(-8);for(int i=0;i<25;i++)frame(screen);
                assertTrue((float)field(screen,"displayScroll")>0,"Actual editor content must scroll in every presentation/motion mode");
                Object toggle=null;
                for(var row:(List<?>)field(screen,"rows")) {
                    var e=rowEntry(row);
                    if(e instanceof com.muoniumplayer.core.screens.hud.HudSettingsModel.Entry entry
                            &&entry.getLabel().equals("歌曲信息栏")){toggle=row;break;}
                }
                assertNotNull(toggle);
                int[] point=physicalPoint(screen,rowNumber(toggle,"x")+rowNumber(toggle,"w")-30,
                    rowNumber(toggle,"y")+rowNumber(toggle,"h")/2);
                boolean was=HudConfig.musicInfoEnabled;
                screen.clickEvent(point[0],point[1],0);assertEquals(!was,HudConfig.musicInfoEnabled,"Rendered toggle click must reach the shared value");
                frame(screen);screen.clickEvent(point[0],point[1],0);assertEquals(was,HudConfig.musicInfoEnabled);
                // All three modes must finish with visible cards; reduced motion never leaves a fade veil.
                assertEquals(1,(double)field(screen,"pageFade"),1e-5);
                Path png=Path.of("../stage-captures/ui-"+modern+"-"+preference.snapshot().motion()+".png");
                Files.createDirectories(png.getParent());SkijaHost.savePng(png,true);
                screen.keyEvent('\0',Keyboard.KEY_ESCAPE);
                for(int i=0;i<55&&client.gui.screen()==screen;i++)frame(screen);
                assertNull(client.gui.screen(),"Escape must close without an orphan input layer");
                assertSame(decoder,CloudMusic.player);assertSame(queue,CloudMusic.playList);
                assertEquals(.35f,HudConfig.hudEditorPanelScaleW,.0001,"Modern layout must preserve legacy geometry");
            }
        } finally {
            if(ownedAudio!=null)ownedAudio.close();CloudMusic.player=null;if(wav!=null)Files.deleteIfExists(wav);
            preference.selectLayout(UiPresentation.layouts()[original.layout()]);preference.selectMotion(UiPresentation.motions()[original.motion()]);
            SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();
        }
    }
}
