package com.muoniumplayer.bedrock;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Current-frame alpha occupancy; preserves even one alpha=1 pixel.
 * Compute mask is bounded to 64x64 uints; legacy reduction uses 64x64 bytes. */
final class AlphaBounds implements AutoCloseable {
    record Region(int left,int bottom,int right,int top) {
        int width(){return right-left;}int height(){return top-bottom;}
        long area(){return (long)width()*height();}
    }
    private record Level(int texture,int fbo,int width,int height){}
    private final ArrayList<Level> levels=new ArrayList<>();
    private int program,vao;private ByteBuffer alpha;private int tile=1;
    private AlphaTileMask compute;
    private java.util.List<Region> regions=java.util.List.of();
    java.util.List<Region> regions(){return regions;}
    AlphaBounds(int width,int height) {
        try {
            program=GL20.glCreateProgram();
            int vertex=compile(GL20.GL_VERTEX_SHADER,"""
                #version 330 core
                void main(){vec2 p=vec2((gl_VertexID==1)?3.0:-1.0,(gl_VertexID==2)?3.0:-1.0);gl_Position=vec4(p,0,1);}
                """);
            int fragment=0;
            try {
                fragment=compile(GL20.GL_FRAGMENT_SHADER,"""
                    #version 330 core
                    uniform sampler2D source;uniform int firstPass;
                    layout(location=0) out vec4 result;
                    void main(){ivec2 base=ivec2(gl_FragCoord.xy)*4;ivec2 size=textureSize(source,0);float a=0;
                        for(int y=0;y<4;y++)for(int x=0;x<4;x++){ivec2 p=base+ivec2(x,y);
                            if(all(lessThan(p,size))){vec4 c=texelFetch(source,p,0);a=max(a,firstPass==1?c.a:c.r);}}
                        result=vec4(a,0,0,1);}
                    """);
                GL20.glAttachShader(program,vertex);GL20.glAttachShader(program,fragment);GL20.glLinkProgram(program);
                if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException("Alpha reduction link: "+GL20.glGetProgramInfoLog(program));
            }finally{GL20.glDeleteShader(vertex);if(fragment!=0)GL20.glDeleteShader(fragment);}
            vao=GL30.glGenVertexArrays();
            do {
                width=(width+3)/4;height=(height+3)/4;tile*=4;
                int texture=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_R8,width,height,0,GL11.GL_RED,GL11.GL_UNSIGNED_BYTE,0L);
                int fbo=GL30.glGenFramebuffers();levels.add(new Level(texture,fbo,width,height));
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
                if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Alpha reduction FBO");
            }while(width>64||height>64);
            alpha=MemoryUtil.memAlloc(width*height);
            if(AlphaTileMask.supported())try {
                compute=new AlphaTileMask(width,height);
                System.out.println("ALPHA_MASK backend=compute-tile cells="+width*height+" tile="+tile);
            }catch(RuntimeException failure){
                System.out.println("ALPHA_MASK backend=fragment-pyramid reason="+failure.getClass().getSimpleName());
            }
        }catch(Throwable failure){close();throw failure;}
    }
    Region find(int sourceTexture,int sourceFbo,int width,int height) {
        var last=levels.getLast();
        if(compute!=null)try{compute.find(sourceTexture,tile,alpha);}
        catch(RuntimeException failure){
            compute.close();compute=null;
            System.out.println("ALPHA_MASK backend=fragment-pyramid reason=dispatch-"+failure.getClass().getSimpleName());
        }
        if(compute==null){
        GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glColorMask(true,true,true,true);GL20.glUseProgram(program);GL30.glBindVertexArray(vao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"source"),0);
        int texture=sourceTexture;
        for(int i=0;i<levels.size();i++) {
            var level=levels.get(i);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,level.fbo);
            GL11.glViewport(0,0,level.width,level.height);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL20.glUniform1i(GL20.glGetUniformLocation(program,"firstPass"),i==0?1:0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);texture=level.texture;
        }
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,1);GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,0);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadPixels(0,0,last.width,last.height,GL11.GL_RED,GL11.GL_UNSIGNED_BYTE,alpha);
        }
        int left=last.width,bottom=last.height,right=0,top=0;
        for(int y=0;y<last.height;y++)for(int x=0;x<last.width;x++)if(alpha.get(y*last.width+x)!=0) {
            left=Math.min(left,x);bottom=Math.min(bottom,y);right=Math.max(right,x+1);top=Math.max(top,y+1);
        }
        var bands=new ArrayList<Region>();
        for(int y=0;y<last.height;y++) {
            int l=last.width,r=0;
            for(int x=0;x<last.width;x++)if(alpha.get(y*last.width+x)!=0){l=Math.min(l,x);r=x+1;}
            if(r==0)continue;
            var row=new Region(l*tile,y*tile,Math.min(width,r*tile),Math.min(height,(y+1)*tile));
            if(!bands.isEmpty()&&bands.getLast().top()==row.bottom()) {
                var old=bands.removeLast();row=new Region(Math.min(old.left(),row.left()),old.bottom(),Math.max(old.right(),row.right()),row.top());
            }
            bands.add(row);
        }
        // Bound window/submit work. Merge the pair with the smallest added area.
        while(bands.size()>4){int at=0;long cost=Long.MAX_VALUE;
            for(int i=0;i<bands.size()-1;i++){var a=bands.get(i);var b=bands.get(i+1);long extra=union(a,b).area()-a.area()-b.area();if(extra<cost){cost=extra;at=i;}}
            bands.set(at,union(bands.get(at),bands.remove(at+1)));
        }
        regions=bands.isEmpty()?java.util.List.of(new Region(0,0,1,1)):java.util.List.copyOf(bands);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,sourceFbo);GL11.glViewport(0,0,width,height);
        if(right==0)return new Region(0,0,1,1);
        return new Region(left*tile,bottom*tile,Math.min(width,right*tile),Math.min(height,top*tile));
    }
    private static Region union(Region a,Region b){return new Region(Math.min(a.left(),b.left()),a.bottom(),Math.max(a.right(),b.right()),b.top());}
    private static int compile(int kind,String source){
        int shader=GL20.glCreateShader(kind);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0){String error=GL20.glGetShaderInfoLog(shader);GL20.glDeleteShader(shader);throw new IllegalStateException(error);}
        return shader;
    }
    public void close(){
        if(compute!=null)compute.close();compute=null;
        if(alpha!=null)MemoryUtil.memFree(alpha);alpha=null;
        for(var level:levels){GL30.glDeleteFramebuffers(level.fbo);GL11.glDeleteTextures(level.texture);}levels.clear();
        if(vao!=0)GL30.glDeleteVertexArrays(vao);vao=0;if(program!=0)GL20.glDeleteProgram(program);program=0;
    }
}
