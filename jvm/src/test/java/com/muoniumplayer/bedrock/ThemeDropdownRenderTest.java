package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.screens.hud.GuiHudEditorPro;
import com.muoniumplayer.core.screens.hud.HudSettingsModel;
import com.muoniumplayer.core.screens.ncm.NCMTheme;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the shared rendered editor, its actual wheel input and choice hit testing. */
class ThemeDropdownRenderTest {
    static Object invoke(HostHudEditor s,String name,Class<?>[] types,Object... args)throws Exception {
        var m=GuiHudEditorPro.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(s,args);
    }
    static int[] drop(HostHudEditor s)throws Exception {
        boolean active=(boolean)invoke(s,"beginAdaptiveLayout",new Class<?>[0]);
        try {
            int[] p=(int[])invoke(s,"panelBounds",new Class<?>[0]);
            return (int[])invoke(s,"choiceDropdownBounds",new Class<?>[]{com.muoniumplayer.core.rendering.font.CFontRenderer.class,int.class,int.class,int.class,int.class},
                    FontManager.pf12,p[0],p[1],p[2],p[3]);
        }finally{invoke(s,"restoreAdaptiveLayout",new Class<?>[]{boolean.class},active);}
    }
    static int item(HostHudEditor s,int[] d,int row)throws Exception {
        return (int)invoke(s,"choiceItemAt",new Class<?>[]{int.class,int.class,int[].class},d[0]+20,d[1]+4+row*d[4]+d[4]/2,d);
    }
    static void wheel(HostHudEditor s,double scale,int x,int y,int delta)throws Exception {
        int[] p=UiPresentationRenderTest.physicalPoint(s,x,y);
        Mouse.position(p[0]*scale,p[1]*scale);Mouse.pushScrollNotches(delta);
        UiPresentationRenderTest.frame(s);
    }
    @Test void themeWheelReachesLastChoiceAndSelectionNeverLeaksToThePage()throws Exception {
        assertTrue(GLFW.glfwInit());long window=0;
        var ui=UiPresentation.global();var old=ui.snapshot();var theme=NCMTheme.getCurrent();
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Owned theme dropdown fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);MuoniumPlayerClient.getInstance().init();
            ui.selectMotion(UiPresentation.motions()[2]);
            for(String layout:UiPresentation.layouts())for(double scale:new double[]{1,1.5,2,3}) {
                ui.selectLayout(layout);client.getWindow().size(1280,720,scale);HudConfig.hudEditorLastCategory=0;
                var s=new HostHudEditor();client.gui.setScreen(s);
                for(int i=0;i<4;i++)UiPresentationRenderTest.frame(s);
                var input=(com.muoniumplayer.compat.mc.GuiTextField)UiPresentationRenderTest.field(s,"searchInput");
                input.setFocused(true);
                for(char ch:"播放器主题".toCharArray())s.keyEvent(ch,0);
                for(int i=0;i<4;i++)UiPresentationRenderTest.frame(s);
                Object themeRow=null;
                for(Object row:(List<?>)UiPresentationRenderTest.field(s,"rows")) {
                    if(UiPresentationRenderTest.rowEntry(row) instanceof HudSettingsModel.Entry e&&e.getLabel().equals("播放器主题")){themeRow=row;break;}
                }
                assertNotNull(themeRow,"Original theme choice must be rendered");
                int[] p=UiPresentationRenderTest.physicalPoint(s,UiPresentationRenderTest.rowNumber(themeRow,"x")+UiPresentationRenderTest.rowNumber(themeRow,"w")-30,
                        UiPresentationRenderTest.rowNumber(themeRow,"y")+UiPresentationRenderTest.rowNumber(themeRow,"h")/2);
                // The search result first navigates to the owning category.
                s.clickEvent(p[0],p[1],0);for(int i=0;i<4;i++)UiPresentationRenderTest.frame(s);
                for(Object row:(List<?>)UiPresentationRenderTest.field(s,"rows")) {
                    if(UiPresentationRenderTest.rowEntry(row) instanceof HudSettingsModel.Entry e&&e.getLabel().equals("播放器主题")){themeRow=row;break;}
                }
                p=UiPresentationRenderTest.physicalPoint(s,UiPresentationRenderTest.rowNumber(themeRow,"x")+UiPresentationRenderTest.rowNumber(themeRow,"w")-30,
                        UiPresentationRenderTest.rowNumber(themeRow,"y")+UiPresentationRenderTest.rowNumber(themeRow,"h")/2);
                s.clickEvent(p[0],p[1],0);UiPresentationRenderTest.frame(s);
                var entry=(HudSettingsModel.Entry)UiPresentationRenderTest.field(s,"openChoice");assertNotNull(entry);
                int[] d=drop(s);assertNotNull(d);assertTrue(entry.getChoices().length>d[5]);
                float page=(float)UiPresentationRenderTest.field(s,"displayScroll");
                wheel(s,scale,d[0]+20,d[1]+20,-1);d=drop(s);
                assertEquals(1,item(s,d,0),"Down wheel must reveal the next theme, not remain on the first seven");
                wheel(s,scale,d[0]+20,d[1]+20,-100);d=drop(s);
                assertEquals(entry.getChoices().length-1,item(s,d,d[5]-1),"Last theme must be reachable");
                assertEquals(page,(float)UiPresentationRenderTest.field(s,"displayScroll"),.01,"Dropdown wheel must not move the page");
                wheel(s,scale,0,0,1);assertEquals(entry.getChoices().length-1,item(s,drop(s),d[5]-1),"Outside wheel must not move dropdown");
                assertEquals(page,(float)UiPresentationRenderTest.field(s,"displayScroll"),.01);
                Path image=Path.of("../stage-captures/theme-bottom-"+ui.snapshot().layout()+"-"+scale+".png");
                Files.createDirectories(image.getParent());SkijaHost.savePng(image,true);
                String last=entry.getChoices()[entry.getChoices().length-1];
                p=UiPresentationRenderTest.physicalPoint(s,d[0]+20,d[1]+4+(d[5]-1)*d[4]+d[4]/2);
                s.clickEvent(p[0],p[1],0);assertEquals(last,entry.getValueText(),"Scrolled hit test must select the visible theme");
                assertNull(UiPresentationRenderTest.field(s,"openChoice"));
                // Reopen, verify the wheel's upper bound, then Esc closes only this dropdown.
                UiPresentationRenderTest.set(s,"openChoice",entry);UiPresentationRenderTest.frame(s);d=drop(s);
                wheel(s,scale,d[0]+20,d[1]+20,100);assertEquals(0,item(s,drop(s),0));
                s.keyEvent('\0',Keyboard.KEY_ESCAPE);assertNull(UiPresentationRenderTest.field(s,"openChoice"));assertSame(s,client.gui.screen());
                client.gui.setScreen(null);
            }
            System.out.println("THEME_DROPDOWN layouts=2 scales=4 lastChoiceSelectable=true wheelIsolated=true");
        }finally {
            NCMTheme.setCurrent(theme);ui.selectLayout(UiPresentation.layouts()[old.layout()]);ui.selectMotion(UiPresentation.motions()[old.motion()]);
            SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();
        }
    }
}
