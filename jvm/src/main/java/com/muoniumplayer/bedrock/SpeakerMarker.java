package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.acoustics.SpeakerState;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import org.joml.Vector4f;

/** Client overlay wire cube. No terrain-depth/through-wall visibility claim. */
final class SpeakerMarker {
    static void draw(Canvas canvas,WorldFrame frame,SpeakerState source,int width,int height,boolean audible) {
        if(canvas==null||frame==null||source==null||!Integer.toString(frame.dimension()).equals(source.dimension()))return;
        double dx=source.x()-frame.eyeX(),dy=source.y()-frame.eyeY(),dz=source.z()-frame.eyeZ();
        if(dx*dx+dy*dy+dz*dz>160*160)return;
        var matrix=frame.projection().mul(frame.viewRotation());
        float[][] points=new float[8][];
        for(int i=0;i<8;i++) {
            var p=matrix.transform(new Vector4f((float)(dx+((i&1)==0?-.2:.2)),
                (float)(dy+((i&2)==0?-.2:.2)),(float)(dz+((i&4)==0?-.2:.2)),1));
            if(!Float.isFinite(p.w)||p.w<=.05f)return;
            points[i]=new float[]{(p.x/p.w+1)*width*.5f,(1-p.y/p.w)*height*.5f};
            if(!Float.isFinite(points[i][0])||!Float.isFinite(points[i][1]))return;
        }
        try(var paint=new Paint().setColor(audible?0xFF67E8D2:0xFFF8C471).setStrokeWidth(2).setAntiAlias(true)) {
            for(int i=0;i<8;i++)for(int bit:new int[]{1,2,4})if((i&bit)==0)
                canvas.drawLine(points[i][0],points[i][1],points[i|bit][0],points[i|bit][1],paint);
        }
    }
}
