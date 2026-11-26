package com.muoniumplayer.core.cine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

/** Actual session frames, not a second copy of the camera integrator. */
class CinematicSmoothnessTest {
    private static final double DT=.05;
    private static final class Harness {
        final CineSession session=new CineSession();
        final Object scene;
        final Method publish;
        final CineDirector.SceneProbe probe=new CinematicRoutePlannerTest.Probe();
        Harness()throws Exception {
            Class<?> type=Class.forName("com.muoniumplayer.core.cine.CineSession$Scene");
            var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
            scene=constructor.newInstance(new Object(),new Object(),0d,0d,0d,0d,0f,0f,true,0,"plains",6000L);
            publish=CineSession.class.getDeclaredMethod("publishPose",CineDirector.SceneProbe.class,type,
                CinePose.class,double.class,float.class,CineSession.Transition.class,double.class);
            publish.setAccessible(true);
        }
        CineSession.Frame step(CinePose target)throws Exception {
            publish.invoke(session,probe,scene,target,DT,0f,null,.8d);
            return session.frame();
        }
    }
    private static double axis(CinePose pose,String axis) {
        return switch(axis){case "position"->pose.x();case "yaw"->pose.yawDeg();case "pitch"->pose.pitchDeg();case "roll"->pose.rollDeg();default->pose.fovOffsetDeg();};
    }
    private static CinePose target(String axis,double value) {
        return new CinePose(axis.equals("position")?value:0,0,0,
            axis.equals("yaw")?(float)value:0,axis.equals("pitch")?(float)value:0,
            axis.equals("roll")?(float)value:0,axis.equals("fov")?(float)value:0);
    }
    @ParameterizedTest @ValueSource(strings={"position","yaw","pitch","roll","fov"})
    void approachingAStationaryTargetBrakesWithoutASuddenFullSpeedStop(String dimension)throws Exception {
        var h=new Harness();double destination=dimension.equals("position")?6:dimension.equals("fov")?6:90;
        double acceleration=dimension.equals("position")?12:dimension.equals("fov")?6:90;
        double maximumSpeed=dimension.equals("position")?5:dimension.equals("fov")?3:45;
        double previous=0,previousSpeed=0,worst=0;
        for(int i=0;i<400;i++) {
            var frame=h.step(target(dimension,destination));double current=axis(frame.current(),dimension);
            double speed=(current-previous)/DT;
            assertTrue(current>=previous-1e-5&&current<=destination+1e-5,"A stationary target must not cause overshoot or reversal");
            assertTrue(Math.abs(speed)<=maximumSpeed+.001,"Camera speed bound must survive smoothing");
            worst=Math.max(worst,Math.abs(speed-previousSpeed)/DT);
            previous=current;previousSpeed=speed;
        }
        System.out.println("CINEMATIC_SMOOTHNESS axis="+dimension+" peakAcceleration="+worst);
        assertEquals(destination,previous,.002,"The smoother must arrive, rather than freeze short of the target");
        assertTrue(worst<=acceleration+.15,"The full camera path must decelerate before arrival; axis="+dimension+" acceleration="+worst);
    }
    @Test void constantTravelDoesNotEaseToZeroAndAccelerateAgainEveryTwentiethOfASecond()throws Exception {
        var h=new Harness();double min=Double.POSITIVE_INFINITY,max=0;
        for(int tick=1;tick<=300;tick++) {
            var frame=h.step(target("position",tick*DT));
            if(tick<200)continue;
            double previous=frame.sample(0).x();
            // 240 render samples/sec over the real 20Hz immutable frames.
            for(int sample=1;sample<=12;sample++) {
                double current=frame.sample(sample/12f).x();
                double speed=(current-previous)/(DT/12);
                min=Math.min(min,speed);max=Math.max(max,speed);previous=current;
            }
        }
        System.out.println("CINEMATIC_SMOOTHNESS steadySpeedMin="+min+" max="+max);
        assertTrue(min>.90&&max<1.10,"A constant 1m/s target must not pulse at tick frequency: "+min+".."+max);
    }
}
