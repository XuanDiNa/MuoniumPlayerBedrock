package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** The former monolithic scheduler regressions, through the immutable session. */
class CinematicScheduleIntegrationTest {
    private final Object world=new Object(),player=new Object();
    private final CineDirector.SceneProbe probe=new CineDirector.SceneProbe(){
        public Object worldIdentity(){return world;}public Object playerIdentity(){return player;}
        public double playerX(){return 10;}public double playerY(){return 64;}public double playerZ(){return 10;}
        public double playerEyeY(){return 65.6;}public float playerYaw(){return 0;}public float playerPitch(){return 0;}
        public Object openScreen(){return null;}public boolean hurtThisTick(){return false;}
        public boolean inputThisTick(){return false;}public boolean playerAlive(){return true;}
        public boolean skyVisible(){return true;}public int dimensionKind(){return 0;}
        public String biomeKey(){return "plains";}public long timeOfDay(){return 6000;}
        public CineDirector.Vec3Holder clipCamera(double x,double y,double z,double ex,double ey,double ez){return new CineDirector.Vec3Holder(ex,ey,ez);}
        public CineDirector.MobSample nearestMovingMob(double range){return null;}
        public CineDirector.MobSample trackedMob(int id,double range){return null;}
    };
    private CineSession session;private long now,position;
    private void tick(){now+=50_000_000L;position+=50;session.tick(probe,CineSettings.snapshot(),
        new CineSession.Playback(true,1,1,position,null,.5f),now);}
    @BeforeEach void setup(){CineSettings.resetForTest();CineSettings.setEnabled(true);CineSettings.setIdleSeconds(5);
        CineSettings.setMinShotSeconds(12);session=new CineSession();now=1_000_000_000L;
        for(int i=0;i<101;i++)tick();assertTrue(session.frame().active());}
    @AfterEach void cleanup(){CineSettings.resetForTest();}
    @Test void highFrameRateReadsNeverConsumeTheShotLifetime(){
        var frame=session.frame();var pose=frame.sample(.5f);int shot=session.shotSequence();
        for(int i=0;i<2400;i++)assertEquals(pose,frame.sample(.5f));
        assertSame(frame,session.frame());assertEquals(shot,session.shotSequence());
        for(int i=0;i<220;i++)tick();assertEquals(shot,session.shotSequence());
    }
    @Test void effectlessCutsHonorTwelveSecondFloorAndDoNotRepeatEachTick(){
        CineSettings.setTransitionMask(0);int initial=session.shotSequence();
        for(int i=0;i<239;i++)tick();assertEquals(initial,session.shotSequence());
        tick();assertEquals(initial+1,session.shotSequence());
        for(int i=0;i<239;i++)tick();assertEquals(initial+1,session.shotSequence());
        tick();assertEquals(initial+2,session.shotSequence());
    }
    @Test void expiredMaskReadsCannotMutateTheScheduler(){
        for(int i=0;i<240;i++)tick();var frame=session.frame();int shot=session.shotSequence();
        if(frame.transition()!=null)for(int i=0;i<1000;i++)frame.transition().at(now+100_000_000_000L);
        assertSame(frame,session.frame());assertEquals(shot,session.shotSequence());
        for(int i=0;i<220;i++)tick();assertEquals(shot,session.shotSequence());
    }
}
