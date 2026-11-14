package com.muoniumplayer.bedrock;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GameMetricsTest {
    @Test void cameraV1StillDecodesWithUnknownMetrics() {
        var f=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
        assertNotNull(f);assertEquals(-1,f.fps());assertEquals(-1,f.ping());
    }
    @Test void metricsReachTheExistingSceneAndExpireIndependently() {
        var raw=WorldFrameTest.packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4,2);b.putInt(224,1);b.putInt(228,144);b.putInt(232,73);b.putInt(236,2);b.putLong(240,900);b.putLong(248,2);
        var f=WorldFrame.decode(raw,1001);assertNotNull(f);
        var scene=f.scene(false);assertEquals(144,scene.fps());assertEquals(73,scene.ping());
        b.putLong(16,5000);assertEquals(-1,WorldFrame.decode(raw,5001).ping());
        b.putLong(16,1000);
        b.putInt(232,0);b.putInt(236,1);b.putLong(240,1000);
        assertEquals(0,WorldFrame.decode(raw,1001).ping());
        b.putInt(236,9);assertEquals(-1,WorldFrame.decode(raw,1001).ping());
        b.putInt(236,2);b.putInt(232,-99);assertEquals(-1,WorldFrame.decode(raw,1001).ping());
        b.putLong(240,1002);assertEquals(-1,WorldFrame.decode(raw,1001).ping());
        b.putInt(228,-100);assertEquals(-1,WorldFrame.decode(raw,1001).fps());
    }
}
