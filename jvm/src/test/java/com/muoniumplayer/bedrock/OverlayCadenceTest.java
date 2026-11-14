package com.muoniumplayer.bedrock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OverlayCadenceTest {
    @Test void worldTargetTracksGameRateEvenWhenCropOrWorkWasExpensive() {
        assertEquals(6_944_444L,OverlayCadence.interval(false,true,144,100,1000,1_000_000));
        assertEquals(6_944_444L,OverlayCadence.interval(false,true,144,100,1000,5_000_000));
        assertEquals(6_944_444L,OverlayCadence.interval(false,true,144,100,1000,20_000_000));
        assertEquals(16_666_666L,OverlayCadence.interval(true,true,144,100,1000,20_000_000));
    }
    @Test void worldLyricsCanFollowHighRateCameraWithoutAcceleratingFullScreenPlayer() throws Exception {
        Class<?> type;
        try{type=Class.forName("com.muoniumplayer.bedrock.OverlayCadence");}
        catch(ClassNotFoundException e){fail("World-positioned lyrics are still fixed to a 60Hz presentation cap");return;}
        var interval=type.getDeclaredMethod("interval",boolean.class,boolean.class,int.class,long.class,long.class);
        assertEquals(6_944_444L,interval.invoke(null,false,true,144,100L,1000L));
        assertEquals(16_666_666L,interval.invoke(null,true,true,144,100L,1000L));
        assertEquals(6_944_444L,interval.invoke(null,false,true,144,900L,1000L));
        assertEquals(16_666_666L,interval.invoke(null,false,true,-1,100L,1000L));
        assertEquals(16_666_666L,interval.invoke(null,false,false,144,100L,1000L));
        assertEquals(4_166_666L,interval.invoke(null,false,true,240,900L,1000L));
        assertEquals(2_777_777L,interval.invoke(null,false,true,360,900L,1000L));
        assertEquals(33_333_333L,interval.invoke(null,false,true,30,100L,1000L));
    }
}
