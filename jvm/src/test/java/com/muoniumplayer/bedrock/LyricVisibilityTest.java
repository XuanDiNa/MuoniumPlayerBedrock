package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class LyricVisibilityTest {
    static WorldFrame camera() {
        var raw=WorldFrameTest.packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(24,7).putInt(36,0).putDouble(40,100.5).putDouble(48,70.5).putDouble(56,-19.5);
        float[] view=new Matrix4f().lookAt(100.5f,70.5f,-19.5f,100.5f,70.5f,-18.5f,0,1,0).get(new float[16]);
        for(int i=0;i<16;i++)b.putFloat(64+4*i,view[i]);
        return WorldFrame.decode(raw,1001);
    }
    static byte[] environment() {
        byte[] raw=EnvironmentFrameTest.packet();var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40,84).putInt(44,54).putInt(48,-36);
        b.putDouble(56,100.5).putDouble(64,70.5).putDouble(72,-19.5);return raw;
    }
    @Test void cameraRelativeBillboardUsesTheSameWorldOriginAsItsVoxelSnapshot() {
        var camera=camera();assertNotNull(camera);
        byte[] raw=environment();var frame=EnvironmentFrame.decode(raw,1001,7);assertNotNull(frame);
        assertTrue(LyricVisibility.visible(frame,camera,0,0,4),"Clear lyrics must remain visible far from world origin");
        WorldSpeakerTest.cell(raw,0,0,2,1);
        assertFalse(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,7),camera,0,0,4),"A real wall between camera and row must hide it");
        assertTrue(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,7),camera,0,0,1),"A wall behind the row must not hide it");
    }
    @Test void missingUnknownOrForeignGeometryDoesNotEraseOtherwiseValidLyrics() {
        var camera=camera();
        assertTrue(LyricVisibility.visible(null,camera,0,0,4),"Pending environment scan must not blank lyrics");
        byte[] raw=environment();WorldSpeakerTest.cell(raw,0,0,2,255);
        assertTrue(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,7),camera,0,0,4),"Unknown cell is not proof of an opaque wall");
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putLong(24,8);
        assertTrue(LyricVisibility.visible(EnvironmentFrame.decode(raw,1001,8),camera,0,0,4),"Foreign geometry must be ignored");
        assertTrue(LyricVisibility.visible(EnvironmentFrame.decode(environment(),1001,7),camera,0,0,20),"Outside bounded scan falls back to normal lyrics");
        assertFalse(LyricVisibility.visible(null,null,0,0,4));
        assertFalse(LyricVisibility.visible(null,camera,Double.NaN,0,4));
    }
}
