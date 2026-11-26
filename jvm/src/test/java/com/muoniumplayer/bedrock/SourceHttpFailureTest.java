package com.muoniumplayer.bedrock;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SourceHttpFailureTest {
    @ParameterizedTest @ValueSource(ints={403,412,429,567})
    void rejectedAudioMustNotReturnToWholeFileDownload(int status)throws Exception {
        var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/audio",e->{requests.incrementAndGet();e.sendResponseHeaders(status,-1);e.close();});server.start();
        try {
            var failure=assertThrows(RuntimeException.class,()->ProgressiveAudio.prepare("http://127.0.0.1:"+server.getAddress().getPort()+"/audio"));
            assertTrue(failure.getMessage().contains(Integer.toString(status)));
            assertEquals(1,requests.get());assertTrue(ProgressiveAudio.transports.isEmpty());
        }finally{server.stop(0);ProgressiveAudio.closePending();}
    }
}
