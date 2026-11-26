package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.cine.CineDirector;
import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CinematicCollisionTest {
    private EnvironmentFrame wall(boolean corner){
        byte[] raw=EnvironmentFrameTest.packet();
        for(int y=-2;y<=4;y++)for(int z=-5;z<=5;z++)
            raw[256+((y+16)*33+z+16)*33+17]=1;
        if(corner)for(int y=-2;y<=4;y++)for(int x=-5;x<=5;x++)
            raw[256+((y+16)*33+17)*33+x+16]=1;
        return EnvironmentFrame.decode(raw,1001,7);
    }
    private CinematicRuntime.Probe probe(EnvironmentFrame env){
        return new CinematicRuntime.Probe(new CinematicState(1,1000,7,1,false,
            .5,0,.5,.5,1.6,.5,0,0,70,1),env,new Object(),new Object());
    }
    @Test void diagonalMovementAtAWallKeepsTheUnblockedTangent(){
        var env=wall(false);var probe=probe(env);double x=.79,z=0;
        for(int i=0;i<20;i++){
            var p=probe.clipCamera(x,1.6,z,x+.06,1.6,z+.08);
            assertTrue(p.x()<1,"Camera must not cross the wall");
            assertEquals(0,EnvironmentGeometry.occlusion(env,x,1.6,z,p.x(),p.y(),p.z()));
            assertTrue(Math.hypot(p.x()-x,p.z()-z)<=.100001,"Collision cannot add camera speed");
            x=p.x();z=p.z();
        }
        assertTrue(z>1.4,"Blocked diagonal movement must slide, not freeze the whole camera; z="+z);
    }
    @Test void insideCornerStopsBothBlockedAxesAndUnknownGeometryRefusesMovement(){
        var env=wall(true);var p=probe(env).clipCamera(.79,1.6,.79,1.1,1.6,1.1);
        assertTrue(p.x()<1&&p.z()<1);
        assertEquals(0,EnvironmentGeometry.occlusion(env,.79,1.6,.79,p.x(),p.y(),p.z()));
        var unknown=CinematicCollision.clip(EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7),
            16.5,1.6,0,20,1.6,0);
        assertEquals(new CineDirector.Vec3Holder(16.5,1.6,0),unknown);
    }
    @Test void slabUpperHalfRemainsClearAndLowerHalfStaysBlocked(){
        var env=EnvironmentFrame.decode(EnvironmentShapesTest.packet(),1001,7);var probe=probe(env);
        assertEquals(new CineDirector.Vec3Holder(2,.75,.5),probe.clipCamera(-2,.75,.5,2,.75,.5));
        var lower=probe.clipCamera(-2,.25,.5,2,.25,.5);
        assertTrue(lower.x()<0);
        assertEquals(0,EnvironmentGeometry.occlusion(env,-2,.25,.5,lower.x(),lower.y(),lower.z()));
    }
    @Test void distantLandscapeCannotDropTheNativeCameraCommand(){
        var env=EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7);
        var p=probe(env).clipCamera(.5,1.6,.5,.5,6.5,16.5);
        var state=new CinematicState(1,1000,7,1,false,.5,0,.5,.5,1.6,.5,0,0,70,1);
        var pose=com.muoniumplayer.core.cine.CinePose.of(p.x(),p.y(),p.z(),0,0,0);
        var command=ByteBuffer.wrap(CinematicCommand.encode(state,pose,1,1001,true,true)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1,command.getInt(40),"An in-flight landscape camera must remain inside the existing 16-block command guard");
    }
    @Test void plannedPathHasCameraClearanceAndPreservesPartialSlabShapes(){
        var wall=wall(false);
        assertTrue(CinematicCollision.clear(wall,new CineDirector.Vec3Holder(.7,1.6,-2),new CineDirector.Vec3Holder(.7,1.6,2)));
        assertFalse(CinematicCollision.clear(wall,new CineDirector.Vec3Holder(.9,1.6,-2),new CineDirector.Vec3Holder(.9,1.6,2)));
        var slab=EnvironmentFrame.decode(EnvironmentShapesTest.packet(),1001,7);
        assertTrue(CinematicCollision.clear(slab,new CineDirector.Vec3Holder(-2,.75,.5),new CineDirector.Vec3Holder(2,.75,.5)));
        assertFalse(CinematicCollision.clear(slab,new CineDirector.Vec3Holder(-2,.6,.5),new CineDirector.Vec3Holder(2,.6,.5)));
        assertFalse(CinematicCollision.clear(slab,new CineDirector.Vec3Holder(15,1.6,0),new CineDirector.Vec3Holder(18,1.6,0)));
    }
    @Test void slidingAgainstAnOuterWallCannotLeaveTheNativeCommandRadius(){
        byte[] raw=EnvironmentFrameTest.packet();
        for(int y=0;y<=6;y++)for(int z=-2;z<=12;z++)raw[256+((y+16)*33+z+16)*33+12+16]=1;
        var env=EnvironmentFrame.decode(raw,1001,7);
        var point=probe(env).clipCamera(13.2,3,0,11,3,10);
        double radius=Math.sqrt(Math.pow(point.x()-.5,2)+point.y()*point.y()+Math.pow(point.z()-.5,2));
        assertTrue(radius<=CinematicCollision.COMMAND_RADIUS+.000001,"Wall sliding pushed the camera beyond the native guard: "+radius);
        assertEquals(0,EnvironmentGeometry.occlusion(env,13.2,3,0,point.x(),point.y(),point.z()));
    }
    @Test void nativeSnapshotPreflightChecksTheLaterWallAndEveryOutputCommand(){
        var env=wall(false);var probe=probe(env);var start=new CineDirector.Vec3Holder(.5,1.6,.5);
        var desired=new CineDirector.Vec3Holder[]{new CineDirector.Vec3Holder(.6,2,-4),
            new CineDirector.Vec3Holder(2,2,-4),new CineDirector.Vec3Holder(4,2,-4)};
        long began=System.nanoTime();
        var route=com.muoniumplayer.core.cine.CineRoutePlanner.plan(probe,start,start,desired);
        System.out.println("OWNED_ROUTE actualAabbs=true mode="+route.mode()+" elapsedUs="+(System.nanoTime()-began)/1000);
        assertEquals("replanned",route.mode());var previous=start;
        var state=new CinematicState(1,1000,7,1,false,.5,0,.5,.5,1.6,.5,0,0,70,1);
        for(int i=0;i<=200;i++){
            var point=route.sample(i/200d);assertTrue(probe.cameraPathClear(previous,point));
            assertEquals(0,EnvironmentGeometry.occlusion(env,previous.x(),previous.y(),previous.z(),point.x(),point.y(),point.z()));
            var pose=com.muoniumplayer.core.cine.CinePose.of(point.x(),point.y(),point.z(),0,0,0);
            assertEquals(1,ByteBuffer.wrap(CinematicCommand.encode(state,pose,i+1,1001,true,true)).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
            previous=point;
        }
    }
    @Test void originalRouteIsRetainedWhenAllNativeSnapshotSegmentsAreClear(){
        var probe=probe(EnvironmentFrame.decode(EnvironmentFrameTest.packet(),1001,7));
        var start=new CineDirector.Vec3Holder(.5,1.6,.5);var desired=new CineDirector.Vec3Holder[257];
        for(int i=0;i<desired.length;i++){
            double angle=i*Math.PI*2/(desired.length-1);
            desired[i]=new CineDirector.Vec3Holder(.5+10*Math.cos(angle),5,.5+10*Math.sin(angle));
        }
        long began=System.nanoTime();var route=com.muoniumplayer.core.cine.CineRoutePlanner.plan(probe,start,start,desired);
        System.out.println("OWNED_ROUTE samples=257 actualAabbs=true mode="+route.mode()+" elapsedUs="+(System.nanoTime()-began)/1000);
        assertEquals("original",route.mode());assertEquals(1,route.attempts());
        assertEquals(desired[256],route.sample(1));
    }
}
