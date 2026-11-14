package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class CinematicScheduleIntegrationTest {
    static final CineDirector.SceneProbe PROBE=new CineDirector.SceneProbe(){
        public double playerX(){return 10;}public double playerY(){return 64;}public double playerZ(){return 10;}
        public float playerYaw(){return 0;}public float playerPitch(){return 0;}
        public Object openScreen(){return null;}public boolean hurtThisTick(){return false;}
        public boolean inputThisTick(){return false;}public boolean playerAlive(){return true;}
        public boolean clipBlocked(double x,double y,double z,double ex,double ey,double ez){return false;}
        public CineDirector.MobSample nearestMovingMob(double range){return null;}
    };
    static void field(String name,Object value)throws Exception{
        var f=CineDirector.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    static int shot()throws Exception{
        var f=CineDirector.class.getDeclaredField("currentShotSequence");f.setAccessible(true);return f.getInt(null);
    }
    static void tick()throws Exception{
        var m=CineDirector.class.getDeclaredMethod("tickActive",CineDirector.SceneProbe.class);m.setAccessible(true);m.invoke(null,PROBE);
    }
    @BeforeEach void reset(){
        System.setProperty("muonium.bedrock.offlineTest","true");
        CineSettings.resetForTest();CineDirector.resetForTest();CineDirector.forceActiveForTest(true);
    }
    @AfterEach void cleanup(){CineDirector.resetForTest();CineSettings.resetForTest();}
    void finishedTransition(boolean renderPolls)throws Exception{
        field("activeTicks",500L);field("lastCutAtTick",0L);field("transitionShotPending",false);
        long now=System.nanoTime();CineDirector.forceTransitionForTest(1,now-2_000_000_000L,now-1_000_000_000L,true);
        if(renderPolls)for(int i=0;i<120;i++)assertNull(CineDirector.currentTransition());
        tick();assertNull(CineDirector.currentTransition(),"Finishing a transition must start a fresh shot interval");
        field("shotStartedNanos",System.nanoTime()-31_000_000_000L);
        for(int i=0;i<239;i++){tick();assertNull(CineDirector.currentTransition(),"Render polling must not cause another immediate cut");}
        tick();assertNotNull(CineDirector.currentTransition(),"The normal twelve-second fallback must still advance");
    }
    @Test void schedulerRetainsItsFullShotIntervalWithoutRenderPolling()throws Exception{finishedTransition(false);}
    @Test void highFrameRateTransitionReadsCannotConsumeTheSchedulerCompletion()throws Exception{finishedTransition(true);}
    @Test void disablingTransitionEffectsStillCutsOncePerShotInterval()throws Exception{
        CineSettings.setTransitionMask(0);field("activeTicks",239L);field("lastCutAtTick",0L);
        field("shotStartedNanos",System.nanoTime()-31_000_000_000L);
        tick();assertEquals(1,shot(),"No visual transition must still commit the next shot");
        for(int i=0;i<239;i++)tick();assertEquals(1,shot(),"No effect must not create a per-tick cutting loop");
        field("shotStartedNanos",System.nanoTime()-31_000_000_000L);
        tick();assertEquals(2,shot());
    }
    @Test void unmeteredFallbackCannotCutBeforeConfiguredMinimum()throws Exception{
        CineSettings.setMinShotSeconds(12f);
        var prepare=CineDirector.class.getDeclaredMethod("prepareShot",CineDirector.SceneProbe.class,long.class,boolean.class);
        prepare.setAccessible(true);prepare.invoke(null,PROBE,System.nanoTime(),false);
        field("activeTicks",239L);field("lastCutAtTick",0L);
        tick();assertNull(CineDirector.currentTransition(),"The tick fallback must still honor the minimum in real elapsed time");
        assertEquals(1,shot());
    }
    @Test void beatTriggeredAndEffectlessCutsAlsoHonorMonotonicShotLifetime()throws Exception{
        var prepare=CineDirector.class.getDeclaredMethod("prepareShot",CineDirector.SceneProbe.class,long.class,boolean.class);
        var begin=CineDirector.class.getDeclaredMethod("beginTransitionTo",CineDirector.SceneProbe.class,long.class);
        prepare.setAccessible(true);begin.setAccessible(true);
        for(int mask:new int[]{1,0}){
            CineDirector.resetForTest();CineDirector.forceActiveForTest(true);
            CineSettings.setMinShotSeconds(12f);CineSettings.setTransitionMask(mask);
            long start=System.nanoTime();prepare.invoke(null,PROBE,start,false);
            begin.invoke(null,PROBE,start+11_000_000_000L);
            assertEquals(1,shot());assertNull(CineDirector.currentTransition(),"Beat changes must not bypass the time floor");
            begin.invoke(null,PROBE,start+13_000_000_000L);
            if(mask==0)assertEquals(2,shot());else assertNotNull(CineDirector.currentTransition());
        }
    }
}
