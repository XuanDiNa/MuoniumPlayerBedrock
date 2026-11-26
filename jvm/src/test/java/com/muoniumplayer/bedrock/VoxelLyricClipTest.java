package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import java.util.*;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent ray/AABB reference checks the polygon projection, including
 * arbitrary plane angles and volumes crossing the lyric plane/camera. */
class VoxelLyricClipTest {
    private static final VoxelLyricClip.Bounds VIEW=new VoxelLyricClip.Bounds(-100,-60,100,60);
    private static List<float[]> polygons(VoxelLyricClip clip,byte[] raw,WorldFrame w,double x,double y,double z,double yaw,double pitch) {
        var result=new ArrayList<float[]>();
        clip.shadows(raw==null?null:EnvironmentFrame.decode(raw,1001,7),w,x,y,z,yaw,pitch,.04f,VIEW,result::add);return result;
    }
    private static boolean contains(List<float[]> shapes,double x,double y) {
        for(var p:shapes) {
            boolean inside=false;
            for(int i=0,j=p.length-2;i<p.length;j=i,i+=2)
                if((p[i+1]>y)!=(p[j+1]>y)&&x<(p[j]-p[i])*(y-p[i+1])/(p[j+1]-p[i+1])+p[i])inside=!inside;
            if(inside)return true;
        }
        return false;
    }
    private static boolean rayBox(Vector3d eye,Vector3d target,int[] box) {
        double lo=0,hi=1-1e-7;
        for(int a=0;a<3;a++) {
            double start=eye.get(a),delta=target.get(a)-start;
            if(Math.abs(delta)<1e-10){if(start<box[a]||start>box[a]+1)return false;}
            else {
                double first=(box[a]-start)/delta,last=(box[a]+1-start)/delta;
                lo=Math.max(lo,Math.min(first,last));hi=Math.min(hi,Math.max(first,last));
                if(lo>hi)return false;
            }
        }
        return hi>1e-7&&lo<=hi;
    }
    private static void compare(List<int[]> blocks,double x,double y,double z,double yaw,double pitch) {
        compare(blocks,LyricVisibilityTest.camera(),x,y,z,yaw,pitch);
    }
    private static void compare(List<int[]> blocks,WorldFrame w,double x,double y,double z,double yaw,double pitch) {
        byte[] raw=LyricVisibilityTest.environment();
        for(int[] b:blocks)WorldSpeakerTest.cell(raw,b[0]-100,b[1]-70,b[2]+20,1);
        var clip=new VoxelLyricClip();
        var p=polygons(clip,raw,w,x,y,z,yaw,pitch);
        var rotation=new Matrix3d().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch));
        var eye=new Vector3d(w.eyeX(),w.eyeY(),w.eyeZ());
        for(double yy=-57.37;yy<60;yy+=4.31)for(double xx=-97.13;xx<100;xx+=4.17) {
            var target=rotation.transform(new Vector3d(-.04*xx,-.04*yy,0)).add(x,y,z).add(eye);
            boolean expected=blocks.stream().anyMatch(b->rayBox(eye,target,b));
            assertEquals(expected,contains(p,xx,yy),"plane="+x+","+y+","+z+" yaw="+yaw+" pitch="+pitch+" point="+xx+","+yy);
        }
        for(var poly:p)for(int i=0;i<poly.length;i+=2) {
            assertTrue(Float.isFinite(poly[i])&&Float.isFinite(poly[i+1]));
            assertTrue(poly[i]>=-100.001&&poly[i]<=100.001&&poly[i+1]>=-60.001&&poly[i+1]<=60.001);
        }
    }
    @Test void continuousOrbitAndNearPlaneCrossingsMatchIndependentVolumeReference(){
        var blocks=List.of(new int[]{100,70,-18},new int[]{99,71,-17},new int[]{102,70,-17});
        for(int frame=0;frame<96;frame++){
            double phase=frame*2*Math.PI/96;
            double ex=100.5+Math.sin(phase)*3,ey=70.5+Math.sin(phase*2)*.7,ez=-19.5+Math.cos(phase)*2;
            var raw=WorldFrameTest.packet(1000);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            b.putDouble(40,ex).putDouble(48,ey).putDouble(56,ez);
            float[] view=new org.joml.Matrix4f().lookAt((float)ex,(float)ey,(float)ez,(float)ex,(float)ey,(float)ez+1,0,1,0).get(new float[16]);
            for(int i=0;i<16;i++)b.putFloat(64+4*i,view[i]);
            var world=WorldFrame.decode(raw,1001);assertNotNull(world);
            compare(blocks,world,100.5-ex,70.5-ey,-15.5-ez,Math.sin(phase)*48,Math.cos(phase)*20);
        }
    }
    @Test void partialGlyphAndAngledPlanesMatchIndependentSegmentVolumeReference() {
        var blocks=List.of(new int[]{100,70,-18},new int[]{99,71,-17},new int[]{102,70,-17});
        compare(blocks,0,0,4,0,0);
        compare(blocks,.6,.2,4,35,17);
        compare(blocks,-.7,-.4,4,-58,-26);
        compare(blocks,.2,.3,2.2,25,-15); // blocks can straddle the lyric plane
    }
    @Test void cameraInsideAndNearACubeDoesNotInvertOrExplodeTheShadow() {
        compare(List.of(new int[]{100,70,-20}),0,0,4,0,0);
        compare(List.of(new int[]{100,70,-20}),.2,.3,3.5,48,-35);
        compare(List.of(new int[]{100,70,-19}),0,0,4,0,0);
    }
    @Test void behindPlaneAndBehindCameraDoNotOcclude() {
        compare(List.of(new int[]{100,70,-23},new int[]{100,70,-14}),0,0,4,0,0);
    }
    @Test void greedyCuboidsPreserveConcaveGapsAndContiguousWallCoverage() {
        var blocks=new ArrayList<int[]>();
        for(int x=98;x<=102;x++)for(int y=69;y<=72;y++)if(x<=100||y<=70)blocks.add(new int[]{x,y,-17});
        compare(blocks,0,0,6,14,9);
        byte[] raw=LyricVisibilityTest.environment();
        for(int x=-4;x<=4;x++)for(int y=-3;y<=3;y++)WorldSpeakerTest.cell(raw,x,y,2,1);
        var clip=new VoxelLyricClip();polygons(clip,raw,LyricVisibilityTest.camera(),0,0,4,0,0);
        assertEquals(1,clip.mergedBoxCount(),"A contiguous wall should not need one contour for every voxel");
    }
    @Test void pendingUnknownLiquidsForeignEpochAndDimensionLeaveNoMask() {
        var w=LyricVisibilityTest.camera();var clip=new VoxelLyricClip();
        assertTrue(polygons(clip,null,w,0,0,4,0,0).isEmpty());
        for(int material:new int[]{0,8,9,255}) {
            var raw=LyricVisibilityTest.environment();WorldSpeakerTest.cell(raw,0,0,2,material);
            assertTrue(polygons(clip,raw,w,0,0,4,0,0).isEmpty());
        }
        var raw=LyricVisibilityTest.environment();WorldSpeakerTest.cell(raw,0,0,2,1);
        assertFalse(polygons(clip,raw,w,0,0,4,0,0).isEmpty());
        for(int foreign:new int[]{0,1}) {
            var bytes=raw.clone();var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if(foreign==0)b.putLong(24,8);else b.putInt(36,1);
            var f=EnvironmentFrame.decode(bytes,1001,foreign==0?8:7);
            assertNotNull(f);assertEquals(0,clip.shadows(f,w,0,0,4,0,0,.04f,VIEW,p->fail("Foreign mask")));
        }
    }
    @Test void worldGeometryChangesInvalidateCachedMaskAndEmptyGeometryReleasesIt() {
        var clip=new VoxelLyricClip();var w=LyricVisibilityTest.camera();var raw=LyricVisibilityTest.environment();
        assertTrue(polygons(clip,raw,w,0,0,4,0,0).isEmpty());
        WorldSpeakerTest.cell(raw,0,0,2,1);assertFalse(polygons(clip,raw,w,0,0,4,0,0).isEmpty());
        WorldSpeakerTest.cell(raw,0,0,2,0);assertTrue(polygons(clip,raw,w,0,0,4,0,0).isEmpty());
        assertEquals(0,clip.mergedBoxCount());
    }
    @Test void representativeDenseWallAndFragmentedWorldCostsAreRecordedOffline() {
        for(boolean fragmented:new boolean[]{false,true}) {
            var raw=LyricVisibilityTest.environment();
            for(int y=-16;y<=16;y++)for(int z=-16;z<=16;z++)for(int x=-16;x<=16;x++)
                if(!fragmented||((x+y+z)&1)==0)WorldSpeakerTest.cell(raw,x,y,z,1);
            var f=EnvironmentFrame.decode(raw,1001,7);var w=LyricVisibilityTest.camera();var clip=new VoxelLyricClip();
            int shapes=0;for(int i=0;i<20;i++)shapes=clip.shadows(f,w,0,0,4,20,12,.04f,VIEW,p->{});
            long start=System.nanoTime();
            for(int i=0;i<40;i++)clip.shadows(f,w,0,0,4,20+i*.01,12,.04f,VIEW,p->{});
            System.out.println("VOXEL_CLIP_OFFLINE fragmented="+fragmented+" boxes="+clip.mergedBoxCount()+" contours="+shapes+" averageMs="+(System.nanoTime()-start)/40e6);
        }
    }
}
