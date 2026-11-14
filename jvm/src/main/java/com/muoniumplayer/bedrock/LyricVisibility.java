package com.muoniumplayer.bedrock;

/** Coarse full-voxel visibility for camera-relative billboards. Missing geometry
 * leaves normal lyrics visible; only a known intervening block may hide a row. */
final class LyricVisibility {
    static boolean visible(EnvironmentFrame f,WorldFrame w,double x,double y,double z) {
        if(w==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return false;
        if(f==null||f.epoch!=w.epoch()||f.dimension!=w.dimension())return true;
        // WorldLyricsRenderer.Host receives row position minus camera position.
        // The voxel snapshot and its DDA operate in absolute world coordinates.
        double distance=Math.sqrt(x*x+y*y+z*z);
        if(!Double.isFinite(distance))return false;
        if(distance<1e-6)return true;
        return EnvironmentGeometry.cast(f,w.eyeX(),w.eyeY(),w.eyeZ(),x/distance,y/distance,z/distance,distance)==null;
    }
}
