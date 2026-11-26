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
    private int shotCount,diagnosticShot=-1;
    private long contextEpoch=Long.MIN_VALUE;private int contextDimension=-1;
    private Object worldIdentity,playerIdentity;
    private long resetCount;private String lastReset="none";
    boolean enabled(){return CineSettings.isEnabled();}
    boolean active(){return CineDirector.isActive()&&latest!=null&&latest.applied();}
    boolean hideHud(){return active()&&CineSettings.hideHud();}
    CinematicState voicePlayer(){return latest;}
    void update(CinematicReader reader,EnvironmentFrame env,WorldFrame world,boolean allowed,long now){
        boolean requested=allowed&&enabled();reader.request(requested);
        // Reader preserves only protocol-valid synchronized values or a bounded
        // contention lease; an explicit invalid publication revokes immediately.
        latest=reader.read();
        var playback=CloudMusic.getSnapshot();
        String gate=!allowed?"host-inactive":!requested?"disabled":latest==null?"camera-unavailable":
            env==null?"environment-unavailable":world==null?"world-unavailable":env.epoch!=world.epoch()?"world-mismatch":
            playback==null||!playback.playing?"music-stopped":"ready";
        boolean ready="ready".equals(gate);
        String reset=session.changeReason(latest);
        if(!"none".equals(reset)){resetCount++;lastReset=reset;}
        if(!"none".equals(reset)||!ready){
            CineDirector.onClientTick(null);
            // A different input/world cannot inherit accumulated simulation time.
            lastTick=ready?now-50_000_000L:now;
        }
        if(ready&&(contextEpoch!=latest.epoch()||contextDimension!=env.dimension)){
            contextEpoch=latest.epoch();contextDimension=env.dimension;
            worldIdentity=new Object();playerIdentity=new Object();
        }
        // Preserve the fractional tick across render frames. Resetting the
        // anchor to each late frame slowed both idle and movement at e.g. 125Hz.
        // Do not replay a long suspension or query old world samples.
        if(ready){
            if(now<lastTick||now-lastTick>250_000_000L)lastTick=now-50_000_000L;
            while(now-lastTick>=50_000_000L){
                lastTick+=50_000_000L;
                CineDirector.onClientTick(new Probe(latest,env,worldIdentity,playerIdentity));
            }
        }
        float partialTick=Math.max(0f,Math.min(1f,(now-lastTick)/50_000_000f));
        var pose=ready?CineDirector.currentPose(partialTick):null;
        reader.write(latest,pose,CineSettings.hideHud(),ready&&CineDirector.isActive());
        if(pose!=null&&CineDirector.isActive()){
            int shot=CineDirector.diagnosticShotSequence();
            if(shotCount<64&&shot!=diagnosticShot){
                diagnosticShot=shot;shotCount++;
                System.out.println("CINEMATIC_SHOT uptimeMs="+readerNow()+" shot="+shot+" epoch="+latest.epoch()+" input="+latest.input());
            }
        }else diagnosticShot=-1;
        String status="allowed="+allowed+" requested="+requested+" ready="+ready+" director="+CineDirector.isActive()+" applied="+(latest!=null&&latest.applied());
        if(diagnosticCount<160&&(!status.equals(diagnosticState)||now-diagnosticAt>5_000_000_000L)){
            diagnosticState=status;diagnosticAt=now;diagnosticCount++;
            System.out.println("CINEMATIC_HOST "+status+" gate="+gate+" resets="+resetCount+" lastReset="+lastReset
                +" epoch="+(latest==null?0:latest.epoch())+" input="+(latest==null?0:latest.input())+" uptimeMs="+readerNow()
                +" baseFov="+(latest==null?0:latest.fov())+" offset="+(pose==null?0:pose.fovOffsetDeg())
                +" radius="+(pose==null||latest==null?0:Math.sqrt(Math.pow(pose.x()-latest.x(),2)+Math.pow(pose.y()-latest.y(),2)+Math.pow(pose.z()-latest.z(),2)))
                +" sampleAgeMs="+(latest==null?-1:readerNow()-latest.at())+" "+reader.diagnostic());
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
        private final Object world,player;
        Probe(CinematicState state,EnvironmentFrame environment,Object world,Object player){this.state=state;this.environment=environment;this.world=world;this.player=player;}
        public Object worldIdentity(){return world;}public Object playerIdentity(){return player;}
        public double playerX(){return state.x();}public double playerY(){return state.y();}public double playerZ(){return state.z();}
        public double playerEyeY(){return state.eyeY();}
        public float playerYaw(){return state.yaw();}public float playerPitch(){return state.pitch();}
        public Object openScreen(){return BedrockClient.getInstance().gui.screen();}public boolean hurtThisTick(){return false;}public boolean inputThisTick(){return false;}
        public boolean skyVisible(){return environment!=null&&(environment.known&64)!=0&&environment.sky>0;}
        public int dimensionKind(){return environment==null?0:environment.dimension;}
        public String biomeKey(){return environment!=null&&(environment.known&4)!=0?environment.biome:"unknown";}
        public long timeOfDay(){return environment!=null&&(environment.known&1)!=0?environment.time:6000;}
        public boolean playerAlive(){return state.health()>0;}
        public CineDirector.Vec3Holder clipCamera(double x,double y,double z,double ex,double ey,double ez){
            var target=CinematicCollision.bound(state,ex,ey,ez);
            var result=CinematicCollision.clip(environment,x,y,z,target.x(),target.y(),target.z());
            return CinematicCollision.boundTravel(state,new CineDirector.Vec3Holder(x,y,z),result);
        }
        public boolean cameraPlanningEnabled(){return true;}
        public boolean cameraAnchorVisible(CineDirector.Vec3Holder anchor,CineDirector.Vec3Holder point){
            return environment!=null&&EnvironmentGeometry.occlusion(environment,anchor.x(),anchor.y(),anchor.z(),point.x(),point.y(),point.z())==0;
        }
        public boolean cameraPathClear(CineDirector.Vec3Holder from,CineDirector.Vec3Holder to){
            for(var p:new CineDirector.Vec3Holder[]{from,to})
                if(Math.pow(p.x()-state.x(),2)+Math.pow(p.y()-state.y(),2)+Math.pow(p.z()-state.z(),2)
                    >CinematicCollision.COMMAND_RADIUS*CinematicCollision.COMMAND_RADIUS)return false;
            return CinematicCollision.clear(environment,from,to);
        }
        public CineDirector.MobSample nearestMovingMob(double range){return null;} // This first adapter exposes player/scenery shots only.
        public CineDirector.MobSample trackedMob(int entityId,double range){return null;}
    }
}
