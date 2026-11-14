package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.screens.ncm.IslandSearchOverlay;
import com.muoniumplayer.core.settings.HudConfig;

/** Adopts the upstream search Screen into the companion's input lifetime. */
final class HostIslandSearch {
    static void open(OverlaySession session) {
        if(!HudConfig.islandSearchEnabled)return;
        if(BedrockClient.getInstance().gui.screen() instanceof IslandSearchOverlay){session.close();return;}
        session.close();IslandSearchOverlay.toggle();
        var page=BedrockClient.getInstance().gui.screen();if(page!=null)session.open(page);
    }
}
