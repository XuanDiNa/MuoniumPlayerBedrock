package com.muoniumplayer.bedrock;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual transport/decoder tests with our own FFmpeg-generated sine fixture. */
class Fmp4RadioTest {
    static byte[] fixture(String name)throws IOException {
        try(var in=Fmp4RadioTest.class.getResourceAsStream("/radio-fmp4/"+name)) {
            if(in==null)throw new IOException("Missing owned radio fixture");return in.readAllBytes();
        }
    }
    static final class Http implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
        final Map<String,byte[]> files=new ConcurrentHashMap<>();
        final List<String> requests=Collections.synchronizedList(new ArrayList<>());
        final List<String> ranges=Collections.synchronizedList(new ArrayList<>());
        final Thread worker;boolean ignoreRanges,holdHeaders;volatile String heldPath;
        final CountDownLatch bodyStarted=new CountDownLatch(1),releaseBody=new CountDownLatch(1);
        Http()throws IOException {
            worker=new Thread(()->{while(!server.isClosed())try(var socket=server.accept()) {
                var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                String request=reader.readLine();if(request==null)continue;
                String path=request.split(" ")[1],range=null,line;
                while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase(Locale.ROOT).startsWith("range:"))range=line.substring(6).trim();
                requests.add(path);if(range!=null)ranges.add(range);
                if(path.equals(heldPath)&&holdHeaders){bodyStarted.countDown();releaseBody.await(5,TimeUnit.SECONDS);}
                byte[] body=files.get(path);int status=body==null?404:200;String contentRange="";
                if(body==null)body=new byte[0];
                if(range!=null&&!ignoreRanges) {
                    String[] span=range.substring(6).split("-");int first=Integer.parseInt(span[0]),last=Integer.parseInt(span[1]);
                    contentRange="Content-Range: bytes "+first+"-"+last+"/"+body.length+"\r\n";
                    body=Arrays.copyOfRange(body,first,last+1);status=206;
                }
                var output=socket.getOutputStream();
                output.write(("HTTP/1.1 "+status+" OK\r\n"+contentRange+"Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if(path.equals(heldPath)&&!holdHeaders) {
                    output.write(body,0,8);output.flush();bodyStarted.countDown();releaseBody.await(5,TimeUnit.SECONDS);
                    output.write(body,8,body.length-8);
                }else output.write(body);
            }catch(IOException ignored){}catch(InterruptedException end){Thread.currentThread().interrupt();break;}},"owned-radio-http");worker.setDaemon(true);worker.start();
        }
        String url(){return "http://127.0.0.1:"+server.getLocalPort()+"/radio.m3u8";}
        void playlist(String text){files.put("/radio.m3u8",text.getBytes(StandardCharsets.UTF_8));}
        public void close()throws Exception{releaseBody.countDown();server.close();worker.join(1000);assertFalse(worker.isAlive());}
    }
    static String finite(Http http)throws IOException {
        String text=new String(fixture("radio.m3u8"),StandardCharsets.UTF_8);
        http.files.put("/init.mp4",fixture("init.mp4"));
        for(String line:text.split("\\r?\\n"))if(!line.isBlank()&&!line.startsWith("#"))http.files.put("/"+line,fixture(line));
        return text;
    }
    @Test void fragmentedPlaylistProducesActualContinuousPcmAndReusesInitialization()throws Exception {
        try(var http=new Http()) {
            http.playlist(finite(http));long samples=0;double energy=0;
            try(var frames=new RadioFrames(LiveRadioTransport.open(http.url()))) {
                RadioFrames.Frame frame;
                while((frame=frames.next())!=null) {
                    assertEquals(44100,frame.format().getSampleRate());assertEquals(2,frame.format().getChannels());
                    byte[] pcm=frame.pcm();assertTrue(pcm.length>0);samples+=pcm.length/4;
                    for(int i=0;i+1<pcm.length;i+=2){short value=(short)((pcm[i]&255)|(pcm[i+1]<<8));energy+=(double)value*value;}
                }
            }
            assertTrue(samples>=44100,"All fragments must decode, not only the first prefix");
            assertTrue(samples<65000);assertTrue(energy>1_000_000,"Decoded sine must not become silence");
            assertEquals(1,Collections.frequency(http.requests,"/init.mp4"));
        }
    }
    @Test void mapAndMediaByteRangesYieldTheSameAudioWithoutFullObjectFetches()throws Exception {
        try(var http=new Http()) {
            var whole=new ByteArrayOutputStream();whole.write(new byte[5]);byte[] init=fixture("init.mp4");whole.write(init);
            StringBuilder text=new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-MAP:URI=\"combined.mp4\",BYTERANGE=\""+init.length+"@5\"\n");
            String list=new String(fixture("radio.m3u8"),StandardCharsets.UTF_8);int segments=0;
            for(String line:list.split("\\r?\\n"))if(!line.isBlank()&&!line.startsWith("#")) {
                byte[] data=fixture(line);text.append("#EXTINF:0.3,\n#EXT-X-BYTERANGE:").append(data.length);
                if(segments++==0)text.append('@').append(whole.size()); // Later offsets inherit the previous media range.
                text.append("\ncombined.mp4\n");whole.write(data);
            }
            text.append("#EXT-X-ENDLIST\n");http.files.put("/combined.mp4",whole.toByteArray());http.playlist(text.toString());
            try(var stream=LiveRadioTransport.open(http.url());var frames=new RadioFrames(stream)) {
                int n=0;while(frames.next()!=null)n++;assertTrue(n>40);
            }
            assertEquals(segments+1,http.ranges.size());assertEquals(segments+1,Collections.frequency(http.requests,"/combined.mp4"));
        }
    }
    @Test void rejectsIgnoredRangesAndEncryptedInitializationWithoutPlayingBytes()throws Exception {
        try(var http=new Http()) {
            http.ignoreRanges=true;http.files.put("/init.mp4",fixture("init.mp4"));
            http.playlist("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"8@0\"\n#EXTINF:1,\na.m4s\n#EXT-X-ENDLIST\n");
            try(var stream=LiveRadioTransport.open(http.url())){assertThrows(IOException.class,stream::read);}
            http.playlist("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1,\na.m4s\n");
            assertThrows(IOException.class,()->LiveRadioTransport.open(http.url()));
            assertEquals(1,Collections.frequency(http.requests,"/init.mp4"));
        }
    }
    @Test void closingBeforeFirstReadFetchesNeitherInitializationNorMedia()throws Exception {
        try(var http=new Http()) {
            http.playlist(finite(http));var stream=LiveRadioTransport.open(http.url());stream.close();
            assertEquals(-1,stream.read());assertEquals(List.of("/radio.m3u8"),http.requests);
        }
    }
    @Test void refreshedInitializationCanChangeTrackIdButCannotSilentlyChangeDecoderFormat()throws Exception {
        try(var http=new Http()) {
            byte[] init=fixture("init.mp4"),media=fixture("segment0.m4s"),secondInit=init.clone(),secondMedia=media.clone();
            ByteBuffer.wrap(secondInit).putInt(FragmentedAacTest.type(secondInit,"tkhd")+16,2)
                .putInt(FragmentedAacTest.type(secondInit,"trex")+8,2);
            ByteBuffer.wrap(secondMedia).putInt(FragmentedAacTest.type(secondMedia,"tfhd")+8,2);
            http.files.put("/init.mp4",init);http.files.put("/init2.mp4",secondInit);
            http.files.put("/a.m4s",media);http.files.put("/b.m4s",secondMedia);
            http.playlist("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:0.3,\na.m4s\n#EXT-X-DISCONTINUITY\n#EXT-X-MAP:URI=\"init2.mp4\"\n#EXTINF:0.3,\nb.m4s\n#EXT-X-ENDLIST\n");
            byte[] elementary=FragmentedAac.initialization(init).fragment(media);
            try(var stream=LiveRadioTransport.open(http.url())){assertArrayEquals(FragmentedAacTest.join(elementary,elementary),stream.readAllBytes());}
            byte[] changed=secondInit.clone();ByteBuffer.wrap(changed).putInt(FragmentedAacTest.type(changed,"mp4a")+28,48000<<16);
            for(int i=0;i<changed.length-4;i++)if(changed[i]==0x12&&changed[i+1]==0x10&&changed[i+2]==0x56){changed[i]=0x11;changed[i+1]=(byte)0x90;break;}
            http.files.put("/init2.mp4",changed);
            try(var stream=LiveRadioTransport.open(http.url())){assertThrows(IOException.class,stream::readAllBytes);}
            assertEquals(1,Collections.frequency(http.requests,"/b.m4s"),"Changed configuration must stop before fetching new media");
        }
    }
    @Test void stopAbortsAnInFlightSegmentWithoutWaitingForItsBody()throws Exception {
        cancellation("/segment0.m4s",false);
    }
    @Test void stopAbortsInitializationDownload()throws Exception {
        cancellation("/init.mp4",false);
    }
    @Test void stopAbortsPendingSegmentHeaders()throws Exception {
        cancellation("/segment0.m4s",true);
    }
    static void cancellation(String path,boolean holdHeaders)throws Exception {
        try(var http=new Http()) {
            http.playlist(finite(http));http.heldPath=path;http.holdHeaders=holdHeaders;
            var tasks=Executors.newFixedThreadPool(2);var stream=LiveRadioTransport.open(http.url());
            try {
                var read=tasks.submit(()->{try{stream.readAllBytes();}catch(IOException stopped){}});
                assertTrue(http.bodyStarted.await(3,TimeUnit.SECONDS));
                var close=tasks.submit(()->{try{stream.close();}catch(IOException stopped){}});
                close.get(700,TimeUnit.MILLISECONDS);read.get(700,TimeUnit.MILLISECONDS);
                assertFalse(http.requests.contains("/segment1.m4s"));
            }finally{http.releaseBody.countDown();tasks.shutdownNow();assertTrue(tasks.awaitTermination(5,TimeUnit.SECONDS));stream.close();}
        }
    }
}
