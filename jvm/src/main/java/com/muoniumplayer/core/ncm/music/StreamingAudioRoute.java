package com.muoniumplayer.core.ncm.music;

import com.muoniumplayer.bedrock.ProgressiveAudio;
import com.muoniumplayer.core.ncm.music.dto.Music;
import java.io.File;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Source-specific authorization stays beside the original source services. */
public final class StreamingAudioRoute {
    private StreamingAudioRoute(){}
    public static File prepare(String url,Music song,BooleanSupplier cancelled,String key,boolean ahead) {
        if(url==null||song==null||!url.matches("(?i)^https?://.*"))return null;
        long at=System.nanoTime();
        if(song.isYouTube()&&com.muoniumplayer.bedrock.YouTubeBrowserAudio.officialUrl(url)){
            File file=com.muoniumplayer.bedrock.YouTubeBrowserAudio.prepare(song.getSourceId(),cancelled,key,ahead);
            System.out.println("PROGRESSIVE_ROUTE source=youtube mode=official-browser-pcm elapsedMs="+(System.nanoTime()-at)/1_000_000);
            return file;
        }
        File file=ProgressiveAudio.prepare(url,cancelled,key,ahead,song.getDuration(),song.isYouTube()?song.getSourceId():null,song.isQishui()?song.getQishuiPlayAuth():null);
        String provider=song.isBilibili()?"bilibili":song.isYouTube()?"youtube":song.isQishui()?"qishui":song.isNetease()?"netease":song.isQQ()?"qq":song.isGd()?"gd":song.isKuGou()?"kugou":song.isKuwo()?"kuwo":song.isMigu()?"migu":song.isApple()?"apple-http":song.isSpotify()?"spotify":"other-http";
        System.out.println("PROGRESSIVE_ROUTE source="+provider+" mode="+(file==null?"whole-fallback":"progressive")+" elapsedMs="+(System.nanoTime()-at)/1_000_000);
        return file;
    }
    public static Map<String,String> headers(String url){
        try{String host=java.net.URI.create(url).getHost();if(host==null)return null;host=host.toLowerCase(java.util.Locale.ROOT);
            for(String domain:new String[]{"bilivideo.com","bilivideo.cn","bilibili.com","akamaized.net"})
                if(host.equals(domain)||host.endsWith("."+domain))return AudioStreamHeaders.forUrl(url);
        }catch(IllegalArgumentException ignored){}return null;
    }
}
