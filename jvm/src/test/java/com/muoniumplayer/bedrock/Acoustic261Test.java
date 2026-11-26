package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.acoustics.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Acoustic261Test {
    static byte[] room(int material) {
        byte[] raw=EnvironmentFrameTest.packet();
        for(int y=-4;y<=4;y++)for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)
            if(Math.abs(x)==4||Math.abs(y)==4||Math.abs(z)==4)WorldSpeakerTest.cell(raw,x,y,z,material);
        return raw;
    }
    static ListenerSample sample(byte[] raw) {return EnvironmentFrame.decode(raw,1001,7).listener();}
    @Test void openFloorHasEarlyReflectionButCannotBecomeASealedHall() {
        byte[] raw=EnvironmentFrameTest.packet();
        for(int z=-16;z<=16;z++)for(int x=-16;x<=16;x++)WorldSpeakerTest.cell(raw,x,-2,z,1);
        var s=sample(raw);
        assertTrue(s.enclosure()>.35f);assertTrue(s.tapCount()>0);
        assertTrue(s.lateEnclosure()<.03f,"Single floor reflection must escape instead of feeding late reverb");
        assertTrue(s.escapeFraction()>.9f);
        assertTrue(sample(room(1)).lateEnclosure()>.9f);
    }
    @Test void woolEarlyReflectionsLoseHighFrequenciesBeforeStone() {
        var stone=sample(room(1));var wool=sample(room(3));
        assertNotNull(wool.tapHighRatios());assertTrue(wool.tapHighRatios().length>=wool.tapCount());
        assertTrue(wool.tapCount()>0);
        double stoneHf=0,woolHf=0;
        for(int i=0;i<stone.tapCount();i++)stoneHf+=stone.tapHighRatios()[i];
        for(int i=0;i<wool.tapCount();i++)woolHf+=wool.tapHighRatios()[i];
        assertTrue(woolHf/wool.tapCount()<stoneHf/stone.tapCount()*.8);
    }
    @Test void unknownDirectSegmentStaysMutedAndDoesNotCreateReflections() {
        byte[] raw=room(1);WorldSpeakerTest.cell(raw,-2,0,0,255);
        var s=EnvironmentGeometry.sample(EnvironmentFrame.decode(raw,1001,7),SpeakerState.place(-2.5,.5,.5,"0"));
        assertEquals(20,s.directOcclusion());assertEquals(0,s.tapCount());
        assertTrue(SceneResponse.respond(s,1).directGain<.0001);
    }
}
