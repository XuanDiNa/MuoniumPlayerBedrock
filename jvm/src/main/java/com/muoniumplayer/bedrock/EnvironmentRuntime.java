package com.muoniumplayer.bedrock;

import java.util.function.Consumer;
import com.muoniumplayer.core.ncm.music.ambient.*;
import com.muoniumplayer.core.ncm.music.acoustics.*;

/** Main companion tick adapter; geometry work uses immutable snapshots off-thread. */
final class EnvironmentRuntime {
    private final Consumer<EnvSample> ambient;
    private final Consumer<AcousticsRuntime.ListenerSampler> acoustics;
    private record Snapshot(EnvironmentFrame frame,SpeakerState source,Object context){}
    private volatile Snapshot latest;
    private volatile long receivedAt;
    private long loggedAt;private String lastLabel="";
    private final AcousticsRuntime.ListenerSampler sampler=new AcousticsRuntime.ListenerSampler(){
        public ListenerSample capture(){var s=current();return s==null?null:EnvironmentGeometry.sample(s.frame,s.source);}
        public Object contextKey(){var s=current();return s==null?null:s.context;}
        public double[] originMeters(){var s=current();return s==null||s.frame==null?null:new double[]{s.frame.x,s.frame.y,s.frame.z,
            Math.atan2(-s.frame.lx,s.frame.lz),Math.asin(Math.max(-1,Math.min(1,-s.frame.ly)))};}
    };
    EnvironmentRuntime(){this(AmbientMusicController::onClientTick,AcousticsRuntime::onClientTick);}
    EnvironmentRuntime(Consumer<EnvSample> ambient,Consumer<AcousticsRuntime.ListenerSampler> acoustics){this.ambient=ambient;this.acoustics=acoustics;}
    private Snapshot current(){var f=latest;return System.nanoTime()-receivedAt<=2_000_000_000L?f:null;}
    void tick(EnvironmentFrame frame,boolean hostEnabled,boolean autoMusic,boolean sceneSound){
        tick(frame,hostEnabled,autoMusic,sceneSound,null);
    }
    void tick(EnvironmentFrame frame,boolean hostEnabled,boolean autoMusic,boolean sceneSound,SpeakerState source){
        var previous=current();
        boolean same=previous!=null&&previous.source==source&&(frame==null
            ||previous.frame!=null&&previous.frame.epoch==frame.epoch&&previous.frame.dimension==frame.dimension);
        receivedAt=System.nanoTime();latest=hostEnabled&&sceneSound&&(frame!=null||source!=null)
            ?new Snapshot(frame,source,same?previous.context:new Object()):null;
        var env=hostEnabled&&autoMusic&&frame!=null?frame.ambient():null;
        ambient.accept(env);acoustics.accept(latest==null?null:sampler);
        if(hostEnabled&&frame!=null) {
            SceneAcoustics.setListenerPose(frame.x,frame.y,frame.z,(float)frame.lx,(float)frame.ly,(float)frame.lz,0,1,0);
            String label=MoodDecider.decide(frame.ambient());long now=System.nanoTime();
            if(!label.equals(lastLabel)||now-loggedAt>10_000_000_000L){lastLabel=label;loggedAt=now;
                System.out.println("BEDROCK_ENV epoch="+frame.epoch+" known="+frame.known+" dimension="+frame.dimension+" water="+frame.water+
                    " biome="+frame.biome+" time="+frame.time+" health="+((frame.known&8)!=0?frame.health:-1)+" shapes="+frame.shapeCount()+" rain="+frame.rain+" sky="+frame.sky+" enclosed="+frame.ambient().inBuilding+" mood="+label+" ambient="+autoMusic+" acoustics="+sceneSound);}
        }
    }
}
