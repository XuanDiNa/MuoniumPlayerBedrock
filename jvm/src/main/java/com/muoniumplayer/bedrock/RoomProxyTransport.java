package com.muoniumplayer.bedrock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** Per-connection proxy handshakes. DNS for the room stays on the proxy side. */
final class RoomProxyTransport {
    static String failureMessage(Throwable failure) {
        for(int i=0;failure!=null&&i<8;i++,failure=failure.getCause()) {
            if(failure instanceof SSLException)return "房间 TLS 证书或加密连接验证失败";
            if(failure instanceof SocketTimeoutException)return "代理或房间连接超时";
            String message=failure.getMessage();
            // Only our bounded local messages; never relay a server reason,
            // invitation URL, arbitrary exception text or a proxy address.
            if(message!=null&&(message.startsWith("系统代理")||message.startsWith("系统自动代理")
                    ||message.startsWith("代理要求认证")||message.startsWith("代理拒绝连接")
                    ||message.startsWith("SOCKS5 代理")))return message;
        }
        return "代理或房主通道不可达，请检查地址、类型和通道状态";
    }
    static Socket open(URI endpoint,RoomProxySettings.Value value,int timeout,Consumer<Socket> owned) throws IOException {
        if(timeout<1)throw new IllegalArgumentException("Connection timeout");
        if(endpoint.getHost()==null||!("ws".equals(endpoint.getScheme())||"wss".equals(endpoint.getScheme())))
            throw new IOException("房间地址无效");
        var policy=value.mode()==RoomProxySettings.Mode.SYSTEM?WindowsRoomProxy.resolve(endpoint):value;
        var deadline=new Deadline(timeout);int port=endpoint.getPort()<0?("wss".equals(endpoint.getScheme())?443:80):endpoint.getPort();
        Socket socket=new Socket(Proxy.NO_PROXY);
        try {
            owned.accept(socket);socket.setTcpNoDelay(true);
            if(policy.mode()==RoomProxySettings.Mode.DIRECT)socket.connect(new InetSocketAddress(endpoint.getHost(),port),deadline.remaining());
            else {
                socket.connect(new InetSocketAddress(policy.host(),policy.port()),deadline.remaining());
                if(policy.mode()==RoomProxySettings.Mode.HTTP)http(socket,endpoint.getHost(),port,deadline);
                else if(policy.mode()==RoomProxySettings.Mode.SOCKS5)socks(socket,endpoint.getHost(),port,deadline);
                else throw new IOException("代理类型不可用");
            }
            if("wss".equals(endpoint.getScheme())) {
                var tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,endpoint.getHost(),port,true);
                socket=tls;owned.accept(tls);tls.setSoTimeout(deadline.remaining());
                var parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);
                tls.startHandshake();
            }
            socket.setSoTimeout(0);
            // Intentionally omit endpoint, invitation, proxy address and credentials.
            System.out.println("ROOM_PROXY connected mode="+value.mode()+" route="+policy.mode()+" tls="+"wss".equals(endpoint.getScheme()));
            return socket;
        }catch(IOException|RuntimeException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }
    private static void http(Socket socket,String host,int port,Deadline deadline) throws IOException {
        String authority=(host.contains(":")&&!host.startsWith("[")?"["+host+"]":host)+":"+port;
        var out=socket.getOutputStream();out.write(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
        var bytes=new ByteArrayOutputStream();int tail=0;boolean ended=false;
        while(bytes.size()<16384) {
            int b=read(socket,deadline);bytes.write(b);tail=(tail<<8)|b;
            if(tail==0x0D0A0D0A){ended=true;break;}
        }
        if(!ended)throw new IOException("代理响应头过长");
        String first=bytes.toString(StandardCharsets.US_ASCII).split("\r\n",2)[0];
        if(!first.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))throw new IOException("HTTP 代理响应无效");
        int code=Integer.parseInt(first.substring(9,12));
        if(code==407)throw new IOException("代理要求认证；此版本仅支持无认证代理");
        if(code<200||code>=300)throw new IOException("代理拒绝连接（HTTP "+code+"）");
    }
    private static void socks(Socket socket,String host,int port,Deadline deadline) throws IOException {
        var out=socket.getOutputStream();out.write(new byte[]{5,1,0});out.flush();
        if(read(socket,deadline)!=5||read(socket,deadline)!=0)throw new IOException("SOCKS5 代理不支持无认证连接");
        byte[] name=host.getBytes(StandardCharsets.US_ASCII);
        if(name.length<1||name.length>255)throw new IOException("房间主机名无效");
        out.write(new byte[]{5,1,0,3,(byte)name.length});out.write(name);out.write(port>>8);out.write(port);out.flush();
        int version=read(socket,deadline),result=read(socket,deadline),reserved=read(socket,deadline),kind=read(socket,deadline);
        if(version!=5||reserved!=0||result!=0)throw new IOException("SOCKS5 代理拒绝连接（状态 "+result+"）");
        int n=switch(kind){case 1->4;case 4->16;case 3->read(socket,deadline);default->throw new IOException("SOCKS5 响应无效");};
        for(int i=0;i<n+2;i++)read(socket,deadline);
    }
    private static int read(Socket socket,Deadline deadline) throws IOException {
        socket.setSoTimeout(deadline.remaining());int b=socket.getInputStream().read();if(b<0)throw new EOFException("代理提前断开连接");return b;
    }
    private record Deadline(long end) {
        Deadline(int millis){this(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis));}
        int remaining() throws SocketTimeoutException {
            long nanos=end-System.nanoTime();if(nanos<=0)throw new SocketTimeoutException("代理连接超时");
            return (int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(nanos));
        }
    }
}
