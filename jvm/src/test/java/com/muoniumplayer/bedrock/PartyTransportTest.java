package com.muoniumplayer.bedrock;

import com.muoniumplayer.party.*;
import com.muoniumplayer.party.direct.*;
import java.net.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real original room engine and framed TCP, bound to loopback in this own JVM. */
class PartyTransportTest {
    @Test void explicitHostJoinsItsRoomAndCloseReleasesOwnedSocket() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");
        System.setProperty("muonium.bedrock.roomLoopbackOnly","true");
        assertTrue(org.lwjgl.glfw.GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
        var client=BedrockClient.getInstance();client.bindWindow(windows.display());
        client.getWindow().size(1000,650,1);presenter.resize(1000,650);
        com.muoniumplayer.render.skija.SkijaHost.renderTarget(presenter.framebuffer());
        com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
        var identity=PartyBridge.identity(Path.of("room-test","identity.json"));
        MpPartyClient.bind(MpHostRouter.wrap(PartyBridge.host(identity)));
        captureRoom(false);
        int port;
        try(var reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=reservation.getLocalPort();}
        try {
            assertFalse(MpDirect.isActive());
            assertNull(MpDirect.hostRoom(port,null));
            long deadline=System.nanoTime()+10_000_000_000L;
            while(System.nanoTime()<deadline&&(!MpDirect.isLoopback()||!MpPartyClient.inRoom())){client.drainTasks();Thread.sleep(25);}
            client.drainTasks();assertTrue(MpDirect.isHosting());assertTrue(MpDirect.isLoopback());
            assertTrue(MpPartyClient.inRoom(),"Original room snapshot must arrive through TCP");
            assertNotNull(MpPartyClient.self());assertEquals(0,MpDirect.onlineMemberCount());
            captureRoom(true);
            try(var relay=TunnelRelay.open(port,TunnelInviteTest.TOKEN);
                var bridge=TunnelClientBridge.open(URI.create("ws://127.0.0.1:"+relay.port()+"/room/"+TunnelInviteTest.TOKEN));
                var peer=new Socket(InetAddress.getLoopbackAddress(),bridge.port())) {
                peer.setSoTimeout(5000);
                var hello=MpProto.msg(MpProto.C_HELLO);hello.addProperty("auth",MpDirect.hostPassword());
                hello.addProperty("uuid",java.util.UUID.randomUUID().toString());hello.addProperty("playerName","TunnelFixture");
                peer.getOutputStream().write(MpProto.encode(hello));
                boolean handshake=false;
                for(int i=0;i<12&&!handshake;i++)handshake=MpProto.S_HELLO.equals(MpProto.str(read(peer.getInputStream()),MpProto.OP,""));
                assertTrue(handshake,"Original room handshake must survive binary WebSocket framing");
                var join=MpProto.msg(MpProto.C_JOIN);join.addProperty("code",MpPartyClient.room().code);
                peer.getOutputStream().write(MpProto.encode(join));
                boolean snapshot=false;
                for(int i=0;i<20&&!snapshot;i++) {
                    var frame=read(peer.getInputStream());
                    snapshot=MpProto.S_ROOM_STATE.equals(MpProto.str(frame,MpProto.OP,""));
                }
                assertTrue(snapshot,"Original joined room snapshot must traverse the WebSocket bridge");
                deadline=System.nanoTime()+5_000_000_000L;
                while(System.nanoTime()<deadline&&MpDirect.onlineMemberCount()!=1){client.drainTasks();Thread.sleep(20);}
                assertEquals(1,MpDirect.onlineMemberCount());
            }
        } finally {PartyBridge.close();client.drainTasks();}
        assertFalse(MpDirect.isActive());assertFalse(MpDirect.isHosting());assertFalse(MpPartyClient.inRoom());
        try(var released=new ServerSocket()){released.setReuseAddress(true);released.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port));}
        com.muoniumplayer.render.skija.SkijaHost.shutdown();
        }finally{org.lwjgl.glfw.GLFW.glfwTerminate();SessionMedia.close();}
    }
    private static void captureRoom(boolean hosting)throws Exception {
        var page=new com.muoniumplayer.party.ui.MpPartyOverlay();page.openDirect();
        for(int i=0;i<35;i++) {
            com.muoniumplayer.render.skija.SkijaHost.beginFrame();
            try{page.setBounds(0,0,1000,650);page.renderWidget(0,0,0);}
            finally{com.muoniumplayer.render.skija.SkijaHost.endFrame();}
            Thread.sleep(16);
        }
        var dialog=page.getChildren().get(1); // first child is the full-window dim mask
        assertEquals(hosting?366:380,dialog.getHeight(),1);
        com.muoniumplayer.render.skija.SkijaHost.savePng(Path.of("../stage-captures/room-"+(hosting?"host":"idle")+".png"),true);
        page.dispose();
    }
    private static com.google.gson.JsonObject read(java.io.InputStream in)throws Exception {
        int n=0,shift=0,b;do{b=in.read();if(b<0)throw new java.io.EOFException();n|=(b&127)<<shift;shift+=7;}while((b&128)!=0&&shift<35);
        if(n<1||n>MpProto.MAX_FRAME)throw new java.io.IOException("Invalid test frame");
        return MpProto.decode(in.readNBytes(n));
    }
}
