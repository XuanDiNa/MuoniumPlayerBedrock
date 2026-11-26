package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import com.muoniumplayer.core.cine.CineDirector.Vec3Holder;

/** Actual session must inspect a shot's future geometry before publishing it. */
class CinematicRoutePlannerTest {
    static class Probe implements CineDirector.SceneProbe {
        final Object world=new Object(),player=new Object();
        int checks;double farthest;boolean wall;
        public Object worldIdentity(){return world;}public Object playerIdentity(){return player;}
        public double playerX(){return 0;}public double playerY(){return 0;}public double playerZ(){return 0;}
        public double playerEyeY(){return 1.6;}public float playerYaw(){return 0;}public float playerPitch(){return 0;}
        public Object openScreen(){return null;}public boolean hurtThisTick(){return false;}
        public boolean inputThisTick(){return false;}public boolean playerAlive(){return true;}
        public boolean skyVisible(){return true;}public int dimensionKind(){return 0;}
        public String biomeKey(){return "plains";}public long timeOfDay(){return 6000;}
        public CineDirector.Vec3Holder clipCamera(double x,double y,double z,double ex,double ey,double ez){
            return new CineDirector.Vec3Holder(wall?Math.min(.75,ex):ex,ey,ez);
        }
        public boolean cameraPlanningEnabled(){return true;}
        public boolean cameraPathClear(CineDirector.Vec3Holder from,CineDirector.Vec3Holder to){
            checks++;farthest=Math.max(farthest,Math.hypot(to.x(),to.z()));
            return to.x()*to.x()+to.y()*to.y()+to.z()*to.z()<=15.5*15.5
                &&(!wall||Math.max(from.x(),to.x())<.8);
        }
        public boolean cameraAnchorVisible(Vec3Holder anchor,Vec3Holder point){return cameraPathClear(anchor,point);}
        public CineDirector.MobSample nearestMovingMob(double range){return null;}
        public CineDirector.MobSample trackedMob(int id,double range){return null;}
    }
    @BeforeEach void setup(){CineSettings.resetForTest();CineSettings.setEnabled(true);
        CineSettings.setIdleSeconds(5);CineSettings.setEnvironmentShotPercent(0);CineSettings.setTransitionMask(0);}
    @AfterEach void cleanup(){CineSettings.resetForTest();}
    @Test void startingAShotChecksItsFutureRouteBeforeCameraMovement(){
        var session=new CineSession();var probe=new Probe();long now=1_000_000_000L;
        for(int i=0;i<102;i++)session.tick(probe,CineSettings.snapshot(),new CineSession.Playback(true,1,1,i*50L,null,0),now+=50_000_000L);
        assertTrue(session.frame().active());
        assertTrue(probe.checks>10,"The full shot must be inspected, not just the current frame's collision");
        assertTrue(probe.farthest>2,"Preflight must inspect future camera positions");
    }
    @Test void wallInTheLaterHalfOfAShotSelectsAnEntireClearAlternative(){
        var probe=new Probe();probe.wall=true;
        var start=new Vec3Holder(0,1.6,0);
        var desired=new Vec3Holder[]{new Vec3Holder(0,2,-4),new Vec3Holder(2,2,-4),new Vec3Holder(4,2,-4)};
        var route=CineRoutePlanner.plan(probe,start,start,desired);
        assertEquals("replanned",route.mode());assertTrue(route.attempts()>1&&route.attempts()<=24);
        var previous=start;
        for(int i=0;i<=200;i++){
            var point=route.sample(i/200d);assertTrue(probe.cameraPathClear(previous,point));previous=point;
        }
        assertEquals(new Vec3Holder(4,2,-4),desired[2],"Planning must not mutate the original shot samples");
    }
    @Test void noClearCandidateHoldsTheExistingCameraWithABoundedSearch(){
        var probe=new Probe(){public boolean cameraPathClear(Vec3Holder from,Vec3Holder to){checks++;return false;}};
        var start=new Vec3Holder(0,1.6,0);
        var route=CineRoutePlanner.plan(probe,start,start,new Vec3Holder[]{new Vec3Holder(1,2,0),new Vec3Holder(3,2,0)});
        assertEquals("hold",route.mode());assertEquals(24,route.attempts());assertEquals(24,probe.checks);
        for(double t:new double[]{0,.25,.5,1})assertEquals(start,route.sample(t));
    }
    @Test void routePreflightPreservesTheNativeRadiusEvenForTheWidestShot(){
        var probe=new Probe();var start=new Vec3Holder(0,1.6,0);
        var route=CineRoutePlanner.plan(probe,start,start,new Vec3Holder[]{new Vec3Holder(0,6.5,16),new Vec3Holder(0,6.5,16)});
        assertEquals("replanned",route.mode());var point=route.sample(1);
        assertTrue(point.x()*point.x()+point.y()*point.y()+point.z()*point.z()<16*16);
    }
    @Test void newlyPlacedWallNeverTeleportsOrThrowsTheCameraOutOfRange(){
        var probe=new Probe();var session=new CineSession();long now=1_000_000_000L;CinePose previous=null;
        for(int i=0;i<700;i++){
            if(i==280)probe.wall=true;
            session.tick(probe,CineSettings.snapshot(),new CineSession.Playback(true,1,1,i*50L,null,0),now+=50_000_000L);
            var current=session.frame().current();if(current==null)continue;
            assertTrue(current.isFinite());
            assertTrue(current.x()*current.x()+current.y()*current.y()+current.z()*current.z()<16*16);
            if(previous!=null){
                double step=Math.sqrt(Math.pow(current.x()-previous.x(),2)+Math.pow(current.y()-previous.y(),2)+Math.pow(current.z()-previous.z(),2));
                assertTrue(step<=.25001,"A replan must retain the existing camera speed bound: "+step);
            }
            previous=current;
        }
    }
    @Test void aFreeCameraPathStillReplansWhenThePlayerWouldBeHiddenBehindAWall(){
        var probe=new Probe(){public boolean cameraAnchorVisible(Vec3Holder anchor,Vec3Holder point){return point.x()<.8;}};
        var start=new Vec3Holder(0,1.6,0);
        var route=CineRoutePlanner.plan(probe,start,start,new Vec3Holder[]{new Vec3Holder(0,2,-6),new Vec3Holder(4,2,-6)});
        assertEquals("replanned",route.mode(),"Free camera travel alone must not discard the original eye-to-camera visibility check");
    }
}
