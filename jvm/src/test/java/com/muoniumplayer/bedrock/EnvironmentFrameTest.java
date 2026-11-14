package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.nio.*;
import com.muoniumplayer.core.ncm.music.ambient.*;
import com.muoniumplayer.core.ncm.music.acoustics.*;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentFrameTest {
    static byte[] packet() {
        var b=ByteBuffer.allocate(36200).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x4556504d).putInt(4,1).putLong(8,1).putLong(16,1000).putLong(24,7);
        b.putInt(32,1).putInt(36,0).putInt(40,-16).putInt(44,-16).putInt(48,-16).putInt(52,33);
        b.putDouble(56,.5).putDouble(64,.5).putDouble(72,.5);
        b.putDouble(80,0).putDouble(88,0).putDouble(96,1);
        b.putInt(104,6000).putFloat(120,15).putFloat(124,15).putFloat(128,1).putInt(144,0x7f);
        byte[] biome="plains".getBytes(java.nio.charset.StandardCharsets.UTF_8);b.position(152);b.put(biome);
        return b.array();
    }
    static Class<?> type() {
        try{return Class.forName("com.muoniumplayer.bedrock.EnvironmentFrame");}
        catch(ClassNotFoundException e){fail("Bedrock environment snapshot adapter is missing");return null;}
    }
    static Object decode(byte[] bytes,long now,long epoch) throws Exception {
        return type().getDeclaredMethod("decode",byte[].class,long.class,long.class).invoke(null,bytes,now,epoch);
    }
    @Test void immutableSnapshotUsesRealBiomeWeatherAndRejectsStaleOrForeignWorld() throws Exception {
        var bytes=packet();Object f=decode(bytes,1001,7);assertNotNull(f);
        EnvSample env=(EnvSample)type().getDeclaredMethod("ambient").invoke(f);
        assertTrue(env.biomePlains);assertEquals(6000,env.timeOfDay);assertEquals(1,env.openness);
        assertEquals(Mood.CALM.display,MoodDecider.decide(env));
        bytes[256]=1;
        assertEquals(0,(int)type().getDeclaredMethod("cell",int.class,int.class,int.class).invoke(f,-16,-16,-16));
        assertNull(decode(packet(),3001,7));assertNull(decode(packet(),999,7));assertNull(decode(packet(),1001,8));
        var bad=packet();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(52,999);assertNull(decode(bad,1001,7));
    }
    @Test void sealedStoneRoomAndWaterProduceOriginalAudibleDspPlan() throws Exception {
        byte[] bytes=packet();
        for(int y=-4;y<=4;y++)for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)
            if(Math.abs(x)==4||Math.abs(y)==4||Math.abs(z)==4)bytes[256+((y+16)*33+(z+16))*33+x+16]=1;
        Object f=decode(bytes,1001,7);
        ListenerSample sample=(ListenerSample)type().getDeclaredMethod("listener").invoke(f);
        assertFalse(sample.openSky());assertTrue(sample.enclosure()>.9);assertTrue(sample.tapCount()>0);
        RoomPlan room=SceneResponse.respond(sample,1);assertTrue(room.wet>.05);assertTrue(room.decaySec>0);
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);b.putInt(148,3);
        f=decode(bytes,1001,7);sample=(ListenerSample)type().getDeclaredMethod("listener").invoke(f);
        assertTrue(sample.submerged());assertTrue(SceneResponse.respond(sample,1).bandHz<2000);
    }
    @Test void waterVolumeDoesNotBecomeARoomWall() {
        var bytes=packet();java.util.Arrays.fill(bytes,256,256+33*33*33,(byte)8);
        var f=EnvironmentFrame.decode(bytes,1001,7);assertNotNull(f);
        assertEquals(0,f.listener().enclosure());assertTrue(f.listener().openSky());
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(132,-1);
        assertNull(EnvironmentFrame.decode(bytes,1001,7));
    }
}
