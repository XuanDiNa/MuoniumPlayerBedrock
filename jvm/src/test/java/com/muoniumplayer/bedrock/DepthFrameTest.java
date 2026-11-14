package com.muoniumplayer.bedrock;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DepthFrameTest {
    static byte[] packet(){
        byte[] raw=new byte[360+64*64*4];var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x5153504d).putInt(4,2).putLong(56,1).putInt(356,1);
        System.arraycopy(WorldFrameTest.packet(1000),0,raw,64,256);
        b.putLong(320,1000).putInt(328,800).putInt(332,600).putInt(336,368).putInt(340,268).putInt(344,64).putInt(348,64).putInt(352,1);
        for(int i=360;i<raw.length;i+=4){raw[i]=raw[i+1]=raw[i+2]=(byte)255;raw[i+3]=(byte)255;}
        return raw;
    }
    @Test void acceptsOnlyFreshBoundedOwnedDepthPixels(){
        assertNotNull(DepthFrame.decode(packet(),1001));
        assertNull(DepthFrame.decode(packet(),1200));
        for(int i=0;i<8;i++){
            var raw=packet();var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            switch(i){case 0->b.putInt(0,0);case 1->b.putInt(4,9);case 2->b.putLong(56,0);
                case 3->b.putInt(352,0);case 4->b.putInt(344,Integer.MAX_VALUE);case 5->b.putInt(336,799);
                case 6->b.putLong(320,1002);case 7->b.putLong(64+24,0);}
            assertNull(DepthFrame.decode(raw,1001),"malformed input "+i);
        }
    }
    @Test void movingOrForeignCameraCannotReuseAnOldDepthMask(){
        var f=DepthFrame.decode(packet(),1001);assertNotNull(f);
        var camera=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
        assertTrue(f.usable(camera,800,600,1001));
        assertFalse(f.usable(camera,800,600,1101));
        assertFalse(f.usable(camera,801,600,1001));
        assertFalse(f.usable(null,800,600,1001));
        for(int kind=0;kind<5;kind++){
            var raw=WorldFrameTest.packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            switch(kind){
                case 0->b.putLong(24,8);
                case 1->b.putInt(36,-1);
                case 2->{
                    b.putDouble(40,100.2);
                    var v=new org.joml.Matrix4f().lookAt(100.2f,70,-20,100.2f,70,-19,0,1,0).get(new float[16]);
                    for(int i=0;i<16;i++)b.putFloat(64+i*4,v[i]);
                }
                case 3->b.putFloat(128,2);
                case 4->{
                    var v=new org.joml.Matrix4f().rotationZ(.15f).mul(new org.joml.Matrix4f().lookAt(100,70,-20,100,70,-19,0,1,0)).get(new float[16]);
                    for(int i=0;i<16;i++)b.putFloat(64+i*4,v[i]);
                }
            }
            var changed=WorldFrame.decode(raw,1001);assertNotNull(changed,"valid camera fixture "+kind);
            assertFalse(f.usable(changed,800,600,1001),"foreign camera "+kind);
        }
    }
    @Test void reducedDepthKeepsLogicalBoundsAndRejectsMixedOrMalformedTransports(){
        int w=1095,h=991,sw=548,sh=496;
        var raw=java.util.Arrays.copyOf(packet(),360+sw*sh*4);
        var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(328,1100).putInt(332,1000).putInt(336,4).putInt(340,8).putInt(344,w).putInt(348,h).putInt(356,2);
        var frame=DepthFrame.decode(raw,1001);assertNotNull(frame);
        assertEquals(w,frame.width);assertEquals(h,frame.height);assertEquals(sw,frame.sampleWidth);assertEquals(sh,frame.sampleHeight);
        assertEquals(sw*sh*4,frame.rgba.length);
        for(int invalid:new int[]{0,1,3,4,Integer.MAX_VALUE}){
            b.putInt(356,invalid);assertNull(DepthFrame.decode(raw,1001),"Invalid sample spacing "+invalid);
        }
        b.putInt(356,2).putInt(4,1);assertNull(DepthFrame.decode(raw,1001),"Old DLL/new host transport refused");
        for(int width:new int[]{1,1023,1920,2560,3840,8192})for(int height:new int[]{1,720,1080,2160,8192}){
            int step=DepthFrame.sampleStep(width,height);
            assertTrue((long)((width+step-1)/step)*((height+step-1)/step)<=DepthFrame.MAX_PIXELS);
        }
    }
    @Test void reprojectionHasItsOwnBoundedMotionAndRetainsAgeIdentityAndProjectionGuards(){
        var depth=DepthFrame.decode(packet(),1001);assertNotNull(depth);
        for(int scenario=0;scenario<4;scenario++){
            double dx=scenario==0?.2:scenario==2?1.1:0;
            double yaw=Math.toRadians(scenario==1?2:scenario==3?12:0);
            var raw=WorldFrameTest.packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            b.putDouble(40,100+dx).putDouble(192,Math.sin(yaw)).putDouble(208,Math.cos(yaw));
            float[] view=new org.joml.Matrix4f().lookAt((float)(100+dx),70,-20,(float)(100+dx+Math.sin(yaw)),70,(float)(-20+Math.cos(yaw)),0,1,0).get(new float[16]);
            for(int i=0;i<16;i++)b.putFloat(64+i*4,view[i]);
            var current=WorldFrame.decode(raw,1001);assertNotNull(current);
            assertEquals(scenario<2,depth.reprojectable(current,800,600,1001),"Bounded motion "+scenario);
            assertTrue(depth.cameraMoved(current));
            assertFalse(depth.reprojectable(current,800,600,1101),"No age extension");
            assertFalse(depth.reprojectable(current,801,600,1001));
            b.putLong(24,8);assertFalse(depth.reprojectable(WorldFrame.decode(raw,1001),800,600,1001));b.putLong(24,7);
            b.putFloat(128,2);assertFalse(depth.reprojectable(WorldFrame.decode(raw,1001),800,600,1001));
        }
        var original=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
        assertFalse(depth.cameraMoved(original));assertFalse(depth.reprojectable(null,800,600,1001));
    }
}
