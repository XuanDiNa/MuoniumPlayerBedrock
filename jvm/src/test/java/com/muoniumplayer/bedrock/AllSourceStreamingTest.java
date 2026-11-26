package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.*;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.utils.Tuple;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class AllSourceStreamingTest {
    static byte[] fixture(String format)throws IOException {
        try(var in=AllSourceStreamingTest.class.getResourceAsStream("/streaming-formats/sine."+format)) {
            if(in==null)throw new IOException("Missing owned sine fixture: "+format);return in.readAllBytes();
        }
    }
    static class Remote implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final ExecutorService workers=Executors.newCachedThreadPool();
        final CountDownLatch release=new CountDownLatch(1);
        final List<String> ranges=new CopyOnWriteArrayList<>();
        final byte[] bytes;final int prefix;boolean ignoreRange,chunked,holdRanges;
        Remote(byte[] bytes,int prefix)throws Exception {
            this.bytes=bytes;this.prefix=prefix;
            server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            server.setExecutor(workers);server.createContext("/audio",e->{try(e){
                String range=e.getRequestHeaders().getFirst("Range");int start=0,end=bytes.length-1;
                if(range!=null&&!ignoreRange){ranges.add(range);String[] parts=range.substring(6).split("-");start=Integer.parseInt(parts[0]);if(parts.length>1)end=Math.min(end,Integer.parseInt(parts[1]));
                    e.getResponseHeaders().add("Content-Range","bytes "+start+"-"+end+"/"+bytes.length);}
                e.getResponseHeaders().add("ETag","\"owned\"");
                e.sendResponseHeaders(range!=null&&!ignoreRange?206:200,chunked?0:end-start+1);
                var out=e.getResponseBody();if(range==null||ignoreRange){int n=Math.min(prefix,bytes.length);out.write(bytes,0,n);out.flush();release.await(25,TimeUnit.SECONDS);out.write(bytes,n,bytes.length-n);}
                else if(holdRanges){out.flush();release.await(25,TimeUnit.SECONDS);out.write(bytes,start,end-start+1);}
                else out.write(bytes,start,end-start+1);
            }catch(Exception ignored){}});server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/audio";}
        public void close(){release.countDown();server.stop(0);workers.shutdownNow();}
    }
    @AfterEach void cleanup(){ProgressiveAudio.closePending();CloudMusic.playThread=null;CloudMusic.currentlyPlaying=null;CloudMusic.playList=new ArrayList<>();PlaybackFiles.global().setPrefetch(false);PlaybackFiles.global().setRetain(false);}
    static Music song(String url,String provider,String reported){
        return new StreamingTransitionTest.Track(url,provider+UUID.randomUUID()) {
            @Override public boolean isNetease(){return provider.equals("netease");}
            @Override public boolean isYouTube(){return provider.equals("youtube");}
            @Override public boolean isBilibili(){return provider.equals("bilibili");}
            @Override public boolean isQishui(){return provider.equals("qishui");}
            @Override public String getSourceId(){return "owned-fixture-video";}
            @Override public long getDuration(){return 16000;}
            @Override public Tuple<String,String> getPlayUrl(){return new Tuple<>(url,reported);}
        };
    }
    static File admit(Music song)throws Exception {
        Object owner=StreamingTransitionTest.owner(song,song);
        return (File)StreamingTransitionTest.call(owner,"bedrockStream",new Class[]{Tuple.class,Music.class,java.util.function.BooleanSupplier.class,boolean.class},song.getPlayUrl(),song,(java.util.function.BooleanSupplier)()->false,false);
    }
    @ParameterizedTest @ValueSource(strings={"aac","m4a","tail.m4a","fmp4","large.fmp4","ogg","wav","float.wav"})
    @Timeout(12) void everySupportedContainerPreparesARealDeckBeforeHttpTail(String format)throws Exception {
        byte[] bytes=fixture(format);int prefix=format.endsWith("wav")?220000:Math.max(18000,bytes.length/3);
        try(var remote=new Remote(bytes,prefix)){
            long started=System.nanoTime();File token=admit(song(remote.url(),"bilibili","unknown"));
            assertNotNull(token,"Playable "+format+" must not return to complete-download preparation");
            assertTrue(System.nanoTime()-started<8_000_000_000L);
            assertEquals(1,remote.release.getCount(),"HTTP tail must still be held");
            var deck=new AudioPlayer(token);try {deck.prepareStart(0);assertTrue(deck.isPrepared());
                assertTrue(deck.getTotalTimeMillis()>14000,"Expose song duration while buffering");
                assertSignal(token);
            }finally{deck.close();}
            assertFalse(token.exists());
        }
    }
    @ParameterizedTest @ValueSource(strings={"netease","qq","gd","bilibili","qishui","kugou","kuwo","migu","spotify","apple-preview","lx"})
    @Timeout(10) void clearAudioIsNotRestrictedToNeteaseOrReportedExtension(String provider)throws Exception {
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))){File f=admit(song(remote.url(),provider,"m4a"));assertNotNull(f,provider);assertTrue(ProgressiveAudio.isProgressive(f));assertEquals(1,remote.release.getCount());ProgressiveAudio.discard(f);}
    }
    @Test @Timeout(10) void invalidQishuiAuthorizationCannotBecomePlainAudio()throws Exception {
        Music m=song("http://127.0.0.1:1/never","qishui","m4a");m.setQishuiPlayAuth("fixture-auth");assertNull(admit(m));
    }
    @Test @Timeout(12) void authorizedQishuiDecryptsSamplesBeforeTheTailArrives()throws Exception {
        try(var remote=new Remote(fixture("encrypted.m4a"),50000)) {
            Music m=song(remote.url(),"qishui","m4a");m.setQishuiPlayAuth("00112233445566778899aabbccddeeff");
            File f=admit(m);assertNotNull(f);assertSignal(f);assertEquals(1,remote.release.getCount());ProgressiveAudio.discard(f);
        }
    }
    @Test @Timeout(10) void encryptedAudioWithoutSourceAuthorizationFallsBack()throws Exception {
        try(var remote=new Remote(fixture("encrypted.m4a"),50000)){assertNull(admit(song(remote.url(),"bilibili","m4a")));}
    }
    @SuppressWarnings("unchecked") static void assertSignal(File token)throws Exception {
        var field=ProgressiveAudio.class.getDeclaredField("pending");field.setAccessible(true);
        var sample=(repackage.com.jsyn.data.FloatSample)((Map<String,?>)field.get(null)).get(token.getAbsolutePath());assertNotNull(sample);
        double peak=0;for(int i=4000;i<12000;i++)peak=Math.max(peak,Math.abs(sample.readDouble(i)));
        assertTrue(peak>.01,"Real decoded sine PCM must be available before the tail; peak="+peak);
    }
    @ParameterizedTest @ValueSource(strings={"m4a","wav"}) @Timeout(12)
    void farSeekReadsExactCodecSamplesAndKeepsThePrefix(String format)throws Exception {
        try(var remote=new Remote(fixture(format),format.equals("wav")?220000:50000)) {
            File f=admit(song(remote.url(),"qq",format));assertNotNull(f);assertSignal(f);
            StreamingSeekTest.ready(f,14000);assertFalse(remote.ranges.isEmpty());assertSignal(f);ProgressiveAudio.discard(f);
        }
    }
    @ParameterizedTest @ValueSource(strings={"aac","ogg","fmp4"}) @Timeout(12)
    void unsupportedRandomAccessUsesSequentialDecodeInsteadOfMp3Indexing(String format)throws Exception {
        try(var remote=new Remote(fixture(format),18000)) {
            File f=admit(song(remote.url(),"bilibili",format));assertNotNull(f);
            assertEquals(1,ProgressiveAudio.state(f,14000));remote.release.countDown();StreamingSeekTest.ready(f,14000);
            assertSignal(f);ProgressiveAudio.discard(f);
        }
    }
    @Test @Timeout(12) void youtubeUsesOriginalWindowRequestsRatherThanABareGet()throws Exception {
        byte[] bytes=fixture("m4a");
        try(var remote=new Remote(bytes,50000)) {
            Music m=song(remote.url()+"?itag=140&clen="+bytes.length,"youtube","m4a");
            // The local response is smaller than a single upstream window. This
            // exercises its real Range/identity code, with no live credentials.
            File f=admit(m);assertNotNull(f);assertFalse(remote.ranges.isEmpty());assertSignal(f);ProgressiveAudio.discard(f);
        }
    }
    @Test @Timeout(12) void youtubeStartsALongTrackWithoutFetchingTheRemainingWindows()throws Exception {
        byte[] bytes=fixture("youtube.m4a");assertTrue(bytes.length>1024*1024);
        try(var remote=new Remote(bytes,50000)) {
            File f=admit(song(remote.url()+"?itag=140&clen="+bytes.length,"youtube","m4a"));assertNotNull(f);assertSignal(f);
            assertEquals(List.of("bytes=0-262143"),remote.ranges,"Only the bounded first YouTube window is needed, not a whole download");
            assertEquals(1,remote.release.getCount());ProgressiveAudio.discard(f);
        }
    }
    @Test @Timeout(10) void chunkedAacDoesNotRequireCompleteLength()throws Exception {
        byte[] bytes=fixture("aac");try(var remote=new Remote(bytes,18000)){remote.chunked=true;File f=admit(song(remote.url(),"bilibili","aac"));assertNotNull(f);assertEquals(1,remote.release.getCount());ProgressiveAudio.discard(f);}
    }
}
