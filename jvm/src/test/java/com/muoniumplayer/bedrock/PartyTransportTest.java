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
            assertEquals("",MpDirect.inviteString(""),"The complete-invitation API must not produce a hostless port/password fragment");
            assertEquals("",MpDirect.inviteString("  "));
            String complete=MpDirect.inviteString("192.168.1.5");
            assertArrayEquals(new String[]{"192.168.1.5",Integer.toString(port),MpDirect.hostPassword()},MpDirect.parseInvite(complete));
            assertEquals(complete,RoomTunnel.completeInvitation("192.168.1.5"));
            assertEquals("",RoomTunnel.completeInvitation(""));
            captureRoom(true);
            try(var relay=TunnelRelay.open(port,TunnelInviteTest.TOKEN);
                var bridge=TunnelClientBridge.open(URI.create("ws://127.0.0.1:"+relay.port()+"/room/"+TunnelInviteTest.TOKEN));
                var peer=new Socket(InetAddress.getLoopbackAddress(),bridge.port())) {
                peer.setSoTimeout(5000);
                var hello=MpProto.msg(MpProto.C_HELLO);hello.addProperty("auth",MpDirect.hostPassword());
                var peerId=java.util.UUID.randomUUID();
                hello.addProperty("uuid",peerId.toString());hello.addProperty("playerName","TunnelFixture");
                hello.addProperty("bedrockLatency",1);
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
                var first=latencyTable(peer,peerId,90);
                assertTrue(first.getAsJsonObject("samples").get(peerId.toString()).getAsInt()>=80);
                var second=latencyTable(peer,peerId,0);
                assertTrue(second.getAsJsonObject("samples").get(peerId.toString()).getAsInt()<80,"Repeated pings must refresh rather than freeze the first sample");
                assertEquals(MpPartyClient.room().id,second.get("room").getAsString());
                long refresh=System.nanoTime()+3_000_000_000L;
                while(RoomLatency.millis(identity.id())<0&&System.nanoTime()<refresh){client.drainTasks();Thread.sleep(20);}
                assertTrue(RoomLatency.millis(identity.id())>=0,"Loopback host receives the same cached roster table");
                captureMembers();
                peer.getOutputStream().write(MpProto.encode(MpProto.msg(MpProto.C_LEAVE)));
                var create=MpProto.msg(MpProto.C_CREATE);create.addProperty("name","Other fixture room");
                peer.getOutputStream().write(MpProto.encode(create));peer.getOutputStream().flush();
                com.google.gson.JsonObject other=null;
                for(int i=0;i<80;i++) {
                    var received=read(peer.getInputStream());String op=received.get("op").getAsString();
                    assertNotEquals(MpProto.S_ERROR,op,"Fixture room transition was rejected");
                    if(MpProto.S_PING.equals(op)) {
                        var pong=MpProto.msg(MpProto.C_PONG);pong.addProperty("t",received.get("t").getAsLong());
                        peer.getOutputStream().write(MpProto.encode(pong));peer.getOutputStream().flush();
                    }else if(RoomLatency.OP.equals(op)&&!MpPartyClient.room().id.equals(received.get("room").getAsString())){other=received;break;}
                }
                assertNotNull(other,"New room receives its own latency table");
                assertFalse(other.getAsJsonObject("samples").has(identity.id().toString()),"Never disclose another room's member RTT");
            }
        } finally {PartyBridge.close();client.drainTasks();}
        assertFalse(MpDirect.isActive());assertFalse(MpDirect.isHosting());assertFalse(MpPartyClient.inRoom());
        assertEquals(-1,RoomLatency.millis(identity.id()),"Leaving the room clears RTT");
        try(var released=new ServerSocket()){released.setReuseAddress(true);released.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),port));}
        com.muoniumplayer.render.skija.SkijaHost.shutdown();
        }finally{org.lwjgl.glfw.GLFW.glfwTerminate();SessionMedia.close();}
    }
    private static com.google.gson.JsonObject latencyTable(Socket peer,java.util.UUID member,int delay)throws Exception {
        boolean echoed=false;
        for(int i=0;i<60;i++) {
            var frame=read(peer.getInputStream());String op=frame.get("op").getAsString();
            if(MpProto.S_PING.equals(op)) {
                if(delay>0)Thread.sleep(delay);
                var pong=MpProto.msg(MpProto.C_PONG);pong.addProperty("t",frame.get("t").getAsLong());
                peer.getOutputStream().write(MpProto.encode(pong));peer.getOutputStream().flush();echoed=true;
            }else if(RoomLatency.OP.equals(op)&&echoed&&frame.getAsJsonObject("samples").has(member.toString())) {
                int ms=frame.getAsJsonObject("samples").get(member.toString()).getAsInt();
                if(delay>0?ms>=80:ms<80)return frame;
            }
        }
        throw new AssertionError("Missing fresh RTT table over WSS bridge");
    }
    private static void captureMembers()throws Exception {
        var page=new com.muoniumplayer.party.ui.MpPartyOverlay();page.onInit();
        var tab=page.getClass().getDeclaredField("tab");tab.setAccessible(true);
        Object members=java.util.Arrays.stream(tab.getType().getEnumConstants()).filter(e->e.toString().equals("MEMBERS")).findFirst().orElseThrow();
        var request=page.getClass().getDeclaredMethod("requestTab",tab.getType());request.setAccessible(true);request.invoke(page,members);
        for(int width:new int[]{1000,620,440}) {
            for(int i=0;i<40;i++) {
                com.muoniumplayer.render.skija.SkijaHost.beginFrame();
                try{page.setBounds(0,0,width,650);page.renderWidget(0,0,0);}finally{com.muoniumplayer.render.skija.SkijaHost.endFrame();}
                Thread.sleep(16);
            }
            var labels=tree(page).filter(com.muoniumplayer.core.rendering.ui.widgets.LabelWidget.class::isInstance)
                .map(com.muoniumplayer.core.rendering.ui.widgets.LabelWidget.class::cast).filter(l->l.getLabel().matches("(—|[0-9]+) ms")).toList();
            assertTrue(labels.size()>=2,"Each member row has a rightmost latency label");
            for(var label:labels) {
                var row=label.getParent();
                assertEquals(row.getX()+row.getWidth()-10,label.getX()+label.getWidth(),1);
                var buttons=tree(row).filter(com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget.class::isInstance).toList();
                for(var button:buttons) {
                    assertTrue(button.getX()+button.getWidth()+6<=label.getX(),"Management tools must leave the RTT lane clear");
                    for(var child:button.getChildren())if(child instanceof com.muoniumplayer.core.rendering.ui.widgets.LabelWidget caption && !caption.getLabel().isBlank())
                        assertTrue(caption.getX()>=button.getX()-1 && caption.getX()+caption.getWidth()<=button.getX()+button.getWidth()+1,"Narrow management controls must fit their own button");
                }
            }
            com.muoniumplayer.render.skija.SkijaHost.savePng(Path.of("../stage-captures/room-members-"+width+".png"),true);
        }
        page.dispose();
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
        assertEquals(hosting?438:420,dialog.getHeight(),1);
        if(hosting) {
            var host=tree(page).filter(com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget.class::isInstance)
                .map(com.muoniumplayer.core.rendering.ui.widgets.TextFieldWidget.class::cast).findFirst().orElseThrow();
            host.setText("");
            var copy=button(page,"复制完整邀请");
            assertNotNull(button(page,"仅端口密码"));
            var proxyNote=tree(page).filter(com.muoniumplayer.core.rendering.ui.widgets.LabelWidget.class::isInstance)
                .map(com.muoniumplayer.core.rendering.ui.widgets.LabelWidget.class::cast)
                .filter(label->label.getLabel().equals("仅代理加入公网房间；保存后下次连接生效")).findFirst().orElseThrow();
            assertTrue(copy.getY()>=proxyNote.getY()+proxyNote.getHeight()+10,"Copy row must not overlap the proxy explanation");
            var client=BedrockClient.getInstance();String clipboard=client.keyboardHandler.getClipboard();
            assertTrue(copy.onMouseClicked(1,1,0));
            assertTrue(java.util.Objects.equals(clipboard,client.keyboardHandler.getClipboard()),"Missing address must not overwrite the clipboard with a fragment");
            var hint=page.getClass().getDeclaredField("hint");hint.setAccessible(true);
            assertTrue(((String)hint.get(page)).contains("请填写房主地址"));
            assertFalse(RoomTunnel.busy());assertFalse(RoomTunnel.ready());
            com.muoniumplayer.render.skija.SkijaHost.beginFrame();
            try{page.renderWidget(0,0,0);}finally{com.muoniumplayer.render.skija.SkijaHost.endFrame();}
        }
        com.muoniumplayer.render.skija.SkijaHost.savePng(Path.of("../stage-captures/room-"+(hosting?"host":"idle")+".png"),true);
        page.dispose();
    }
    private static java.util.stream.Stream<com.muoniumplayer.core.rendering.ui.AbstractWidget<?>> tree(com.muoniumplayer.core.rendering.ui.AbstractWidget<?> root) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(root),root.getChildren().stream().flatMap(PartyTransportTest::tree));
    }
    private static com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget button(com.muoniumplayer.core.rendering.ui.AbstractWidget<?> page,String caption) {
        return tree(page).filter(com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget.class::isInstance)
            .map(com.muoniumplayer.core.rendering.ui.widgets.RoundedButtonWidget.class::cast)
            .filter(button->button.getChildren().stream().anyMatch(child->child instanceof com.muoniumplayer.core.rendering.ui.widgets.LabelWidget label&&caption.equals(label.getLabel())))
            .findFirst().orElseThrow(()->new AssertionError("Missing button: "+caption));
    }
    private static com.google.gson.JsonObject read(java.io.InputStream in)throws Exception {
        int n=0,shift=0,b;do{b=in.read();if(b<0)throw new java.io.EOFException();n|=(b&127)<<shift;shift+=7;}while((b&128)!=0&&shift<35);
        if(n<1||n>MpProto.MAX_FRAME)throw new java.io.IOException("Invalid test frame");
        return MpProto.decode(in.readNBytes(n));
    }
}
