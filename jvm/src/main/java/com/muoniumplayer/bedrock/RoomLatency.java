package com.muoniumplayer.bedrock;

import com.google.gson.JsonObject;
import com.muoniumplayer.party.MpPartyClient;
import java.util.*;

/** Cached room-transport RTT. No sockets, account data, or game-server ping. */
public final class RoomLatency {
    public static final String OP="bedrock_latency";
    private static final long PROBE_TIMEOUT=10_000_000_000L, SAMPLE_TTL=10_000_000_000L, TABLE_TTL=6_000_000_000L;
    private static final View VIEW=new View();
    private RoomLatency(){}
    public static void bind(Object owner){VIEW.bind(owner);}
    public static void clear(Object owner){VIEW.clear(owner);}
    public static void receive(Object owner,JsonObject frame){VIEW.receive(owner,frame,System.nanoTime());}
    public static int millis(UUID member) {
        var room=MpPartyClient.room();return room==null?-1:VIEW.millis(room.id,member,System.nanoTime());
    }
    public static String label(UUID member){int value=millis(member);return value<0?"— ms":value+" ms";}

    public static final class Probe {
        private long pending,started,sampled;private int value=-1;private boolean waiting,hasSample;
        public synchronized long begin(long now) {
            if(waiting&&now-started<PROBE_TIMEOUT)return 0;
            pending=now==0?1:now;started=now;waiting=true;return pending;
        }
        public synchronized boolean accept(long token,long now) {
            long elapsed=now-started;
            if(!waiting||token!=pending||elapsed<0||elapsed>=PROBE_TIMEOUT)return false;
            value=(int)Math.min(9999,(elapsed+999_999)/1_000_000);sampled=now;waiting=false;hasSample=true;return true;
        }
        public synchronized int millis(long now) {
            long age=now-sampled;return hasSample&&age>=0&&age<SAMPLE_TTL?value:-1;
        }
    }
    static final class View {
        private Object owner;
        private volatile Snapshot snapshot=Snapshot.EMPTY;
        synchronized void bind(Object next){owner=Objects.requireNonNull(next);snapshot=Snapshot.EMPTY;}
        synchronized void clear(Object source){if(owner==source){owner=null;snapshot=Snapshot.EMPTY;}}
        synchronized boolean receive(Object source,JsonObject frame,long now) {
            if(owner!=source||source==null||frame==null)return false;
            try {
                if(!OP.equals(frame.get("op").getAsString()))return false;
                String room=frame.get("room").getAsString();
                if(room.isBlank()||room.length()>128)return false;
                JsonObject raw=frame.getAsJsonObject("samples");
                if(raw==null||raw.size()>256)return false;
                var values=new HashMap<UUID,Integer>();
                for(var entry:raw.entrySet())try {
                    var number=entry.getValue();if(!number.isJsonPrimitive()||!number.getAsJsonPrimitive().isNumber())continue;
                    int ms=number.getAsBigDecimal().intValueExact();if(ms<0||ms>9999)continue;
                    values.put(UUID.fromString(entry.getKey()),ms);
                }catch(RuntimeException malformedEntry){}
                snapshot=new Snapshot(room,Map.copyOf(values),now);return true;
            }catch(RuntimeException malformed){return false;}
        }
        int millis(String room,UUID member,long now) {
            Snapshot s=snapshot;long age=now-s.at;
            return member!=null&&room!=null&&room.equals(s.room)&&age>=0&&age<TABLE_TTL?s.values.getOrDefault(member,-1):-1;
        }
    }
    private record Snapshot(String room,Map<UUID,Integer> values,long at) {
        static final Snapshot EMPTY=new Snapshot("",Map.of(),0);
    }
}
