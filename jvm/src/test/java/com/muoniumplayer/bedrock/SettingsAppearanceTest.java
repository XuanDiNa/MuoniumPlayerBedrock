package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsAppearanceTest {
    @TempDir Path root;
    @Test void themeAccentAndAnimationsPersistWithoutChangingExistingLayoutOrOtherSettings()throws Exception {
        var layout=new UiPresentation(root);layout.selectLayout("新版设置中心");
        byte[] original=Files.readAllBytes(root.resolve("config/muonium/ui-presentation.json"));
        var appearance=new SettingsAppearance(root);
        assertEquals(0,appearance.snapshot().theme());assertEquals(0xFF2B4FFF,appearance.accent());assertTrue(appearance.animations());
        for(String theme:SettingsAppearance.themes()){
            appearance.selectTheme(theme);assertEquals(theme,new SettingsAppearance(root).label());
        }
        appearance.setAccent(0xFF287A35);appearance.flush();appearance.setAnimations(false);
        var restored=new SettingsAppearance(root);assertEquals(0xFF287A35,restored.accent());assertFalse(restored.animations());
        assertArrayEquals(original,Files.readAllBytes(root.resolve("config/muonium/ui-presentation.json")));
    }
    @Test void pickerDragDoesNotWriteAFileUntilReleaseFlushes()throws Exception {
        var appearance=new SettingsAppearance(root);Path file=root.resolve("config/muonium/settings-appearance.json");
        for(int i=0;i<500;i++)appearance.setAccent(i);
        assertFalse(Files.exists(file));appearance.flush();assertEquals(0xFF0001F3,new SettingsAppearance(root).accent());
    }
    @Test void invalidPreferencesFallBackWithoutOverwritingTheOriginalFile()throws Exception {
        Path p=root.resolve("config/muonium/settings-appearance.json");Files.createDirectories(p.getParent());
        for(String bad:new String[]{"{\"theme\":5}","{\"theme\":\"1\"}","{\"theme\":0.5}","{\"accent\":16777216}","{\"animations\":1}","{\"theme\":0,\"theme\":1}","[]"}){
            Files.writeString(p,bad);assertEquals(new SettingsAppearance.State(0,0x2B4FFF,true),new SettingsAppearance(root).snapshot());
            assertEquals(bad,Files.readString(p));
        }
    }
    @Test void bothLightThemesHaveReadableTextAndMinecraftThemesAreOpaque(){
        for(int i=0;i<4;i++){
            var p=SettingsAppearance.palette(i);
            assertTrue(contrast(p.panel(),p.text())>7,"Primary text must remain readable in every theme");
            assertTrue(contrast(p.panel(),p.secondary())>4.5,"Descriptions must remain readable in every theme");
            if(i>=2)assertEquals(255,p.panel()>>>24);
        }
    }
    private static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
    private static double luminance(int c){double[] rgb={((c>>16)&255)/255.,((c>>8)&255)/255.,(c&255)/255.};
        for(int i=0;i<3;i++)rgb[i]=rgb[i]<=.04045?rgb[i]/12.92:Math.pow((rgb[i]+.055)/1.055,2.4);
        return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2];}
}
