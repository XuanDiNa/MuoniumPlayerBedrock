package com.muoniumplayer.bedrock;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-client local adapter for the unchanged upstream DirectClient. */
final class TunnelClientBridge implements AutoCloseable {
    private final ServerSocket listener;
    private final WebSocketClient websocket;
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile TunnelPipe pipe;
    private volatile Exception failure;
    private volatile Socket connecting;
    private Thread acceptor;
    private TunnelClientBridge(URI endpoint,RoomProxySettings.Value proxy)throws Exception {
        if(!"wss".equals(endpoint.getScheme())&&!("ws".equals(endpoint.getScheme())&&"127.0.0.1".equals(endpoint.getHost())))
            throw new IllegalArgumentException("Encrypted transport required");
        listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());listener.setSoTimeout(20000);
        websocket=new WebSocketClient(endpoint,new Draft_6455(List.of(),TunnelPipe.MAX_MESSAGE)) {
            public void onOpen(ServerHandshake handshake){}
            @Override protected void onSetSSLParameters(javax.net.ssl.SSLParameters parameters) {
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
            }
            public void onMessage(String text){TunnelClientBridge.this.close();}
            public void onMessage(ByteBuffer bytes){var p=pipe;if(p!=null)p.receive(bytes);else TunnelClientBridge.this.close();}
            public void onClose(int code,String reason,boolean remote){TunnelClientBridge.this.close();}
            public void onError(Exception error){failure=error;TunnelClientBridge.this.close();}
        };
        // The factory returns a connected TLS socket for wss. Java-WebSocket
        // does not automatically TLS-wrap sockets supplied by a custom factory.
        websocket.setSocketFactory(new javax.net.SocketFactory() {
            public Socket createSocket()throws java.io.IOException {
                return RoomProxyTransport.open(endpoint,proxy,8000,s->{
                    connecting=s;if(closed.get())try{s.close();}catch(java.io.IOException ignored){}
                });
            }
            public Socket createSocket(String h,int p)throws java.io.IOException{throw new java.io.IOException("Room endpoint required");}
            public Socket createSocket(String h,int p,InetAddress l,int lp)throws java.io.IOException{throw new java.io.IOException("Room endpoint required");}
            public Socket createSocket(InetAddress h,int p)throws java.io.IOException{throw new java.io.IOException("Room endpoint required");}
            public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws java.io.IOException{throw new java.io.IOException("Room endpoint required");}
        });
        websocket.setDaemon(true);websocket.setConnectionLostTimeout(30);
    }
    static TunnelClientBridge open(URI endpoint)throws Exception {
        return open(endpoint,new RoomProxySettings.Value(RoomProxySettings.Mode.DIRECT,"",0));
    }
    static TunnelClientBridge open(URI endpoint,RoomProxySettings.Value proxy)throws Exception {return prepare(endpoint,proxy).connect();}
    static TunnelClientBridge prepare(URI endpoint,RoomProxySettings.Value proxy)throws Exception {return new TunnelClientBridge(endpoint,proxy);}
    TunnelClientBridge connect()throws Exception {
        var bridge=this;
        try {
            if(!bridge.websocket.connectBlocking(12,TimeUnit.SECONDS)||bridge.closed.get()) {
                var error=new ConnectException("Internet room unavailable");if(bridge.failure!=null)error.initCause(bridge.failure);throw error;
            }
            bridge.acceptor=Thread.ofVirtual().name("muonium-tunnel-local").start(()->{
                try {
                    var socket=bridge.listener.accept();bridge.listener.close();
                    var p=new TunnelPipe(bridge.websocket,socket);bridge.pipe=p;
                    if(bridge.closed.get())p.close();else p.start(0);
                }catch(Exception ignored){bridge.close();}
            });return bridge;
        }catch(Exception e){bridge.close();throw e;}
    }
    int port(){return listener.getLocalPort();}
    public void close(){
        if(!closed.compareAndSet(false,true))return;
        try{listener.close();}catch(Exception ignored){}
        var socket=connecting;if(socket!=null)try{socket.close();}catch(Exception ignored){}
        var p=pipe;if(p!=null)p.close();websocket.close();if(acceptor!=null)acceptor.interrupt();
    }
}
