package com.muoniumplayer.bedrock;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LiveRadioTransportTest {
    @Test void hlsMasterReloadDeduplicatesAlreadyPlayedSegments()throws Exception {
        byte[] song=ProgressiveAudioTest.mp3(2),ts=transport(song);var polls=new java.util.concurrent.atomic.AtomicInteger();
        try(var server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())){
            Thread http=new Thread(()->{while(!server.isClosed())try(var s=server.accept()){
                var r=new BufferedReader(new InputStreamReader(s.getInputStream()));String line=r.readLine();while(!r.readLine().isEmpty()){}
                byte[] body;
                if(line.contains("master"))body="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=24000\nmedia.m3u8\n".getBytes();
                else if(line.contains("media"))body=(polls.getAndIncrement()==0?
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n#EXTINF:1,\n1.ts\n#EXTINF:1,\n2.ts\n#EXTINF:1,\n3.ts\n":
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:2\n#EXTINF:1,\n2.ts\n#EXTINF:1,\n3.ts\n#EXTINF:1,\n4.ts\n#EXT-X-ENDLIST\n").getBytes();
                else body=ts;
                var o=s.getOutputStream();o.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes());o.write(body);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            try(var input=LiveRadioTransport.open("http://127.0.0.1:"+server.getLocalPort()+"/master.m3u8")){
                assertEquals(song.length*3,input.readAllBytes().length,"Live starts near current end and reload does not repeat old segments");
            }assertEquals(2,polls.get());
        }
    }
    @Test void radioFramesDecodeMp3AndAacToNonemptyPcm()throws Exception {
        try(var frames=new RadioFrames(new ByteArrayInputStream(ProgressiveAudioTest.mp3(4)))){
            var f=frames.next();assertNotNull(f);assertTrue(f.pcm().length>0);assertEquals(44100,f.format().getSampleRate());
        }
        // AAC-LC stereo silent raw_data_block, wrapped in three own ADTS frames.
        byte[] adts=HexFormat.of().parseHex("fff1508001bffc211004608c1c");
        var own=new ByteArrayOutputStream();for(int i=0;i<3;i++)own.write(adts);
        try(var frames=new RadioFrames(new ByteArrayInputStream(own.toByteArray()))){
            var f=frames.next();assertNotNull(f);assertTrue(f.pcm().length>0);assertEquals(2,f.format().getChannels());
        }
    }
    @Test void rejectsProtectedHlsAndStopsWithoutFetchingSegments()throws Exception {
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Thread http=new Thread(()->{try(var s=server.accept()){
                var r=new BufferedReader(new InputStreamReader(s.getInputStream()));while(!r.readLine().isEmpty()){}
                byte[] b="#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=key\n#EXTINF:1,\ns.ts\n".getBytes();
                var o=s.getOutputStream();o.write(("HTTP/1.1 200 OK\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes());o.write(b);
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            var e=assertThrows(IOException.class,()->LiveRadioTransport.open("http://127.0.0.1:"+server.getLocalPort()+"/p"));
            assertTrue(e.getMessage().contains("Encrypted"));http.join(1000);
        }
    }
    @Test void hlsMpegTransportYieldsAudioAndHonorsEndList() throws Exception {
        Class<?> type;
        try{type=Class.forName("com.muoniumplayer.bedrock.LiveRadioTransport");}
        catch(Exception e){fail("MP3-only radio cannot consume an HLS playlist");return;}
        // Deterministic single TS PES containing synthetic MPEG audio; no game/account.
        byte[] elementary=ProgressiveAudioTest.mp3(3),ts=transport(elementary);
        var requests=Collections.synchronizedList(new ArrayList<String>());
        try(var server=new ServerSocket(0,8,InetAddress.getLoopbackAddress())) {
            Thread http=new Thread(()->{while(!server.isClosed())try(var s=server.accept()){
                var r=new BufferedReader(new InputStreamReader(s.getInputStream()));String request=r.readLine();requests.add(request);
                while(!r.readLine().isEmpty()){}
                byte[] body=request.contains("segment.ts")?ts:"#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:14\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n".getBytes();
                var o=s.getOutputStream();o.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes());o.write(body);o.flush();
            }catch(Exception ignored){}});http.setDaemon(true);http.start();
            try(InputStream input=(InputStream)type.getMethod("open",String.class).invoke(null,"http://127.0.0.1:"+server.getLocalPort()+"/live.m3u8")) {
                assertArrayEquals(elementary,input.readAllBytes());
            }
            assertEquals(2,requests.size(),"Finite HLS must not endlessly refetch/replay the same segment");
        }
    }
    static byte[] transport(byte[] audio)throws IOException {
        var pes=new ByteArrayOutputStream();pes.write(new byte[]{0,0,1,(byte)192,0,0,(byte)128,0,0});pes.write(audio);
        byte[] p=pes.toByteArray();var out=new ByteArrayOutputStream();int offset=0,cc=0;
        while(offset<p.length){byte[] packet=new byte[188];Arrays.fill(packet,(byte)255);packet[0]=71;packet[1]=(byte)(offset==0?65:1);packet[2]=1;
            int n=Math.min(184,p.length-offset),start=4;
            if(n<184){packet[3]=(byte)(48|(cc++&15));packet[4]=(byte)(183-n);if(n<183)packet[5]=0;start=188-n;}
            else packet[3]=(byte)(16|(cc++&15));System.arraycopy(p,offset,packet,start,n);offset+=n;out.write(packet);
        }return out.toByteArray();
    }
}
