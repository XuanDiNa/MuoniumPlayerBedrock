package com.muoniumplayer.bedrock;


import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSeekTest {
    // Owned, nonconstant FLAC: every block identifies its exact absolute time.
    static byte[] audio(int frames)throws Exception {
        var out=new ByteArrayOutputStream();
        out.write(Arrays.copyOf(ProgressiveAudioTest.flac(frames),42));
        for(int i=0;i<frames;i++){
            var frame=new ByteArrayOutputStream();frame.write(new byte[]{(byte)255,(byte)248,(byte)201,8});
            if(i<128)frame.write(i);else{frame.write(192|(i>>6));frame.write(128|(i&63));}
            frame.write(ProgressiveAudioTest.crc(frame.toByteArray(),8,7));
            frame.write(2); // Verbatim mono subframe, signed 16-bit samples.
            for(int j=0;j<4096;j++){frame.write((i+1)>>8);frame.write(i+1);}
            int crc=ProgressiveAudioTest.crc(frame.toByteArray(),16,0x8005);frame.write(crc>>8);frame.write(crc);
            out.write(frame.toByteArray());
        }return out.toByteArray();
    }
    static final class Remote implements AutoCloseable {
        final ServerSocket server;final ExecutorService pool=Executors.newCachedThreadPool();
        final List<Long> starts=new CopyOnWriteArrayList<>();final AtomicLong transferred=new AtomicLong();
        final CountDownLatch release=new CountDownLatch(1);final byte[] bytes;volatile String mode="range";
        Remote(byte[] bytes)throws Exception {this.bytes=bytes;server=new ServerSocket(0,16,InetAddress.getLoopbackAddress());
            pool.submit(()->{while(!server.isClosed())try{var socket=server.accept();pool.submit(()->serve(socket));}catch(IOException ignored){}});}
        void serve(Socket socket){try(socket){
                var reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));String line=reader.readLine(),h=null;
                while((line=reader.readLine())!=null&&!line.isEmpty())if(line.toLowerCase().startsWith("range:"))h=line.substring(6).trim();
                long start=0,end=bytes.length-1;
                if(h!=null){String[] parts=h.substring(6).split("-");start=Long.parseLong(parts[0]);if(parts.length>1)end=Math.min(end,Long.parseLong(parts[1]));starts.add(start);}
                boolean range=h!=null&&!mode.equals("ignore");
                String headers="ETag: "+(mode.equals("changed")&&start>0?"\"changed\"":"\"original\"")+"\r\n";
                if(range)headers+="Content-Range: bytes "+(mode.equals("wrong")&&start>0?start+1:start)+"-"+end+"/"+bytes.length+"\r\n";
                if(!range){start=0;end=bytes.length-1;}
                var out=socket.getOutputStream();out.write(((range?"HTTP/1.1 206 Partial Content":"HTTP/1.1 200 OK")+"\r\n"+headers+"Content-Length: "+(end-start+1)+"\r\nConnection: close\r\n\r\n").getBytes());
                int n=(int)(end-start+1);
                if(!range){int prefix=Math.min(n,170000);out.write(bytes,0,prefix);out.flush();transferred.addAndGet(prefix);release.await(8,TimeUnit.SECONDS);out.write(bytes,prefix,n-prefix);}
                else{out.write(bytes,(int)start,n);transferred.addAndGet(n);}
            }catch(Exception ignored){}}
        String url(){return "http://127.0.0.1:"+server.getLocalPort()+"/audio";}
        public void close()throws IOException{release.countDown();server.close();pool.shutdownNow();}
    }
    static void ready(File f,float ms)throws Exception {
        long until=System.nanoTime()+4_000_000_000L;int state;
        do{state=ProgressiveAudio.state(f,ms);if(state!=1)break;Thread.sleep(10);}while(System.nanoTime()<until);
        assertEquals(0,state,"A distant seek should decode its own range without waiting for the sequential tail");
    }
    @Test void distantSeekDecodesCorrectAbsoluteFrameAndRetainsEarlierInterval()throws Exception {
        try(var remote=new Remote(audio(500))){
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);var sample=ProgressiveAudio.claim(f);
            try{
                float ms=400*4096*1000f/44100;
                long before=System.nanoTime();sample.readDouble(400*4096+100);
                assertTrue(System.nanoTime()-before<100_000_000L,"The audio callback must never fetch HTTP");
                ready(f,ms);
                assertEquals(401/32768.0,sample.readDouble(400*4096+100),.000001,"Seek must use frame sample numbers, not byte ratio time");
                ready(f,0);assertEquals(1/32768.0,sample.readDouble(100),.000001);
                assertTrue(remote.starts.stream().anyMatch(n->n>1_000_000),"A distant range must actually be fetched");
                assertTrue(remote.transferred.get()<remote.bytes.length/2,"Seeking must not download/decode the whole prefix");
            }finally{sample.cleanUp();}
            assertFalse(f.exists());
        }
    }
    @Test void serverIgnoringRangeFallsBackToSequentialBuffering()throws Exception {
        try(var remote=new Remote(audio(500))){remote.mode="ignore";
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);var sample=ProgressiveAudio.claim(f);
            try{assertEquals(1,ProgressiveAudio.state(f,37000));assertEquals(0,sample.readDouble(400*4096+100));
                assertEquals(1/32768.0,sample.readDouble(100),.000001);
            }finally{sample.cleanUp();}
        }
    }
    @Test void changedObjectAndMalformedRangeNeverBecomeDecodedAudio()throws Exception {
        for(String mode:List.of("changed","wrong"))try(var remote=new Remote(audio(500))){
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);var sample=ProgressiveAudio.claim(f);remote.mode=mode;
            try{long until=System.nanoTime()+4_000_000_000L;
                while(ProgressiveAudio.state(f,37000)!=3&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(3,ProgressiveAudio.state(f,37000),mode+" must be rejected");
                assertEquals(0,sample.readDouble(400*4096+100));
            }finally{sample.cleanUp();}
        }
    }
    @Test void mp3SeekBuildsExactIndexAndUsesRangeFrames()throws Exception {
        try(var remote=new Remote(ProgressiveAudioTest.mp3(6000))){
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);var sample=ProgressiveAudio.claim(f);
            try{ready(f,85000);assertEquals(0,sample.readDouble(44100*2*85),.000001);
                assertTrue(remote.starts.stream().anyMatch(n->n>1_000_000));
                ready(f,0);
            }finally{sample.cleanUp();}
        }
    }
    @Test void releaseAbortsBlockedHttpWithoutWaitingForReadTimeout()throws Exception {
        try(var remote=new Remote(audio(500))){
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);var sample=ProgressiveAudio.claim(f);
            Thread.sleep(100);long before=System.nanoTime();sample.cleanUp();
            assertTrue(System.nanoTime()-before<1_000_000_000L,"Cancelling a source must abort the socket instead of waiting for its blocked reader");
            assertFalse(f.exists());
        }
    }
    @Test void incompleteAudioIsHiddenFromAnalysisAndCompletePcmIsRealWav()throws Exception {
        try(var remote=new Remote(audio(30))){
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);
            var player=new com.muoniumplayer.core.ncm.music.AudioPlayer(f);
            try{assertNull(player.getSourceFile(),"Incomplete PCM must not be treated as a whole track");
                remote.release.countDown();long until=System.nanoTime()+3_000_000_000L;
                while(player.getSourceFile()==null&&System.nanoTime()<until){ProgressiveAudio.state(f,2500);Thread.sleep(10);}
                File wav=player.getSourceFile();assertNotNull(wav,"Complete decoded PCM should enable original analysis");
                byte[] bytes=java.nio.file.Files.readAllBytes(wav.toPath());
                assertEquals("RIFF",new String(bytes,0,4));assertEquals("WAVE",new String(bytes,8,4));
                assertEquals(44+30*4096*4,bytes.length);
                var b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                assertEquals(30/32768f,b.getFloat(44+(29*4096+100)*4),.000001);
            }finally{player.close();}
        }
    }
    @Test void crcBrokenLosslessFrameIsRejectedInsteadOfAcceptedAsSilence()throws Exception {
        byte[] encoded=audio(50);encoded[42+10]^=1;
        try(var remote=new Remote(encoded)){
            File f=ProgressiveAudio.prepare(remote.url());
            try{assertNull(f,"Corrupt FLAC PCM must not be published as valid audio");}
            finally{ProgressiveAudio.discard(f);}
        }
    }
    @Test void cancellingBeforeAnyAudioArrivesAbortsPreparePromptly()throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            var headers=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new AtomicBoolean();
            Thread http=new Thread(()->{try(var socket=server.accept()){
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));while(!r.readLine().isEmpty()){}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n".getBytes());
                socket.getOutputStream().flush();headers.countDown();release.await(5,TimeUnit.SECONDS);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            var pool=Executors.newSingleThreadExecutor();
            try{
                var future=pool.submit(()->ProgressiveAudio.prepare("http://127.0.0.1:"+server.getLocalPort()+"/audio",cancelled::get));
                assertTrue(headers.await(2,TimeUnit.SECONDS));cancelled.set(true);
                ExecutionException failure=assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class,failure.getCause());
            }finally{release.countDown();pool.shutdownNow();http.join(1000);}
        }
    }
    @Test void compressedStereoFlacAndNonSilentVbrMp3SeekMatchSequentialDecode()throws Exception {
        for(String codec:List.of("flac","mp3")){
            String fixture=codec.equals("flac")?"compressed.flac.b64":"vbr.mp3.b64";
            byte[] encoded;
            try(var in=getClass().getResourceAsStream("/owned-audio/"+fixture)){assertNotNull(in);encoded=Base64.getMimeDecoder().decode(in.readAllBytes());}
            int target=44100*80;float[] expected=new float[2048];
            try(ProgressiveAudio.FrameSource baseline=codec.equals("flac")?new ProgressiveAudio.FlacSource(new ByteArrayInputStream(encoded)):new ProgressiveAudio.Mp3Source(new ByteArrayInputStream(encoded),encoded.length)){
                long pos=0;int copied=0;float[] block;
                while(copied<expected.length&&(block=baseline.next())!=null){
                    for(int j=0;j<block.length;j++)if(pos+j>=target*2L&&copied<expected.length)expected[copied++]=block[j];
                    pos+=block.length;
                }assertEquals(expected.length,copied);assertTrue(Math.abs(expected[50])>.001);
            }
            try(var remote=new Remote(encoded)){
                File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f,codec);var sample=ProgressiveAudio.claim(f);
                try{ready(f,80000);for(int j=0;j<expected.length;j++)assertEquals(expected[j],sample.readDouble(target*2+j),.00005,codec+" sample "+j);
                    assertEquals(1,remote.release.getCount(),"Must seek while initial sequential transfer is still delayed");
                }finally{sample.cleanUp();}
            }
        }
    }
    @Test void seekingNearEndDoesNotRefetchTheSameShortTailForever()throws Exception {
        try(var remote=new Remote(audio(500))){
            File f=ProgressiveAudio.prepare(remote.url());assertNotNull(f);var sample=ProgressiveAudio.claim(f);
            try{
                float ms=499*4096*1000f/44100;ready(f,ms);Thread.sleep(120);
                int requests=remote.starts.size();Thread.sleep(200);
                assertEquals(requests,remote.starts.size(),"A buffered tail shorter than one second is already ready");
                assertEquals(500/32768.0,sample.readDouble(499*4096+100),.000001);
                assertEquals(2,ProgressiveAudio.state(f,500*4096*1000f/44100+1),"A decoded sparse tail can finish without fetching unused gaps");
            }finally{sample.cleanUp();}
        }
    }
}
