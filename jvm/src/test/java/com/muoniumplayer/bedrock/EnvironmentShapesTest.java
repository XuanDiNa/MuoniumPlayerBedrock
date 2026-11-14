package com.muoniumplayer.bedrock;

import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentShapesTest {
    static byte[] packet() {
        byte[] p=java.util.Arrays.copyOf(EnvironmentFrameTest.packet(),75208);
        var b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4,2).putInt(108,3).putInt(144,0xff);
        int dict=256+2*33*33*33;
        for(int a=3;a<6;a++)b.putShort(dict+a*2,(short)4096);
        int[] slab={0,0,0,4096,2048,4096};
        for(int a=0;a<6;a++)b.putShort(dict+24+a*2,(short)slab[a]);
        int i=((0+16)*33+16)*33+16;
        p[256+i]=1;p[256+33*33*33+i]=2;
        return p;
    }
    @Test void metadataFeedsOriginalAmbientFieldsAndMetalKeepsItsShape() {
        var raw=packet();var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(104,18000).putFloat(128,.25f).putInt(144,511).putInt(216,2);
        byte[] biome="minecraft:cherry_grove".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(biome,0,raw,152,biome.length);
        int i=(16*33+16)*33+16;raw[256+i]=10;
        var f=EnvironmentFrame.decode(raw,1001,7);assertNotNull(f);
        assertEquals(18000,f.ambient().timeOfDay);assertEquals(.25f,f.ambient().healthPct);
        assertTrue(f.ambient().biomeForest);assertEquals(2,f.ambient().blockClue);assertTrue(EnvironmentGeometry.occlusion(f,-2,.25,.5,2,.25,.5)>0);
        assertEquals(0,EnvironmentGeometry.occlusion(f,-2,.75,.5,2,.75,.5));
    }
    @Test void slabHasClearUpperHalfAndBlocksLowerHalf() {
        var f=EnvironmentFrame.decode(packet(),1001,7);assertNotNull(f);
        assertEquals(0,EnvironmentGeometry.occlusion(f,-2,.75,.5,2,.75,.5));
        assertTrue(EnvironmentGeometry.occlusion(f,-2,.25,.5,2,.25,.5)>0);
        var hit=EnvironmentGeometry.cast(f,-2,.25,.5,1,0,0,5);
        assertNotNull(hit);assertEquals(2,hit.distance(),1e-8);assertEquals(0,hit.axis());
        assertNull(EnvironmentGeometry.cast(f,-2,.75,.5,1,0,0,5));
    }
    @Test void shapeOnlyChangeInvalidatesGeometryAndMalformedShapeFailsClosed() {
        byte[] p=packet();var f=EnvironmentFrame.decode(p,1001,7);assertNotNull(f);
        var b=ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);int dict=256+2*33*33*33;
        b.putShort(dict+24+8,(short)1024);
        var other=EnvironmentFrame.decode(p,1001,7);assertNotNull(other);assertFalse(f.sameGeometry(other));
        b.putShort(dict+24+8,(short)-1);assertNull(EnvironmentFrame.decode(p,1001,7));
        p=packet();p[256+33*33*33]=100;assertNull(EnvironmentFrame.decode(p,1001,7));
    }
}
