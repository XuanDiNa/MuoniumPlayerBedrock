package com.muoniumplayer.bedrock;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;
/** Host room extension. Called on engine tick; ingress only validates/queues. */
public final class RoomVoiceRouter {
 public static final String SESSION="room_voice_session",AUDIO="room_voice_audio";
 public interface Peer {UUID id();String room();boolean open();boolean supportsVoice();default boolean supportsSpatialVoice(){return false;}void control(JsonObject m);void audio(JsonObject m);}
 private record Packet(long at,long seq,String data,RoomVoiceSpatial.Source spatial){}
 private static final class State {String room="",epoch="";long seq=-1,window,published;int frames;final ArrayDeque<Packet> queue=new ArrayDeque<>();}
 private final Supplier<List<Peer>> peers;private final LongSupplier clock;
 private final IdentityHashMap<Peer,State> states=new IdentityHashMap<>();
 public RoomVoiceRouter(Supplier<List<Peer>> peers,LongSupplier clock){this.peers=peers;this.clock=clock;}
 public synchronized int pending(){return states.values().stream().mapToInt(s->s.queue.size()).sum();}
 public synchronized boolean accept(Peer peer,JsonObject m){
  State s=states.get(peer);if(s==null||s.epoch.isEmpty()||!peer.open())return false;
  try{
   if(!AUDIO.equals(m.get("op").getAsString())||!s.epoch.equals(m.get("epoch").getAsString()))return false;
   long seq=m.get("seq").getAsBigDecimal().longValueExact();if(seq<0||seq<=s.seq)return false;
   String data=m.get("data").getAsString();if(data.length()>536)return false;
   int bytes=Base64.getDecoder().decode(data).length;if(bytes<1||bytes>400)return false;
   var spatial=m.has("spatial")?RoomVoiceSpatial.Source.decode(m.getAsJsonObject("spatial")):null;
   if(spatial!=null&&!peer.supportsSpatialVoice())return false;
   long now=clock.getAsLong();if(now-s.window>=1_000_000_000L){s.window=now;s.frames=0;}
   if(++s.frames>55)return false;s.seq=seq;
   if(s.queue.size()==8)s.queue.removeFirst();s.queue.addLast(new Packet(now,seq,data,spatial));return true;
  }catch(RuntimeException invalid){return false;}
 }
 public void tick(){
  List<Peer> live=peers.get().stream().filter(p->p.open()&&p.supportsVoice()).limit(64).toList();
  List<Runnable> sends=new ArrayList<>();long now=clock.getAsLong();
  synchronized(this){
   states.keySet().removeIf(p->!live.contains(p));Map<String,Integer> count=new HashMap<>();
   for(Peer p:live){
    String room=Objects.toString(p.room(),"");if(!room.isEmpty()&&count.merge(room,1,Integer::sum)>8)room="";
    State s=states.get(p);boolean fresh=s==null;if(fresh){s=new State();states.put(p,s);}
    if(fresh||!room.equals(s.room)){
     s.room=room;s.epoch=room.isEmpty()?"":UUID.randomUUID().toString();s.queue.clear();s.seq=-1;s.frames=0;s.window=now;
     s.published=now-1_000_000_000L;
    }
    if(now-s.published>=1_000_000_000L){
     s.published=now;var m=new JsonObject();m.addProperty("op",SESSION);m.addProperty("room",s.room);m.addProperty("epoch",s.epoch);m.addProperty("version",1);
     if(p.supportsSpatialVoice())m.addProperty("spatialVersion",1);
     sends.add(()->p.control(m));
    }
   }
   for(Peer source:live){State s=states.get(source);Packet packet;
    while((packet=s.queue.pollFirst())!=null){
     if(s.room.isEmpty()||now<packet.at||now-packet.at>200_000_000L)continue;
     for(Peer target:live){State t=states.get(target);if(target==source||!s.room.equals(t.room)||t.epoch.isEmpty())continue;
      if(packet.spatial!=null&&!target.supportsSpatialVoice())continue;
      var m=new JsonObject();m.addProperty("op",AUDIO);m.addProperty("epoch",t.epoch);m.addProperty("stream",s.epoch);m.addProperty("sender",source.id().toString());m.addProperty("seq",packet.seq);m.addProperty("data",packet.data);
      if(packet.spatial!=null)m.add("spatial",packet.spatial.json());
      sends.add(()->target.audio(m));
     }
    }
   }
  }
  for(Runnable send:sends)send.run();
 }
}
