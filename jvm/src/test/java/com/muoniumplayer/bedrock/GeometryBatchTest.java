package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeometryBatchTest {
    @Test void clearAndOccludedLyricsUseMatchingOwnedWorldOnly() {
        var raw=EnvironmentFrameTest.packet();var camera=WorldSpeakerTest.camera(7,0);
        assertTrue(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,7),camera,0,0,4));
        WorldSpeakerTest.cell(raw,0,0,2,1);
        assertFalse(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,7),camera,0,0,4));
        assertTrue(LyricVisibility.visible(null,camera,0,0,4));
        assertTrue(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,7),WorldSpeakerTest.camera(8,0),0,0,4));
    }
    @Test void unknownSpaceCannotBeUsedAsADiffractionShortcut() {
        var raw=EnvironmentFrameTest.packet();WorldSpeakerTest.cell(raw,-3,0,0,255);
        var f=EnvironmentFrame.decode(raw,1001,7);
        assertNull(EnvironmentGeometry.diffraction(f,-7.5,.5,.5,.5,.5,.5));
    }
    @Test void singleBlockHasAKnownBentPathButUnboundedWallDoesNot() {
        var raw=EnvironmentFrameTest.packet();WorldSpeakerTest.cell(raw,-3,0,0,1);
        var f=EnvironmentFrame.decode(raw,1001,7);
        var path=EnvironmentGeometry.diffraction(f,-7.5,.5,.5,.5,.5,.5);
        assertNotNull(path);assertTrue(path.length()>8);assertTrue(path.angle()>0&&path.angle()<180);
        for(int y=-16;y<=16;y++)for(int z=-16;z<=16;z++)WorldSpeakerTest.cell(raw,-3,y,z,1);
        assertNull(EnvironmentGeometry.diffraction(EnvironmentFrame.decode(raw,1001,7),-7.5,.5,.5,.5,.5,.5));
    }
}
