package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** ModMenu's authoritative host controls; the rest of the player keeps its
 * original preferences. A bad edit never partially changes the live player. */
public record HostSettings(boolean enabled, int volume, boolean lyricsEnabled,
                           int lyricsTimeOffsetMs, int repeat, boolean shuffle,
                           int lyricsMode, int lyricsFontSize, int lyricsOffsetY,
                           int stageStyle, int stageScalePercent, int stageSpreadDeg,
                           boolean hudEnabled, boolean dynamicIslandEnabled,
                           boolean musicInfoEnabled,boolean musicLyricsEnabled,boolean spectrumEnabled,
                           int infoXPercent,int infoYPercent,int infoScalePercent,
                           int desktopLyricsXPercent,int desktopLyricsYPercent,int desktopLyricsScalePercent,
                           int islandOffsetX,int islandOffsetY,int islandScalePercent,
                           int spectrumOffsetX,int spectrumOffsetY,int spectrumScalePercent,
                           boolean lyricColorCustom,int lyricColorRed,int lyricColorGreen,int lyricColorBlue,
                           boolean speakerEnabled,boolean worldLyricsOcclusion,boolean smtcPublishEnabled,
                           Set<String> configuredKeys) {
    public int lyricArgb(){return 0xFF000000 | lyricColorRed<<16 | lyricColorGreen<<8 | lyricColorBlue;}
    boolean applies(HostSettings previous,String key,boolean differs) {
        return configuredKeys.contains(key) && (previous==null || !previous.configuredKeys.contains(key) || differs);
    }
    public static HostSettings parse(String json) {
        if (json == null || json.length() > 65536) throw new IllegalArgumentException("Settings size");
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            var keys = new HashSet<String>(); var object = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!keys.add(key)) throw new IllegalArgumentException("Duplicate settings key");
                object.add(key, JsonParser.parseReader(reader));
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing settings data");
            return new HostSettings(bool(object, "enabled", true), number(object, "volume", 70, 0, 100),
                    bool(object, "lyricsEnabled", true), number(object, "lyricsTimeOffsetMs", 0, -60000, 60000),
                    number(object, "repeat", 0, 0, 2), bool(object, "shuffle", false),
                    number(object, "lyricsMode", 1, 0, 2), number(object, "lyricsFontSize", 22, 14, 48),
                    number(object, "lyricsOffsetY", 70, 0, 500),
                    number(object, "stageStyle", 0, 0, 2), number(object, "stageScalePercent", 100, 40, 300),
                    number(object, "stageSpreadDeg", 20, 0, 40),
                    bool(object,"hudEnabled",true),bool(object,"dynamicIslandEnabled",true),
                    bool(object,"musicInfoEnabled",true),bool(object,"musicLyricsEnabled",true),bool(object,"spectrumEnabled",true),
                    number(object,"infoXPercent",0,0,100),number(object,"infoYPercent",43,0,100),number(object,"infoScalePercent",100,50,200),
                    number(object,"desktopLyricsXPercent",50,0,100),number(object,"desktopLyricsYPercent",87,0,100),number(object,"desktopLyricsScalePercent",85,50,200),
                    number(object,"islandOffsetX",0,-1000,1000),number(object,"islandOffsetY",0,-1000,1000),number(object,"islandScalePercent",88,60,135),
                    number(object,"spectrumOffsetX",0,-1000,1000),number(object,"spectrumOffsetY",0,-1000,1000),number(object,"spectrumScalePercent",100,25,200),
                    bool(object,"lyricColorCustom",false),number(object,"lyricColorRed",255,0,255),number(object,"lyricColorGreen",255,0,255),number(object,"lyricColorBlue",255,0,255),bool(object,"speakerEnabled",false),bool(object,"worldLyricsOcclusion",false),bool(object,"smtcPublishEnabled",false),Set.copyOf(keys));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid host settings", e); }
    }
    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        if (!obj.has(key)) return fallback;
        var p = obj.get(key);
        if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key);
        return p.getAsBoolean();
    }
    private static int number(JsonObject obj, String key, int fallback, int min, int max) {
        if (!obj.has(key)) return fallback;
        var p = obj.get(key);
        if (!p.isJsonPrimitive() || !p.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key);
        int value = p.getAsBigDecimal().intValueExact();
        if (value < min || value > max) throw new IllegalArgumentException(key);
        return value;
    }
}
