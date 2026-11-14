package com.muoniumplayer.bedrock;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Real native matrices; coordinates are camera-relative per upstream Host. */
public final class WorldProjection {
    public static Matrix4f billboard(WorldFrame frame,int width,int height,
            double x,double y,double z,double yaw,double pitch,float scale) {
        if(frame==null||width<1||height<1||width>16384||height>16384
                ||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||Math.abs(x)>10000||Math.abs(y)>10000||Math.abs(z)>10000
                ||!Double.isFinite(yaw)||!Double.isFinite(pitch)||!Float.isFinite(scale)||scale<=0||scale>1000)return null;
        var clip=frame.projection().mul(frame.viewRotation()).translate((float)x,(float)y,(float)z)
                .rotateY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch)).scale(-scale,-scale,scale);
        var center=clip.transform(new Vector4f(0,0,0,1));
        if(!Float.isFinite(center.w)||center.w<=.05f)return null;
        // Native NDC -> top-left physical/GUI viewport. Keep homogeneous W.
        return new Matrix4f().identity().m00(width*.5f).m11(-height*.5f)
                .m30(width*.5f).m31(height*.5f).mul(clip);
    }
}
