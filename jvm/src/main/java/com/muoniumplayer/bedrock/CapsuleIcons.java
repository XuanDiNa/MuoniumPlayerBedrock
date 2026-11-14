package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.render.skija.SkijaGfx;
import io.github.humbleui.skija.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Original bundled icon outlines, centered by their ink rather than text metrics.
 * Render-thread only; shared by the mini page and passive capsule card. */
public final class CapsuleIcons {
    private record Icon(Path path,io.github.humbleui.types.Rect bounds) implements AutoCloseable {
        public void close(){path.close();}
    }
    private static final Map<String,Icon> icons=new HashMap<>();
    private CapsuleIcons(){}
    public static void transport(String glyph,double cx,double cy,double size,int color) {
        draw(glyph.equals("\ue144")?"player-action-icons":"icomoon",glyph,cx,cy,size*.65,color);
    }
    public static void mode(double x,double y,double size,double box,float alpha) {
        var mode=CloudMusic.playMode;if(mode==null)return;
        String glyph=switch(mode){case Random->"F";case LoopInList->"I";case LoopSingle->"L";case Sequential->"G";};
        int color=(Math.max(0,Math.min(255,Math.round(alpha*255)))<<24)|0xFFFFFF;
        draw("icomoon",glyph,x+box*.5,y+box*.5,Math.min(size,box)*.58,color);
    }
    private static void draw(String font,String glyph,double cx,double cy,double size,int color) {
        var canvas=SkijaGfx.canvas();if(canvas==null||!(size>0))return;
        Icon icon=icons.computeIfAbsent(font+":"+glyph,key->load(font,glyph));
        var b=icon.bounds();double scale=size/Math.max(b.getWidth(),b.getHeight());
        int save=canvas.save();
        try(var paint=new Paint().setAntiAlias(true).setColor(color)) {
            canvas.translate((float)cx,(float)cy);canvas.scale((float)scale,(float)scale);
            canvas.translate(-(b.getLeft()+b.getRight())*.5f,-(b.getTop()+b.getBottom())*.5f);
            canvas.drawPath(icon.path(),paint);
        }finally{canvas.restoreToCount(save);}
    }
    private static Icon load(String name,String glyph) {
        try(var input=CapsuleIcons.class.getResourceAsStream("/muonium/fonts/"+name+".ttf")) {
            if(input==null)throw new IOException("Missing bundled capsule icon font");
            try(var bytes=Data.makeFromBytes(input.readAllBytes());var typeface=FontMgr.getDefault().makeFromData(bytes);
                var font=new Font(typeface,100)) {
                short[] ids=font.getStringGlyphs(glyph);
                if(ids.length!=1||ids[0]==0)throw new IOException("Missing bundled capsule glyph");
                Path path=font.getPath(ids[0]);if(path==null)throw new IOException("Empty capsule glyph");
                var bounds=path.computeTightBounds();
                if(!(bounds.getWidth()>0&&bounds.getHeight()>0)){path.close();throw new IOException("Empty capsule bounds");}
                return new Icon(path,bounds);
            }
        }catch(IOException e){throw new IllegalStateException("Capsule icon unavailable: "+name,e);}
    }
    public static void close(){for(var icon:icons.values())icon.close();icons.clear();}
}
