package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiButton;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;

/** Explicit local operations formerly exposed by the ModMenu Workspace. */
public final class HostToolsScreen extends GuiScreen {
    private static Runnable ordinarySettings;
    private final boolean entry;
    public HostToolsScreen(){this(false);}
    private HostToolsScreen(boolean entry){this.entry=entry;}
    static HostToolsScreen entry(){return new HostToolsScreen(true);}
    static void ordinarySettings(Runnable action){ordinarySettings=action;}
    public static void openPlayer(){BedrockClient.getInstance().gui.setScreen(NCMScreen.getInstance());}
    public static void openHudEditor(){BedrockClient.getInstance().gui.setScreen(new HostHudEditor());}
    public static void openPresets(){
        var client=BedrockClient.getInstance();
        var parent=client.gui.screen();
        client.gui.setScreen(new com.muoniumplayer.core.screens.hud.GuiPresetSettings(
                ()->client.gui.setScreen(parent),com.muoniumplayer.core.screens.hud.PresetSettingsActions.Mode.MANAGE));
    }
    public static void openMiniPlayer(){BedrockClient.getInstance().gui.setScreen(new HostMiniPlayer());}
    public static void openIslandSearch(){
        if(HudConfig.islandSearchEnabled)com.muoniumplayer.core.screens.ncm.IslandSearchOverlay.toggle();
    }
    @Override public void initGui() {
        int w=Math.max(100,Math.min(360,width-24)),h=Math.max(16,Math.min(32,(height-76)/8));
        int x=(width-w)/2,y=Math.max(40,(height-7*(h+6))/2);
        String[] labels={"打开播放器 / Player","HUD 编辑 / Edit HUD","预设管理 / Presets","迷你播放器 / Mini player",
                HudConfig.islandSearchEnabled?"岛内搜索 / Island search":"岛内搜索（设置中已关闭）",
                "普通设置 / Settings",entry?"关闭 / Close":"返回播放器 / Back"};
        for(int i=0;i<labels.length;i++) {
            var button=new GuiButton(i,x,y+i*(h+6),w,h,labels[i]);
            if(i==4)button.enabled=HudConfig.islandSearchEnabled;
            if(i==5)button.enabled=ordinarySettings!=null;
            buttonList.add(button);
        }
    }
    @Override public void drawScreen(int x,int y,float partial) {
        drawDefaultBackground();
        var font=FontManager.pf18;
        String title="播放器工具 / Player tools";
        font.drawString(title,(width-font.getStringWidth(title))/2d,14,0xFFFFFFFF);
        for(var button:buttonList) {
            if(!button.visible)continue;
            try(var paint=new Paint().setAntiAlias(true).setColor(button.mousePressed(x,y)?0xFF3D526A:0xFF263342)) {
                SkijaHost.canvas().drawRRect(RRect.makeXYWH(button.xPosition,button.yPosition,button.width,button.height,6),paint);
            }
            font.drawString(button.displayString,button.xPosition+(button.width-font.getStringWidth(button.displayString))/2d,
                    button.yPosition+(button.height-font.getFontHeight())/2d,button.enabled?0xFFFFFFFF:0xFF8C98A5);
        }
    }
    @Override protected void actionPerformed(GuiButton button) {
        switch(button.id) {
            case 0 -> openPlayer();
            case 1 -> openHudEditor();
            case 2 -> openPresets();
            case 3 -> openMiniPlayer();
            case 4 -> openIslandSearch();
            case 5 -> {if(ordinarySettings!=null)ordinarySettings.run();}
            case 6 -> onClose();
            default -> { }
        }
    }
    @Override public void onClose(){if(entry)closeScreen();else mc.gui.setScreen(NCMScreen.getInstance());}
}
