package com.muoniumplayer.bedrock;

import org.joml.Matrix4f;
import io.github.humbleui.skija.Matrix44;

/** Perspective for an explicitly view-following lyric stage, not a game camera. */
public final class StageProjection {
    public static Matrix4f projection(int width, int height, double fov) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384 || !Double.isFinite(fov) || fov < 10 || fov > 150)
            throw new IllegalArgumentException("Invalid lyric viewport");
        float focal = (float) (height / (2 * Math.tan(Math.toRadians(fov) / 2)));
        return new Matrix4f().zero().m00(-focal).m11(-focal)
                .m20(width / 2f).m21(height / 2f).m22(1.0001f).m23(1f).m32(-.10001f);
    }
    public static Matrix4f billboard(int width, int height, double fov,
                                     double x, double y, double z, double yaw, double pitch, float scale) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || z <= .1
                || Math.abs(x) > 100000 || Math.abs(y) > 100000 || z > 100000
                || !Double.isFinite(yaw) || !Double.isFinite(pitch) || Math.abs(yaw) > 360000 || Math.abs(pitch) > 360000
                || !Float.isFinite(scale) || scale <= 0 || scale > 1000)
            throw new IllegalArgumentException("Invalid lyric plane");
        return projection(width, height, fov).translate((float) x, (float) y, (float) z)
                .rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch)).scale(-scale, -scale, scale);
    }
    public static Matrix44 skija(Matrix4f m) {
        return new Matrix44(m.m00(), m.m10(), m.m20(), m.m30(),
                m.m01(), m.m11(), m.m21(), m.m31(),
                m.m02(), m.m12(), m.m22(), m.m32(),
                m.m03(), m.m13(), m.m23(), m.m33());
    }
}
