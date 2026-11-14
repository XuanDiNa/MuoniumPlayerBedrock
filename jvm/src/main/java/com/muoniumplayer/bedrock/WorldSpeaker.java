package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.acoustics.SpeakerState;

/** Session-only client sound origin. No world writes or cross-world persistence. */
final class WorldSpeaker {
    private WorldFrame world;
    private SpeakerState anchor;
    private boolean enabled;

    void update(WorldFrame next,boolean allow) {
        if(!allow||next==null||world==null||next.epoch()!=world.epoch()||next.dimension()!=world.dimension())anchor=null;
        world=next;enabled=allow;
    }
    SpeakerState anchor(){return anchor;}

    boolean toggle(EnvironmentFrame environment) {
        if(!enabled||world==null||environment==null||environment.epoch!=world.epoch()
                ||environment.dimension!=world.dimension())return false;
        // Raycast current camera against an owned, slightly older voxel cube.
        var hit=EnvironmentGeometry.cast(environment,world.eyeX(),world.eyeY(),world.eyeZ(),
            world.lookX(),world.lookY(),world.lookZ(),6);
        if(hit==null)return false;
        if(anchor!=null&&Math.pow(hit.x()-anchor.x(),2)+Math.pow(hit.y()-anchor.y(),2)
                +Math.pow(hit.z()-anchor.z(),2)<6.25) {
            anchor=null;System.out.println("BEDROCK_SPEAKER removed");return true;
        }
        double x=hit.x()+(hit.axis()==0?hit.sign()*.5:0);
        double y=hit.y()+(hit.axis()==1?hit.sign()*.5:0);
        double z=hit.z()+(hit.axis()==2?hit.sign()*.5:0);
        int cell=environment.cell((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        if(cell==255||cell>=1&&cell<=7||cell==10)return false;
        anchor=SpeakerState.place(x,y,z,Integer.toString(world.dimension()));
        if(anchor==null)return false;
        System.out.println("BEDROCK_SPEAKER placed session-only");return true;
    }
}
