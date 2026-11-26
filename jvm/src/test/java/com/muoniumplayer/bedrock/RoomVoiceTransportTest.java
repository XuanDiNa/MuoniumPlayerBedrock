package com.muoniumplayer.bedrock;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoomVoiceTransportTest {
 static final class Peer implements RoomVoiceRouter.Peer {
  final UUID id=UUID.randomUUID();String room="a";boolean live=true,supported=true,spatial=true;
  final List<JsonObject> controls=new ArrayList<>(),audio=new ArrayList<>();
  public UUID id(){return id;}public String room(){return room;}public boolean open(){return live;}
  public boolean supportsVoice(){return supported;}public void control(JsonObject m){controls.add(m);}
  public boolean supportsSpatialVoice(){return spatial;}
  public void audio(JsonObject m){audio.add(m);}
  String epoch(){return controls.getLast().get("epoch").getAsString();}
 }
 static JsonObject frame(String epoch,long seq){var m=new JsonObject();m.addProperty("op",RoomVoiceRouter.AUDIO);m.addProperty("epoch",epoch);m.addProperty("seq",seq);m.addProperty("data",Base64.getEncoder().encodeToString(new byte[]{1,2,3}));return m;}
 @Test void forwardsOnlySameRoomNoEchoAndDerivesIdentity(){
  var a=new Peer();var b=new Peer();var c=new Peer();c.room="b";var legacy=new Peer();legacy.supported=false;
  var router=new RoomVoiceRouter(()->List.of(a,b,c,legacy),()->1_000_000L);router.tick();
  var message=frame(a.epoch(),0);message.addProperty("sender",c.id.toString());
  assertTrue(router.accept(a,message));router.tick();
  assertEquals(0,a.audio.size());assertEquals(1,b.audio.size());assertEquals(0,c.audio.size());assertEquals(0,legacy.audio.size());
  assertEquals(a.id.toString(),b.audio.getFirst().get("sender").getAsString());assertEquals(b.epoch(),b.audio.getFirst().get("epoch").getAsString());
  String old=a.epoch();a.room="b";router.tick();assertNotEquals(old,a.epoch());assertFalse(router.accept(a,frame(old,1)));
 }
 @Test void boundsDuplicatesRateAndPendingFramesAndRejectsDisconnectedOwners(){
  var a=new Peer();var b=new Peer();long[] now={1_000_000L};var router=new RoomVoiceRouter(()->List.of(a,b),()->now[0]);router.tick();
  assertTrue(router.accept(a,frame(a.epoch(),0)));assertFalse(router.accept(a,frame(a.epoch(),0)));
  for(int i=1;i<80;i++)router.accept(a,frame(a.epoch(),i));assertTrue(router.pending()<=8);
  now[0]+=300_000_000L;router.tick();assertEquals(0,b.audio.size());
  a.live=false;router.tick();assertFalse(router.accept(a,frame(a.epoch(),90)));
 }
 @Test void limitsEightPeersRejectsMalformedFramesAndRefreshesSameEpoch(){
  var peers=new ArrayList<Peer>();for(int i=0;i<9;i++)peers.add(new Peer());long[] now={1_000_000L};
  var router=new RoomVoiceRouter(()->new ArrayList<RoomVoiceRouter.Peer>(peers),()->now[0]);router.tick();
  assertEquals("",peers.get(8).epoch());assertFalse(router.accept(peers.get(8),frame("",0)));
  var a=peers.getFirst();String epoch=a.epoch();var bad=frame(epoch,0);bad.addProperty("data","!");assertFalse(router.accept(a,bad));
  bad=frame(epoch,0);bad.addProperty("seq",.5);assertFalse(router.accept(a,bad));
  bad=frame(epoch,0);bad.addProperty("data",Base64.getEncoder().encodeToString(new byte[401]));assertFalse(router.accept(a,bad));
  for(int i=0;i<55;i++)assertTrue(router.accept(a,frame(epoch,i)));assertFalse(router.accept(a,frame(epoch,55)));
  now[0]+=1_000_000_000L;router.tick();assertEquals(epoch,a.epoch());assertEquals(2,a.controls.size());assertTrue(router.accept(a,frame(epoch,56)));
  peers.remove(a);router.tick();assertFalse(peers.getLast().epoch().isEmpty());assertFalse(router.accept(a,frame(epoch,57)));
 }
 @Test void spatialAudioPreservesBoundedMetadataAndNeverDowngradesForOldClients(){
  var a=new Peer();var b=new Peer();var old=new Peer();old.spatial=false;
  var router=new RoomVoiceRouter(()->List.of(a,b,old),()->1_000_000L);router.tick();
  var m=frame(a.epoch(),0);var p=new JsonObject();p.addProperty("world",UUID.randomUUID().toString());
  p.addProperty("dimension",0);p.addProperty("x",2);p.addProperty("y",64);p.addProperty("z",3);p.addProperty("range",32);m.add("spatial",p);
  assertTrue(router.accept(a,m));router.tick();assertEquals(1,b.audio.size());assertTrue(b.audio.getFirst().has("spatial"));assertTrue(old.audio.isEmpty());
  m=frame(a.epoch(),1);p.addProperty("x",Double.NaN);m.add("spatial",p);assertFalse(router.accept(a,m));
 }
}
