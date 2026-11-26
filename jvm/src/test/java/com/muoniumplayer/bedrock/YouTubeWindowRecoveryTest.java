package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.YouTubeMusicService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback CDN bodies and metadata. No YouTube account or live service. */
@Timeout(12)
class YouTubeWindowRecoveryTest {
    Object savedClient, savedEndpoint,savedSession,savedLoaded;
    static java.lang.reflect.Field sessionField(String name)throws Exception {
        var f=com.muoniumplayer.core.ncm.music.YouTubeSessionStore.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    static java.lang.reflect.Field field(String name)throws Exception {
        var f=YouTubeMusicService.class.getDeclaredField(name);f.setAccessible(true);return f;
    }
    @BeforeEach void save()throws Exception {
        savedClient=field("cachedClient").get(null);savedEndpoint=field("cachedProxyEndpoint").get(null);
        savedSession=sessionField("data").get(null);savedLoaded=sessionField("loaded").get(null);
        var type=Class.forName("com.muoniumplayer.core.ncm.music.YouTubeSessionStore$Data");
        var constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
        sessionField("data").set(null,constructor.newInstance());sessionField("loaded").set(null,true);
    }
    @AfterEach void restore()throws Exception {
        field("cachedClient").set(null,savedClient);field("cachedProxyEndpoint").set(null,savedEndpoint);
        sessionField("data").set(null,savedSession);sessionField("loaded").set(null,savedLoaded);
        ProgressiveAudio.closePending();
    }
    void route(Origin origin)throws Exception {
        field("cachedClient").set(null,new RoutedClient(origin));field("cachedProxyEndpoint").set(null,"");
    }
    static final class Origin implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService workers=Executors.newCachedThreadPool();
        final List<String> ranges=new CopyOnWriteArrayList<>(),rangeHeaders=new CopyOnWriteArrayList<>();
        final AtomicInteger resolves=new AtomicInteger(), calls=new AtomicInteger();
        final byte[] audio;volatile boolean ignoreContinuation, changedTotal,changedTag,rejectOld,resolveUnavailable;
        volatile int breakCall=-1,failCall=-1,segmentLimit=Integer.MAX_VALUE,refreshLengthDelta;
        volatile boolean ignoreAll,invalidStart;
        volatile boolean queryContinuation,rejectQuery,rejectDuplicateRange,queryStatus200,omitQueryContentRange;
        volatile int queryOnlyAfter=Integer.MAX_VALUE;
        volatile int rejectAbove=Integer.MAX_VALUE, cooldownAfter=Integer.MAX_VALUE;
        volatile long retryAt;
        volatile int cooldownStatus=429;
        volatile String retryAfterValue="1";
        final CountDownLatch throttled=new CountDownLatch(1);
        final List<String> queries=new CopyOnWriteArrayList<>();
        Origin(byte[] data)throws Exception {
            audio=data;server.setExecutor(workers);
            server.createContext("/resolve",e->{try(e){resolves.incrementAndGet();
                if(resolveUnavailable){e.sendResponseHeaders(503,-1);return;}
                String fresh=url("fresh").replace("clen="+audio.length,"clen="+(audio.length+refreshLengthDelta));
                String body="{\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"adaptiveFormats\":[{\"itag\":140,\"url\":\""+fresh+"\"}]}}";
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
            }});
            server.createContext("/audio",e->{try(e){
                String range=e.getRequestHeaders().getFirst("Range");rangeHeaders.add(Objects.toString(range,"none"));
                String query=e.getRequestURI().getRawQuery();queries.add(query);
                String queryRange=null;
                for(String part:query.split("&"))if(part.startsWith("range="))queryRange=part.substring(6);
                if(range==null&&queryRange==null){e.sendResponseHeaders(400,-1);return;}
                ranges.add(range==null?"bytes="+queryRange:range);
                String[] bounds=(range==null?queryRange:range.substring(6)).split("-");
                int start=Integer.parseInt(bounds[0]),end=Math.min(audio.length-1,Integer.parseInt(bounds[1]));
                int call=calls.incrementAndGet();
                if(rejectDuplicateRange&&queryRange!=null&&range!=null){e.sendResponseHeaders(416,-1);return;}
                if(start>=queryOnlyAfter&&queryRange==null){e.sendResponseHeaders(403,-1);return;}
                if(call==failCall){e.sendResponseHeaders(503,-1);return;}
                if(rejectQuery&&query.contains("range=")){e.sendResponseHeaders(400,-1);return;}
                if(start>=cooldownAfter){
                    if(retryAt==0)retryAt=System.nanoTime()+1_000_000_000L;
                    if(System.nanoTime()<retryAt){
                        if(retryAfterValue!=null)e.getResponseHeaders().set("Retry-After",retryAfterValue);
                        e.sendResponseHeaders(cooldownStatus,-1);throttled.countDown();return;
                    }
                }
                if(end-start+1>rejectAbove){e.sendResponseHeaders(403,-1);return;}
                if(queryContinuation&&start>0&&!query.contains("range="+start+"-"+end)){e.sendResponseHeaders(403,-1);return;}
                if(rejectOld&&start>0&&e.getRequestURI().getQuery().contains("token=old")){e.sendResponseHeaders(403,-1);return;}
                boolean ignore=ignoreAll||ignoreContinuation&&start>0;if(ignore){start=0;end=audio.length-1;}
                else end=(int)Math.min(end,(long)start+segmentLimit-1);
                if(!ignore&&!(omitQueryContentRange&&queryRange!=null))e.getResponseHeaders().set("Content-Range","bytes "+(invalidStart&&start>0?0:start)+"-"+end+"/"+(changedTotal&&start>0?audio.length+1:audio.length));
                e.getResponseHeaders().set("ETag",changedTag&&start>0?"\"changed\"":"\"original\"");
                e.sendResponseHeaders(ignore||queryStatus200&&queryRange!=null?200:206,end-start+1);
                e.getResponseBody().write(audio,start,call==breakCall?Math.min(10000,end-start+1):end-start+1);
            }catch(IOException ignored){}});server.start();
        }
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        String url(String token){return base()+"/audio?itag=140&clen="+audio.length+"&token="+token;}
        public void close(){server.stop(0);workers.shutdownNow();}
    }
    /** Only route metadata's fixed HTTPS endpoint to our owned origin. Audio
     * still uses the production exchange/body/cancellation code over real HTTP. */
    static class RoutedClient extends HttpClient {
        final HttpClient real=HttpClient.newBuilder().proxy(new ProxySelector(){
            public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
            public void connectFailed(URI uri,SocketAddress address,IOException failure){}
        }).build();final Origin origin;
        RoutedClient(Origin origin){this.origin=origin;}
        HttpRequest route(HttpRequest request){
            if(!request.uri().getHost().equals("www.youtube.com"))return request;
            return HttpRequest.newBuilder(URI.create(origin.base()+"/resolve"))
                .POST(request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){return real.sendAsync(route(r),h);}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){return real.sendAsync(route(r),h,p);}
        public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h)throws IOException,InterruptedException{return real.send(route(r),h);}
        public Optional<CookieHandler> cookieHandler(){return real.cookieHandler();}
        public Optional<Duration> connectTimeout(){return real.connectTimeout();}
        public Redirect followRedirects(){return real.followRedirects();}
        public Optional<ProxySelector> proxy(){return real.proxy();}
        public SSLContext sslContext(){return real.sslContext();}
        public SSLParameters sslParameters(){return real.sslParameters();}
        public Optional<Authenticator> authenticator(){return real.authenticator();}
        public Version version(){return real.version();}
        public Optional<Executor> executor(){return real.executor();}
    }
    static byte[] payload(){byte[] bytes=new byte[262144+2*1048576+127];new Random(42).nextBytes(bytes);return bytes;}
    @Test void entireTrackContinuesAcrossWindowsWithoutRequiringAnotherMetadataRequest()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.resolveUnavailable=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertArrayEquals(origin.audio,stream.readAllBytes());
            }
            assertEquals(0,origin.resolves.get(),"A valid CDN URL must keep playing even if the metadata service is unavailable");
            assertEquals(List.of("bytes=0-262143","bytes=262144-1310719","bytes=1310720-2359295","bytes=2359296-2359422"),origin.ranges);
        }
    }
    @Test void closingAWindowStreamCannotReopenItOnTheNextRead()throws Exception {
        try(var origin=new Origin(payload())){route(origin);
            var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"));
            stream.readNBytes(10);int before=origin.calls.get();stream.close();
            assertThrows(IOException.class,stream::read);assertEquals(before,origin.calls.get());
        }
    }
    @Test void aCdnRequiringUrlByteRangesContinuesWithoutMetadataOrChangingSignedParameters()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.resolveUnavailable=true;origin.queryContinuation=true;
            String url=origin.url("old")+"&sig=a%2Bb%2Fc&range=stale&n=a%20b";
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",url)){
                stream.readNBytes(262144);
                assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertArrayEquals(
                    Arrays.copyOfRange(origin.audio,262144,origin.audio.length),stream.readAllBytes()));
            }
            assertEquals(0,origin.resolves.get(),"A supported CDN request shape must be tried before metadata refresh");
            for(String query:origin.queries){assertTrue(query.contains("sig=a%2Bb%2Fc"));assertTrue(query.contains("n=a%20b"));}
            assertTrue(origin.queries.stream().anyMatch(q->q.contains("range=262144-1310719")));
        }
    }
    @Test void aUrlRangeMustNotApplyTheSameAbsoluteRangeAgainAsAHeaderAfterOneMiB()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.queryOnlyAfter=1048576;
            origin.segmentLimit=262144;origin.rejectDuplicateRange=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old")+"&sig=a%2Bb%2Fc")){
                assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertArrayEquals(origin.audio,stream.readAllBytes()));
            }
            assertEquals(0,origin.resolves.get(),"A range transport mismatch must not require new metadata");
            assertTrue(origin.queries.stream().anyMatch(q->q.contains("range=1048576-")));
            for(int i=0;i<origin.queries.size();i++)if(origin.queries.get(i).contains("range="))
                assertEquals("none",origin.rangeHeaders.get(i),"The URL already selects the absolute bytes");
        }
    }
    @Test void aUrlRangeWithAnExplicitValidContentRangeCanUseStatus200()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.queryOnlyAfter=1048576;
            origin.segmentLimit=262144;origin.rejectDuplicateRange=true;origin.queryStatus200=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertArrayEquals(origin.audio,stream.readAllBytes()));
            }
            assertEquals(0,origin.resolves.get());
        }
    }
    @Test void aStatus200UrlSliceWithoutAnExplicitContentRangeRemainsRejected()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.queryOnlyAfter=1048576;
            origin.segmentLimit=262144;origin.rejectDuplicateRange=true;
            origin.queryStatus200=true;origin.omitQueryContentRange=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(IOException.class,stream::readAllBytes));
            }
            assertEquals(0,origin.resolves.get());
        }
    }
    @Test void aStatus200UrlSliceForAnotherOffsetRemainsRejected()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.queryOnlyAfter=0;
            origin.segmentLimit=262144;origin.rejectDuplicateRange=true;
            origin.queryStatus200=true;origin.invalidStart=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertArrayEquals(Arrays.copyOf(origin.audio,262144),stream.readNBytes(262144));
                assertTimeoutPreemptively(Duration.ofSeconds(3),()->assertThrows(IOException.class,stream::read));
            }
            assertEquals(0,origin.resolves.get());
        }
    }
    @Test void lateAudioAndEofRemainAudibleWhenTheCdnRejectsDuplicateRangesAfterOneMiB()throws Exception {
        try(var origin=new Origin(AllSourceStreamingTest.fixture("youtube.m4a"))){route(origin);
            origin.queryOnlyAfter=1048576;origin.segmentLimit=262144;origin.rejectDuplicateRange=true;
            File token=ProgressiveAudio.prepare(origin.url("old"),()->false,null,false,80000,"owned");assertNotNull(token);
            try{var sample=ProgressiveAudio.claim(token);assertNotNull(sample);
                assertTrue(ProgressiveAudio.completeForAnalysis(token,true,256L*1024*1024));
                long until=System.nanoTime()+6_000_000_000L;
                while(ProgressiveAudio.analysisFile(token)==null&&ProgressiveAudio.state(token,64000)!=3&&System.nanoTime()<until)Thread.sleep(10);
                assertNotNull(ProgressiveAudio.analysisFile(token),"Playback must cross 1 MiB and reach actual EOF");
                for(int seconds:new int[]{64,76}){
                    int index=(int)(seconds*sample.getFrameRate())*sample.getChannelsPerFrame();double energy=0;
                    for(int i=0;i<1000;i++){double value=sample.readDouble(index+i);energy+=value*value;}
                    assertTrue(energy>0.1,"Actual PCM must be audible at "+seconds+" seconds");
                }
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1));
                assertEquals(0,origin.resolves.get());
                System.out.println("YOUTUBE_RANGE_PCM fixture=owned-fmp4 audibleSeconds=64,76 end=decoded selector=url-only");
            }finally{ProgressiveAudio.discard(token);}
        }
    }
    @Test void aWorkingSmallerWindowIsRetainedInsteadOfTriggeringRejectionsThroughoutTheTrack()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.rejectAbove=262144;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertTrue(origin.resolves.get()<=1,"Repeated successful small windows must not require player metadata again");
            assertTrue(origin.calls.get()<=14,"Do not probe the rejected large window again after every successful segment");
        }
    }
    @Test void aCdnRejectingUrlRangesStillPlaysThroughItsSupportedHeaderRanges()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.rejectQuery=true;origin.rejectAbove=262144;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertTrue(origin.resolves.get()<=1);
        }
    }
    @Test void aShortCdnRetryAfterDoesNotBecomeATwentySecondPlaybackGap()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.cooldownAfter=1048576;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertTimeoutPreemptively(Duration.ofSeconds(4),()->assertArrayEquals(origin.audio,stream.readAllBytes()));
            }
            assertEquals(0,origin.resolves.get(),"429 with Retry-After is a wait, not an expired media URL");
            assertTrue(origin.calls.get()<=6,"Respect Retry-After without hammering the player endpoint or CDN");
        }
    }
    @Test void closingDuringARetryAfterWaitStopsTheReaderPromptly()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.cooldownAfter=0;origin.retryAfterValue="60";
            origin.retryAt=System.nanoTime()+60_000_000_000L;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                var executor=Executors.newSingleThreadExecutor();
                try{
                    var reader=executor.submit(()->assertThrows(IOException.class,stream::read));
                    assertTrue(origin.throttled.await(2,TimeUnit.SECONDS));Thread.sleep(100);
                    stream.close();reader.get(1,TimeUnit.SECONDS);
                    int calls=origin.calls.get();Thread.sleep(100);assertEquals(calls,origin.calls.get());
                }finally{executor.shutdownNow();}
            }
        }
    }
    @Test void anExcessiveRetryAfterStopsRatherThanRetryingEarlierThanTheCdnAllows()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.cooldownAfter=0;origin.retryAfterValue="999999";
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertThrows(IOException.class,stream::read));
            }
            assertEquals(1,origin.calls.get());assertEquals(0,origin.resolves.get());
        }
    }
    @Test void anHttpDateRetryAfterAlsoHonoursCancellation()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.cooldownAfter=0;
            origin.retryAt=System.nanoTime()+60_000_000_000L;
            origin.retryAfterValue=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            try(var stream=new ProgressiveAudio.InterruptibleInput(YouTubeMusicService.openProgressiveStream("owned",origin.url("old")))){
                var executor=Executors.newSingleThreadExecutor();
                try{
                    var reader=executor.submit(()->assertThrows(IOException.class,stream::read));
                    assertTrue(origin.throttled.await(2,TimeUnit.SECONDS));Thread.sleep(100);
                    stream.close();reader.get(1,TimeUnit.SECONDS);
                    assertEquals(1,origin.calls.get());assertEquals(0,origin.resolves.get());
                }finally{executor.shutdownNow();}
            }
        }
    }
    @Test void queryRangeFallbackStillRejectsAChangedObject()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.queryContinuation=true;origin.changedTag=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                stream.readNBytes(262144);assertThrows(IOException.class,stream::read);
            }
            assertEquals(3,origin.calls.get());assertEquals(0,origin.resolves.get());
        }
    }
    @Test void aLateSeekAndTheFinalPcmRemainPlayableAfterSmallWindowsAndTemporary403()throws Exception {
        try(var origin=new Origin(AllSourceStreamingTest.fixture("youtube.m4a"))){
            route(origin);origin.rejectAbove=262144;origin.cooldownAfter=1048576;origin.queryContinuation=true;
            origin.cooldownStatus=403;origin.retryAfterValue=null;
            File token=ProgressiveAudio.prepare(origin.url("old"),()->false,null,false,80000,"owned");assertNotNull(token);
            try{
                var sample=ProgressiveAudio.claim(token);assertNotNull(sample);
                long until=System.nanoTime()+6_000_000_000L;
                while(ProgressiveAudio.state(token,55000)==1&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(0,ProgressiveAudio.state(token,55000),ProgressiveAudio.failureKind(token));
                int index=(int)(55*sample.getFrameRate())*sample.getChannelsPerFrame();double energy=0;
                for(int i=0;i<1000;i++){double value=sample.readDouble(index+i);energy+=value*value;}
                assertTrue(energy>0.1,"Seeking must recover actual audible PCM, not just report resumed");
                until=System.nanoTime()+6_000_000_000L;
                while(ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1)==1&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1),ProgressiveAudio.failureKind(token));
            }finally{ProgressiveAudio.discard(token);}
        }
    }
    @Test void aTransientCdnFailureRetriesWithoutReopeningMetadata()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.failCall=2;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertEquals(0,origin.resolves.get());assertEquals(origin.ranges.get(1),origin.ranges.get(2));
        }
    }
    @Test void refreshedUrlWithAnotherObjectLengthIsRejectedBeforeItsBytesAreRead()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.rejectOld=true;origin.refreshLengthDelta=100;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){stream.readNBytes(262144);assertThrows(IOException.class,stream::read);}
            assertEquals(1,origin.resolves.get());
            assertTrue(origin.queries.stream().noneMatch(q->q.contains("token=fresh")),"Changed metadata must not be fetched as audio");
        }
    }
    @Test void aComplete200ResponseIsAllowedOnlyAtTheBeginning()throws Exception {
        try(var origin=new Origin(new byte[18000])){route(origin);origin.ignoreAll=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertEquals(1,origin.calls.get());assertEquals(0,origin.resolves.get());
        }
    }
    @Test void aRangeForAnotherOffsetCannotReachTheDecoder()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.invalidStart=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){stream.readNBytes(262144);assertThrows(IOException.class,stream::read);}
            assertEquals(2,origin.calls.get());
        }
    }
    @Test void wholeDownloadUsesTheSameValidatedContinuationWhenTheCdnReturnsShorterWindows()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.resolveUnavailable=true;origin.segmentLimit=512*1024;
            try(var stream=YouTubeMusicService.openStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertEquals(0,origin.resolves.get());assertEquals(5,origin.calls.get());
        }
    }
    @Test void aBrokenWindowRetriesTheUndeliveredOffsetWithoutSkippingAudio()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.breakCall=2;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertEquals(0,origin.resolves.get());assertEquals(origin.ranges.get(1),origin.ranges.get(2));
        }
    }
    @Test void ignoredRangeCannotAppendTheSongPrefixAgain()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.ignoreContinuation=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){
                assertArrayEquals(Arrays.copyOf(origin.audio,262144),stream.readNBytes(262144));
                assertThrows(IOException.class,stream::read);
            }
            assertEquals(2,origin.calls.get(),"A protocol mismatch must stop without a quota retry loop");
        }
    }
    @Test void changedObjectLengthCannotBeSplicedIntoTheCurrentDecoder()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.changedTotal=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){stream.readNBytes(262144);assertThrows(IOException.class,stream::read);}
            assertEquals(2,origin.calls.get());
        }
    }
    @Test void changedEtagCannotBeSplicedIntoTheCurrentDecoder()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.changedTag=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){stream.readNBytes(262144);assertThrows(IOException.class,stream::read);}
            assertEquals(2,origin.calls.get());
        }
    }
    @Test void rejectedCdnUrlRefreshesOnceAndResumesTheSameFormatAtTheSameByte()throws Exception {
        try(var origin=new Origin(payload())){route(origin);origin.rejectOld=true;
            try(var stream=YouTubeMusicService.openProgressiveStream("owned",origin.url("old"))){assertArrayEquals(origin.audio,stream.readAllBytes());}
            assertEquals(1,origin.resolves.get());assertEquals(origin.ranges.get(1),origin.ranges.get(2));
        }
    }
    @Test void longFragmentedAacDecodesThroughTheFinalYoutubeWindow()throws Exception {
        byte[] audio=AllSourceStreamingTest.fixture("youtube.m4a");
        try(var origin=new Origin(audio)){route(origin);
            File token=ProgressiveAudio.prepare(origin.url("old"),()->false,null,true,80000,"owned");assertNotNull(token);
            try{long until=System.nanoTime()+8_000_000_000L;
                while(ProgressiveAudio.analysisFile(token)==null&&ProgressiveAudio.state(token,0)!=3&&System.nanoTime()<until)Thread.sleep(10);
                assertNotNull(ProgressiveAudio.analysisFile(token),ProgressiveAudio.failureKind(token));
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1));
                assertEquals(0,origin.resolves.get());
            }finally{ProgressiveAudio.discard(token);}
        }
    }
    @Test void fragmentedMp4DecodesAcrossWindowsEvenWhenMetadataResolutionIsUnavailable()throws Exception {
        byte[] original=AllSourceStreamingTest.fixture("fmp4");
        int moof=FragmentedAacTest.type(original,"moof")-4;
        // Ordinary small free atoms force the first fragment across the initial
        // HTTP window, without introducing a large random seek or changing
        // any owned audio packets or relative moof offsets.
        var padding=new ByteArrayOutputStream();
        for(int i=0;i<10;i++)padding.write(FragmentedAacTest.box("free",new byte[30000]));
        byte[] audio=FragmentedAacTest.join(Arrays.copyOf(original,moof),
                padding.toByteArray(),Arrays.copyOfRange(original,moof,original.length));
        try(var origin=new Origin(audio)){route(origin);origin.resolveUnavailable=true;
            File token=ProgressiveAudio.prepare(origin.url("old"),()->false,null,true,16000,"owned");assertNotNull(token);
            try{long until=System.nanoTime()+8_000_000_000L;
                while(ProgressiveAudio.analysisFile(token)==null&&ProgressiveAudio.state(token,0)!=3&&System.nanoTime()<until)Thread.sleep(10);
                assertNotNull(ProgressiveAudio.analysisFile(token),ProgressiveAudio.failureKind(token));
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1));
                assertTrue(origin.ranges.size()>1);assertEquals(0,origin.resolves.get());
            }finally{ProgressiveAudio.discard(token);}
        }
    }
}
