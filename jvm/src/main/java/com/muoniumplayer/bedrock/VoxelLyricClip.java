package com.muoniumplayer.bedrock;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathFillMode;
import org.joml.Matrix3d;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Projects known solid volumes onto each lyric plane. The one difference clip
 * applies to ink, glow and trails alike, including cuts through a single glyph.
 * No game depth texture, per-pixel CPU ray cast or native pointer is involved.
 * Render-thread owned; scratch storage is reused and snapshots are bounded. */
final class VoxelLyricClip {
    record Bounds(double left,double top,double right,double bottom) {
        boolean valid(){return Double.isFinite(left)&&Double.isFinite(top)&&Double.isFinite(right)&&Double.isFinite(bottom)
            &&left<right&&top<bottom;}
    }
    private record Box(double x,double y,double z,double xx,double yy,double zz) {}
    private EnvironmentFrame cached;
    private final List<Box> boxes=new ArrayList<>();
    private final double[] vx=new double[8],vy=new double[8],vz=new double[8],depth=new double[8];
    private final double[] px=new double[40],py=new double[40],hx=new double[80],hy=new double[80];
    private final double[] ax=new double[80],ay=new double[80],bx=new double[80],by=new double[80];
    private final double[] sideX=new double[4],sideY=new double[4],sideZ=new double[4];
    private int points;
    private double ux,uy,uz,tx,ty,tz,nx,ny,nz,d,ox,oy,oz,invScale;

    void clear(){cached=null;boxes.clear();}
    int mergedBoxCount(){return boxes.size();}
    int apply(Canvas canvas,EnvironmentFrame f,WorldFrame w,
               double x,double y,double z,double yaw,double pitch,float scale) {
        var r=canvas.getLocalClipBounds();
        if(r==null)return 0;
        try(var path=new PathBuilder().setFillMode(PathFillMode.WINDING)) {
            int count=shadows(f,w,x,y,z,yaw,pitch,scale,
                new Bounds(r.getLeft(),r.getTop(),r.getRight(),r.getBottom()),p->path.addPolygon(p,true));
            if(count>0)try(var mask=path.detach()){canvas.clipPath(mask,ClipMode.DIFFERENCE,true);}
            return count;
        }
    }
    /** Emits consistently wound, viewport-clipped convex shadows. A solid box
     * is clipped between the camera and plane before perspective division; a
     * cube behind, crossing or surrounding the eye cannot create an inverted
     * shadow. All arithmetic is camera relative and double precision. */
    int shadows(EnvironmentFrame f,WorldFrame w,double x,double y,double z,double yaw,double pitch,float scale,
                Bounds bounds,Consumer<float[]> emit) {
        if(f==null||w==null||f.epoch!=w.epoch()||f.dimension!=w.dimension()) {clear();return 0;}
        if(!bounds.valid()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
            ||!Double.isFinite(yaw)||!Double.isFinite(pitch)||!Float.isFinite(scale)||scale<=0)return 0;
        prepare(f);
        var rotation=new Matrix3d().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch));
        ux=rotation.m00();uy=rotation.m01();uz=rotation.m02();
        tx=rotation.m10();ty=rotation.m11();tz=rotation.m12();
        nx=rotation.m20();ny=rotation.m21();nz=rotation.m22();
        d=nx*x+ny*y+nz*z;
        if(d<0){nx=-nx;ny=-ny;nz=-nz;d=-d;}
        if(d<1e-5)return 0;
        ox=x;oy=y;oz=z;invScale=-1.0/scale;
        double ou=ox*ux+oy*uy+oz*uz,ot=ox*tx+oy*ty+oz*tz;
        side(0,ou-bounds.left*scale,-d,ux,uy,uz);
        side(1,bounds.right*scale-ou,d,ux,uy,uz);
        side(2,ot-bounds.top*scale,-d,tx,ty,tz);
        side(3,bounds.bottom*scale-ot,d,tx,ty,tz);
        int emitted=0;
        for(var box:boxes) {
            // Conservative AABB tests avoid projecting volumes behind this row
            // or outside the canvas cone. No visible portion can be discarded.
            double cx=(box.x+box.xx)*.5-w.eyeX(),cy=(box.y+box.yy)*.5-w.eyeY(),cz=(box.z+box.zz)*.5-w.eyeZ();
            double ex=(box.xx-box.x)*.5,ey=(box.yy-box.y)*.5,ez=(box.zz-box.z)*.5;
            double center=nx*cx+ny*cy+nz*cz,extent=Math.abs(nx)*ex+Math.abs(ny)*ey+Math.abs(nz)*ez;
            if(center-extent>=d||center+extent<=0)continue;
            boolean outside=false;
            for(int i=0;i<4;i++)if(sideX[i]*cx+sideY[i]*cy+sideZ[i]*cz
                +Math.abs(sideX[i])*ex+Math.abs(sideY[i])*ey+Math.abs(sideZ[i])*ez<0){outside=true;break;}
            if(outside)continue;
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(int i=0;i<8;i++) {
                vx[i]=((i&1)==0?box.x:box.xx)-w.eyeX();
                vy[i]=((i&2)==0?box.y:box.yy)-w.eyeY();
                vz[i]=((i&4)==0?box.z:box.zz)-w.eyeZ();
                depth[i]=nx*vx[i]+ny*vy[i]+nz*vz[i];
                min=Math.min(min,depth[i]);max=Math.max(max,depth[i]);
            }
            // Exclude contact at the lyric plane to avoid self-occlusion.
            double near=Math.min(1e-5,d*.01),far=d-Math.max(1e-7,d*1e-7);
            if(min>=far||max<=near)continue;
            points=0;
            for(int i=0;i<8;i++)if(depth[i]>=near&&depth[i]<=far)project(vx[i],vy[i],vz[i],depth[i]);
            for(int i=0;i<8;i++)for(int bit=1;bit<=4;bit<<=1)if((i&bit)==0) {
                int j=i|bit;
                intersect(i,j,near);intersect(i,j,far);
            }
            if(points<3)continue;
            double l=px[0],r=l,t=py[0],b=t;
            for(int i=1;i<points;i++){l=Math.min(l,px[i]);r=Math.max(r,px[i]);t=Math.min(t,py[i]);b=Math.max(b,py[i]);}
            if(r<=bounds.left||l>=bounds.right||b<=bounds.top||t>=bounds.bottom)continue;
            int hull=hull();if(hull<3)continue;
            int n=clip(hull,bounds);if(n<3)continue;
            float[] polygon=new float[n*2];
            for(int i=0;i<n;i++){polygon[2*i]=(float)ax[i];polygon[2*i+1]=(float)ay[i];}
            emit.accept(polygon);emitted++;
        }
        return emitted;
    }
    private void side(int i,double normal,double tangent,double x,double y,double z) {
        sideX[i]=normal*nx+tangent*x;sideY[i]=normal*ny+tangent*y;sideZ[i]=normal*nz+tangent*z;
    }
    private void intersect(int a,int b,double plane) {
        if((depth[a]<plane&&depth[b]>plane)||(depth[b]<plane&&depth[a]>plane)) {
            double q=(plane-depth[a])/(depth[b]-depth[a]);
            project(vx[a]+q*(vx[b]-vx[a]),vy[a]+q*(vy[b]-vy[a]),vz[a]+q*(vz[b]-vz[a]),plane);
        }
    }
    private void project(double x,double y,double z,double distance) {
        double q=d/distance,rx=x*q-ox,ry=y*q-oy,rz=z*q-oz;
        px[points]=(rx*ux+ry*uy+rz*uz)*invScale;
        py[points]=(rx*tx+ry*ty+rz*tz)*invScale;points++;
    }
    private static double cross(double ax,double ay,double bx,double by,double cx,double cy) {
        return (bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
    }
    private int hull() {
        // At most 8 vertices + 24 edge/slab intersections. No per-vertex objects.
        for(int i=1;i<points;i++) {
            double x=px[i],y=py[i];int j=i;
            while(j>0&&(px[j-1]>x||px[j-1]==x&&py[j-1]>y)){px[j]=px[j-1];py[j]=py[j-1];j--;}
            px[j]=x;py[j]=y;
        }
        int n=0;
        for(int i=0;i<points;i++) {
            while(n>=2&&cross(hx[n-2],hy[n-2],hx[n-1],hy[n-1],px[i],py[i])<=0)n--;
            hx[n]=px[i];hy[n++]=py[i];
        }
        int lower=n+1;
        for(int i=points-2;i>=0;i--) {
            while(n>=lower&&cross(hx[n-2],hy[n-2],hx[n-1],hy[n-1],px[i],py[i])<=0)n--;
            hx[n]=px[i];hy[n++]=py[i];
        }
        return n-1;
    }
    private int clip(int n,Bounds r) {
        System.arraycopy(hx,0,ax,0,n);System.arraycopy(hy,0,ay,0,n);
        for(int edge=0;edge<4&&n>=3;edge++) {
            int count=0;double limit=switch(edge){case 0->r.left;case 1->r.right;case 2->r.top;default->r.bottom;};
            for(int i=0,j=n-1;i<n;j=i++) {
                double a=edge<2?ax[j]:ay[j],b=edge<2?ax[i]:ay[i];
                boolean inA=edge==0||edge==2?a>=limit:a<=limit,inB=edge==0||edge==2?b>=limit:b<=limit;
                if(inA!=inB){double q=(limit-a)/(b-a);bx[count]=ax[j]+q*(ax[i]-ax[j]);by[count++]=ay[j]+q*(ay[i]-ay[j]);}
                if(inB){bx[count]=ax[i];by[count++]=ay[i];}
            }
            n=count;System.arraycopy(bx,0,ax,0,n);System.arraycopy(by,0,ay,0,n);
        }
        return n;
    }
    private void prepare(EnvironmentFrame f) {
        if(cached==f)return;
        if(cached!=null&&cached.sameGeometry(f)){cached=f;return;}
        cached=f;boxes.clear();
        int s=EnvironmentFrame.SIDE;boolean[] used=new boolean[s*s*s];
        // Greedily merge adjacent opaque voxels into exact cuboids. A wall or
        // solid terrain volume takes one contour, rather than one per block.
        for(int y=0;y<s;y++)for(int z=0;z<s;z++)for(int x=0;x<s;x++) {
            int px=f.bx+x,py=f.by+y,pz=f.bz+z,kind=f.cell(px,py,pz);
            if((kind>=1&&kind<=7||kind==10)&&!full(f,px,py,pz)) {
                short[] shape=f.shape(px,py,pz);
                if(shape[0]<shape[3]&&shape[1]<shape[4]&&shape[2]<shape[5])
                    boxes.add(new Box(px+shape[0]/4096.0,py+shape[1]/4096.0,pz+shape[2]/4096.0,
                        px+shape[3]/4096.0,py+shape[4]/4096.0,pz+shape[5]/4096.0));
                continue;
            }
            if(!available(f,used,x,y,z))continue;
            int xx=x+1,zz=z+1,yy=y+1;
            while(xx<s&&available(f,used,xx,y,z))xx++;
            while(zz<s&&rectangle(f,used,x,xx,zz,zz+1,y))zz++;
            while(yy<s&&rectangle(f,used,x,xx,z,zz,yy))yy++;
            for(int a=y;a<yy;a++)for(int b=z;b<zz;b++)for(int c=x;c<xx;c++)used[(a*s+b)*s+c]=true;
            boxes.add(new Box(f.bx+x,f.by+y,f.bz+z,f.bx+xx,f.by+yy,f.bz+zz));
        }
    }
    private static boolean available(EnvironmentFrame f,boolean[] used,int x,int y,int z) {
        if(used[(y*EnvironmentFrame.SIDE+z)*EnvironmentFrame.SIDE+x])return false;
        int c=f.cell(f.bx+x,f.by+y,f.bz+z);return (c>=1&&c<=7||c==10)&&full(f,f.bx+x,f.by+y,f.bz+z);
    }
    private static boolean full(EnvironmentFrame f,int x,int y,int z) {
        short[] b=f.shape(x,y,z);return b[0]==0&&b[1]==0&&b[2]==0&&b[3]==4096&&b[4]==4096&&b[5]==4096;
    }
    private static boolean rectangle(EnvironmentFrame f,boolean[] used,int x,int xx,int z,int zz,int y) {
        for(int b=z;b<zz;b++)for(int a=x;a<xx;a++)if(!available(f,used,a,y,b))return false;
        return true;
    }
}
