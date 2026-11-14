package com.muoniumplayer.bedrock;

import java.util.*;
import com.muoniumplayer.core.ncm.music.acoustics.*;

/** Fibonacci reflection logic adapted from pinned ListenerProbe over an owned voxel snapshot.
 * Native collision AABBs; rays stop at the snapshot boundary or unknown cells. */
final class EnvironmentGeometry {
    private static final int RAYS=96,BOUNCES=6;
    private static final double[][] RHO={{0,0,0},{1.5,1.5,1.45},{.65,.6,.5},{.2,.1,.03},{.8,.75,.75},{.35,.3,.2},{.25,.2,.12},{.18,.15,.08},{.9,.8,.65},{.9,.8,.65},{1.25,1.25,1.3}};
    record Hit(double x,double y,double z,int axis,int sign,int material,double distance) {}
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
        int px=(int)Math.floor(x),py=(int)Math.floor(y),pz=(int)Math.floor(z);
        double[] direction={dx,dy,dz},pos={x,y,z},next=new double[3],delta=new double[3];int[] voxel={px,py,pz},step=new int[3];
        for(int a=0;a<3;a++){step[a]=direction[a]>0?1:-1;delta[a]=Math.abs(direction[a])<1e-12?Double.POSITIVE_INFINITY:Math.abs(1/direction[a]);next[a]=Double.isInfinite(delta[a])?delta[a]:((step[a]>0?voxel[a]+1:voxel[a])-pos[a])/direction[a];}
        double entered=0;
        for(int i=0;i<192;i++) {
            int material=f.cell(voxel[0],voxel[1],voxel[2]);if(material==255)return null;
            if((material>=1&&material<=7||material==10)) {
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
        return new Hit(pos[0]+dir[0]*lo,pos[1]+dir[1]*lo,pos[2]+dir[2]*lo,axis,sign,material,lo);
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
        var delays=new ArrayList<Float>();var gains=new ArrayList<Float>();var pans=new ArrayList<Float>();var histogram=new EnergyField();
        int first=0,segments=0,hits=0,reachable=0;double firstSum=0,segmentSum=0,rhoSum=0;double[] bands=new double[3];
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
        for(int i=0;i<RAYS&&directOcclusion<20;i++) {
            double yy=2*(i+.5)/RAYS-1,rad=Math.sqrt(1-yy*yy),phi=2.399963229728653*i;
            double dx=rad*Math.cos(phi),dy=yy,dz=rad*Math.sin(phi),x=sourceX,y=sourceY,z=sourceZ,path=0,retention=1;
            for(int bounce=0;bounce<=BOUNCES;bounce++) {
                Hit hit=cast(f,x,y,z,dx,dy,dz,40);if(hit==null)break;
                if(bounce==0){first++;firstSum+=hit.distance;}else{segments++;segmentSum+=hit.distance;}
                hits++;path+=hit.distance;double[] rho=RHO[hit.material];double mean=(rho[0]+rho[1]+rho[2])/3;rhoSum+=mean;for(int b=0;b<3;b++)bands[b]+=rho[b];
                retention*=Math.max(.15,Math.min(.92,Math.sqrt(mean)));
                double sx=hit.x+(hit.axis==0?hit.sign*.01:0),sy=hit.y+(hit.axis==1?hit.sign*.01:0),sz=hit.z+(hit.axis==2?hit.sign*.01:0);
                double ex=f.x-sx,ey=f.y-sy,ez=f.z-sz,dist=Math.sqrt(ex*ex+ey*ey+ez*ez);
                if(dist>1e-5&&occlusion(f,sx,sy,sz,f.x,f.y,f.z)==0) {
                    reachable++;double total=path+dist;delays.add((float)(Math.max(0,total-directDistance)/343));gains.add((float)(retention*2/Math.max(.1,total)));
                    double panSign=source==null?1:-1; // Retain accepted listener-mode behavior.
                    pans.add((float)Math.max(-1,Math.min(1,Math.hypot(ex,ez)>1e-6?panSign*(ex*rightX+ez*rightZ)/Math.hypot(ex,ez):0)));
                    for(int b=0;b<3;b++)histogram.accumulate(b,total/343,retention*retention*4/(total*total)*rho[b]*Math.pow(new double[]{.999,.997,.992}[b],total));
                }
                if(hit.axis==0)dx=-dx;else if(hit.axis==1)dy=-dy;else dz=-dz;x=sx;y=sy;z=sz;
            }
        }
        final float enclosure=first/(float)RAYS,firstMean=first==0?-1:(float)(firstSum/first),segmentMean=segments==0?firstMean:(float)(segmentSum/segments),reflect=hits==0?0:(float)(rhoSum/hits),reach=hits==0?0:reachable/(float)hits;
        final float[] bandRho=new float[3];for(int b=0;b<3;b++)bandRho[b]=hits==0?0:(float)(bands[b]/hits);
        final float[] ds=array(delays),gs=array(gains),ps=array(pans);
        final boolean open=cast(f,f.x,f.y,f.z,0,1,0,16)==null;
        return new ListenerSample() {
            public boolean submerged(){return AcousticsRuntime.wantsSubmersion()&&(f.water&2)!=0;}
            public boolean openSky(){return open;}
            public float enclosure(){return AcousticsRuntime.wantsEnclosure()?enclosure:0;}
            public float precipitation(){return AcousticsRuntime.wantsRainfall()&&open?(f.thunder>.2?1:f.rain>.2?.5f:0):0;}
            public float firstHitMeanMeters(){return firstMean;}public float bounceSegmentMeanMeters(){return segmentMean;}
            public float meanReflectivity(){return reflect;}public float[] bandReflectivities(){return bandRho;}
            public float reachableFraction(){return reach;}public int tapCount(){return ds.length;}
            public float[] tapDelaysSec(){return ds;}public float[] tapGains(){return gs;}public float[] tapPans(){return ps;}
            public EnergyField bandHistogram(){return histogram;}
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
    private static float[] array(List<Float> list){float[] result=new float[list.size()];for(int i=0;i<result.length;i++)result[i]=list.get(i);return result;}
}
