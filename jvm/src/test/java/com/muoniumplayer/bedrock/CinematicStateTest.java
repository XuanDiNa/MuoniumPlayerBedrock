package com.muoniumplayer.bedrock;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CinematicStateTest {
    static byte[] packet(){
        var b=ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x5350434d).putInt(4,1).putLong(8,5).putLong(16,1000).putLong(24,8).putLong(32,77).putInt(40,1);
        b.putDouble(48,12).putDouble(56,63.4).putDouble(64,-4);
        b.putDouble(72,14).putDouble(80,67).putDouble(88,-8);
        b.putFloat(96,90).putFloat(100,30).putFloat(104,70).putFloat(108,1);
        return b.array();
    }
    @Test void playerAndRenderEyeAreIndependent(){
        var s=CinematicState.decode(packet(),1010);assertNotNull(s);
        assertEquals(12,s.x());assertEquals(14,s.eyeX());assertEquals(8,s.epoch());assertEquals(77,s.input());
        assertEquals(63.4,s.y());assertEquals(67,s.eyeY());assertEquals(90,s.yaw());
    }
    @Test void rejectStaleUnknownAndCorruptOwners(){
        assertNull(CinematicState.decode(packet(),1251));assertNull(CinematicState.decode(packet(),999));
        for(int at:new int[]{0,4,8,24,40}){
            var p=packet();ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(at,0);
            assertNull(CinematicState.decode(p,1010),"field "+at);
        }
        var p=packet();ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putDouble(48,Double.NaN);assertNull(CinematicState.decode(p,1010));
        p=packet();ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putFloat(108,0);assertNull(CinematicState.decode(p,1010));
    }
    @Test void inputAndWorldReplacementResetDirectorButStaticRenderCameraDoesNot(){
        var gate=new CinematicState.Session();var s=CinematicState.decode(packet(),1010);
        assertTrue(gate.changed(s));assertFalse(gate.changed(s));
        var p=packet();ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putDouble(72,100);
        assertFalse(gate.changed(CinematicState.decode(p,1010))); // Render pose never feeds back as input.
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putLong(32,78);assertTrue(gate.changed(CinematicState.decode(p,1010)));
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putLong(24,9);assertTrue(gate.changed(CinematicState.decode(p,1010)));
        assertTrue(gate.changed(null));assertFalse(gate.changed(null));
    }
}
