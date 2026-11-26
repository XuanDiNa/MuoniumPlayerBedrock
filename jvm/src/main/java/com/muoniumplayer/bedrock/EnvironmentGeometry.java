package com.muoniumplayer.bedrock;

import java.util.*;
import com.muoniumplayer.core.ncm.music.acoustics.*;

/** Upstream AcousticRayField over the owned Bedrock voxel snapshot.
 * Native collision AABBs; rays stop at the snapshot boundary or unknown cells. */
final class EnvironmentGeometry {
    private static final int RAYS=96;
    private static final double[][] RHO={{0,0,0},{1.5,1.5,1.45},{.65,.6,.5},{.2,.1,.03},{.8,.75,.75},{.35,.3,.2},{.25,.2,.12},{.18,.15,.08},{.9,.8,.65},{.9,.8,.65},{1.25,1.25,1.3}};
    private record Cell(int x,int y,int z) {}
    record Hit(double x,double y,double z,int axis,int sign,int material,double distance,Cell key) {}
    record BentPath(double x,double y,double z,double length,double angle) {}
    /** Bounded single-bend approximation. Both legs must be completely known air.
     * We never search through unknown chunks or infer clearance from a missing hit. */
    static BentPath diffraction(EnvironmentFrame f,double x,double y,double z,double ex,double ey,double ez) {
        if(f==null)return null;
        double blocked=occlusion(f,x,y,z,ex,ey,ez);
        if(blocked<=0||blocked>=20)return null;
        double distance=Math.sqrt((ex-x)*(ex-x)+(ey-y)*(ey-y)+(ez-z)*(ez-z));
        if(distance<1e-5||distance>28)return null;
        var hit=cast(f,x,y,z,(ex-x)/distance,(ey-y)/distance,(ez-z)/distance,distance);
        if(hit==null)return null;
        double bx=Math.floor(hit.x-hit.sign*(hit.axis==0?.01:0))+.5;
        double by=Math.floor(hit.y-hit.sign*(hit.axis==1?.01:0))+.5;
        double bz=Math.floor(hit.z-hit.sign*(hit.axis==2?.01:0))+.5;
        BentPath best=null;
        for(double radius:new double[]{1.1,2.1,3.1})for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++) {
            if(dx==0&&dy==0&&dz==0)continue;
            double px=bx+dx*radius,py=by+dy*radius,pz=bz+dz*radius;
            int material=f.cell((int)Math.floor(px),(int)Math.floor(py),(int)Math.floor(pz));
            if(material==255||(material>=1&&material<=7||material==10))continue;
            if(occlusion(f,x,y,z,px,py,pz)!=0||occlusion(f,px,py,pz,ex,ey,ez)!=0)continue;
            double a=Math.sqrt((px-x)*(px-x)+(py-y)*(py-y)+(pz-z)*(pz-z));
            double b=Math.sqrt((ex-px)*(ex-px)+(ey-py)*(ey-py)+(ez-pz)*(ez-pz));
            if(a<1e-6||b<1e-6)continue;
            double length=a+b,angle=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,
                ((px-x)*(ex-px)+(py-y)*(ey-py)+(pz-z)*(ez-pz))/(a*b)))));
            if(best==null||length<best.length)best=new BentPath(px,py,pz,length,angle);
        }
        return best;
    }
    static Hit cast(EnvironmentFrame f,double x,double y,double z,double dx,double dy,double dz,double limit) {
        return cast(f,x,y,z,dx,dy,dz,limit,null,false);
    }
    private static Hit cast(EnvironmentFrame f,double x,double y,double z,double dx,double dy,double dz,double limit,Cell ignore,boolean unknownBlocks) {
        int px=(int)Math.floor(x),py=(int)Math.floor(y),pz=(int)Math.floor(z);
        double[] direction={dx,dy,dz},pos={x,y,z},next=new double[3],delta=new double[3];int[] voxel={px,py,pz},step=new int[3];
        for(int a=0;a<3;a++){step[a]=direction[a]>0?1:-1;delta[a]=Math.abs(direction[a])<1e-12?Double.POSITIVE_INFINITY:Math.abs(1/direction[a]);next[a]=Double.isInfinite(delta[a])?delta[a]:((step[a]>0?voxel[a]+1:voxel[a])-pos[a])/direction[a];}
        double entered=0;
        for(int i=0;i<192;i++) {
            int material=f.cell(voxel[0],voxel[1],voxel[2]);
            if(material==255)return unknownBlocks?new Hit(x+dx*entered,y+dy*entered,z+dz*entered,0,0,255,entered,new Cell(voxel[0],voxel[1],voxel[2])):null;
            if((material>=1&&material<=7||material==10) && (ignore==null||ignore.x!=voxel[0]||ignore.y!=voxel[1]||ignore.z!=voxel[2])) {
                Hit hit=intersect(f,voxel[0],voxel[1],voxel[2],pos,direction,Math.max(0,entered),limit,material);
                if(hit!=null)return hit;
            }
            int a=next[0]<=next[1]&&next[0]<=next[2]?0:next[1]<=next[2]?1:2;
            double d=next[a];if(d>limit)return null;voxel[a]+=step[a];next[a]+=delta[a];
            entered=d;
        }return null;
    }
    private static Hit intersect(EnvironmentFrame f,int x,int y,int z,double[] pos,double[] dir,double entered,double limit,int material) {
        short[] box=f.shape(x,y,z);double lo=-Double.MAX_VALUE,hi=limit;int axis=0,sign=1;
        for(int a=0;a<3;a++) {
            double base=a==0?x:a==1?y:z,min=base+box[a]/4096.0,max=base+box[a+3]/4096.0;
            if(min>=max)return null;
            if(Math.abs(dir[a])<1e-12){if(pos[a]<min||pos[a]>max)return null;continue;}
            double first=(min-pos[a])/dir[a],last=(max-pos[a])/dir[a];
            double near=Math.min(first,last),far=Math.max(first,last);
            if(near>lo){lo=near;axis=a;sign=dir[a]>0?-1:1;}hi=Math.min(hi,far);
            if(lo>hi)return null;
        }
        if(hi<=Math.max(entered,1e-6)||lo>limit)return null;
        // Do not reflect off the volume containing the source; wait until exit.
        if(lo<1e-6)return null;
        return new Hit(pos[0]+dir[0]*lo,pos[1]+dir[1]*lo,pos[2]+dir[2]*lo,axis,sign,material,lo,new Cell(x,y,z));
    }
    static ListenerSample sample(EnvironmentFrame f) {
        return sample(f,null);
    }
    /** 33-cube geometry only: never interpret an unknown direct segment as air. */
    static double occlusion(EnvironmentFrame f,double x,double y,double z,double ex,double ey,double ez) {
        double dx=ex-x,dy=ey-y,dz=ez-z,distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(distance<1e-6)return f.cell((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z))==255?20:0;
        dx/=distance;dy/=distance;dz/=distance;
        int[] voxel={(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)};
        double[] dir={dx,dy,dz},pos={x,y,z},next=new double[3],delta=new double[3];int[] step=new int[3];
        for(int a=0;a<3;a++){step[a]=dir[a]>0?1:-1;delta[a]=Math.abs(dir[a])<1e-12?Double.POSITIVE_INFINITY:Math.abs(1/dir[a]);next[a]=Double.isInfinite(delta[a])?delta[a]:((step[a]>0?voxel[a]+1:voxel[a])-pos[a])/dir[a];}
        double occluded=0,entered=0;
        for(int i=0;i<192;i++) {
            int material=f.cell(voxel[0],voxel[1],voxel[2]);if(material==255)return 20;
            if((material>=1&&material<=7||material==10)&&segmentIntersects(f,voxel,pos,dir,entered,distance))occluded+=material==4||material==6?.15:1;
            int a=next[0]<=next[1]&&next[0]<=next[2]?0:next[1]<=next[2]?1:2;
            if(next[a]>=distance-1e-5)return occluded;
            entered=next[a];voxel[a]+=step[a];next[a]+=delta[a];
        }
        return 20;
    }
    private static boolean segmentIntersects(EnvironmentFrame f,int[] voxel,double[] pos,double[] dir,double entered,double limit) {
        short[] box=f.shape(voxel[0],voxel[1],voxel[2]);double lo=Math.max(0,entered),hi=limit-1e-5;
        for(int a=0;a<3;a++) {
            double min=voxel[a]+box[a]/4096.0,max=voxel[a]+box[a+3]/4096.0;
            if(min>=max)return false;
            if(Math.abs(dir[a])<1e-12){if(pos[a]<min||pos[a]>max)return false;}
            else {double first=(min-pos[a])/dir[a],last=(max-pos[a])/dir[a];lo=Math.max(lo,Math.min(first,last));hi=Math.min(hi,Math.max(first,last));if(lo>=hi)return false;}
        }
        return lo<hi;
    }
    static ListenerSample sample(EnvironmentFrame f,SpeakerState source) {
        if(f==null) {
            if(source==null)return null;
            // Preserve source mode while a scan is pending; clearing the whole
            // plan here would unexpectedly resume full-volume headset sound.
            return new ListenerSample() {
                public boolean submerged(){return false;}public boolean openSky(){return false;}
                public float enclosure(){return 0;}public float precipitation(){return 0;}
                public float firstHitMeanMeters(){return NO_HIT;}public float bounceSegmentMeanMeters(){return NO_HIT;}
                public float meanReflectivity(){return 0;}public float reachableFraction(){return 0;}
                public int tapCount(){return 0;}public float[] tapDelaysSec(){return new float[0];}
                public float[] tapGains(){return new float[0];}public float[] tapPans(){return new float[0];}
                public EnergyField bandHistogram(){return null;}
                public boolean hasDirectSource(){return true;}public float directOcclusion(){return 20;}
                public double speakerX(){return source.x();}public double speakerY(){return source.y();}public double speakerZ(){return source.z();}
            };
        }
        double rightX=-f.lz,rightZ=f.lx,rightLen=Math.hypot(rightX,rightZ);if(rightLen>1e-6){rightX/=rightLen;rightZ/=rightLen;}
        final double sourceX=source==null?f.x:source.x(),sourceY=source==null?f.y:source.y(),sourceZ=source==null?f.z:source.z();
        final double directDistance=source==null?0:source.distanceTo(f.x,f.y,f.z);
        final double directOcclusion=source==null?0:!Integer.toString(f.dimension).equals(source.dimension())?20:occlusion(f,sourceX,sourceY,sourceZ,f.x,f.y,f.z);
        final float directPan=(float)(Math.hypot(sourceX-f.x,sourceZ-f.z)>1e-6
            ?Math.max(-1,Math.min(1,((sourceX-f.x)*rightX+(sourceZ-f.z)*rightZ)/Math.hypot(sourceX-f.x,sourceZ-f.z))):0);
        final var bent=source==null||!Integer.toString(f.dimension).equals(source.dimension())?null:
            diffraction(f,sourceX,sourceY,sourceZ,f.x,f.y,f.z);
        final float bentPan=bent==null?0:(float)Math.max(-1,Math.min(1,
            ((bent.x-f.x)*rightX+(bent.z-f.z)*rightZ)/Math.max(1e-6,Math.hypot(bent.x-f.x,bent.z-f.z))));
        // Primary rays stop at the 33-cube boundary. Ear-return visibility must
        // treat internal unknown cells as blocked, never as an unobstructed path.
        AcousticRayField.Tracer tracer=new AcousticRayField.Tracer() {
            public Object blockKeyAt(double x,double y,double z) {return new Cell((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
            public AcousticRayField.Hit cast(double x,double y,double z,double tx,double ty,double tz,Object ignoreKey) {
                double dx=tx-x,dy=ty-y,dz=tz-z,len=Math.sqrt(dx*dx+dy*dy+dz*dz);
                if(len<1e-6)return null;
                Hit h=EnvironmentGeometry.cast(f,x,y,z,dx/len,dy/len,dz/len,len,(Cell)ignoreKey,ignoreKey==null);
                return h==null?null:new AcousticRayField.Hit(h.x,h.y,h.z,h.axis==0?h.sign:0,h.axis==1?h.sign:0,h.axis==2?h.sign:0,h.key);
            }
            public void reflectivity(Object key,float[] bands) {
                Cell c=(Cell)key;int material=f.cell(c.x,c.y,c.z);
                double[] rho=RHO[material];for(int i=0;i<3;i++)bands[i]=(float)rho[i];
            }
        };
        final var stats=directOcclusion>=20?AcousticRayField.Stats.NONE:
            AcousticRayField.cast(tracer,sourceX,sourceY,sourceZ,f.x,f.y,f.z,RAYS);
        final float[] ps=new float[stats.tapCount];
        // Upstream stores wall-to-ear vectors. Bedrock's camera right is (-lz,lx).
        for(int i=0;i<ps.length;i++)ps[i]=(float)Math.max(-1,Math.min(1,
            -(stats.tapArrX[i]*rightX+stats.tapArrZ[i]*rightZ)/Math.max(1e-6,stats.tapHoriz[i])));
        final boolean open=cast(f,f.x,f.y,f.z,0,1,0,16)==null;
        return new ListenerSample() {
            public boolean submerged(){return AcousticsRuntime.wantsSubmersion()&&(f.water&2)!=0;}
            public boolean openSky(){return open;}
            public float enclosure(){return AcousticsRuntime.wantsEnclosure()?stats.enclosure:0;}
            public float lateEnclosure(){return AcousticsRuntime.wantsEnclosure()?stats.trapped:0;}
            public float escapeFraction(){return stats.escape;}
            public float precipitation(){return AcousticsRuntime.wantsRainfall()&&open?(f.thunder>.2?1:f.rain>.2?.5f:0):0;}
            public float firstHitMeanMeters(){return stats.firstHitMean;}public float bounceSegmentMeanMeters(){return stats.segMean;}
            public float meanReflectivity(){return stats.reflectivity;}public float[] bandReflectivities(){return stats.bandRho;}
            public float reachableFraction(){return stats.reachable;}public int tapCount(){return stats.tapCount;}
            public float[] tapDelaysSec(){return stats.tapDelays;}public float[] tapGains(){return stats.tapGains;}public float[] tapPans(){return ps;}
            public float[] tapHighRatios(){return stats.tapHighRatio;}
            public EnergyField bandHistogram(){return stats.histogram;}
            public boolean hasDirectSource(){return source!=null;}
            public float directDistanceMeters(){return (float)directDistance;}
            public float directOcclusion(){return (float)directOcclusion;}
            public float directPan(){return directPan;}
            public double speakerX(){return sourceX;}public double speakerY(){return sourceY;}public double speakerZ(){return sourceZ;}
            public boolean hasDiffraction(){return bent!=null;}
            public float diffractionPathMeters(){return bent==null?0:(float)bent.length;}
            public float diffractionExcessMeters(){return bent==null?0:(float)Math.max(0,bent.length-directDistance);}
            public float diffractionBendDegrees(){return bent==null?0:(float)bent.angle;}
            public double diffractionEdgeX(){return bent==null?0:bent.x;}public double diffractionEdgeY(){return bent==null?0:bent.y;}
            public double diffractionEdgeZ(){return bent==null?0:bent.z;}public float diffractionPan(){return bentPan;}
        };
    }
}
