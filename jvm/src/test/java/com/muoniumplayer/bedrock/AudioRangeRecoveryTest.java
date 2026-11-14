package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP bodies: no external music, signed URLs or player accounts. */
class AudioRangeRecoveryTest {
    static final class Origin implements AutoCloseable {
        final byte[] audio;
        final ServerSocket server = new ServerSocket(0,16,InetAddress.getLoopbackAddress());
        final ExecutorService pool = Executors.newCachedThreadPool();
        final List<Long> ranges = new CopyOnWriteArrayList<>();
        final List<String> rangeHeaders = new CopyOnWriteArrayList<>();
        final AtomicInteger requests = new AtomicInteger();
        volatile boolean changed,ignoreRange,alwaysBreak,weakTag,changedModified;
        Origin() throws Exception { this(StreamingSeekTest.audio(30)); }
        Origin(byte[] payload) throws Exception {
            audio=payload;
            pool.submit(()->{while(!server.isClosed())try{var s=server.accept();pool.submit(()->serve(s));}catch(IOException ignored){}});
        }
        void serve(Socket socket) {
            try(socket) {
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line=r.readLine(),range=null;
                while((line=r.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("range:"))range=line.substring(6).trim();
                requests.incrementAndGet();long start=0,end=audio.length-1;
                boolean partial=range!=null&&!ignoreRange;
                if(range!=null){rangeHeaders.add(range);String[] p=range.substring(6).split("-",-1);start=Long.parseLong(p[0]);if(!p[1].isEmpty())end=Math.min(end,Long.parseLong(p[1]));ranges.add(start);}
                if(!partial){start=0;end=audio.length-1;}
                var out=socket.getOutputStream();
                String headers="ETag: "+(weakTag?"W/":"")+(range!=null&&changed?"\"changed\"":"\"original\"")+"\r\n";
                if(weakTag)headers+="Last-Modified: "+(range!=null&&changedModified?"Tue, 10 Nov 2026 10:00:00 GMT":"Mon, 09 Nov 2026 10:00:00 GMT")+"\r\n";
                if(partial)headers+="Content-Range: bytes "+start+"-"+end+"/"+audio.length+"\r\n";
                out.write(((partial?"HTTP/1.1 206 Partial Content":"HTTP/1.1 200 OK")+"\r\n"+headers+"Content-Length: "+(end-start+1)+"\r\nConnection: close\r\n\r\n").getBytes());
                int n=(int)(end-start+1);
                if(range==null||alwaysBreak)n=Math.min(n,10000);
                out.write(audio,(int)start,n);out.flush();
            } catch(IOException ignored) {}
        }
        String url(){return "http://127.0.0.1:"+server.getLocalPort()+"/owned.flac";}
        public void close() throws IOException {server.close();pool.shutdownNow();}
    }
    @Test void interruptedSequentialBodyResumesAtTheExactByteWithoutRepeatingAudio() throws Exception {
        try(var origin=new Origin();var remote=new AudioRanges(origin.url(),()->false)) {
            assertArrayEquals(origin.audio,remote.initial.readAllBytes(),"A validated byte-range continuation must recover the whole original body");
            assertEquals(10000L,origin.ranges.getFirst());
            assertTrue(origin.requests.get()<8,"Recovery must be bounded, not a new whole-file download");
        }
    }
    @Test void sequentialResumeKeepsOneContinuousBodyInsteadOfReopeningEvery64KiB() throws Exception {
        try(var origin=new Origin();var remote=new AudioRanges(origin.url(),()->false)) {
            assertArrayEquals(origin.audio,remote.initial.readAllBytes());
            assertEquals(List.of("bytes=10000-"),origin.rangeHeaders,
                "Sequential playback must resume the remaining stream, not use the small random-seek reader");
            assertEquals(2,origin.requests.get(),"One initial body and one continuous recovery body");
        }
    }
    @Test void mp3DecoderSurvivesAMidFrameDisconnectWithoutRestartingTheSong() throws Exception {
        try(var origin=new Origin(ProgressiveAudioTest.mp3(3000))) {
            File token=ProgressiveAudio.prepare(origin.url(),()->false,null,true);
            assertNotNull(token,"A mid-frame transport interruption must preserve the MP3 decoder state");
            try {
                long deadline=System.nanoTime()+5_000_000_000L;
                while(ProgressiveAudio.analysisFile(token)==null&&ProgressiveAudio.state(token,0)!=3&&System.nanoTime()<deadline)Thread.sleep(10);
                assertNotNull(ProgressiveAudio.analysisFile(token),ProgressiveAudio.failureKind(token));
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1));
                assertEquals(List.of("bytes=10000-"),origin.rangeHeaders);
                assertEquals(2,origin.requests.get());
            } finally {ProgressiveAudio.discard(token);}
        }
    }
    @Test void weakEtagUsesLastModifiedForSameObjectValidation() throws Exception {
        try(var origin=new Origin()) {
            origin.weakTag=true;
            try(var remote=new AudioRanges(origin.url(),()->false)) {assertArrayEquals(origin.audio,remote.initial.readAllBytes());}
        }
        try(var origin=new Origin()) {
            origin.weakTag=true;origin.changedModified=true;
            try(var remote=new AudioRanges(origin.url(),()->false)) {assertThrows(IOException.class,()->remote.initial.readAllBytes());}
            assertEquals(1,origin.ranges.size(),"Changed Last-Modified must not splice stale audio or retry forever");
        }
    }
    @Test void changedObjectIsNeverSplicedIntoTheCurrentTrack() throws Exception {
        try(var origin=new Origin()) {
            origin.changed=true;
            try(var remote=new AudioRanges(origin.url(),()->false)) {
                assertThrows(IOException.class,()->remote.initial.readAllBytes());
                assertEquals(List.of(10000L),origin.ranges,"Validate the resumed object's identity once, then fail closed");
            }
        }
    }
    @Test void serverIgnoringRangeDoesNotLoopOrDuplicateTheSongPrefix() throws Exception {
        try(var origin=new Origin()) {
            origin.ignoreRange=true;
            try(var remote=new AudioRanges(origin.url(),()->false)) {
                assertThrows(IOException.class,()->remote.initial.readAllBytes());
                assertEquals(2,origin.requests.get());
            }
        }
    }
    @Test void repeatedDisconnectsStopAfterTwoContinuationAttempts() throws Exception {
        try(var origin=new Origin()) {
            origin.alwaysBreak=true;
            try(var remote=new AudioRanges(origin.url(),()->false)) {
                assertThrows(IOException.class,()->remote.initial.readAllBytes());
                assertEquals(3,origin.requests.get(),"Initial request plus at most two transport continuations");
            }
        }
    }
    @Test void exactFlacSampleEndDoesNotWaitForUnneededTrailingHttpBytes() throws Exception {
        byte[] audio=StreamingSeekTest.audio(30);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var release=new CountDownLatch(1);
            Thread http=new Thread(()->{try(var socket=server.accept()) {
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));while(!r.readLine().isEmpty()){}
                var out=socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+(audio.length+8192)+"\r\nConnection: close\r\n\r\n").getBytes());
                out.write(audio);out.flush();release.await(5,TimeUnit.SECONDS);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            File token=null;
            try {
                token=ProgressiveAudio.prepare("http://127.0.0.1:"+server.getLocalPort()+"/trailing.flac");assertNotNull(token);
                long deadline=System.nanoTime()+1_500_000_000L;
                while(ProgressiveAudio.analysisFile(token)==null&&System.nanoTime()<deadline)Thread.sleep(10);
                assertNotNull(ProgressiveAudio.analysisFile(token),"All exact FLAC samples are decoded: trailing transport bytes cannot hold the song open");
                assertEquals(1,release.getCount());
                assertEquals(2,ProgressiveAudio.state(token,ProgressiveAudio.durationMillis(token)+1));
            } finally {ProgressiveAudio.discard(token);release.countDown();http.join(1000);}
        }
    }
    @Test void missingOneExactFlacFrameIsFailureEvenWhenItIsLessThanTwoSeconds() throws Exception {
        byte[] complete=StreamingSeekTest.audio(30);
        byte[] shortAudio=Arrays.copyOf(complete,complete.length-8202);
        try(var remote=new StreamingSeekTest.Remote(shortAudio)) {
            remote.release.countDown();File token=ProgressiveAudio.prepare(remote.url());
            try {
                if(token==null)return; // Rejection during prebuffer is also a real failure.
                long deadline=System.nanoTime()+2_000_000_000L;
                while(ProgressiveAudio.state(token,0)!=3&&System.nanoTime()<deadline)Thread.sleep(10);
                assertEquals(3,ProgressiveAudio.state(token,0),"An exact FLAC tail must never be silently shortened to call it complete");
                assertNull(ProgressiveAudio.analysisFile(token));
            } finally {ProgressiveAudio.discard(token);}
        }
    }
}
