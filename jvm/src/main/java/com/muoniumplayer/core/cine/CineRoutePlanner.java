package com.muoniumplayer.core.cine;

import com.muoniumplayer.core.cine.CineDirector.SceneProbe;
import com.muoniumplayer.core.cine.CineDirector.Vec3Holder;

/** Bounded shot preflight over value snapshots, without advancing the live director. */
public final class CineRoutePlanner {
    private static final double[] ANGLES={0,45,-45,90,-90,180};
    private static final double[] SCALES={1,.75,.5,.25};
    private CineRoutePlanner(){}

    public static final class Route {
        private final Vec3Holder[] points;
        private final String mode;
        private final int attempts;
        private Route(Vec3Holder[] points,String mode,int attempts){
            this.points=points.clone();this.mode=mode;this.attempts=attempts;
        }
        public String mode(){return mode;}
        public int attempts(){return attempts;}
        public Vec3Holder sample(double fraction){
            double index=Math.max(0,Math.min(1,fraction))*(points.length-1);
            int a=(int)index,b=Math.min(a+1,points.length-1);double t=index-a;
            return new Vec3Holder(points[a].x()+(points[b].x()-points[a].x())*t,
                points[a].y()+(points[b].y()-points[a].y())*t,
                points[a].z()+(points[b].z()-points[a].z())*t);
        }
    }

    public static Route plan(SceneProbe probe,Vec3Holder start,Vec3Holder center,Vec3Holder[] desired){
        if(desired==null||desired.length<2||desired.length>257)throw new IllegalArgumentException("Bounded camera samples");
        int attempts=0;
        for(double scale:SCALES)for(double angle:ANGLES){
            attempts++;
            double c=Math.cos(Math.toRadians(angle)),s=Math.sin(Math.toRadians(angle));
            Vec3Holder[] points=new Vec3Holder[desired.length];Vec3Holder previous=start;
            boolean clear=true;
            for(int i=0;i<points.length;i++){
                double x=(desired[i].x()-center.x())*scale,z=(desired[i].z()-center.z())*scale;
                var point=new Vec3Holder(center.x()+x*c-z*s,
                    center.y()+(desired[i].y()-center.y())*scale,center.z()+x*s+z*c);
                if(!finite(point)||!probe.cameraPathClear(previous,point)
                    ||!probe.cameraAnchorVisible(center,point)){clear=false;break;}
                points[i]=point;previous=point;
            }
            if(clear)return new Route(points,scale==1&&angle==0?"original":"replanned",attempts);
        }
        // There is no proven alternative. Never launch an unvalidated shot.
        return new Route(new Vec3Holder[]{start,start},"hold",attempts);
    }
    private static boolean finite(Vec3Holder p){return Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z());}
}
