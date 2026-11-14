package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.core.settings.HudConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Saves only explicit editor/preset changes into the matching host controls.
 * A newer ModMenu value wins. Never serializes the upstream account/config tree. */
public final class HostHudSync {
    private static HostHudSync active;
    private final Path path;
    private JsonObject host=new JsonObject(),ui=new JsonObject();
    public HostHudSync(Path path){this.path=path.toAbsolutePath().normalize();}
    static void bind(Path path){active=new HostHudSync(path);}
    static void observe(String text){if(active!=null)active.observed(parse(text),capture());}
    public static void persist(){if(active!=null)active.save(capture());}
    synchronized void observed(JsonObject host,JsonObject ui){this.host=host.deepCopy();this.ui=ui.deepCopy();}
    synchronized boolean save(JsonObject next) {
        Path temporary=null;
        try {
            byte[] before=Files.readAllBytes(path);
            if(before.length>65536)return false;
            JsonObject current=parse(new String(before,StandardCharsets.UTF_8));
            boolean changed=false;
            // Only these UI values can be bridged; caller objects cannot add keys.
            for(String key:ALLOWED) {
                JsonElement value=next.get(key);
                if(value==null||Objects.equals(value,ui.get(key)))continue;
                if(Objects.equals(current.get(key),host.get(key))) {
                    current.add(key,value.deepCopy());changed=true;
                }
            }
            if(changed) {
                String text=new GsonBuilder().setPrettyPrinting().create().toJson(current)+"\n";
                HostSettings.parse(text); // Complete host validation before any write.
                temporary=Files.createTempFile(path.getParent(),".muonium-hud-",".tmp");
                Files.writeString(temporary,text,StandardCharsets.UTF_8);
                if(!Arrays.equals(before,Files.readAllBytes(path)))return false;
                Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }
            host=current.deepCopy();
            ui=next.deepCopy();return true;
        }catch(Exception invalid){return false;}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}}
    }
    private static final Set<String> ALLOWED=Set.of("musicInfoEnabled","musicLyricsEnabled","spectrumEnabled","dynamicIslandEnabled",
        "infoXPercent","infoYPercent","infoScalePercent","desktopLyricsXPercent","desktopLyricsYPercent","desktopLyricsScalePercent",
        "islandScalePercent","stageScalePercent","stageSpreadDeg","stageStyle","lyricsEnabled","smtcPublishEnabled");
    static JsonObject capture() {
        var out=new JsonObject();
        out.addProperty("musicInfoEnabled",HudConfig.musicInfoEnabled);out.addProperty("musicLyricsEnabled",HudConfig.musicLyricsEnabled);
        out.addProperty("spectrumEnabled",HudConfig.spectrumInHud);out.addProperty("dynamicIslandEnabled",HudConfig.dynamicIslandEnabled);
        out.addProperty("lyricsEnabled",HudConfig.worldLyricsEnabled);
        out.addProperty("smtcPublishEnabled",com.muoniumplayer.core.ncm.music.SmtcPublisher.enabled());
        out.addProperty("infoXPercent",percent(HudConfig.infoX,0,100));out.addProperty("infoYPercent",percent(HudConfig.infoY,0,100));
        out.addProperty("infoScalePercent",percent(HudConfig.infoScale,50,200));
        out.addProperty("desktopLyricsXPercent",percent(HudConfig.lyricX,0,100));out.addProperty("desktopLyricsYPercent",percent(HudConfig.lyricY,0,100));
        out.addProperty("desktopLyricsScalePercent",percent(HudConfig.lyricScale,50,200));
        out.addProperty("islandScalePercent",percent(HudConfig.dynamicIslandScale,60,135));
        out.addProperty("stageScalePercent",percent(HudConfig.worldLyricsScale,40,300));
        out.addProperty("stageSpreadDeg",Math.round(Math.max(0,Math.min(40,HudConfig.worldLyricsSpreadDeg))));
        out.addProperty("stageStyle",HudConfig.worldLyricsHuiguang?2:HudConfig.worldLyricsCadenza?1:0);
        return out;
    }
    private static int percent(float value,int low,int high){return Math.max(low,Math.min(high,Math.round(value*100)));}
    private static JsonObject parse(String text){HostSettings.parse(text);return JsonParser.parseString(text).getAsJsonObject();}
}
