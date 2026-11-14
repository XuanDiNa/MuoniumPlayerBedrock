package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldFrameTest {
    static byte[] packet(long now) {
        var b = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x4357504d); b.putInt(4, 1); b.putLong(8, 1);
        b.putLong(16, now); b.putLong(24, 7); b.putInt(32, 1); b.putInt(36, 0);
        b.putDouble(40, 100); b.putDouble(48, 70); b.putDouble(56, -20);
        var v = new Matrix4f().lookAt(100,70,-20,100,70,-19,0,1,0);
        var p = new Matrix4f().perspective((float)Math.toRadians(70),16f/9,.1f,1000);
        float[] a = new float[16]; v.get(a); for(int i=0;i<16;i++) b.putFloat(64+i*4,a[i]);
        p.get(a); for(int i=0;i<16;i++) b.putFloat(128+i*4,a[i]);
        b.putDouble(192,0); b.putDouble(200,0); b.putDouble(208,1); b.putDouble(216,70);
        return b.array();
    }
    @Test void coherentCameraDrivesOriginalCameraViewAndPerspective() {
        var f = WorldFrame.decode(packet(1000),1001);
        assertNotNull(f); assertEquals(100,f.eyeX()); assertEquals(0,f.yawDeg(),.001);
        assertEquals(70,f.fovDeg(),.001); assertEquals(7,f.epoch());
        var m = WorldProjection.billboard(f,1280,720,0,0,8,0,0,.04f);
        var c = m.transformProject(new Vector3f());
        assertEquals(640,c.x,.01); assertEquals(360,c.y,.01);
        assertTrue(m.transformProject(new Vector3f(20,0,0)).x>c.x);
        var moved = WorldProjection.billboard(f,1280,720,1,0,8,0,0,.04f);
        assertTrue(moved.transformProject(new Vector3f()).x<c.x);
        assertNull(WorldProjection.billboard(f,1280,720,0,0,-8,0,0,.04f));
    }
    @Test void staleHiddenCorruptAndIncoherentFramesAreRejected() {
        assertNull(WorldFrame.decode(packet(1000),1251));
        assertNull(WorldFrame.decode(packet(1000),999));
        for(int at:new int[]{0,4,24,32}) {
            var raw=packet(1000); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(at,0);
            assertNull(WorldFrame.decode(raw,1001),"field "+at);
        }
        var raw=packet(1000); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putDouble(40,Double.NaN);
        assertNull(WorldFrame.decode(raw,1001));
        raw=packet(1000); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putFloat(128+60,1);
        assertNull(WorldFrame.decode(raw,1001));
        raw=packet(1000); ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putDouble(40,200);
        assertNull(WorldFrame.decode(raw,1001));
    }
    @Test void invalidOrStaleFrameClearsWorldPresenceWithoutInventingData() {
        var session = new WorldFrame.Session();
        assertFalse(session.update(null));
        assertTrue(session.update(WorldFrame.decode(packet(1000),1001)));
        assertFalse(session.update(WorldFrame.decode(packet(1000),1001)));
        assertTrue(session.update(null));
        assertNull(session.frame());
        assertTrue(session.update(WorldFrame.decode(packet(1000),1001)));
        var next=packet(1000);ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN).putInt(36,1);
        assertTrue(session.update(WorldFrame.decode(next,1001)));
        ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN).putLong(24,8);
        assertTrue(session.update(WorldFrame.decode(next,1001)));
    }
    @Test void rotationMovementAndFieldOfViewUseNativeMatrices() {
        var raw=packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        // A camera looking west: a fixed point north/east leaves its viewport.
        float[] values=new float[16];
        new Matrix4f().lookAt(100,70,-20,99,70,-20,0,1,0).get(values);
        for(int i=0;i<16;i++)b.putFloat(64+i*4,values[i]);
        b.putDouble(192,-1).putDouble(208,0);
        var rotated=WorldFrame.decode(raw,1001);assertNotNull(rotated);assertEquals(90,rotated.yawDeg(),.01);
        var m=WorldProjection.billboard(rotated,1280,720,-8,0,0,90,0,.04f);
        assertNotNull(m);var c=m.transformProject(new Vector3f());assertEquals(640,c.x,.1);assertEquals(360,c.y,.1);
        assertNull(WorldProjection.billboard(rotated,1280,720,8,0,0,0,0,.04f));
        var normal=WorldFrame.decode(packet(1000),1001);
        var near=WorldProjection.billboard(normal,1280,720,0,0,4,0,0,.04f);
        var far=WorldProjection.billboard(normal,1280,720,0,0,8,0,0,.04f);
        assertTrue(near.transformProject(new Vector3f(20,0,0)).x>far.transformProject(new Vector3f(20,0,0)).x);
    }
}
