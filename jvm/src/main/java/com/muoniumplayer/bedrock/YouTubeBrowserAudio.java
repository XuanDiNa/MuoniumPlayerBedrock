package com.muoniumplayer.bedrock;

import com.google.gson.*;
import com.muoniumplayer.core.ncm.music.YouTubeProxySettings;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Official page decodes its own audio; our original engine receives clear PCM. */
public final class YouTubeBrowserAudio {
    private YouTubeBrowserAudio(){}
    // Official-page PCM passed actual full-song and 120s-seek/EOF probes.
    // Keep an explicit diagnostic opt-out and never start it in offline tests.
    public static boolean preferred(){return Boolean.parseBoolean(System.getProperty("muonium.youtube.experimentalBrowserAudio","true"))
        &&!Boolean.getBoolean("muonium.bedrock.offlineTest")&&BrowserAudioSession.browser()!=null;}
    public static String watchUrl(String id){
        if(id==null||!id.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("Invalid YouTube video identity");
        return "https://www.youtube.com/watch?v="+id;
    }
    public static boolean officialUrl(String url){
        try{var uri=URI.create(url);return "https".equals(uri.getScheme())&&"www.youtube.com".equals(uri.getHost())&&"/watch".equals(uri.getPath());}
        catch(Exception invalid){return false;}
    }
    public static File prepare(String videoId,java.util.function.BooleanSupplier cancelled,String key,boolean ahead){
        Source source=null;
        try {
            String proxy=YouTubeProxySettings.isEnabled()?YouTubeProxySettings.getEndpoint():null;
            var cookies=com.muoniumplayer.core.ncm.music.YouTubeSessionStore.parseCookieInput(
                com.muoniumplayer.core.ncm.music.YouTubeSessionStore.cookieHeader());
            source=new Source(URI.create(watchUrl(videoId)),videoId,proxy,cookies,cancelled,true);
            return ProgressiveAudio.prepareSource(source,cancelled,key,ahead);
        }catch(Exception failure){if(source!=null)source.close();
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
            System.err.println("YOUTUBE_BROWSER state=failed reason="+failure.getClass().getSimpleName());
            throw new IllegalStateException("YouTube 官方播放未就绪："+(failure instanceof PlaybackFailure?failure.getMessage():"请检查 YouTube 代理、登录或验证页面"),failure);
        }
    }
    static Source fixture(URI page)throws Exception {
        if(!"http".equals(page.getScheme())||!"127.0.0.1".equals(page.getHost()))throw new IllegalArgumentException("Owned fixture only");
        return new Source(page,"",null,Map.of());
    }
    static final class PlaybackFailure extends IOException {PlaybackFailure(String message){super(message);}}
    static final class Source implements ProgressiveAudio.PositionedSource {
        final BrowserAudioSession session;
        final int rate,frames;
        final AtomicLong target=new AtomicLong(-1);
        final ArrayDeque<JsonObject> queue=new ArrayDeque<>();
        volatile boolean closed;boolean ended;long position,generation;volatile long cursor;
        Source(URI page,String id,String proxy,Map<String,String> cookies)throws Exception {
            this(page,id,proxy,cookies,()->false);
        }
        Source(URI page,String id,String proxy,Map<String,String> cookies,java.util.function.BooleanSupplier cancelled)throws Exception {
            this(page,id,proxy,cookies,cancelled,false);
        }
        Source(URI page,String id,String proxy,Map<String,String> cookies,java.util.function.BooleanSupplier cancelled,boolean completeLogin)throws Exception {
            session=new BrowserAudioSession(proxy,completeLogin,cancelled);
            try {
                if(completeLogin)session.restoreMissingLogin(cookies);else session.cookies(cookies);
                session.navigate(page);
                String script;try(var in=YouTubeBrowserAudio.class.getResourceAsStream("/muonium/bedrock/browser-audio.js")){
                    if(in==null)throw new IOException("Missing browser audio bridge");script=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("__VIDEO_ID__",new Gson().toJson(id));}
                long deadline=System.nanoTime()+25_000_000_000L;JsonObject state=null;boolean advertising=false;
                while(System.nanoTime()<deadline){
                    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Browser preparation cancelled");
                    state=session.evaluate(script);
                    if("advertisement".equals(string(state,"state"))){
                        if(!advertising){advertising=true;deadline=System.nanoTime()+180_000_000_000L;
                            System.out.println("YOUTUBE_BROWSER state=official-advertisement action=wait");}
                        session.skipOfferedAd();Thread.sleep(200);continue;
                    }
                    check(state);
                    if("ready".equals(string(state,"state")))break;Thread.sleep(100);
                }
                if(state==null||!"ready".equals(string(state,"state")))throw new PlaybackFailure("官方页面加载超时");
                rate=state.get("rate").getAsInt();double seconds=state.get("duration").getAsDouble();
                if(rate!=48000||!Double.isFinite(seconds)||seconds<1||seconds>12*3600||seconds*rate>Integer.MAX_VALUE/16)
                    throw new PlaybackFailure("官方音频长度或采样率不受支持");
                frames=(int)Math.ceil(seconds*rate);
                System.out.println("YOUTUBE_BROWSER state=ready transport=official-page-pcm rate="+rate+" frames="+frames);
            }catch(Exception failure){session.close();throw failure;}
        }
        static String string(JsonObject o,String key){return o.has(key)?o.get(key).getAsString():"";}
        static void check(JsonObject state)throws PlaybackFailure {
            String status=string(state,"state");
            if(status.equals("restricted"))throw new PlaybackFailure("官方页面要求登录或验证，请在专用登录浏览器完成后重试");
            if(status.equals("advertisement"))throw new PlaybackFailure("官方页面正在播放广告，请使用官方播放器完成后重试；不会绕过广告");
            if(status.equals("wrong-video"))throw new PlaybackFailure("官方页面已切换曲目");
            if(status.equals("media-error"))throw new PlaybackFailure("官方播放器报告媒体错误");
        }
        public void request(long frame){if(frame>=0&&frame<frames&&Math.abs(frame-cursor)>2L*rate)target.set(frame);}
        public boolean seekPending(){return target.get()>=0;}
        public long position(){return position;}
        public float[] next()throws Exception {
            long deadline=System.nanoTime()+25_000_000_000L;
            boolean advertising=false;
            while(!closed){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Browser audio released");
                long wanted=target.getAndSet(-1);
                if(wanted>=0){
                    cursor=wanted;queue.clear();ended=false;
                    var result=session.evaluate("(()=>{let q=window.__muAudio;q.seek("+wanted+");return {g:q.generation};})()");
                    generation=result.get("g").getAsLong();
                }
                if(!queue.isEmpty()){
                    var packet=queue.removeFirst();if(packet.get("g").getAsLong()!=generation)continue;
                    byte[] bytes=Base64.getDecoder().decode(packet.get("pcm").getAsString());
                    if(bytes.length==0||bytes.length>4096*8||bytes.length%8!=0)throw new IOException("Browser PCM bound");
                    long at=packet.get("at").getAsLong();
                    if(at<0||at>frames||at+bytes.length/8>frames)throw new IOException("Browser PCM timeline bound");
                    float[] out=new float[bytes.length/4];var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    for(int i=0;i<out.length;i++){float sample=data.getFloat();if(!Float.isFinite(sample)||Math.abs(sample)>2)throw new IOException("Invalid browser PCM");out[i]=sample;}
                    position=at;cursor=at+out.length/2;return out;
                }
                if(ended)return null;
                var state=session.evaluate("window.__muAudio.pull()");
                if("advertisement".equals(string(state,"state"))){
                    if(!advertising){advertising=true;deadline=System.nanoTime()+180_000_000_000L;
                        System.out.println("YOUTUBE_BROWSER state=official-advertisement action=wait");}
                    if(System.nanoTime()>deadline)throw new PlaybackFailure("官方页面广告等待超时");
                    session.skipOfferedAd();Thread.sleep(200);continue;
                }
                if(advertising){advertising=false;deadline=System.nanoTime()+25_000_000_000L;}
                check(state);
                if(state.has("blocks"))for(var block:state.getAsJsonArray("blocks"))queue.add(block.getAsJsonObject());
                if(queue.size()>8)throw new IOException("Browser PCM queue bound");
                ended=state.has("ended")&&state.get("ended").getAsBoolean();
                if(queue.isEmpty()&&!ended){if(System.nanoTime()>deadline){
                    System.out.println("YOUTUBE_BROWSER state=buffer-timeout paused="+string(state,"paused")+" held="+string(state,"held")
                        +" seeking="+string(state,"seeking")+" ready="+string(state,"ready")+" context="+string(state,"context")+" seconds="+string(state,"time")
                        +" callbacks="+string(state,"callbacks")+" sameVideo="+string(state,"sameVideo")+" frames="+string(state,"cursor"));
                    throw new PlaybackFailure("官方页面音频缓冲超时");}Thread.sleep(20);}
            }throw new InterruptedIOException("Browser audio released");
        }
        public int frames(){return frames;}public int channels(){return 2;}public int rate(){return rate;}public String codec(){return "wav";}
        public void close(){closed=true;session.close();}
    }
}
