package com.muoniumplayer.bedrock;
import com.google.gson.*;
import com.muoniumplayer.party.*;
import com.muoniumplayer.party.direct.*;
import yun.shan.mpsync.MpParty;
import java.net.*;import java.nio.file.*;import java.io.*;import java.util.*;import java.util.function.Predicate;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;

/** Real room engine + TCP and binary WebSocket tunnel; no microphone or game. */
class RoomVoiceSocketTest {
 @TempDir Path root;
 static final class Peer implements AutoCloseable {
  final Socket socket;final UUID id=UUID.randomUUID();
  Peer(int port,boolean voice)throws Exception{this(port,voice,false);}
  Peer(int port,boolean voice,boolean spatial)throws Exception{socket=new Socket(InetAddress.getLoopbackAddress(),port);socket.setSoTimeout(2400);
   var m=MpProto.msg(MpProto.C_HELLO);m.addProperty("auth","fixture-auth");m.addProperty("uuid",id.toString());m.addProperty("playerName","Voice fixture");if(voice)m.addProperty("bedrockVoice",1);if(spatial)m.addProperty("bedrockSpatialVoice",1);send(m);await(m2->MpProto.S_HELLO.equals(m2.get("op").getAsString()));}
  void send(JsonObject m)throws Exception{socket.getOutputStream().write(MpProto.encode(m));socket.getOutputStream().flush();}
  JsonObject await(Predicate<JsonObject> predicate)throws Exception{long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){var m=read(socket.getInputStream());if(predicate.test(m))return m;}throw new AssertionError("Missing room frame");}
  JsonObject op(String op)throws Exception{return await(m->op.equals(m.get("op").getAsString()));}
  public void close()throws Exception{socket.close();}
 }
 @Test @Timeout(20) void opusSurvivesTunnelWithAuthenticatedSameRoomRelayAndEpochReset()throws Exception{
  int port;try(var reservation=new ServerSocket(0)){port=reservation.getLocalPort();}
  var server=new DirectServer(port,"fixture-auth",null);var config=root.resolve("room.json");
  MpParty.init(server.platform(),DirectRoomConfig.load(config),config);
  try{server.start();
   try(var relay=TunnelRelay.open(port,TunnelInviteTest.TOKEN);
       var bridge=TunnelClientBridge.open(URI.create("ws://127.0.0.1:"+relay.port()+"/room/"+TunnelInviteTest.TOKEN));
       var a=new Peer(port,true,true);var b=new Peer(bridge.port(),true,true);var oldVoice=new Peer(port,true);var legacy=new Peer(port,false)){
    var create=MpProto.msg(MpProto.C_CREATE);create.addProperty("name","Voice room");a.send(create);
    var snapshot=a.op(MpProto.S_ROOM_STATE);String code=snapshot.getAsJsonObject("room").get("code").getAsString();
    var join=MpProto.msg(MpProto.C_JOIN);join.addProperty("code",code);b.send(join);oldVoice.send(join);legacy.send(join);
    var as=a.await(m->RoomVoiceRouter.SESSION.equals(m.get("op").getAsString())&&!m.get("epoch").getAsString().isEmpty());
    var bs=b.await(m->RoomVoiceRouter.SESSION.equals(m.get("op").getAsString())&&!m.get("epoch").getAsString().isEmpty());
    assertEquals(1,as.get("spatialVersion").getAsInt());assertEquals(1,bs.get("spatialVersion").getAsInt());
    try(var codec=new RoomVoiceCodec()){
     float[] pcm=new float[960];for(int i=0;i<960;i++)pcm[i]=(float)(.2*Math.sin(i*.07));
     var audio=RoomVoiceTransportTest.frame(as.get("epoch").getAsString(),0);audio.addProperty("data",Base64.getEncoder().encodeToString(codec.encode(pcm)));audio.addProperty("sender",legacy.id.toString());a.send(audio);
     var forwarded=b.op(RoomVoiceRouter.AUDIO);assertEquals(a.id.toString(),forwarded.get("sender").getAsString());assertEquals(bs.get("epoch"),forwarded.get("epoch"));
     float[] decoded=codec.decode(Base64.getDecoder().decode(forwarded.get("data").getAsString()));double energy=0;for(float f:decoded)energy+=f*f;assertTrue(energy>.01);
     assertFalse(oldVoice.op(RoomVoiceRouter.AUDIO).has("spatial"),"Ordinary0.19 room audio remains compatible");
     audio.addProperty("seq",1);audio.add("spatial",RoomVoiceSpatialTest.source(4,64,2,0,32));a.send(audio);
     var positioned=b.op(RoomVoiceRouter.AUDIO);assertEquals(4,positioned.getAsJsonObject("spatial").get("x").getAsInt());
     oldVoice.socket.setSoTimeout(200);assertThrows(SocketTimeoutException.class,()->{while(true){var m=read(oldVoice.socket.getInputStream());assertNotEquals(RoomVoiceRouter.AUDIO,m.get("op").getAsString(),"Spatial audio must not downgrade for0.19 peers");}});
     // No sender echo and no unknown voice frames sent to a legacy connection.
     assertNoAudioUntilSession(a);legacy.socket.setSoTimeout(180);assertThrows(SocketTimeoutException.class,()->{while(true){var m=read(legacy.socket.getInputStream());assertNotEquals(RoomVoiceRouter.AUDIO,m.get("op").getAsString());assertNotEquals(RoomVoiceRouter.SESSION,m.get("op").getAsString());}});
     b.send(MpProto.msg(MpProto.C_LEAVE));var empty=b.await(m->RoomVoiceRouter.SESSION.equals(m.get("op").getAsString())&&m.get("epoch").getAsString().isEmpty());assertEquals("",empty.get("room").getAsString());
    }
   }
  }finally{server.stop();MpParty.shutdown();}
 }
 private static void assertNoAudioUntilSession(Peer peer)throws Exception{peer.await(m->{assertNotEquals(RoomVoiceRouter.AUDIO,m.get("op").getAsString());return RoomVoiceRouter.SESSION.equals(m.get("op").getAsString());});}
 static JsonObject read(InputStream in)throws Exception{int n=0,shift=0,b;do{b=in.read();if(b<0)throw new EOFException();n|=(b&127)<<shift;shift+=7;}while((b&128)!=0&&shift<35);if(n<1||n>MpProto.MAX_FRAME)throw new IOException("Fixture frame");return MpProto.decode(in.readNBytes(n));}
}
