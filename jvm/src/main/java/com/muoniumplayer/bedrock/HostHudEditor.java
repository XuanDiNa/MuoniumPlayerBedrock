package com.muoniumplayer.bedrock;

/** Original live editor over the game, with its own opaque settings panel. */
public final class HostHudEditor extends com.muoniumplayer.core.screens.hud.GuiHudEditorPro {
    private final com.muoniumplayer.compat.mc.GuiScreen parent;
    public HostHudEditor(){this(null);}
    HostHudEditor(com.muoniumplayer.compat.mc.GuiScreen parent){this.parent=parent;}
    public boolean transparentHost(){return true;}
    @Override public void closeScreen(){mc.gui.setScreen(parent);if(parent instanceof com.muoniumplayer.core.screens.ncm.NCMScreen player)player.openMpDirect();}
}
