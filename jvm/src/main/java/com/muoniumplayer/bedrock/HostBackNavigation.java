package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.mc.GuiScreen;

/** Render-thread Esc intent. Normal modal/child dispatch always runs first. */
public final class HostBackNavigation {
    private static Runnable externalReturn;
    private static GuiScreen retiringRoot;
    private HostBackNavigation() {}
    static void bind(Runnable action) { clear(); externalReturn=action; }
    static void clear() { externalReturn=null; retiringRoot=null; }
    public static void afterKey(GuiScreen source,int code,boolean wasClosing) {
        if(code!=Keyboard.KEY_ESCAPE||externalReturn==null)return;
        var current=BedrockClient.getInstance().gui.screen();
        if(current==null)requestReturn();
        else if(current==source&&!wasClosing&&source.hostBackClosing())retiringRoot=source;
    }
    public static void screenChanged(GuiScreen previous,GuiScreen next) {
        if(retiringRoot!=previous||retiringRoot==null)return;
        retiringRoot=null;
        if(next==null)requestReturn();
    }
    private static void requestReturn() {
        var action=externalReturn;clear();
        // Only queue the owned handoff; no native calls or background shutdown.
        if(action!=null)action.run();
    }
}
