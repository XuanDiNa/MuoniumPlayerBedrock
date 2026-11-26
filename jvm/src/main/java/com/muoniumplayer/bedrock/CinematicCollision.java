package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.cine.CineDirector.Vec3Holder;

/** Bounded point-camera sweep over the existing immutable collision snapshot. */
final class CinematicCollision {
    static final double COMMAND_RADIUS=15.25,CLEARANCE=.15;
    static Vec3Holder bound(CinematicState state,double x,double y,double z){
        double dx=x-state.x(),dy=y-state.y(),dz=z-state.z(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double scale=distance>COMMAND_RADIUS?COMMAND_RADIUS/distance:1;
        return new Vec3Holder(state.x()+dx*scale,state.y()+dy*scale,state.z()+dz*scale);
    }
    /** Shorten only along the already-clear chord, never project across a corner. */
    static Vec3Holder boundTravel(CinematicState state,Vec3Holder from,Vec3Holder to){
        double x=from.x()-state.x(),y=from.y()-state.y(),z=from.z()-state.z();
        double dx=to.x()-from.x(),dy=to.y()-from.y(),dz=to.z()-from.z();
        double radius2=COMMAND_RADIUS*COMMAND_RADIUS;
        if(Math.pow(to.x()-state.x(),2)+Math.pow(to.y()-state.y(),2)+Math.pow(to.z()-state.z(),2)<=radius2)return to;
        double a=dx*dx+dy*dy+dz*dz,b=x*dx+y*dy+z*dz,c=x*x+y*y+z*z-radius2;
        if(c>1e-6||a<1e-12)return from;
        double t=Math.max(0,Math.min(1,(-b+Math.sqrt(Math.max(0,b*b-a*c)))/a-1e-7));
        return new Vec3Holder(from.x()+dx*t,from.y()+dy*t,from.z()+dz*t);
    }
    /** Exact segment vs expanded native AABBs. Clearance is a conservative cube. */
    static boolean clear(EnvironmentFrame f,Vec3Holder from,Vec3Holder to){
        if(f==null)return false;
        double[] a={from.x(),from.y(),from.z()},b={to.x(),to.y(),to.z()};
        int[] min=new int[3],max=new int[3];
        // Native shapes may extend one cell beyond their owning voxel.
        for(int axis=0;axis<3;axis++){
            if(!Double.isFinite(a[axis])||!Double.isFinite(b[axis]))return false;
            min[axis]=(int)Math.floor(Math.min(a[axis],b[axis])-CLEARANCE)-1;
            max[axis]=(int)Math.floor(Math.max(a[axis],b[axis])+CLEARANCE)+1;
        }
        if((long)(max[0]-min[0]+1)*(max[1]-min[1]+1)*(max[2]-min[2]+1)>EnvironmentFrame.SIDE*EnvironmentFrame.SIDE*EnvironmentFrame.SIDE)return false;
        for(int y=min[1];y<=max[1];y++)for(int z=min[2];z<=max[2];z++)for(int x=min[0];x<=max[0];x++){
            int material=f.cell(x,y,z);if(material==255)return false;
            if(!(material>=1&&material<=7||material==10))continue;
            var shape=f.shape(x,y,z);double lo=0,hi=1;boolean hit=true;
            int[] cell={x,y,z};
            for(int axis=0;axis<3;axis++){
                if(shape[axis]>=shape[axis+3]){hit=false;break;}
                double lower=cell[axis]+shape[axis]/4096.0-CLEARANCE;
                double upper=cell[axis]+shape[axis+3]/4096.0+CLEARANCE,d=b[axis]-a[axis];
                if(Math.abs(d)<1e-12){if(a[axis]<=lower||a[axis]>=upper){hit=false;break;}}
                else{double t1=(lower-a[axis])/d,t2=(upper-a[axis])/d;
                    lo=Math.max(lo,Math.min(t1,t2));hi=Math.min(hi,Math.max(t1,t2));
                    if(lo>=hi){hit=false;break;}}
            }
            if(hit)return false;
        }
        return true;
    }
    static Vec3Holder clip(EnvironmentFrame environment,double x,double y,double z,double ex,double ey,double ez){
        var origin=new Vec3Holder(x,y,z);
        if(environment==null)return origin;
        double[] position={x,y,z},target={ex,ey,ez};
        Vec3Holder firstStop=null;
        // Each contact removes one blocked normal; at most three independent
        // axes can constrain a 3D move. Never search around unknown geometry.
        for(int contact=0;contact<3;contact++){
            double dx=target[0]-position[0],dy=target[1]-position[1],dz=target[2]-position[2];
            double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
            if(length<1e-6)break;
            var hit=EnvironmentGeometry.cast(environment,position[0],position[1],position[2],dx/length,dy/length,dz/length,length);
            if(hit==null){
                if(EnvironmentGeometry.occlusion(environment,position[0],position[1],position[2],target[0],target[1],target[2])==0)
                    position=target.clone();
                break;
            }
            double travel=Math.max(0,hit.distance()-.2)/length;
            position[0]+=dx*travel;position[1]+=dy*travel;position[2]+=dz*travel;
            if(firstStop==null)firstStop=new Vec3Holder(position[0],position[1],position[2]);
            // Preserve tangential travel instead of discarding the entire step.
            target[hit.axis()]=position[hit.axis()];
        }
        // The original director interpolates endpoints with a straight segment.
        // A multi-contact path is usable only if that chord is clear as well.
        if(EnvironmentGeometry.occlusion(environment,x,y,z,position[0],position[1],position[2])!=0)
            return firstStop==null?origin:firstStop;
        return new Vec3Holder(position[0],position[1],position[2]);
    }
}
