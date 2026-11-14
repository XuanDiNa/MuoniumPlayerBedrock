package com.muoniumplayer.bedrock;

import org.java_websocket.*;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.drafts.*;
import org.java_websocket.handshake.*;
import org.java_websocket.exceptions.InvalidDataException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Only cloudflared on loopback can reach this listener; the target is fixed. */
final class TunnelRelay implements AutoCloseable {
    private final ConcurrentHashMap<WebSocket,TunnelPipe> peers=new ConcurrentHashMap<>();
    private final CompletableFuture<Void> started=new CompletableFuture<>();
    private final WebSocketServer server;
    private TunnelRelay(int targetPort,String token) {
        if(targetPort<1||targetPort>65535||!token.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Relay configuration");
        server=new WebSocketServer(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),1,
            List.of(new Draft_6455(List.of(),TunnelPipe.MAX_MESSAGE))) {
            @Override public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn,Draft draft,ClientHandshake request) throws InvalidDataException {
                if(!request.getResourceDescriptor().equals("/room/"+token)||peers.size()>=16)
                    throw new InvalidDataException(1008,"Room endpoint unavailable");
                return super.onWebsocketHandshakeReceivedAsServer(conn,draft,request);
            }
            public void onOpen(WebSocket ws,ClientHandshake request) {
                if(peers.size()>=16){ws.close(1013,"Room full");return;}
                var pipe=new TunnelPipe(ws,new Socket());peers.put(ws,pipe);pipe.start(targetPort);
            }
            public void onMessage(WebSocket ws,String text){ws.close(1003,"Binary room transport required");}
            public void onMessage(WebSocket ws,ByteBuffer bytes){var pipe=peers.get(ws);if(pipe!=null)pipe.receive(bytes);}
            public void onClose(WebSocket ws,int code,String reason,boolean remote){var pipe=peers.remove(ws);if(pipe!=null)pipe.close();}
            public void onError(WebSocket ws,Exception error){if(!started.isDone())started.completeExceptionally(error);if(ws!=null)ws.close();}
            public void onStart(){started.complete(null);}
        };
        server.setReuseAddr(true);server.setDaemon(true);server.setConnectionLostTimeout(30);
    }
    static TunnelRelay open(int targetPort,String token)throws Exception {
        var relay=new TunnelRelay(targetPort,token);
        try{relay.server.start();relay.started.get(5,TimeUnit.SECONDS);return relay;}
        catch(Exception e){relay.close();throw e;}
    }
    int port(){return server.getPort();}
    public void close(){for(var pipe:peers.values())pipe.close();peers.clear();try{server.stop(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
