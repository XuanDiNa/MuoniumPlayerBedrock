package com.muoniumplayer.bedrock;

/** Bounded render-thread counters. Observes clipping; never retains a mask. */
final class LyricOcclusionTrace {
    private long nextLog,frames,missing,foreign,unmasked,masked,releases;
    private int logs,rows,contours,previousContours;private boolean previousWorld;
    private WorldFrame world;private EnvironmentFrame environment;private boolean enabled;
    void begin(WorldFrame w,EnvironmentFrame f,boolean value){world=w;environment=f;enabled=value;rows=contours=0;}
    void row(int count){rows++;contours+=count;}
    void end(long now){
        if(!enabled||world==null){previousWorld=false;previousContours=0;return;}
        frames++;
        if(environment==null)missing++;
        else if(environment.epoch!=world.epoch()||environment.dimension!=world.dimension())foreign++;
        if(rows>0){if(contours>0)masked++;else unmasked++;}
        if(previousWorld&&previousContours>0&&rows>0&&contours==0)releases++;
        previousWorld=true;previousContours=contours;
        if(logs>=120||now<nextLog)return;
        nextLog=now+5_000_000_000L;logs++;
        long uptime=com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64();
        System.out.println("LYRIC_OCCLUSION uptimeMs="+uptime+" epoch="+world.epoch()+" worldSequence="+world.sequence()
            +" geometry="+(environment==null?"missing":environment.epoch!=world.epoch()||environment.dimension!=world.dimension()?"foreign":"ready")
            +" geometryAgeMs="+(environment==null?-1:uptime-environment.at)+" rows="+rows+" contours="+contours
            +" frames="+frames+" maskedFrames="+masked+" unmaskedFrames="+unmasked
            +" missingGeometryFrames="+missing+" foreignGeometryFrames="+foreign+" maskReleases="+releases);
        frames=missing=foreign=unmasked=masked=releases=0;
    }
}
