package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelRelayTest {
    @Test void binaryRoomBytesRoundTripAndOwnedPortsAreReleased() throws Exception {
        try(var echo=new ServerSocket(0,4,InetAddress.getLoopbackAddress())) {
            var task=CompletableFuture.runAsync(()->{
                try(var socket=echo.accept()){socket.getInputStream().transferTo(socket.getOutputStream());}
                catch(Exception e){throw new CompletionException(e);}
            });
            int relayPort,clientPort;
            try(var relay=TunnelRelay.open(echo.getLocalPort(),TunnelInviteTest.TOKEN)) {
                relayPort=relay.port();
                try(var bridge=TunnelClientBridge.open(URI.create("ws://127.0.0.1:"+relayPort+"/room/"+TunnelInviteTest.TOKEN));
                    var socket=new Socket(InetAddress.getLoopbackAddress(),bridge.port())) {
                    clientPort=bridge.port();socket.setSoTimeout(5000);
                    byte[] payload=new byte[130_000];new java.util.Random(47).nextBytes(payload);
                    var sender=CompletableFuture.runAsync(()->{try{socket.getOutputStream().write(payload);}catch(Exception e){throw new CompletionException(e);}});
                    assertArrayEquals(payload,socket.getInputStream().readNBytes(payload.length));
                    sender.get(5,TimeUnit.SECONDS);
                }
            }
            task.get(5,TimeUnit.SECONDS);
            for(int port:new int[]{relayPort,clientPort})try(var freed=new ServerSocket()) {
                freed.setReuseAddress(true);freed.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port));
            }
        }
    }
    @Test void wrongPathIsRejectedWithoutOpeningTheRoomSocket() throws Exception {
        try(var target=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            var relay=TunnelRelay.open(target.getLocalPort(),TunnelInviteTest.TOKEN)) {
            target.setSoTimeout(250);
            assertThrows(Exception.class,()->TunnelClientBridge.open(URI.create("ws://127.0.0.1:"+relay.port()+"/room/wrong")));
            assertThrows(SocketTimeoutException.class,target::accept);
        }
    }
    @Test void textAndOversizedMessagesCloseOnlyTheirOwnedConnection()throws Exception {
        try(var target=new ServerSocket(0,4,InetAddress.getLoopbackAddress());
            var relay=TunnelRelay.open(target.getLocalPort(),TunnelInviteTest.TOKEN)) {
            target.setSoTimeout(5000);
            for(boolean oversized:new boolean[]{false,true}) {
                var closed=new CountDownLatch(1);
                var ws=new org.java_websocket.client.WebSocketClient(URI.create("ws://127.0.0.1:"+relay.port()+"/room/"+TunnelInviteTest.TOKEN)) {
                    public void onOpen(org.java_websocket.handshake.ServerHandshake h){}
                    public void onMessage(String text){}
                    public void onClose(int code,String reason,boolean remote){closed.countDown();}
                    public void onError(Exception error){}
                };
                ws.setDaemon(true);
                try {
                    assertTrue(ws.connectBlocking(5,TimeUnit.SECONDS));
                    try(var socket=target.accept()) {
                        socket.setSoTimeout(5000);
                        if(oversized)ws.send(new byte[TunnelPipe.MAX_MESSAGE+1]);else ws.send("not a binary room frame");
                        assertTrue(closed.await(5,TimeUnit.SECONDS));
                        assertEquals(-1,socket.getInputStream().read(),"Rejected peers must release the target socket");
                    }
                }finally{ws.close();}
            }
        }
    }
}
