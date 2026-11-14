package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import repackage.com.jsyn.data.FloatSample;
import static org.junit.jupiter.api.Assertions.*;

class ProgressiveAudioTest {
    static byte[] mp3(int frames) {
        byte[] data=new byte[417*frames];
        for(int i=0;i<frames;i++){int p=417*i;data[p]=(byte)255;data[p+1]=(byte)251;data[p+2]=(byte)144;}
        return data; // Own synthetic MPEG1/L3 silent frames, no media license/network.
    }
    static Class<?> implementation() {
        try{return Class.forName("com.muoniumplayer.bedrock.ProgressiveAudio");}
        catch(Exception e){return fail("Missing progressive audio; current player waits for full download");}
    }
    @Test void startsBeforeDelayedTailAndCloseStopsTransferAndDeletesWork() throws Exception {
        var type=implementation();
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var release=new CountDownLatch(1);var waiting=new CountDownLatch(1);
            byte[] payload=mp3(1000);
            Thread http=new Thread(()->{try(var socket=server.accept()){
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));
                while(!r.readLine().isEmpty()){}
                var out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+payload.length+"\r\nContent-Type: audio/mpeg\r\nConnection: close\r\n\r\n").getBytes());
                out.write(payload,0,417*130);out.flush();waiting.countDown();release.await(8,TimeUnit.SECONDS);
                out.write(payload,417*130,payload.length-417*130);out.flush();
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            var call=Executors.newSingleThreadExecutor();File file=null;FloatSample sample=null;
            try {
                var future=call.submit(()->(File)type.getMethod("prepare",String.class).invoke(null,"http://127.0.0.1:"+server.getLocalPort()+"/track.mp3"));
                assertTrue(waiting.await(2,TimeUnit.SECONDS));
                file=future.get(3,TimeUnit.SECONDS);
                assertNotNull(file,"Supported MP3 must stream instead of falling back");
                assertEquals(1,release.getCount(),"Do not wait for EOF before playback");
                sample=(FloatSample)type.getMethod("claim",File.class).invoke(null,file);
                assertNotNull(sample);assertTrue(sample.getNumFrames()>1_000_000,"Length comes from encoded bytes, not prefix");
                assertEquals(0,sample.readDouble(0),.01);
                long before=System.nanoTime();
                assertEquals(1,type.getMethod("state",File.class,float.class).invoke(null,file,8000f),"Seek beyond downloaded audio must enter buffering");
                sample.readDouble(44100*2*8);
                assertTrue(System.nanoTime()-before<100_000_000L,"Underrun must not block the shared audio thread on network");
                sample.cleanUp();sample=null;
                assertFalse(file.exists(),"Release removes session work immediately");
            } finally {
                if(sample!=null)sample.cleanUp();release.countDown();call.shutdownNow();http.join(1200);
            }
        }
    }
    @Test void invalidPayloadIsRejectedWithoutRetainedWork() throws Exception {
        var type=implementation();
        assertFalse(ProgressiveAudio.isProgressive(new File("progressive-user-owned.flac")),"A local filename must not become a streaming token");
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Thread http=new Thread(()->{try(var socket=server.accept()){
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));while(!r.readLine().isEmpty()){}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\n<html>no</html>".getBytes());
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            assertNull(type.getMethod("prepare",String.class).invoke(null,"http://127.0.0.1:"+server.getLocalPort()+"/bad.mp3"));
            http.join(1000);
        }
    }
    @Test void originalSoundFileReceivesProgressiveSampleWithoutTryingToDecodeToken()throws Exception {
        byte[] encoded=flac(24);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Thread http=new Thread(()->{try(var socket=server.accept()){
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));while(!r.readLine().isEmpty()){}
                var out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+encoded.length+"\r\nConnection: close\r\n\r\n").getBytes());out.write(encoded);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            File token=ProgressiveAudio.prepare("http://127.0.0.1:"+server.getLocalPort()+"/owned.flac");assertNotNull(token);
            var player=new com.muoniumplayer.core.ncm.music.AudioPlayer(token);
            try {assertTrue(player.isUsable());assertTrue(player.getTotalTimeMillis()>2000);
                assertNotEquals(token,player.getSourceFile(),"Whole-file analysis must never decode the registry token; completed PCM may be exposed");
            }finally{player.close();}
            assertFalse(token.exists(),"Original SoundFile cleanup releases progressive work");
        }
    }
    static int crc(byte[] data,int bits,int polynomial){int crc=0,top=1<<(bits-1),mask=(1<<bits)-1;for(byte b:data){crc^=(b&255)<<(bits-8);for(int j=0;j<8;j++)crc=((crc<<1)^((crc&top)!=0?polynomial:0))&mask;}return crc;}
    static byte[] flac(int frames)throws IOException {
        var out=new ByteArrayOutputStream();out.write("fLaC".getBytes());out.write(new byte[]{(byte)128,0,0,34});
        byte[] info=new byte[34];info[0]=16;info[2]=16;
        long packed=(44100L<<44)|(15L<<36)|(frames*4096L);
        for(int i=0;i<8;i++)info[10+i]=(byte)(packed>>((7-i)*8));out.write(info);
        for(int i=0;i<frames;i++){
            var f=new ByteArrayOutputStream();f.write(new byte[]{(byte)255,(byte)248,(byte)201,8});
            if(i<128)f.write(i);else {f.write(192|(i>>6));f.write(128|(i&63));}
            f.write(crc(f.toByteArray(),8,7));f.write(new byte[]{0,0x20,0});
            int c=crc(f.toByteArray(),16,0x8005);f.write(c>>8);f.write(c);out.write(f.toByteArray());
        }return out.toByteArray();
    }
    @Test void losslessFlacStartsWithoutWaitingForWholeTrack() throws Exception {
        var type=implementation();byte[] encoded=flac(120);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            var tail=new CountDownLatch(1);Thread http=new Thread(()->{try(var socket=server.accept()){
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));while(!r.readLine().isEmpty()){}
                var o=socket.getOutputStream();o.write(("HTTP/1.1 200 OK\r\nContent-Length: "+encoded.length+"\r\nConnection: close\r\n\r\n").getBytes());
                // decoder buffered reads need >8k; repeat 1200 frames below if necessary.
                int split=encoded.length-400;o.write(encoded,0,split);o.flush();tail.await(8,TimeUnit.SECONDS);o.write(encoded,split,400);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            var pool=Executors.newSingleThreadExecutor();FloatSample sample=null;
            try {
                File f=pool.submit(()->(File)type.getMethod("prepare",String.class).invoke(null,"http://127.0.0.1:"+server.getLocalPort()+"/song.flac")).get(3,TimeUnit.SECONDS);
                assertNotNull(f,"Default lossless songs must also support early playback");
                sample=(FloatSample)type.getMethod("claim",File.class).invoke(null,f);
                assertEquals(120*4096,sample.getNumFrames());assertEquals(.25,sample.readDouble(0),.000001);
                assertEquals(1,tail.getCount());
                tail.countDown();http.join(1000);
                long until=System.nanoTime()+2_000_000_000L;
                while(ProgressiveAudio.state(f,120*4096*1000f/44100)!=2&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(2,ProgressiveAudio.state(f,120*4096*1000f/44100+1));
                assertEquals(.25,sample.readDouble(120*4096-1),.000001,"Seek/read at the decoded tail retains PCM");
                assertEquals(.25,sample.readDouble(0),.000001,"Backward seek retains earlier PCM in this session");
            }finally{if(sample!=null)sample.cleanUp();tail.countDown();pool.shutdownNow();http.join(1000);}
        }
    }
    @Test void streamTruncationIsReportedInsteadOfSilentPlayingTail()throws Exception {
        byte[] payload=mp3(1000);var stop=new CountDownLatch(1);
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Thread http=new Thread(()->{try(var socket=server.accept()){
                var r=new BufferedReader(new InputStreamReader(socket.getInputStream()));while(!r.readLine().isEmpty()){}
                var out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes());
                out.write(payload,0,417*130);out.flush();stop.await(4,TimeUnit.SECONDS);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            File token=ProgressiveAudio.prepare("http://127.0.0.1:"+server.getLocalPort()+"/truncated.mp3");assertNotNull(token);
            FloatSample sample=ProgressiveAudio.claim(token);
            try {stop.countDown();http.join(1000);long until=System.nanoTime()+2_000_000_000L;
                while(ProgressiveAudio.state(token,0)!=3&&System.nanoTime()<until)Thread.sleep(10);
                assertEquals(3,ProgressiveAudio.state(token,0));
            }finally{sample.cleanUp();}
        }
    }
}
