package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.lyrics3d.WorldLyricsState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.nio.*;

/** Owned, immutable copy. Never contains native object addresses. */
public final class WorldFrame implements WorldLyricsState.CameraView {
    public static final int BYTES=256;
    private final long epoch,sequence;
    private final int dimension;
    private final int fps,ping;
    private final double x,y,z,lx,ly,lz,fov;
    private final Matrix4f view, projection;
    private WorldFrame(ByteBuffer b,long now) {
        epoch=b.getLong(24);sequence=b.getLong(8); dimension=b.getInt(36);
        boolean metrics=b.getInt(4)==2 && b.getInt(224)==1;
        int rawFps=metrics?b.getInt(228):-1;
        fps=rawFps>=0&&rawFps<=999999?rawFps:-1;
        long at=metrics?b.getLong(240):0;
        int kind=metrics?b.getInt(236):0,rawPing=metrics?b.getInt(232):-1;
        boolean fresh=at>0 && at<=now && now-at<=3000 && b.getLong(248)>0;
        ping=fresh&&(kind==1&&rawPing==0||kind==2&&rawPing>=0&&rawPing<65535)?rawPing:-1;
        x=b.getDouble(40); y=b.getDouble(48); z=b.getDouble(56);
        lx=b.getDouble(192); ly=b.getDouble(200); lz=b.getDouble(208); fov=b.getDouble(216);
        float[] v=new float[16],p=new float[16];
        for(int i=0;i<16;i++){v[i]=b.getFloat(64+4*i);p[i]=b.getFloat(128+4*i);}
        view=new Matrix4f().set(v); projection=new Matrix4f().set(p);
    }
    public static WorldFrame decode(byte[] bytes,long nowMillis) {
        if(bytes==null||bytes.length!=BYTES)return null;
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt(0)!=0x4357504d||(b.getInt(4)!=1&&b.getInt(4)!=2)||b.getLong(8)<=0||b.getLong(24)<=0||b.getInt(32)!=1)return null;
        long at=b.getLong(16);
        if(at>nowMillis||nowMillis-at>250)return null;
        var f=new WorldFrame(b,nowMillis);
        for(double n:new double[]{f.x,f.y,f.z,f.lx,f.ly,f.lz,f.fov})if(!Double.isFinite(n))return null;
        if(Math.abs(f.x)>32_000_000||Math.abs(f.y)>32_000_000||Math.abs(f.z)>32_000_000||f.fov<10||f.fov>170)return null;
        if(!f.view.isFinite()||!f.projection.isFinite()||Math.abs(f.view.determinant()-1)>.05)return null;
        if(Math.abs(f.view.m03())>.001||Math.abs(f.view.m13())>.001||Math.abs(f.view.m23())>.001||Math.abs(f.view.m33()-1)>.001)return null;
        if(Math.abs(f.projection.m33())>.001||Math.abs(Math.abs(f.projection.m23())-1)>.001
                ||Math.abs(f.projection.m00())<.01||Math.abs(f.projection.m11())<.01)return null;
        if(Math.abs(f.lx*f.lx+f.ly*f.ly+f.lz*f.lz-1)>.02)return null;
        var origin=f.view.transformPosition(new Vector3f((float)f.x,(float)f.y,(float)f.z));
        if(origin.length()>4)return null;
        var look=f.view.transformDirection(new Vector3f((float)f.lx,(float)f.ly,(float)f.lz));
        if(look.z>-.98||Math.abs(look.x)>.05||Math.abs(look.y)>.05)return null;
        return f;
    }
    public long epoch(){return epoch;}
    public long sequence(){return sequence;}
    public boolean sameFrame(WorldFrame other){return other!=null&&epoch==other.epoch&&dimension==other.dimension&&sequence==other.sequence;}
    public int dimension(){return dimension;}
    public int fps(){return fps;}
    public int ping(){return ping;}
    public BedrockClient.Scene scene(boolean panelOpen){return new BedrockClient.Scene(true,false,panelOpen,false,false,fps,ping);}
    public double eyeX(){return x;} public double eyeY(){return y;} public double eyeZ(){return z;}
    public double lookX(){return lx;} public double lookY(){return ly;} public double lookZ(){return lz;}
    public double yawDeg(){return Math.toDegrees(Math.atan2(-lx,lz));}
    public double pitchDeg(){return Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,-ly))));}
    public double fovDeg(){return fov;}
    public int windowWidth(){return BedrockClient.getInstance().getWindow().getWidth();}
    public int windowHeight(){return BedrockClient.getInstance().getWindow().getHeight();}
    public Matrix4f viewRotation(){return new Matrix4f(view).m30(0).m31(0).m32(0);}
    public Matrix4f projection(){return new Matrix4f(projection);}
    public static final class Session {
        private WorldFrame frame;
        /** A newly eligible session or dimension needs fresh upstream anchors. */
        public boolean update(WorldFrame next) {
            boolean changed=(frame==null)!=(next==null)||frame!=null&&next!=null
                    &&(frame.epoch!=next.epoch||frame.dimension!=next.dimension);
            frame=next; return changed;
        }
        public WorldFrame frame(){return frame;}
    }
}
