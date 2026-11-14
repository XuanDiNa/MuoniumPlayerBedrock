package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;

/** Forward-project owned depth into the current camera. Unknown/disoccluded
 * pixels stay clear; discontinuous triangles never bridge entity silhouettes.
 * At most 192x192 cells, one warp per source/current-camera pair. */
final class MotionDepthWarp implements AutoCloseable {
    private int program,vao,input,output,fbo,zbuffer,width,height,inputWidth,inputHeight;
    private ByteBuffer upload;private DepthFrame uploaded,lastDepth;private WorldFrame lastWorld;
    private BackendTexture backend;private Image image;
    private static final String VERTEX="""
        #version 330 core
        uniform sampler2D source;
        uniform mat4 oldInverse; uniform mat4 currentVP;
        uniform vec3 eyeDelta; uniform vec4 crop; uniform vec2 viewport;
        uniform float sampleStep; uniform ivec2 stride; uniform int columns;
        flat out int valid;
        bool point(ivec2 t,out vec3 p,out vec4 q){
            vec3 rgb=texelFetch(source,t,0).rgb;
            float z=dot(rgb,vec3(65536,256,1))*(255.0/16777215.0);
            vec2 pixel=crop.xy+(vec2(t)+vec2(.5))*sampleStep;
            vec2 ndc=vec2(pixel.x/viewport.x*2-1,1-pixel.y/viewport.y*2);
            vec4 h=oldInverse*vec4(ndc,z,1);
            if(z<=0||z>=.999999||abs(h.w)<.000001)return false;
            p=h.xyz/h.w;
            q=currentVP*vec4(p+eyeDelta,1);
            return q.w>.01;
        }
        void main(){
            int cell=gl_VertexID/6,vertex=gl_VertexID%6;
            ivec2 base=ivec2(cell%columns,cell/columns)*stride;
            ivec2 maximum=textureSize(source,0)-ivec2(1);
            ivec2 a,b,c;
            if(vertex<3){a=base;b=base+ivec2(stride.x,0);c=base+ivec2(0,stride.y);}
            else{a=base+ivec2(0,stride.y);b=base+ivec2(stride.x,0);c=base+stride;}
            vec3 pa,pb,pc;vec4 qa,qb,qc;
            valid=0;gl_Position=vec4(2,2,2,1);
            if(!point(min(a,maximum),pa,qa)||!point(min(b,maximum),pb,qb)||!point(min(c,maximum),pc,qc))return;
            float limit=.08+.05*max(length(pa),max(length(pb),length(pc)));
            if(distance(pa,pb)>limit||distance(pa,pc)>limit||distance(pb,pc)>limit)return;
            vec4 q=vertex%3==0?qa:vertex%3==1?qb:qc;
            // D3D's native [0,1] depth -> OpenGL's clip [-1,1].
            gl_Position=vec4(q.xy,2*q.z-q.w,q.w);valid=1;
        }
        """;
    private static final String FRAGMENT="""
        #version 330 core
        flat in int valid;layout(location=0) out vec4 color;
        void main(){
            if(valid==0)discard;
            uint z=uint(round(clamp(gl_FragCoord.z,0.0,1.0)*16777215.0));
            color=vec4(float((z>>16)&255u),float((z>>8)&255u),float(z&255u),255)/255.0;
        }
        """;
    Image render(DepthFrame source,WorldFrame current){
        if(source==lastDepth&&current==lastWorld&&image!=null)return image;
        if(source.sampleWidth<2||source.sampleHeight<2)return null;
        var context=SkijaHost.context();context.flush();
        int oldDraw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),oldRead=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        try{
            initialize();resize(source.viewportWidth,source.viewportHeight);
            if(image!=null){image.close();image=null;}
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,input);
            if(uploaded!=source){
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,1);GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0);
                if(upload==null||upload.capacity()<source.rgba.length){if(upload!=null)MemoryUtil.memFree(upload);upload=MemoryUtil.memAlloc(source.rgba.length);}
                upload.clear();upload.put(source.rgba).flip();
                if(inputWidth!=source.sampleWidth||inputHeight!=source.sampleHeight){
                    inputWidth=source.sampleWidth;inputHeight=source.sampleHeight;
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,inputWidth,inputHeight,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,upload);
                }else GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,inputWidth,inputHeight,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,upload);
                uploaded=source;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,width,height);
            GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LESS);GL11.glDepthMask(true);GL11.glColorMask(true,true,true,true);
            GL11.glClearColor(1,1,1,1);GL11.glClearDepth(1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);
            GL20.glUseProgram(program);GL30.glBindVertexArray(vao);
            int sx=Math.max(1,(inputWidth-1+191)/192),sy=Math.max(1,(inputHeight-1+191)/192);
            int columns=(inputWidth-1+sx-1)/sx,rows=(inputHeight-1+sy-1)/sy;
            uniform("source",0);uniform("columns",columns);
            GL20.glUniform2i(location("stride"),sx,sy);
            GL20.glUniform4f(location("crop"),source.x,source.y,source.width,source.height);
            GL20.glUniform2f(location("viewport"),width,height);GL20.glUniform1f(location("sampleStep"),source.sampleStep);
            GL20.glUniform3f(location("eyeDelta"),(float)(source.camera.eyeX()-current.eyeX()),(float)(source.camera.eyeY()-current.eyeY()),(float)(source.camera.eyeZ()-current.eyeZ()));
            GL20.glUniformMatrix4fv(location("oldInverse"),false,source.camera.projection().mul(source.camera.viewRotation()).invert().get(new float[16]));
            GL20.glUniformMatrix4fv(location("currentVP"),false,source.camera.projection().mul(current.viewRotation()).get(new float[16]));
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,columns*rows*6);
            int error=GL11.glGetError();if(error!=GL11.GL_NO_ERROR)throw new IllegalStateException("Depth reprojection GL error "+error);
            image=Image.borrowTextureFrom(context,backend,SurfaceOrigin.BOTTOM_LEFT,ColorType.RGBA_8888,ColorAlphaType.OPAQUE,null,null);
            if(image==null)throw new IllegalStateException("Depth reprojection image unavailable");
            lastDepth=source;lastWorld=current;return image;
        }finally{
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,oldDraw);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,oldRead);
            GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);context.resetGLAll();
        }
    }
    private int location(String name){return GL20.glGetUniformLocation(program,name);}
    private void uniform(String name,int value){GL20.glUniform1i(location(name),value);}
    private void initialize(){
        if(program!=0)return;int v=compile(GL20.GL_VERTEX_SHADER,VERTEX),f=0;
        try{
            f=compile(GL20.GL_FRAGMENT_SHADER,FRAGMENT);program=GL20.glCreateProgram();
            GL20.glAttachShader(program,v);GL20.glAttachShader(program,f);GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            vao=GL30.glGenVertexArrays();input=GL11.glGenTextures();configure(input);
        }finally{GL20.glDeleteShader(v);if(f!=0)GL20.glDeleteShader(f);}
    }
    private static int compile(int kind,String code){
        int s=GL20.glCreateShader(kind);GL20.glShaderSource(s,code);GL20.glCompileShader(s);
        if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(s);GL20.glDeleteShader(s);throw new IllegalStateException(error);}return s;
    }
    private static void configure(int texture){
        GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
    }
    private void resize(int w,int h){
        if(width==w&&height==h&&fbo!=0)return;releaseTarget();width=w;height=h;
        output=GL11.glGenTextures();configure(output);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,0L);
        fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,output,0);
        zbuffer=GL30.glGenRenderbuffers();GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,zbuffer);GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER,GL14.GL_DEPTH_COMPONENT24,w,h);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,zbuffer);
        if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Depth reprojection framebuffer");
        backend=BackendTexture.makeGL(w,h,false,new GLTextureInfo(GL11.GL_TEXTURE_2D,output,GL11.GL_RGBA8));
    }
    private void releaseTarget(){
        if(image!=null)image.close();image=null;if(backend!=null)backend.close();backend=null;
        if(fbo!=0)GL30.glDeleteFramebuffers(fbo);fbo=0;if(output!=0)GL11.glDeleteTextures(output);output=0;
        if(zbuffer!=0)GL30.glDeleteRenderbuffers(zbuffer);zbuffer=0;lastDepth=null;lastWorld=null;
    }
    public void close(){
        releaseTarget();if(input!=0)GL11.glDeleteTextures(input);input=0;if(vao!=0)GL30.glDeleteVertexArrays(vao);vao=0;
        if(program!=0)GL20.glDeleteProgram(program);program=0;if(upload!=null)MemoryUtil.memFree(upload);upload=null;uploaded=null;
    }
}
