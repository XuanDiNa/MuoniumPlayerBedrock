package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.nio.*;

/** Per-row GPU depth comparison. Records existing glyph/effect commands once,
 * then replays only their bounded layer. No CPU pixel rays or depth guesses. */
final class GameDepthClip implements AutoCloseable {
    private static final String SOURCE="""
        uniform shader depth;
        uniform float4 row0; uniform float4 row1; uniform float4 row2; uniform float4 row3;
        uniform float4 crop; uniform float2 viewport; uniform float sampleStep;
        half4 main(float2 point) {
            float4 p=float4(point,0,1);
            float4 q=float4(dot(row0,p),dot(row1,p),dot(row2,p),dot(row3,p));
            if(q.w<=0.0001) return half4(1);
            float2 pixel=float2((q.x/q.w*.5+.5)*viewport.x,(.5-q.y/q.w*.5)*viewport.y);
            float2 uv=floor(pixel)-crop.xy;
            if(uv.x<0||uv.y<0||uv.x>=crop.z||uv.y>=crop.w) return half4(1);
            float3 rgb=float3(depth.eval(floor(uv/sampleStep)+float2(.5)).rgb);
            float z=dot(rgb,float3(65536,256,1))*(255.0/16777215.0);
            float own=q.z/q.w;
            return half4(own<0||own>1||own<=z+0.000002?1:0);
        }
        """;
    private DepthFrame frame,cached;private long now;
    private Image image;private RuntimeEffect effect;private boolean broken;
    private Image maskImage;private MotionDepthWarp warp;private boolean reprojected;
    private int maskX,maskY,maskWidth,maskHeight,maskStep;
    private PictureRecorder recorder;private Canvas target;
    private WorldFrame world;private Matrix4f screen,nativeClip;
    private float left,top,right,bottom;private boolean hasBounds;
    private double cropLeft,cropTop,cropRight,cropBottom;private boolean hasCrop;
    private long applied,fallback,partial,motionApplied;
    void update(DepthFrame f,long time){frame=f;now=time;}
    void beginFrame(){hasCrop=false;}
    void begin(WorldFrame w,Matrix4f matrix,double x,double y,double z,double yaw,double pitch,float scale,boolean enabled){
        if(recorder!=null)throw new IllegalStateException("Nested depth billboard");
        world=w;screen=matrix;hasBounds=false;nativeClip=null;reprojected=false;maskImage=null;
        if(!enabled||broken||w==null||frame==null)return;
        var window=BedrockClient.getInstance().getWindow();
        if(!frame.reprojectable(w,window.getWidth(),window.getHeight(),now))return;
        try {
            if(effect==null)effect=RuntimeEffect.makeForShader(SOURCE);
            reprojected=frame.cameraMoved(w);
            maskX=frame.x;maskY=frame.y;maskWidth=frame.width;maskHeight=frame.height;maskStep=frame.sampleStep;
            if(reprojected){
                if(warp==null)warp=new MotionDepthWarp();maskImage=warp.render(frame,w);if(maskImage==null)return;
                maskX=maskY=0;maskWidth=frame.viewportWidth;maskHeight=frame.viewportHeight;maskStep=1;
                nativeClip=frame.camera.projection().mul(w.viewRotation()).translate((float)x,(float)y,(float)z);
            }else{
            if(cached!=frame){
                if(image!=null)image.close();image=null;
                image=Image.makeRaster(new ImageInfo(frame.sampleWidth,frame.sampleHeight,ColorType.RGBA_8888,ColorAlphaType.OPAQUE,null),frame.rgba,(long)frame.sampleWidth*4);cached=frame;
            }
            maskImage=image;
            nativeClip=frame.camera.projection().mul(frame.camera.viewRotation())
                .translate((float)(w.eyeX()-frame.camera.eyeX()+x),(float)(w.eyeY()-frame.camera.eyeY()+y),(float)(w.eyeZ()-frame.camera.eyeZ()+z));
            }
            nativeClip.rotateY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch)).scale(-scale,-scale,scale);
            recorder=new PictureRecorder();var recording=recorder.beginRecording(Rect.makeLTRB(-32768,-32768,32768,32768));
            target=SkijaHost.redirectCanvas(recording);
        }catch(RuntimeException error){broken=true;nativeClip=null;
            if(target!=null){SkijaHost.redirectCanvas(target);target=null;}if(recorder!=null){recorder.close();recorder=null;}
            System.err.println("DEPTH shader unavailable; voxel clipping retained: "+error.getClass().getSimpleName());
        }
    }
    boolean recording(){return recorder!=null;}
    void include(float x,float y,float w,float h){
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0)return;
        if(!hasBounds){left=x;top=y;right=x+w;bottom=y+h;hasBounds=true;}
        else{left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+w);bottom=Math.max(bottom,y+h);}
    }
    private double[] project(Matrix4f matrix,boolean nativeViewport){
        if(matrix==null||!hasBounds)return null;
        double l=Double.POSITIVE_INFINITY,t=l,r=Double.NEGATIVE_INFINITY,b=r;
        for(int i=0;i<4;i++){
            var p=matrix.transform(new Vector4f((i&1)==0?left:right,(i&2)==0?top:bottom,0,1));
            if(!Float.isFinite(p.w)||p.w<=.0001f)return null;
            double x=p.x/p.w,y=p.y/p.w;
            if(nativeViewport){x=(x*.5+.5)*frame.viewportWidth;y=(.5-y*.5)*frame.viewportHeight;}
            else{x*=SkijaHost.guiScale();y*=SkijaHost.guiScale();}
            l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);
        }return new double[]{l,t,r,b};
    }
    private void track(){
        if(world==null)return;var rect=project(screen,false);if(rect==null)return;
        if(!hasCrop){cropLeft=rect[0];cropTop=rect[1];cropRight=rect[2];cropBottom=rect[3];hasCrop=true;}
        else{cropLeft=Math.min(cropLeft,rect[0]);cropTop=Math.min(cropTop,rect[1]);cropRight=Math.max(cropRight,rect[2]);cropBottom=Math.max(cropBottom,rect[3]);}
    }
    int[] crop(){
        if(!hasCrop)return null;var window=BedrockClient.getInstance().getWindow();
        int l=(int)Math.max(0,Math.floor(cropLeft)-8),t=(int)Math.max(0,Math.floor(cropTop)-8);
        int r=(int)Math.min(window.getWidth(),Math.ceil(cropRight)+8),b=(int)Math.min(window.getHeight(),Math.ceil(cropBottom)+8);
        return r>l&&b>t?new int[]{l,t,r-l,b-t}:null;
    }
    void end(Runnable voxelFallback){
        track();if(recorder==null)return;
        var recording=recorder;var canvas=target;recorder=null;target=null;SkijaHost.redirectCanvas(canvas);
        try(recording;var picture=hasBounds?recording.finishRecordingAsPicture(Rect.makeLTRB(left,top,right,bottom)):recording.finishRecordingAsPicture()){
            var bounds=project(nativeClip,true);
            boolean covered=bounds!=null&&bounds[0]>=maskX&&bounds[1]>=maskY&&bounds[2]<=maskX+maskWidth&&bounds[3]<=maskY+maskHeight;
            boolean overlaps=bounds!=null&&bounds[2]>maskX&&bounds[3]>maskY&&bounds[0]<maskX+maskWidth&&bounds[1]<maskY+maskHeight;
            if(!overlaps){voxelFallback.run();canvas.drawPicture(picture);fallback++;return;}
            // Keep known foreground pixels when animation grows outside the old
            // crop. Uncaptured pixels retain voxel clipping, not an old mask.
            if(!covered||reprojected){voxelFallback.run();partial++;}
            var uniforms=ByteBuffer.allocate(effect.getUniformSize()).order(ByteOrder.nativeOrder());
            for(int row=0;row<4;row++)for(int col=0;col<4;col++)uniforms.putFloat(nativeClip.get(col,row));
            uniforms.putFloat(maskX).putFloat(maskY).putFloat(maskWidth).putFloat(maskHeight).putFloat(frame.viewportWidth).putFloat(frame.viewportHeight).putFloat(maskStep);
            try(var data=Data.makeFromBytes(uniforms.array());var depth=maskImage.makeShader(FilterTileMode.CLAMP,FilterTileMode.CLAMP,SamplingMode.DEFAULT,null);
                var shader=effect.makeShader(data,new Shader[]{depth});var paint=new Paint().setShader(shader).setBlendMode(BlendMode.DST_IN)){
                int save=canvas.saveLayer(Rect.makeLTRB(left-1,top-1,right+1,bottom+1),null);
                try{canvas.drawPicture(picture);canvas.drawPaint(paint);applied++;if(reprojected)motionApplied++;}finally{canvas.restoreToCount(save);}
            }
        }
    }
    long applied(){return applied;}
    long fallback(){return fallback;}
    long partial(){return partial;}
    long motionApplied(){return motionApplied;}
    public void close(){
        if(target!=null)SkijaHost.redirectCanvas(target);target=null;
        if(recorder!=null)recorder.close();recorder=null;
        if(image!=null)image.close();image=null;cached=null;if(effect!=null)effect.close();effect=null;
        if(warp!=null)warp.close();warp=null;maskImage=null;
    }
}
