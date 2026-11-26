package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.StreamingAudioRoute;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StreamingTransportCoverageTest {
    @AfterEach void close(){ProgressiveAudio.closePending();}
    @Test void bilibiliHeadersAreReusedOnlyForTheActualCdnHost() {
        for(String domain:List.of("bilivideo.com","bilivideo.cn","bilibili.com","akamaized.net")) {
            var headers=StreamingAudioRoute.headers("https://audio."+domain+"/owned");assertNotNull(headers);
            assertEquals("https://www.bilibili.com",headers.get("Referer"));assertEquals(headers.get("Referer"),headers.get("Origin"));assertTrue(headers.get("User-Agent").contains("Chrome"));
        }
        for(String address:List.of("https://evilbilivideo.com/a","https://bilivideo.com.evil.invalid/a","http://127.0.0.1/a","not a URL"))assertNull(StreamingAudioRoute.headers(address));
    }
    @ParameterizedTest @ValueSource(strings={"aac","m4a","ogg","wav"}) @Timeout(12)
    void frontMetadataDoesNotRequireServerRangeSupport(String format)throws Exception {
        try(var remote=new AllSourceStreamingTest.Remote(AllSourceStreamingTest.fixture(format),format.equals("wav")?220000:18000)) {
            remote.ignoreRange=true;var f=AllSourceStreamingTest.admit(AllSourceStreamingTest.song(remote.url(),"qq",format));assertNotNull(f);assertEquals(1,remote.release.getCount());AllSourceStreamingTest.assertSignal(f);ProgressiveAudio.discard(f);
        }
    }
    @Test @Timeout(8) void tailMetadataWithoutRangeFallsBackBeforeConsumingTheSong()throws Exception {
        try(var remote=new AllSourceStreamingTest.Remote(AllSourceStreamingTest.fixture("tail.m4a"),18000)) {
            remote.ignoreRange=true;long at=System.nanoTime();assertNull(AllSourceStreamingTest.admit(AllSourceStreamingTest.song(remote.url(),"bilibili","m4a")));
            assertTrue(System.nanoTime()-at<3_000_000_000L);assertEquals(1,remote.release.getCount());
        }
    }
    @ParameterizedTest @ValueSource(strings={"aac","m4a","ogg"}) @Timeout(8)
    void cancellingPreparationAbortsBlockedHeadersOrBody(String format)throws Exception {
        try(var remote=new AllSourceStreamingTest.Remote(AllSourceStreamingTest.fixture(format),0)) {
            var executor=Executors.newSingleThreadExecutor();var cancelled=new AtomicBoolean();
            try{var future=executor.submit(()->ProgressiveAudio.prepare(remote.url(),cancelled::get,null,false,16000,null));
                long until=System.nanoTime()+2_000_000_000L;while(ProgressiveAudio.transports.isEmpty()&&System.nanoTime()<until)Thread.sleep(5);
                assertFalse(ProgressiveAudio.transports.isEmpty());cancelled.set(true);
                var error=assertThrows(ExecutionException.class,()->future.get(1500,TimeUnit.MILLISECONDS));assertInstanceOf(CancellationException.class,error.getCause());
                assertTrue(ProgressiveAudio.transports.isEmpty());
            }finally{cancelled.set(true);executor.shutdownNow();}
        }
    }
    @Test @Timeout(10) void malformedMp4SampleCountIsRejectedBeforeAllocation()throws Exception {
        byte[] bytes=AllSourceStreamingTest.fixture("m4a");int at=FragmentedAacTest.type(bytes,"stsz");java.nio.ByteBuffer.wrap(bytes).putInt(at+12,Integer.MAX_VALUE);
        try(var remote=new AllSourceStreamingTest.Remote(bytes,50000)){assertNull(AllSourceStreamingTest.admit(AllSourceStreamingTest.song(remote.url(),"bilibili","m4a")));}
    }
    @Test @Timeout(10) void partialAdtsFrameIsFailureRatherThanNaturalEnd()throws Exception {
        byte[] bytes=Arrays.copyOf(AllSourceStreamingTest.fixture("aac"),22003);
        try(var remote=new AllSourceStreamingTest.Remote(bytes,bytes.length)) {
            var f=AllSourceStreamingTest.admit(AllSourceStreamingTest.song(remote.url(),"bilibili","aac"));assertNotNull(f);
            long until=System.nanoTime()+3_000_000_000L;int state;
            do{state=ProgressiveAudio.state(f,10000);if(state==3)break;Thread.sleep(10);}while(System.nanoTime()<until);
            assertEquals(3,state);ProgressiveAudio.discard(f);
        }
    }
    @Test @Timeout(10) void id3TaggedAdtsIsDetectedBeforeMpegAudio()throws Exception {
        byte[] aac=AllSourceStreamingTest.fixture("aac");byte[] bytes=new byte[aac.length+10];System.arraycopy(new byte[]{'I','D','3',4,0,0,0,0,0,0},0,bytes,0,10);System.arraycopy(aac,0,bytes,10,aac.length);
        try(var remote=new AllSourceStreamingTest.Remote(bytes,18000)){var f=AllSourceStreamingTest.admit(AllSourceStreamingTest.song(remote.url(),"migu","mp3"));assertNotNull(f);AllSourceStreamingTest.assertSignal(f);ProgressiveAudio.discard(f);}
    }
    @Test @Timeout(10) void unknownEncryptedSampleLayoutCannotBeSilentlyDecoded()throws Exception {
        byte[] bytes=AllSourceStreamingTest.fixture("encrypted.m4a");int at=FragmentedAacTest.type(bytes,"senc");java.nio.ByteBuffer.wrap(bytes).putInt(at+4,2);
        try(var remote=new AllSourceStreamingTest.Remote(bytes,50000)) {
            var song=AllSourceStreamingTest.song(remote.url(),"qishui","m4a");song.setQishuiPlayAuth("00112233445566778899aabbccddeeff");
            assertNull(AllSourceStreamingTest.admit(song));assertEquals(1,remote.release.getCount());
        }
    }
    @Test @Timeout(8) void cancelAbortsAnInFlightYoutubeWindowExchange()throws Exception {
        byte[] bytes=AllSourceStreamingTest.fixture("m4a");
        try(var remote=new AllSourceStreamingTest.Remote(bytes,0)) {
            remote.holdRanges=true;var executor=Executors.newSingleThreadExecutor();var cancelled=new AtomicBoolean();
            try{var future=executor.submit(()->ProgressiveAudio.prepare(remote.url()+"?itag=140&clen="+bytes.length,cancelled::get,null,false,16000,"owned-video"));
                long until=System.nanoTime()+2_000_000_000L;while(remote.ranges.isEmpty()&&System.nanoTime()<until)Thread.sleep(5);assertFalse(remote.ranges.isEmpty());
                cancelled.set(true);var error=assertThrows(ExecutionException.class,()->future.get(1500,TimeUnit.MILLISECONDS));assertInstanceOf(CancellationException.class,error.getCause());
            }finally{cancelled.set(true);executor.shutdownNow();}
        }
    }
}
