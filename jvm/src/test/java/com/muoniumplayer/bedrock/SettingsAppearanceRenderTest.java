package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.screens.hud.*;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.muoniumplayer.bedrock.UiPresentationRenderTest.*;

class SettingsAppearanceRenderTest {
    @Test @Timeout(80) void existingHudEntryRendersAndClicksAllFourThemeCardsAndColorPicker()throws Exception {
        assertTrue(GLFW.glfwInit());long window=0;
        var pref=UiPresentation.global();var original=pref.snapshot();
        var appearance=SettingsAppearance.global();var originalAppearance=appearance.snapshot();
        var decoder=CloudMusic.player;var queue=CloudMusic.playList;
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Owned settings appearance fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(1280,720,1);
            MuoniumPlayerClient.getInstance().init();
            pref.selectLayout("新版设置中心");pref.selectMotion("减少动态");
            var screen=new HostHudEditor();client.gui.setScreen(screen);
            var categories=HudSettingsModel.categories();int category=-1;
            for(int i=0;i<categories.length;i++)if(categories[i].id.equals("settings-appearance"))category=i;
            assertTrue(category>=0);set(screen,"categoryIndex",category);
            for(int i=0;i<4;i++){
                frame(screen);Object themeRow=row(screen,"设置页主题");assertNotNull(themeRow);
                var bounds=GuiHudEditorPro.class.getDeclaredMethod("settingsThemeBounds",themeRow.getClass(),int.class);bounds.setAccessible(true);
                int[] r=(int[])bounds.invoke(screen,themeRow,i);
                int[] p=physicalPoint(screen,r[0]+r[2]/2,r[1]+r[3]/2);screen.clickEvent(p[0],p[1],0);frame(screen);
                assertEquals(i,appearance.snapshot().theme(),"Each visible theme card must select its own theme");
                Path png=Path.of("../stage-captures/settings-theme-"+i+".png");Files.createDirectories(png.getParent());SkijaHost.savePng(png,true);
                assertTrue(Files.size(png)>10000);
            }
            int[] panel=bounds(screen);Mouse.position(panel[0]+panel[2]-50,panel[1]+panel[3]/2);
            Mouse.pushScrollNotches(-6);for(int i=0;i<12;i++)frame(screen);
            var color=row(screen,"设置页主色");assertNotNull(color);
            int[] p=physicalPoint(screen,rowNumber(color,"x")+rowNumber(color,"w")-25,rowNumber(color,"y")+rowNumber(color,"h")/2);
            screen.clickEvent(p[0],p[1],0);frame(screen);
            assertEquals(HudSettingsModel.ColorTarget.SETTINGS_ACCENT,field(screen,"editingColor"));
            var title=GuiHudEditorPro.class.getDeclaredMethod("pickerTitle",HudSettingsModel.ColorTarget.class);title.setAccessible(true);
            assertEquals("设置页主色",title.invoke(null,HudSettingsModel.ColorTarget.SETTINGS_ACCENT));
            SkijaHost.savePng(Path.of("../stage-captures/settings-accent-picker.png"),true);
            var entry=(HudSettingsModel.Entry)rowEntry(color);entry.setColor(new java.awt.Color(0x156F45));
            appearance.flush();assertEquals(0xFF156F45,appearance.accent());
            assertSame(decoder,CloudMusic.player);assertSame(queue,CloudMusic.playList);
            client.gui.setScreen(null);
        }finally {
            appearance.selectTheme(SettingsAppearance.themes()[originalAppearance.theme()]);
            appearance.setAccent(originalAppearance.accent());appearance.setAnimations(originalAppearance.animations());
            pref.selectLayout(UiPresentation.layouts()[original.layout()]);pref.selectMotion(UiPresentation.motions()[original.motion()]);
            SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
    private static Object row(HostHudEditor screen,String label)throws Exception {
        for(Object row:(List<?>)field(screen,"rows"))if(rowEntry(row) instanceof HudSettingsModel.Entry e&&e.getLabel().equals(label))return row;
        return null;
    }
}
