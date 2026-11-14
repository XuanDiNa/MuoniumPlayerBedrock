package com.muoniumplayer.bedrock;

import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Optional one-dispatch same-frame occupancy. No previous-frame mask or alpha threshold. */
final class AlphaTileMask implements AutoCloseable {
    private final int width,height;
    private int program,buffer,sourceUniform,tileUniform;
    private IntBuffer values;
    static boolean supported() {
        var c=GL.getCapabilities();
        return c.OpenGL43||(c.GL_ARB_compute_shader&&c.GL_ARB_shader_storage_buffer_object);
    }
    AlphaTileMask(int width,int height) {
        this.width=width;this.height=height;
        if(width<1||height<1||width>64||height>64)throw new IllegalArgumentException("Alpha tile budget");
        try {
            int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            try {
                GL20.glShaderSource(shader,"""
                    #version 330 core
                    #extension GL_ARB_compute_shader : require
                    #extension GL_ARB_shader_storage_buffer_object : require
                    layout(local_size_x=16,local_size_y=16) in;
                    uniform sampler2D source;
                    uniform int tileSize;
                    layout(std430,binding=0) buffer Tiles { uint occupied[]; };
                    shared uint anyAlpha;
                    void main() {
                        if(gl_LocalInvocationIndex==0u)anyAlpha=0u;
                        barrier();
                        ivec2 size=textureSize(source,0);
                        ivec2 base=ivec2(gl_WorkGroupID.xy)*tileSize;
                        bool found=false;
                        for(int y=int(gl_LocalInvocationID.y);y<tileSize&&!found;y+=16) {
                            if(base.y+y>=size.y)break;
                            for(int x=int(gl_LocalInvocationID.x);x<tileSize;x+=16) {
                                if(base.x+x>=size.x)break;
                                if(texelFetch(source,base+ivec2(x,y),0).a>0.0){found=true;break;}
                            }
                        }
                        if(found)atomicOr(anyAlpha,1u);
                        barrier();
                        if(gl_LocalInvocationIndex==0u)occupied[gl_WorkGroupID.y*gl_NumWorkGroups.x+gl_WorkGroupID.x]=anyAlpha;
                    }
                    """);
                GL20.glCompileShader(shader);
                if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
                program=GL20.glCreateProgram();GL20.glAttachShader(program,shader);GL20.glLinkProgram(program);
                if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            } finally {GL20.glDeleteShader(shader);}
            sourceUniform=GL20.glGetUniformLocation(program,"source");tileUniform=GL20.glGetUniformLocation(program,"tileSize");
            buffer=GL15.glGenBuffers();int previous=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
            try {GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(long)width*height*4,GL15.GL_DYNAMIC_READ);}
            finally {GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,previous);}
            values=MemoryUtil.memAllocInt(width*height);
        } catch(Throwable failure){close();throw failure;}
    }
    void find(int texture,int tile,ByteBuffer alpha) {
        int previous=GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        int indexed=GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING,0);
        try {
            GL20.glUseProgram(program);GL13.glActiveTexture(GL13.GL_TEXTURE0);GL33.glBindSampler(0,0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL20.glUniform1i(sourceUniform,0);GL20.glUniform1i(tileUniform,tile);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,buffer);
            GL43.glDispatchCompute(width,height,1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,buffer);values.clear();
            GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER,0,values);
            for(int i=0;i<width*height;i++)alpha.put(i,(byte)(values.get(i)==0?0:1));
            if(GL11.glGetError()!=GL11.GL_NO_ERROR)throw new IllegalStateException("Alpha compute dispatch/readback");
        } finally {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER,0,indexed);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,previous);
        }
    }
    public void close() {
        if(values!=null)MemoryUtil.memFree(values);values=null;
        if(buffer!=0)GL15.glDeleteBuffers(buffer);buffer=0;
        if(program!=0)GL20.glDeleteProgram(program);program=0;
    }
}
