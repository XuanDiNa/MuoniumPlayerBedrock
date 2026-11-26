package com.muoniumplayer.bedrock;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomLatencyTest {
    @Test void measuresOnlyOutstandingEchoAndExpiresSamples() {
        var probe=new RoomLatency.Probe();
        long token=probe.begin(1_000_000_000L);
        assertNotEquals(0,token);
        assertFalse(probe.accept(token+1,1_020_000_000L));
        assertEquals(-1,probe.millis(1_020_000_000L));
        assertTrue(probe.accept(token,1_025_000_000L));
        assertEquals(25,probe.millis(1_030_000_000L));
        assertFalse(probe.accept(token,1_050_000_000L));
        assertEquals(-1,probe.millis(12_000_000_000L));
    }
    @Test void slowEchoIsKeptUntilTimeoutAndCannotBecomeFreshAgain() {
        var probe=new RoomLatency.Probe();
        long token=probe.begin(1_000_000_000L);
        assertEquals(0,probe.begin(3_000_000_000L));
        assertTrue(probe.accept(token,4_500_000_000L));
        assertEquals(3500,probe.millis(4_500_000_000L));
        long next=probe.begin(6_000_000_000L);
        assertFalse(probe.accept(next,5_000_000_000L));
        assertEquals(0,probe.begin(8_000_000_000L));
        long afterTimeout=probe.begin(17_000_000_000L);
        assertNotEquals(0,afterTimeout);
        assertFalse(probe.accept(next,17_001_000_000L));
        assertTrue(probe.accept(afterTimeout,17_002_000_000L));
    }
    @Test void snapshotsBelongToConnectionRoomAndFreshWindow() {
        Object old=new Object(),current=new Object();UUID member=UUID.randomUUID();
        var view=new RoomLatency.View();view.bind(old);
        assertTrue(view.receive(old,table("roomA",member,42),100));
        assertEquals(42,view.millis("roomA",member,101));
        assertEquals(-1,view.millis("roomB",member,101));
        view.bind(current);view.clear(old);
        assertEquals(-1,view.millis("roomA",member,101));
        assertFalse(view.receive(old,table("roomA",member,999),102));
        assertTrue(view.receive(current,table("roomA",member,83),103));
        assertEquals(83,view.millis("roomA",member,104));
        assertEquals(-1,view.millis("roomA",member,7_000_000_104L));
        view.clear(current);assertEquals(-1,view.millis("roomA",member,104));
    }
    @Test void rejectsInvalidNumbersAndOversizedOrMalformedTables() {
        Object owner=new Object();UUID member=UUID.randomUUID();var view=new RoomLatency.View();view.bind(owner);
        for(String bad:new String[]{"-1","1.5","1e100","\"2\"","null","true"}) {
            var frame=table("roomA",member,1);frame.getAsJsonObject("samples").add(member.toString(),com.google.gson.JsonParser.parseString(bad));
            assertTrue(view.receive(owner,frame,100));assertEquals(-1,view.millis("roomA",member,101),bad);
        }
        var large=table("roomA",member,1);
        for(int i=0;i<257;i++)large.getAsJsonObject("samples").addProperty(UUID.randomUUID().toString(),5);
        assertFalse(view.receive(owner,large,200));
        assertFalse(view.receive(owner,new JsonObject(),200));
    }
    static JsonObject table(String room,UUID member,int ms) {
        var frame=new JsonObject();frame.addProperty("op",RoomLatency.OP);frame.addProperty("room",room);
        var samples=new JsonObject();samples.addProperty(member.toString(),ms);frame.add("samples",samples);return frame;
    }
}
