package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.lyrics3d.WorldLyricsSettings;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaHost;

/** Host controls for the original renderers. Does not own audio or media state. */
public final class HudElements {
    private static int islandX,islandY,spectrumX,spectrumY,spectrumScale=100;
    private static boolean hostSpectrumControlled;
    private static boolean customColor;
    private static int customArgb=0xFFFFFFFF;
    private HudElements(){}

    static void apply(HostSettings s,HostSettings p) {
        if(s.applies(p,"musicInfoEnabled",p==null||s.musicInfoEnabled()!=p.musicInfoEnabled())) {
            HudConfig.musicInfoEnabled=s.musicInfoEnabled();
            if(com.muoniumplayer.MuoniumPlayerClient.getInstance().isReady())
                MuoniumPlayerExtension.getInstance().musicInfo.setEnabled(s.musicInfoEnabled());
        }
        if(s.applies(p,"musicLyricsEnabled",p==null||s.musicLyricsEnabled()!=p.musicLyricsEnabled())) {
            HudConfig.musicLyricsEnabled=s.musicLyricsEnabled();
            if(com.muoniumplayer.MuoniumPlayerClient.getInstance().isReady())
                MuoniumPlayerExtension.getInstance().musicLyrics.setEnabled(s.musicLyricsEnabled());
        }
        // This is specifically the in-world spectrum, not the player's own visualization.
        if(s.applies(p,"spectrumEnabled",p==null||s.spectrumEnabled()!=p.spectrumEnabled()))HudConfig.spectrumInHud=s.spectrumEnabled();
        hostSpectrumControlled=s.configuredKeys().contains("spectrumEnabled");
        if(s.applies(p,"infoXPercent",p==null||s.infoXPercent()!=p.infoXPercent()))HudConfig.infoX=s.infoXPercent()/100f;
        if(s.applies(p,"infoYPercent",p==null||s.infoYPercent()!=p.infoYPercent()))HudConfig.infoY=s.infoYPercent()/100f;
        if(s.applies(p,"infoScalePercent",p==null||s.infoScalePercent()!=p.infoScalePercent()))HudConfig.infoScale=s.infoScalePercent()/100f;
        if(s.applies(p,"desktopLyricsXPercent",p==null||s.desktopLyricsXPercent()!=p.desktopLyricsXPercent()))HudConfig.lyricX=s.desktopLyricsXPercent()/100f;
        if(s.applies(p,"desktopLyricsYPercent",p==null||s.desktopLyricsYPercent()!=p.desktopLyricsYPercent()))HudConfig.lyricY=s.desktopLyricsYPercent()/100f;
        if(s.applies(p,"desktopLyricsScalePercent",p==null||s.desktopLyricsScalePercent()!=p.desktopLyricsScalePercent()))HudConfig.lyricScale=s.desktopLyricsScalePercent()/100f;
        if(s.applies(p,"islandScalePercent",p==null||s.islandScalePercent()!=p.islandScalePercent()))HudConfig.dynamicIslandScale=s.islandScalePercent()/100f;
        islandX=s.islandOffsetX();islandY=s.islandOffsetY();spectrumX=s.spectrumOffsetX();spectrumY=s.spectrumOffsetY();spectrumScale=s.spectrumScalePercent();
        customColor=s.lyricColorCustom();customArgb=s.lyricArgb();
    }
    // Resolve at draw time rather than rewriting upstream preferences. Their
    // save buttons cannot accidentally persist the temporary host override.
    public static boolean customSubtitleColor(){return customColor;}
    public static int subtitleColor(int original){return customColor?customArgb:original;}
    static void applyLyricColor(WorldLyricsSettings target,HostSettings s) {
        if(!s.lyricColorCustom())return;
        target.colorCustom=true;target.colorRgb=s.lyricArgb()&0xFFFFFF;
        target.coverColorMode=0;target.coverColorGradient=false;target.keywordColors="";
    }
    /** Island notifications are deferred by the original handler; wrap at the
     * actual draw, not at scheduling, so a later canvas restore cannot lose it. */
    public static void renderIsland(Runnable render) { transformed(islandX,islandY,1,0,0,render); }
    /** Explicit host controls apply only to HUD spectrum; the player retains
     * its own global/inside-player preferences. Old JSON keeps upstream rules. */
    public static boolean spectrumAllowed(boolean globallyEnabled,boolean inHud) {return (globallyEnabled||hostSpectrumControlled)&&inHud;}
    public static void renderSpectrum(Runnable render) {
        transformed(spectrumX,spectrumY,spectrumScale/100f,SkijaHost.guiWidth()/2f,SkijaHost.guiHeight(),render);
    }
    private static void transformed(float x,float y,float scale,float anchorX,float anchorY,Runnable render) {
        var canvas=SkijaHost.canvas();if(canvas==null){render.run();return;}
        int save=canvas.save();
        try{canvas.translate(x,y);canvas.translate(anchorX,anchorY);canvas.scale(scale,scale);canvas.translate(-anchorX,-anchorY);render.run();}
        finally{canvas.restoreToCount(save);}
    }
}
