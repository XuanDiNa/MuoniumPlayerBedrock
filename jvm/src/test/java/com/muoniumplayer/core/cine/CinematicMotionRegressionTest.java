package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** A shot change must keep looking at the subject during the camera's travel. */
class CinematicMotionRegressionTest {
    private final Object world=new Object(),player=new Object();
    private final CineDirector.SceneProbe probe=new CineDirector.SceneProbe(){
        public Object worldIdentity(){return world;}public Object playerIdentity(){return player;}
        public double playerX(){return 0;}public double playerY(){return 0;}public double playerZ(){return 0;}
        public double playerEyeY(){return 1.6;}public float playerYaw(){return 0;}public float playerPitch(){return 0;}
        public Object openScreen(){return null;}public boolean hurtThisTick(){return false;}
        public boolean inputThisTick(){return false;}public boolean playerAlive(){return true;}
        public boolean skyVisible(){return true;}public int dimensionKind(){return 0;}
        public String biomeKey(){return "plains";}public long timeOfDay(){return 6000;}
        public CineDirector.Vec3Holder clipCamera(double x,double y,double z,double ex,double ey,double ez){
            return new CineDirector.Vec3Holder(ex,ey,ez);
        }
        public CineDirector.MobSample nearestMovingMob(double range){return null;}
        public CineDirector.MobSample trackedMob(int id,double range){return null;}
    };
    @BeforeEach void setup(){CineSettings.resetForTest();CineSettings.setEnabled(true);
        CineSettings.setIdleSeconds(5);CineSettings.setMinShotSeconds(12);CineSettings.setTransitionMask(0);}
    @AfterEach void cleanup(){CineSettings.resetForTest();}
    @Test void turningTowardTheDestinationMustNotTurnAwayFromTheSubjectDuringTravel()throws Exception {
        var session=new CineSession();long now=1_000_000_000L;
        for(int i=0;i<102;i++){
            now+=50_000_000L;session.tick(probe,CineSettings.snapshot(),
                new CineSession.Playback(true,1,1,i*50L,null,0),now);
        }
        var start=CinePose.of(0,2.8,8,180,(float)Math.toDegrees(Math.atan2(1.8,8)),0);
        var frame=CineSession.class.getDeclaredField("frame");frame.setAccessible(true);
        frame.set(session,new CineSession.Frame(1,start,start,null,0,CineSession.Motion.ZERO,CineSession.Motion.ZERO));
        var shot=CineSession.class.getDeclaredField("shot");shot.setAccessible(true);
        shot.set(session,CineShots.Shot.PLAYER_CINEMA_FRONT_LONG);
        var reset=CineSession.class.getDeclaredMethod("resetVelocity");reset.setAccessible(true);reset.invoke(session);
        double worst=0;
        for(int i=0;i<60;i++){
            now+=50_000_000L;session.tick(probe,CineSettings.snapshot(),
                new CineSession.Playback(true,1,1,(102+i)*50L,null,0),now);
            var pose=session.frame().current();
            double lookingYaw=Math.toDegrees(Math.atan2(pose.x(),-pose.z()));
            worst=Math.max(worst,Math.abs(CineShots.wrapDegrees((float)(pose.yawDeg()-lookingYaw))));
        }
        assertTrue(worst<30,"The subject drifts out of frame while the camera translates: "+worst+" degrees");
    }
    private CinePose poseAfterOneSecond(float softness){
        CineSettings.setTrackingSoftness(softness);var session=new CineSession();long now=1_000_000_000L;
        for(int i=0;i<123;i++){
            now+=50_000_000L;session.tick(probe,CineSettings.snapshot(),
                new CineSession.Playback(true,1,1,i*50L,null,0),now);
        }
        return session.frame().current();
    }
    @Test void trackingSoftnessMustActuallyChangeCameraTravel(){
        var fast=poseAfterOneSecond(.2f);var slow=poseAfterOneSecond(2f);
        double distance=Math.sqrt(Math.pow(fast.x()-slow.x(),2)+Math.pow(fast.y()-slow.y(),2)+Math.pow(fast.z()-slow.z(),2));
        assertTrue(distance>.1,"Tracking softness currently produces identical motion: "+distance);
    }
}
