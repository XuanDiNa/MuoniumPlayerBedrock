package com.muoniumplayer.bedrock;
import com.muoniumplayer.core.cine.*;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.render.skija.SkijaHost;
/** Original shot/beat director, backed by independent player and camera samples. */
final class CinematicRuntime {
    private final CinematicState.Session session=new CinematicState.Session();
    private long lastTick;private CinematicState latest;
    private long diagnosticAt;private int diagnosticCount;private String diagnosticState;
    private long diagnosticPulse;private int pulseCount,shotCount,diagnosticShot=-1;
    boolean enabled(){return CineSettings.isEnabled();}
    boolean active(){return CineDirector.isActive()&&latest!=null&&latest.applied();}
    boolean hideHud(){return active()&&CineSettings.hideHud();}
    void update(CinematicReader reader,EnvironmentFrame env,WorldFrame world,boolean allowed,long now){
        boolean requested=allowed&&enabled();reader.request(requested);
        var sample=reader.read();if(sample!=null)latest=sample;
        // A contended nonblocking read can reuse at most one short lease. Native
        // always checks current input/focus/health independently before writes.
        if(!requested||latest!=null&&readerNow()-latest.at()>100)latest=null;
        var playback=CloudMusic.getSnapshot();
        boolean ready=requested&&latest!=null&&env!=null&&world!=null&&env.epoch==world.epoch()
            &&playback!=null&&playback.playing;
        if(session.changed(latest)||!ready)CineDirector.onClientTick(null);
        if(now-lastTick>=50_000_000L){lastTick=now;CineDirector.onClientTick(ready?new Probe(latest,env):null);}
        var pose=ready?CineDirector.currentPose(latest.eyeX(),latest.eyeY(),latest.eyeZ()):null;
        reader.write(latest,pose,CineSettings.hideHud(),ready&&CineDirector.isActive());
        if(pose!=null&&CineDirector.isActive()){
            int shot=CineDirector.diagnosticShotSequence();
            if(shotCount<64&&shot!=diagnosticShot){
                diagnosticShot=shot;shotCount++;
                System.out.println("CINEMATIC_SHOT uptimeMs="+readerNow()+" shot="+shot+" epoch="+latest.epoch()+" input="+latest.input());
            }
            long pulse=CineDirector.diagnosticPulseNanos();
            if(pulseCount<256&&pulse>0&&pulse!=diagnosticPulse){
                diagnosticPulse=pulse;pulseCount++;
                System.out.println("CINEMATIC_PULSE uptimeMs="+readerNow()+" shot="+shot+" epoch="+latest.epoch()+" input="+latest.input()
                    +" pulseAgeMs="+Math.max(0,(System.nanoTime()-pulse)/1_000_000L)+" offset="+pose.fovOffsetDeg()+" configuredDegrees="+CineSettings.fovPunchDegrees());
            }
        }else diagnosticShot=-1;
        String status="allowed="+allowed+" requested="+requested+" ready="+ready+" director="+CineDirector.isActive()+" applied="+(latest!=null&&latest.applied());
        if(diagnosticCount<160&&(!status.equals(diagnosticState)||now-diagnosticAt>5_000_000_000L)){
            diagnosticState=status;diagnosticAt=now;diagnosticCount++;
            System.out.println("CINEMATIC_HOST "+status+" uptimeMs="+readerNow()+" baseFov="+(latest==null?0:latest.fov())+" offset="+(pose==null?0:pose.fovOffsetDeg())+" sampleAgeMs="+(latest==null?-1:readerNow()-latest.at()));
        }
    }
    private static long readerNow(){return com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64();}
    void draw(){
        if(!active())return;var canvas=SkijaHost.canvas();if(canvas==null)return;
        double width=RenderSystem.getLayoutWidth(),height=RenderSystem.getLayoutHeight();
        if(width<=0||height<=0)return;
        // Upstream effects use a fixed layout axis. Passive host frames begin
        // in GUI units, so map that axis to the complete physical client once.
        var window=BedrockClient.getInstance().getWindow();int save=canvas.save();
        try{
            canvas.scale((float)(window.getWidth()/SkijaHost.guiScale()/width),
                (float)(window.getHeight()/SkijaHost.guiScale()/height));
            CineTransition.render();CineBars.render();
        }finally{canvas.restoreToCount(save);}
    }
    static final class Probe implements CineDirector.SceneProbe {
        private final CinematicState state;private final EnvironmentFrame environment;
        Probe(CinematicState state,EnvironmentFrame environment){this.state=state;this.environment=environment;}
        public double playerX(){return state.x();}public double playerY(){return state.y();}public double playerZ(){return state.z();}
        public float playerYaw(){return state.yaw();}public float playerPitch(){return state.pitch();}
        public Object openScreen(){return null;}public boolean hurtThisTick(){return false;}public boolean inputThisTick(){return false;}
        public boolean playerAlive(){return state.health()>0;}
        public boolean clipBlocked(double x,double y,double z,double ex,double ey,double ez){
            return environment==null||EnvironmentGeometry.occlusion(environment,x,y,z,ex,ey,ez)>0;
        }
        public CineDirector.MobSample nearestMovingMob(double range){return null;} // This first adapter exposes player/scenery shots only.
    }
}
