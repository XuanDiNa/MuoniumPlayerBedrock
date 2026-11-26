package com.muoniumplayer.core.cine;

/** Test-only immutable-frame publisher; never included in a distribution. */
public final class CinematicFrameFixture {
    public static void publish(float bars,int effect,long start,long cut,long end,boolean applied)throws Exception{
        var pose=CinePose.of(0,65.6,0,0,0,0);
        var transition=effect==0?null:new CineSession.Transition(effect,start,cut,end,applied);
        var field=CineDirector.class.getDeclaredField("frame");field.setAccessible(true);
        field.set(null,new CineSession.Frame(1,pose,pose,transition,bars,CineSession.Motion.ZERO,CineSession.Motion.ZERO));
    }
    public static void clear()throws Exception{
        var field=CineDirector.class.getDeclaredField("frame");field.setAccessible(true);field.set(null,CineSession.Frame.IDLE);
    }
}
