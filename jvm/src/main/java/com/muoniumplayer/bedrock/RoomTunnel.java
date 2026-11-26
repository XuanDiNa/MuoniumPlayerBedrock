package com.muoniumplayer.bedrock;

import com.muoniumplayer.party.MpPartyClient;
import com.muoniumplayer.party.direct.MpDirect;
import java.net.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Explicit user actions only. A generation owns its helper, relay and bridge. */
public final class RoomTunnel {
    private static long generation;
    private static volatile String status="公网穿透未开启",invite="";
    private static volatile boolean busy;
    private static TunnelRelay relay;
    private static TunnelProcess helper;
    private static TunnelClientBridge member;
    private static Thread worker;
    private RoomTunnel(){}
    public static String status(){return status;}
    public static boolean busy(){return busy;}
    public static boolean ready(){return !invite.isEmpty();}
    public static String invitation(){return invite;}
    /** Complete copy action: prefer the ready public invitation, never a hostless fragment. */
    public static String completeInvitation(String host){
        String publicInvite=invite;
        return publicInvite.isEmpty()?MpDirect.inviteString(host):publicInvite;
    }
    public static String buttonLabel(){return busy?"正在穿透 · 点击取消":ready()?"复制公网邀请":"一键公网穿透";}
    public static void cancel() {
        boolean joining;
        synchronized(RoomTunnel.class){joining=member!=null||busy&&!MpDirect.isHosting();}
        if(joining)MpDirect.close();else close();
    }
    public static String hostRoom() {
        if(busy)return "公网连接正在准备，请稍候";
        if(MpDirect.isHosting())return start();
        if(MpDirect.isJoined()||MpDirect.isPendingJoin())return "请先退出当前房间连接";
        int port;
        try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        catch(Exception e){return "无法分配本地房间端口";}
        String error=MpDirect.hostRoom(port,null);if(error!=null)return error;
        return start();
    }
    public static synchronized String start() {
        if(busy||relay!=null||helper!=null)return "公网穿透已启动，请先取消再重试";
        if(!MpDirect.isHosting())return "请先开房";
        long mine=++generation;int port=MpDirect.port();busy=true;status="正在建立加密公网通道…";invite="";
        worker=Thread.ofVirtual().name("muonium-room-tunnel").start(()->{
            TunnelRelay r=null;TunnelProcess h=null;
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while(!MpPartyClient.inRoom()&&System.nanoTime()<deadline){if(!current(mine))return;Thread.sleep(25);}
                if(!MpDirect.isHosting()||MpDirect.port()!=port||!MpPartyClient.inRoom())throw new IllegalStateException("Local room unavailable");
                String path=UUID.randomUUID().toString().replace("-","");
                r=TunnelRelay.open(port,path);
                synchronized(RoomTunnel.class){if(mine!=generation)return;relay=r;}
                h=TunnelProcess.start(r.port());
                synchronized(RoomTunnel.class){if(mine!=generation)return;helper=h;}
                String url=h.awaitReady(Duration.ofSeconds(90));
                var room=MpPartyClient.room();
                String value=TunnelInvite.create(url,path,MpDirect.hostPassword(),room==null?"":room.code);
                synchronized(RoomTunnel.class){if(mine!=generation)return;invite=value;busy=false;status="公网通道已就绪 · 点击复制邀请";}
                while(current(mine)&&h.isAlive()&&MpDirect.isHosting())Thread.sleep(500);
                if(current(mine))fail(mine,"公网通道已断开，可重试或使用直连");
            }catch(Exception e){if(current(mine))fail(mine,"公网穿透失败 · 检查网络后重试或使用直连");}
            finally {
                if(h!=null)h.close();if(r!=null)r.close();
                synchronized(RoomTunnel.class){if(mine==generation){helper=null;relay=null;worker=null;busy=false;}}
            }
        });return null;
    }
    private static synchronized boolean current(long mine){return mine==generation;}
    private static synchronized void fail(long mine,String message){if(mine==generation){invite="";status=message;busy=false;}}
    public static String joinRoom(String value,Consumer<String> done) {
        if(value!=null&&value.strip().matches("[0-9]{1,5}#[^#\\s]+(?:#[^#\\s]+)?"))
            return "邀请缺少房主地址，请索取完整邀请或公网邀请";
        if(!TunnelInvite.isTunnel(value))return MpDirect.joinRoom(value,done);
        final TunnelInvite parsed;try{parsed=TunnelInvite.parse(value);}catch(IllegalArgumentException e){return "公网邀请格式不正确，请粘贴完整邀请";}
        final long mine;
        synchronized(RoomTunnel.class) {
            if(busy||member!=null||MpDirect.isActive()||MpDirect.isHosting()||MpDirect.isPendingJoin())return "请先退出当前房间或等待连接完成";
            mine=++generation;busy=true;status="正在读取公网房间代理设置…";
            worker=Thread.ofVirtual().name("muonium-room-connect").start(()->{
                TunnelClientBridge bridge=null;
                RoomProxySettings.Value proxy=null;
                try {
                    proxy=RoomProxySettings.global().get();final var selected=proxy;
                    synchronized(RoomTunnel.class){if(mine!=generation)return;status="正在连接公网房间 · "+proxy.mode().label+"…";}
                    bridge=TunnelClientBridge.prepare(parsed.endpoint(),proxy);
                    synchronized(RoomTunnel.class){if(mine!=generation){bridge.close();return;}member=bridge;}
                    bridge.connect();
                    String error;
                    synchronized(RoomTunnel.class){
                      if(mine!=generation){bridge.close();return;}member=bridge;
                      error=MpDirect.joinRoom(parsed.localInvite(bridge.port()),err->{
                        synchronized(RoomTunnel.class) {
                            if(mine!=generation)return;
                            if(err!=null){close();status="公网房间连接失败";}else{busy=false;status="已连接公网房间 · "+selected.mode().label;}
                            if(done!=null)done.accept(err);
                        }
                      });
                    }
                    if(error!=null){close();status="公网房间连接失败";deliver(done,error);}
                }catch(Exception e){
                    if(bridge!=null)bridge.close();
                    if(current(mine)){
                        close();boolean via=proxy==null||proxy.mode()!=RoomProxySettings.Mode.DIRECT;
                        status=via?"代理房间连接失败 · 未改走直连":"公网房间不可达";
                        String reason=RoomProxyTransport.failureMessage(e);
                        deliver(done,proxy==null?"代理设置损坏，请打开连接代理并重新保存":via?reason+"。未改走直连。":"公网房间不可达，请让房主检查穿透状态或重建邀请");
                        System.err.println("ROOM_PROXY failed mode="+(proxy==null?"CONFIG":proxy.mode())+" class="+e.getClass().getSimpleName()+" reason="+reason);
                    }
                }
            });
        }
        return null;
    }
    private static void deliver(Consumer<String> done,String error){if(done!=null)BedrockClient.getInstance().execute(()->done.accept(error));}
    /** Cancels only the owned tunnel; closing a host additionally closes MpDirect. */
    public static void close() {
        final TunnelRelay r;final TunnelProcess h;final TunnelClientBridge c;final Thread w;
        synchronized(RoomTunnel.class) {
            generation++;r=relay;h=helper;c=member;w=worker;relay=null;helper=null;member=null;worker=null;
            busy=false;invite="";status="公网穿透未开启";
        }
        if(w!=null)w.interrupt();if(h!=null)h.close();if(c!=null)c.close();
        if(r!=null)Thread.ofVirtual().name("muonium-room-cleanup").start(r::close);
    }
}
