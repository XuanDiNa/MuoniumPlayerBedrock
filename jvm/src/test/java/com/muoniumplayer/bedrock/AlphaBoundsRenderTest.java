package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real GPU occupancy, same-frame pixel coverage and measured intermediate work. */
class AlphaBoundsRenderTest {
    private static void mark(int x,int y,int w,int h,int alpha) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(x,y,w,h);
        GL11.glClearColor(0,0,0,alpha/255f);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
    private static void covered(List<AlphaBounds.Region> regions,int x,int y) {
        assertTrue(regions.stream().anyMatch(r->r.left()<=x&&x<r.right()&&r.bottom()<=y&&y<r.top()),"alpha texel missing at "+x+","+y);
    }
    @Test void currentFrameCoverageAndBoundedRasterWork() {
        assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
            var caps=GL.getCapabilities();
            System.out.println("ALPHA_CAPS version="+GL11.glGetString(GL11.GL_VERSION)+" renderer="+GL11.glGetString(GL11.GL_RENDERER)
                +" gl43="+caps.OpenGL43+" compute="+caps.GL_ARB_compute_shader+" ssbo="+caps.GL_ARB_shader_storage_buffer_object);
            boolean modern=caps.OpenGL43||(caps.GL_ARB_compute_shader&&caps.GL_ARB_shader_storage_buffer_object);
            for(int[] size:new int[][]{{2560,1406},{321,211},{1,1},{8192,1}}) {
                int w=size[0],h=size[1];presenter.resize(w,h);
                int texture=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                try(var bounds=new AlphaBounds(w,h)) {
                    long total=0,complete=0;int measured=0;long maxSamples=0;
                    for(int frame=0;frame<36;frame++) {
                        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,presenter.framebuffer());GL11.glViewport(0,0,w,h);
                        GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glColorMask(true,true,true,true);GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                        var points=new ArrayList<int[]>();
                        if(frame%4==1){int[] p={frame%w,(frame*13)%h};mark(p[0],p[1],1,1,1);points.add(p);}
                        else if(frame%4!=0) {
                            for(int[] p:new int[][]{{0,0},{w-1,h-1},{w/2,h/2},{frame%w,(frame*13)%h}}) {mark(p[0],p[1],1,1,1);points.add(p);}
                            if(w>100&&h>100){mark(4,8,w-8,16,128);mark(4,h-24,w-8,16,255);points.add(new int[]{w/2,12});points.add(new int[]{w/2,h-12});}
                        }
                        int query=GL15.glGenQueries();GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED,query);
                        long start=System.nanoTime();var union=bounds.find(texture,presenter.framebuffer(),w,h);long elapsed=System.nanoTime()-start;
                        GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);long samples=GL33.glGetQueryObjectui64(query,GL15.GL_QUERY_RESULT);GL15.glDeleteQueries(query);
                        maxSamples=Math.max(maxSamples,samples);
                        assertEquals(presenter.framebuffer(),GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING));
                        assertTrue(bounds.regions().size()<=4);
                        for(var p:points){covered(bounds.regions(),p[0],p[1]);covered(List.of(union),p[0],p[1]);}
                        if(points.isEmpty()){assertEquals(1,union.area(),"clear frame must discard all previous occupancy");assertEquals(1,bounds.regions().stream().mapToLong(AlphaBounds.Region::area).sum());}
                        if(points.size()==1){int tile=4;while((w+tile-1)/tile>64||(h+tile-1)/tile>64)tile*=4;assertTrue(union.area()<=(long)tile*tile,"isolated alpha must retain a sparse current-frame tile");}
                        if(frame>=8){total+=elapsed;measured++;}
                        GL11.glFinish();start=System.nanoTime();bounds.find(texture,presenter.framebuffer(),w,h);GL11.glFinish();if(frame>=8)complete+=System.nanoTime()-start;
                        assertEquals(GL11.GL_NO_ERROR,GL11.glGetError());
                    }
                    System.out.println("ALPHA_BENCH size="+w+"x"+h+" frames="+measured+" avgUs="+total/measured/1000+" completedSourceAvgUs="+complete/measured/1000+" maxRasterSamples="+maxSamples);
                    if(modern&&!Boolean.getBoolean("muonium.alphaCharacterize"))assertTrue(maxSamples<=4096,"alpha extraction must avoid screen-sized intermediate raster work on compute-capable drivers; observed "+maxSamples);
                }
            }
        }finally{GLFW.glfwTerminate();}
    }
}
