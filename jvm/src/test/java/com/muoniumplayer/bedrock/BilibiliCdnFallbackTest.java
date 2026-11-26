package com.muoniumplayer.bedrock;
import com.muoniumplayer.core.ncm.music.BilibiliCdnRoutes;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BilibiliCdnFallbackTest {
    @Test void rejectedPrimaryUsesSameRepresentationsBackupAndStaysOnThatObject()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var first=new AtomicInteger();var backup=new AtomicInteger();byte[] body={1,2,3,4,5,6,7,8};
        server.createContext("/primary",e->{first.incrementAndGet();e.sendResponseHeaders(403,-1);e.close();});
        server.createContext("/backup",e->{backup.incrementAndGet();var range=e.getRequestHeaders().getFirst("Range");
            e.getResponseHeaders().set("ETag","\"same-track\"");
            int start=range==null?0:Integer.parseInt(range.substring(6,range.indexOf('-')));
            if(range!=null)e.getResponseHeaders().set("Content-Range","bytes "+start+"-7/8");
            e.sendResponseHeaders(range==null?200:206,body.length-start);e.getResponseBody().write(body,start,body.length-start);e.close();});
        server.start();String root="http://127.0.0.1:"+server.getAddress().getPort();
        try{
            var stream=new JsonObject();var urls=new JsonArray();urls.add(root+"/backup");urls.add(root+"/backup");stream.add("backupUrl",urls);
            String primary=BilibiliCdnRoutes.remember(stream,root+"/primary");
            try(var audio=new AudioRanges(primary,()->false)){
                assertArrayEquals(body,audio.initial.readAllBytes());assertEquals(root+"/backup",audio.url);
                try(var tail=audio.from(4)){assertArrayEquals(new byte[]{5,6,7,8},tail.readAllBytes());}
            }
            assertEquals(1,first.get());assertEquals(2,backup.get());
            assertTrue(ProgressiveAudio.transports.isEmpty());
        }finally{server.stop(0);}
    }
    @Test void candidateAddressesAreDeduplicatedBoundedAndKeepRepresentationOrder(){
        var stream=JsonParser.parseString("{\"backup_url\":[\"https://b.example/a\",\"https://b.example/a\",\"file:///c:/x\",\"https://c.example/a\",\"https://d.example/a\"]}").getAsJsonObject();
        String primary=BilibiliCdnRoutes.remember(stream,"https://a.example/a");
        assertEquals(List.of(primary,"https://b.example/a","https://c.example/a"),BilibiliCdnRoutes.candidates(primary));
        assertEquals(List.of("https://unrelated.example/a"),BilibiliCdnRoutes.candidates("https://unrelated.example/a"));
    }
}
