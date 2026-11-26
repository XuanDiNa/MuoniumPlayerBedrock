package com.muoniumplayer.bedrock;

import com.muoniumplayer.render.skija.SkijaHost;
import com.muoniumplayer.core.screens.ncm.PlayerTransitionContentCache;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamSnapshotRenderTest {
    @Test void ownedGpuRoiSurvivesSourceChangesAndCacheDiesBeforeResize()throws Exception{
        assertTrue(GLFW.glfwInit());long window=0;LayeredPresenter target=null;
        try{
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);
            window=GLFW.glfwCreateWindow(640,480,"Owned snapshot regression",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(640,480,1);
            target=new LayeredPresenter(null);target.resize(640,480);SkijaHost.renderTarget(target.framebuffer());
            com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
            SkijaHost.beginFrame(0);
            try(var paint=new Paint().setColor(0xFFFF0000)){
                SkijaHost.canvas().drawRect(Rect.makeXYWH(30,40,80,60),paint);
                try(var image=SkijaHost.snapshotContent(IRect.makeXYWH(30,40,80,60))){
                    assertNotNull(image);assertEquals(80,image.getWidth());assertEquals(60,image.getHeight());
                    SkijaHost.canvas().clear(0);
                    try(var pixels=new Bitmap()){
                        assertTrue(pixels.allocN32Pixels(80,60));
                        assertTrue(image.readPixels(SkijaHost.context(),pixels,0,0,false));
                        assertEquals(0xFFFF0000,pixels.getColor(40,30));
                    }
                }
                var getter=PlayerTransitionContentCache.class.getDeclaredMethod("get");getter.setAccessible(true);
                Object cache=getter.invoke(null),owner=new Object();
                var capture=PlayerTransitionContentCache.class.getDeclaredMethod("capture",Object.class,long.class,double.class,double.class,double.class,double.class);
                capture.setAccessible(true);capture.invoke(cache,owner,1L,30d,40d,80d,60d);
                var image=PlayerTransitionContentCache.class.getDeclaredField("image");image.setAccessible(true);
                assertNotNull(image.get(cache),"Actual upstream transition cache must receive the owned GPU ROI");
                SkijaHost.endFrame();target.resize(800,600);SkijaHost.renderTarget(target.framebuffer());
                assertNull(image.get(cache),"A cached texture cannot outlive the surface/context storage");
            }finally{if(SkijaHost.inFrame())SkijaHost.endFrame();}
        }finally{
            SkijaHost.shutdown();if(target!=null)target.close();
            if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
}
