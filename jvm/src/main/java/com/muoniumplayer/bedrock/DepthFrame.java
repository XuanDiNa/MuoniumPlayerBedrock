package com.muoniumplayer.bedrock;
import java.nio.*;
import java.util.Arrays;

/** Owned asynchronous game depth snapshot. */
final class DepthFrame {
    static final int HEADER=360,MAX_PIXELS=1024*1024;
    final long sequence,at;final WorldFrame camera;
    final int viewportWidth,viewportHeight,x,y,width,height,sampleStep,sampleWidth,sampleHeight;final byte[] rgba;
    private DepthFrame(ByteBuffer b,byte[] data,WorldFrame camera){
        this.camera=camera;sequence=b.getLong(56);at=b.getLong(320);
        viewportWidth=b.getInt(328);viewportHeight=b.getInt(332);x=b.getInt(336);y=b.getInt(340);width=b.getInt(344);height=b.getInt(348);
        sampleStep=b.getInt(356);sampleWidth=(width+sampleStep-1)/sampleStep;sampleHeight=(height+sampleStep-1)/sampleStep;
        rgba=Arrays.copyOfRange(data,HEADER,data.length);
    }
    static int pixelBytes(byte[] header){
        if(header==null||header.length<HEADER)return 0;
        var b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int vw=b.getInt(328),vh=b.getInt(332),x=b.getInt(336),y=b.getInt(340),w=b.getInt(344),h=b.getInt(348);
        if(b.getInt(0)!=0x5153504d||b.getInt(4)!=2||b.getLong(56)<=0||b.getInt(352)!=1
            ||vw<1||vh<1||vw>8192||vh>8192||(long)vw*vh>16777216||w<1||h<1||w>vw||h>vh||x<0||y<0||x>vw-w||y>vh-h)return 0;
        int step=sampleStep(w,h);if(b.getInt(356)!=step)return 0;
        return ((w+step-1)/step)*((h+step-1)/step)*4;
    }
    static int sampleStep(int w,int h){
        int step=1;while(step<8&&((long)w+step-1)/step*(((long)h+step-1)/step)>MAX_PIXELS)step*=2;return step;
    }
    static DepthFrame decode(byte[] data,long now){
        int bytes=pixelBytes(data);if(bytes==0||data.length!=HEADER+bytes)return null;
        var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);long at=b.getLong(320);
        if(at<=0||at>now||now-at>150)return null;
        var camera=WorldFrame.decode(Arrays.copyOfRange(data,64,320),now);if(camera==null)return null;
        return new DepthFrame(b,data,camera);
    }
    boolean usable(WorldFrame current,int w,int h,long now){
        if(current==null||now<at||now-at>100||current.epoch()!=camera.epoch()||current.dimension()!=camera.dimension()
            ||w!=viewportWidth||h!=viewportHeight)return false;
        double dx=current.eyeX()-camera.eyeX(),dy=current.eyeY()-camera.eyeY(),dz=current.eyeZ()-camera.eyeZ();
        var rotation=current.viewRotation();var oldRotation=camera.viewRotation();
        for(int col=0;col<3;col++)for(int row=0;row<3;row++)
            if(Math.abs(rotation.get(col,row)-oldRotation.get(col,row))>.0175f)return false;
        // Small camera deltas are reprojected. Larger disocclusion is unknown.
        return dx*dx+dy*dy+dz*dz<=.08*.08
            &&current.lookX()*camera.lookX()+current.lookY()*camera.lookY()+current.lookZ()*camera.lookZ()>=.99985
            &&Math.abs(current.projection().m00()-camera.projection().m00())<.001
            &&Math.abs(current.projection().m11()-camera.projection().m11())<.001;
    }
    boolean reprojectable(WorldFrame current,int w,int h,long now){
        if(current==null||now<at||now-at>100||current.epoch()!=camera.epoch()||current.dimension()!=camera.dimension()
            ||w!=viewportWidth||h!=viewportHeight)return false;
        double dx=current.eyeX()-camera.eyeX(),dy=current.eyeY()-camera.eyeY(),dz=current.eyeZ()-camera.eyeZ();
        if(dx*dx+dy*dy+dz*dz>1)return false;
        var a=current.viewRotation();var b=camera.viewRotation();
        for(int c=0;c<3;c++)for(int r=0;r<3;r++)if(Math.abs(a.get(c,r)-b.get(c,r))>.18f)return false;
        return current.lookX()*camera.lookX()+current.lookY()*camera.lookY()+current.lookZ()*camera.lookZ()>=.98480775
            &&Math.abs(current.projection().m00()-camera.projection().m00())<.001
            &&Math.abs(current.projection().m11()-camera.projection().m11())<.001;
    }
    boolean cameraMoved(WorldFrame current){
        if(Math.abs(current.eyeX()-camera.eyeX())+Math.abs(current.eyeY()-camera.eyeY())+Math.abs(current.eyeZ()-camera.eyeZ())>.00001)return true;
        var a=current.viewRotation();var b=camera.viewRotation();
        for(int c=0;c<3;c++)for(int r=0;r<3;r++)if(Math.abs(a.get(c,r)-b.get(c,r))>.00001f)return true;
        return false;
    }
}
