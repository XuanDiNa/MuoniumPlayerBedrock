package com.muoniumplayer;

import com.muoniumplayer.api.OpenAPI;
import com.muoniumplayer.api.impl.OpenAPIImpl;
import com.muoniumplayer.api.impl.WindowResolutionImpl;
import com.muoniumplayer.api.events.EventRender2D;
import com.muoniumplayer.core.MuoniumPlayerExtension;

/** Bedrock lifecycle adapter; all music and screen implementations stay upstream. */
public final class MuoniumPlayerClient {
    public static final String MOD_ID = "muonium";
    public static final String MOD_NAME = "MuoniumPlayer Bedrock";
    public static final String MOD_VERSION = "2.5.2-bedrock-0.12.0-test2-mc2645-2651";
    public static final String MC_VERSION = "1.26.52.03";
    private static final MuoniumPlayerClient INSTANCE = new MuoniumPlayerClient();
    private OpenAPIImpl api;
    public static MuoniumPlayerClient getInstance() { return INSTANCE; }
    public static String loaderId() { return "levilauncher"; }
    public void init() {
        if (api != null) throw new IllegalStateException("Already initialized");
        api = new OpenAPIImpl(); OpenAPI.setInstance(api);
        MuoniumPlayerExtension.getInstance().init(api);
        if(!Boolean.getBoolean("muonium.bedrock.offlineTest"))com.muoniumplayer.bedrock.PartyBridge.start();
    }
    public void tick() {
        for (var handler : api.getEventHandlers()) handler.onLoop();
        for (var module : api.getModules()) {
            var handler = module.getEventHandler();
            if (handler != null) handler.onLoop();
            if (module.isEnabled()) module.onTick();
        }
        if (!Boolean.getBoolean("muonium.bedrock.offlineTest"))
            com.muoniumplayer.core.ncm.music.ExternalPlayback.onClientTick();
    }
    public void renderHud() {
        var event = new EventRender2D(new WindowResolutionImpl());
        for (var handler : api.getEventHandlers()) handler.onRender2D(event);
        for (var module : api.getModules()) {
            var handler=module.getEventHandler();
            if(handler!=null)handler.onRender2D(event);
        }
        for(var widget:api.getWidgets())if(widget.renderPredicate())widget.render();
    }
    public boolean isReady() { return api != null; }
}
