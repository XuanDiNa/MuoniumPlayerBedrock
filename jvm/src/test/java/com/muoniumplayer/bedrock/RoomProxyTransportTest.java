package com.muoniumplayer.bedrock;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Owned loopback proxy + genuine TLS/WebSocket. No public service or system proxy changes. */
class RoomProxyTransportTest {
    static SSLContext trusted;static SSLContext previous;
    @BeforeAll static void tls() throws Exception {
        var keys=KeyStore.getInstance("PKCS12");
        try(var in=RoomProxyTransportTest.class.getResourceAsStream("/room-proxy-fixture.p12")){assertNotNull(in);keys.load(in,"test-only-room".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-only-room".toCharArray());
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        trusted=SSLContext.getInstance("TLS");trusted.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        previous=SSLContext.getDefault();SSLContext.setDefault(trusted);
    }
    @AfterAll static void restore(){SSLContext.setDefault(previous);}
    @Test void httpConnectCarriesTlsWebsocketAndBinaryRoomTraffic() throws Exception { roundTrip(false); }
    @Test void socks5SendsRemoteDnsNameAndCarriesTlsWebsocket() throws Exception { roundTrip(true); }
    void roundTrip(boolean socks) throws Exception {
        try(var echo=new Echo();var proxy=new ProxyFixture(socks,echo.port(),200)) {
            var value=proxy.settings();
            URI endpoint=URI.create("wss://room.invalid:"+echo.port()+"/room/fixture");
            try(var bridge=TunnelClientBridge.open(endpoint,value);
                var client=new Socket(InetAddress.getLoopbackAddress(),bridge.port())) {
                client.setSoTimeout(5000);byte[] payload=new byte[130000];new Random(19).nextBytes(payload);
                var sender=CompletableFuture.runAsync(()->{try{client.getOutputStream().write(payload);}catch(IOException e){throw new CompletionException(e);}});
                assertArrayEquals(payload,client.getInputStream().readNBytes(payload.length));sender.get(5,TimeUnit.SECONDS);
                assertEquals("room.invalid:"+echo.port(),proxy.request.get(5,TimeUnit.SECONDS));
            }
        }
    }
    @Test void proxyRejectionNeverFallsBackToAnOtherwiseReachableTarget() throws Exception {
        try(var target=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var proxy=new ProxyFixture(false,target.getLocalPort(),407)) {
            target.setSoTimeout(250);
            assertThrows(IOException.class,()->RoomProxyTransport.open(URI.create("ws://127.0.0.1:"+target.getLocalPort()+"/"),proxy.settings(),1500,s->{}));
            assertEquals("127.0.0.1:"+target.getLocalPort(),proxy.request.get(5,TimeUnit.SECONDS));
            assertThrows(SocketTimeoutException.class,target::accept);
        }
    }
    @Test void tlsNameValidationSurvivesTheProxy() throws Exception {
        try(var echo=new Echo();var proxy=new ProxyFixture(false,echo.port(),200)) {
            assertThrows(SSLException.class,()->RoomProxyTransport.open(URI.create("wss://wrong.invalid:"+echo.port()+"/"),proxy.settings(),3000,s->{}));
        }
    }
    @Test void failureHintsDoNotExposeEndpointOrCredentials() {
        var cause=new ConnectException("outer");cause.initCause(new IOException("代理要求认证；此版本仅支持无认证代理"));
        assertTrue(RoomProxyTransport.failureMessage(cause).contains("认证"));
        String message=RoomProxyTransport.failureMessage(new IOException("secret-user:secret-password@room.invalid/private-invite"));
        assertFalse(message.contains("secret"));assertFalse(message.contains("room.invalid"));
    }
    @Test void hangingProxyTimesOutAndCloseCancelsAnInflightHandshake() throws Exception {
        try(var proxy=new ServerSocket(0,2,InetAddress.getLoopbackAddress())) {
            var setting=new RoomProxySettings.Value(RoomProxySettings.Mode.HTTP,"127.0.0.1",proxy.getLocalPort());
            var held=new AtomicReference<Socket>();
            long start=System.nanoTime();
            assertThrows(SocketTimeoutException.class,()->RoomProxyTransport.open(URI.create("ws://127.0.0.1:1/"),setting,250,held::set));
            assertTrue(held.get().isClosed());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(3));
            try(var accepted=proxy.accept()){}
            var cancelled=new CompletableFuture<Socket>();
            var task=CompletableFuture.runAsync(()->assertThrows(IOException.class,()->RoomProxyTransport.open(
                    URI.create("wss://room.invalid:443/"),setting,10000,cancelled::complete)));
            try(var accepted=proxy.accept()){cancelled.get(2,TimeUnit.SECONDS).close();task.get(2,TimeUnit.SECONDS);}
        }
    }
    @Test void directIgnoresAmbientJavaProxyAndDoesNotUseProxySelector() throws Exception {
        var old=ProxySelector.getDefault();var calls=new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){calls.incrementAndGet();throw new AssertionError("Global proxy leaked");}public void connectFailed(URI u,SocketAddress a,IOException e){}});
        try(var target=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            var socket=RoomProxyTransport.open(URI.create("ws://127.0.0.1:"+target.getLocalPort()+"/"),new RoomProxySettings.Value(RoomProxySettings.Mode.DIRECT,"",0),1000,s->{});
            var accepted=target.accept()) {assertTrue(socket.isConnected());assertEquals(0,calls.get());}
        finally{ProxySelector.setDefault(old);}
    }
    @Test void closingPreparedBridgeCancelsPendingHandshakeAndReleasesItsPort() throws Exception {
        try(var proxy=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            proxy.setSoTimeout(3000);
            var setting=new RoomProxySettings.Value(RoomProxySettings.Mode.HTTP,"127.0.0.1",proxy.getLocalPort());
            var bridge=TunnelClientBridge.prepare(URI.create("wss://room.invalid/room/fixture"),setting);int local=bridge.port();
            var pending=CompletableFuture.runAsync(()->assertThrows(Exception.class,bridge::connect));
            try(var accepted=proxy.accept()){bridge.close();pending.get(3,TimeUnit.SECONDS);}
            try(var released=new ServerSocket()){released.setReuseAddress(true);released.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),local));}
        }
    }
    static final class Echo implements AutoCloseable {
        final SSLServerSocket listener;final Thread worker;final AtomicReference<Socket> accepted=new AtomicReference<>();
        final CompletableFuture<Void> completed=new CompletableFuture<>();
        Echo() throws Exception {
            // Blocking JDK TLS avoids the dependency's SSLEngine selector/close
            // assertion race. Production still uses its real WebSocket client.
            listener=(SSLServerSocket)trusted.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress());
            worker=Thread.ofVirtual().start(()->{try(var socket=listener.accept()){
                accepted.set(socket);socket.setSoTimeout(5000);var in=new DataInputStream(socket.getInputStream());var out=new DataOutputStream(socket.getOutputStream());
                String request=header(in);String key=Arrays.stream(request.split("\r\n")).filter(l->l.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))
                        .map(l->l.substring(l.indexOf(':')+1).strip()).findFirst().orElseThrow();
                String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
                while(true){int first=in.readUnsignedByte(),second=in.readUnsignedByte();assertTrue((second&128)!=0,"Client frames must be masked");
                    long length=second&127;if(length==126)length=in.readUnsignedShort();else if(length==127)length=in.readLong();
                    assertTrue(length>=0&&length<=2_000_000);byte[] mask=in.readNBytes(4),data=in.readNBytes((int)length);assertEquals(length,data.length);
                    for(int i=0;i<data.length;i++)data[i]^=mask[i%4];int opcode=first&15;
                    out.writeByte(opcode==9?0x8a:first);if(length<126)out.writeByte((int)length);else if(length<=65535){out.writeByte(126);out.writeShort((int)length);}else{out.writeByte(127);out.writeLong(length);}
                    out.write(data);out.flush();if(opcode==8)break;
                }
                completed.complete(null);
            }catch(IOException e){completed.complete(null);}catch(Throwable e){completed.completeExceptionally(e);}});
        }
        int port(){return listener.getLocalPort();}
        public void close() throws Exception {listener.close();var socket=accepted.get();if(socket!=null)socket.close();worker.join(2000);completed.get(2,TimeUnit.SECONDS);}
    }
    static final class ProxyFixture implements AutoCloseable {
        final ServerSocket listener;final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final CompletableFuture<String> request=new CompletableFuture<>();final boolean socks;final Thread worker;
        ProxyFixture(boolean socks,int targetPort,int status) throws Exception {
            this.socks=socks;listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            worker=Thread.ofVirtual().start(()->{try(var client=listener.accept()) {
                sockets.add(client);client.setSoTimeout(5000);var in=client.getInputStream();var out=client.getOutputStream();
                if(socks) {
                    assertArrayEquals(new byte[]{5,1,0},in.readNBytes(3));out.write(new byte[]{5,0});out.flush();
                    assertArrayEquals(new byte[]{5,1,0,3},in.readNBytes(4));int n=in.read();
                    String host=new String(in.readNBytes(n),StandardCharsets.US_ASCII);int port=(in.read()<<8)|in.read();request.complete(host+":"+port);
                    out.write(new byte[]{5,0,0,1,127,0,0,1,0,1});out.flush();
                } else {
                    String header=header(in);assertTrue(header.contains("\r\nHost: "));assertFalse(header.contains("/room/"));assertFalse(header.contains("Proxy-Authorization"));
                    request.complete(header.split(" ")[1]);out.write(("HTTP/1.1 "+status+" Fixture\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();if(status!=200)return;
                }
                try(var target=new Socket(InetAddress.getLoopbackAddress(),targetPort)) {
                    sockets.add(target);var pump=CompletableFuture.runAsync(()->{try{in.transferTo(target.getOutputStream());target.shutdownOutput();}catch(IOException ignored){}});
                    target.getInputStream().transferTo(out);pump.get(5,TimeUnit.SECONDS);
                }
            }catch(Throwable e){request.completeExceptionally(e);}});
        }
        RoomProxySettings.Value settings(){return new RoomProxySettings.Value(socks?RoomProxySettings.Mode.SOCKS5:RoomProxySettings.Mode.HTTP,"127.0.0.1",listener.getLocalPort());}
        public void close() throws Exception {listener.close();for(var s:sockets)s.close();worker.join(2000);}
    }
    static String header(InputStream in)throws IOException {
        var bytes=new ByteArrayOutputStream();while(bytes.size()<16384){int b=in.read();if(b<0)throw new EOFException();bytes.write(b);
            if(bytes.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))return bytes.toString(StandardCharsets.US_ASCII);}
        throw new IOException("Fixture header too large");
    }
}
