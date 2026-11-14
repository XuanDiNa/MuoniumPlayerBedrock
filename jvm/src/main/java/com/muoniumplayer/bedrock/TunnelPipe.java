package com.muoniumplayer.bedrock;

import org.java_websocket.WebSocket;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded, binary, fixed-destination byte pipe. No room JSON is interpreted. */
final class TunnelPipe implements AutoCloseable {
    static final int CHUNK=32768,MAX_MESSAGE=65536;
    private final WebSocket websocket;
    private final Socket socket;
    private final ArrayBlockingQueue<byte[]> incoming=new ArrayBlockingQueue<>(32);
    private final AtomicBoolean closed=new AtomicBoolean();
    private Thread reader,writer;
    TunnelPipe(WebSocket websocket,Socket socket){this.websocket=websocket;this.socket=socket;}
    void receive(ByteBuffer bytes) {
        if(closed.get())return;
        if(bytes.remaining()>MAX_MESSAGE){close();return;}
        byte[] copy=new byte[bytes.remaining()];bytes.get(copy);
        if(!incoming.offer(copy))close();
    }
    void start(int targetPort) {
        reader=Thread.ofVirtual().name("muonium-tunnel-read").start(()->{
            try {
                if(!socket.isConnected())socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),targetPort),5000);
                socket.setTcpNoDelay(true);
                if(closed.get())return;
                writer=Thread.ofVirtual().name("muonium-tunnel-write").start(()->{
                    try{var out=socket.getOutputStream();while(!closed.get())out.write(incoming.take());}
                    catch(Exception ignored){}finally{close();}
                });
                byte[] buffer=new byte[CHUNK];var in=socket.getInputStream();int n;
                while(!closed.get()&&(n=in.read(buffer))>=0) {
                    if(n==0)continue;websocket.send(Arrays.copyOf(buffer,n));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(websocket.hasBufferedData()&&!closed.get()) {
                        if(!websocket.isOpen()||System.nanoTime()>deadline)throw new SocketTimeoutException();
                        Thread.sleep(5);
                    }
                }
            }catch(Exception ignored){}finally{close();}
        });
    }
    public void close() {
        if(!closed.compareAndSet(false,true))return;
        try{socket.close();}catch(Exception ignored){}
        incoming.clear();if(reader!=null)reader.interrupt();if(writer!=null)writer.interrupt();
        websocket.close();
    }
}
