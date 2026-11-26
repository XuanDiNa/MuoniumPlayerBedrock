package com.muoniumplayer.core.ncm.music;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.spotify.SpotifyWebApiClient;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import java.util.*;

/** Short-lived, bounded failure descriptions. No URLs, credentials or disk cache. */
public final class SourceFailureDetails {
    private record Failure(String message,long expires) {}
    private static final Map<String,Failure> failures=new LinkedHashMap<>();
    private static final long TTL_NANOS=120_000_000_000L;
    private SourceFailureDetails() {}

    public static final class ApiHttpException extends IllegalStateException {
        public final String source;
        public final int status;
        public final String stage;
        public ApiHttpException(String source,int status) {
            this(source,status,"api");
        }
        public ApiHttpException(String source,int status,String stage) {
            super("HTTP "+status);this.source=source;this.status=status;
            this.stage=Set.of("view","playurl").contains(stage)?stage:"api";
        }
    }
    private static String key(String source,String id) {
        String value=id==null?"":id.trim();
        return source+":"+(source.equals("kugou")?value.toLowerCase(Locale.ROOT):value);
    }
    private static synchronized void remember(String source,String id,String message) {
        if(id==null||id.isBlank()||id.length()>512)return;
        failures.put(key(source,id),new Failure(message,System.nanoTime()+TTL_NANOS));
        while(failures.size()>128)failures.remove(failures.keySet().iterator().next());
    }
    public static synchronized void clear(String source,String id) {failures.remove(key(source,id));}
    public static synchronized String lookup(String source,String id) {
        String key=key(source,id);Failure failure=failures.get(key);
        if(failure==null)return "";
        if(System.nanoTime()-failure.expires>=0){failures.remove(key);return "";}
        return failure.message;
    }
    public static void bilibiliFailed(String id,Throwable failure) {
        if(failure instanceof ApiHttpException http&&http.source.equals("bilibili")) {
            String step=http.stage.equals("view")?"影片信息":http.stage.equals("playurl")?"播放地址":"当前请求";
            remember("bilibili",id,http.status==412||http.status==429
                    ?"Bilibili 拒绝了"+step+"请求（HTTP "+http.status+"），已登录也可能受到接口风控"
                    :"Bilibili 播放接口请求失败（HTTP "+http.status+"）");
            System.err.println("MUSIC_SOURCE_FAILURE source=bilibili stage="+http.stage+" http="+http.status);
        }
    }
    public static void kugouDenied(String id,JsonObject payload) {
        if(payload==null||!payload.has("fail_process")||!payload.get("fail_process").isJsonArray())return;
        for(JsonElement element:payload.getAsJsonArray("fail_process")) {
            if(!element.isJsonPrimitive()||!element.getAsJsonPrimitive().isString())continue;
            if(Set.of("pkg","buy","vip").contains(element.getAsString())) {
                kugouRestricted(id);return;
            }
        }
    }
    public static void kugouRestricted(String id) {
        remember("kugou",id,"当前酷狗账号未获歌曲或音质授权，请检查登录与会员权益");
    }
    public static void youtubeReauthRequired(String id) {
        remember("youtube",id,"YouTube 官方页面未接受当前登录会话，请在 YouTube 账号页使用浏览器登录重新登录");
    }
    public static String forSong(Music song) {
        if(song==null)return "没有可用音频源";
        if(song.isQQ()&&song.getQqResultCode()>0) {
            return switch(song.getQqResultCode()) {
                case 104003 -> "QQ 音乐取流权限校验未通过（104003），请检查登录状态与歌曲／音质授权";
                case 104004 -> "QQ 音乐取流 VKey 失败，请稍后重试";
                case 104013 -> "QQ 音乐登录设备已达上限，请在 App 内移除设备";
                default -> "QQ 音乐取流接口拒绝请求（代码 "+song.getQqResultCode()+"）";
            };
        }
        String reason=song.isBilibili()?lookup("bilibili",song.getSourceId())
                :song.isKuGou()?lookup("kugou",song.getSourceId())
                :song.isYouTube()?lookup("youtube",song.getSourceId()):"";
        return reason.isEmpty()?"没有可用音频源，请检查登录、网络与歌曲权限":reason;
    }
    public static void spotifySearchFailed(Throwable failure) {
        String reason="Spotify 搜索连接失败，请检查网络后重试";
        if(failure instanceof SpotifyWebApiClient.SpotifyApiException api) {
            if(api.reauthRequired||"NOT_LOGGED_IN".equals(api.reason)||"REAUTH_REQUIRED".equals(api.reason))
                reason="请先在账号页登录并授权 Spotify，再重新搜索";
            else if(api.statusCode==403)reason="Spotify 拒绝搜索访问，请检查授权与应用访问权限";
            else if(api.statusCode==429)reason="Spotify 搜索请求过于频繁，请稍后重试";
            else reason="Spotify 搜索接口请求失败（HTTP "+api.statusCode+"）";
        }
        // The existing notice publisher only updates its synchronized notice queue.
        // No drawing, login flow or network operation runs here.
        DownloadDynamicIsland.showGdSearchFailure("Spotify",reason);
    }
}
