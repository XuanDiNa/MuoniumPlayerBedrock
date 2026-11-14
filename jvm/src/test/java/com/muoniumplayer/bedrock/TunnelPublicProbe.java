package com.muoniumplayer.bedrock;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Explicit development probe. Disposable echo bytes only, no game/private data. */
public final class TunnelPublicProbe {
    public static void main(String[] args)throws Exception {
        try(var echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var task=CompletableFuture.runAsync(()->{
                try(var socket=echo.accept()){socket.getInputStream().transferTo(socket.getOutputStream());}
                catch(Exception e){throw new CompletionException(e);}
            });
            try(var relay=TunnelRelay.open(echo.getLocalPort(),TunnelInviteTest.TOKEN);
                var helper=TunnelProcess.startVerified(Path.of(args[0]),relay.port())) {
                String url=helper.awaitReady(Duration.ofSeconds(90));
                var parsed=TunnelInvite.parse(TunnelInvite.create(url,TunnelInviteTest.TOKEN,"abcdefgh",""));
                // A new temporary hostname can take time to propagate; no DNS
                // substitution or certificate bypass is used by this probe.
                TunnelClientBridge connected=null;Exception last=null;
                for(int attempt=0;attempt<3&&connected==null;attempt++) {
                    try{connected=TunnelClientBridge.open(parsed.endpoint());}
                    catch(Exception e){last=e;if(attempt<2)Thread.sleep(16000);}
                }
                if(connected==null){
                    Throwable cause=last;while(cause!=null&&cause.getCause()!=null)cause=cause.getCause();
                    System.err.println("PUBLIC_TUNNEL edgeRegistered=true transportFailed="+(cause==null?"unknown":cause.getClass().getSimpleName()));
                    throw new java.io.IOException("Public room transport did not connect");
                }
                try(var bridge=connected;var peer=new Socket(InetAddress.getLoopbackAddress(),bridge.port())) {
                    peer.setSoTimeout(15000);byte[] bytes="Muonium disposable room transport fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    peer.getOutputStream().write(bytes);
                    if(!java.util.Arrays.equals(bytes,peer.getInputStream().readNBytes(bytes.length)))throw new AssertionError("Public echo mismatch");
                    System.out.println("PUBLIC_TUNNEL tls=true binaryRoundtrip=true no-game=true no-credentials=true");
                }
            }
            task.get(10,TimeUnit.SECONDS);
        }
    }
}
