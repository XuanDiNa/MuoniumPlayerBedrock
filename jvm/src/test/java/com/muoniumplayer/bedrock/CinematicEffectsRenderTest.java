package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.cine.*;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CinematicEffectsRenderTest {
    static void director(String name,Object value)throws Exception{
        var field=CineDirector.class.getDeclaredField(name);field.setAccessible(true);field.set(null,value);
    }
    @Test void barsAndTransitionsCoverTheClientAtEveryWindowAndGuiScale()throws Exception{
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());long window=0;LayeredPresenter presenter=null;
        var io=CineSettings.class.getDeclaredField("testIoHooked");io.setAccessible(true);
        boolean oldIo=io.getBoolean(null);io.setBoolean(null,true);var settings=CineSettings.toJson();
        try{
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Offline cinematic geometry",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();var client=BedrockClient.getInstance();client.bindWindow(window);
            com.muoniumplayer.MuoniumPlayerClient.getInstance().init();
            presenter=new LayeredPresenter(null); // Same size-independent offscreen target as the real host.
            var config=CineSettings.defaultsJson();config.addProperty("enabled",true);config.addProperty("showCinematicBars",true);
            config.addProperty("cinematicBarPercent",13);CineSettings.applyJson(config);CinematicFrameFixture.publish(.13f,0,0,0,0,false);
            var runtime=new CinematicRuntime();var latest=CinematicRuntime.class.getDeclaredField("latest");latest.setAccessible(true);
            latest.set(runtime,new CinematicState(1,1,1,1,true,0,64,0,0,65.6,0,0,0,70,1));
            for(double[] size:new double[][]{{1280,720,1},{1920,1080,1.5},{2560,1440,2},{1600,900,1.25},{800,600,1}}){
                int w=(int)size[0],h=(int)size[1];double scale=size[2];
                presenter.resize(w,h);SkijaHost.renderTarget(presenter.framebuffer());
                client.getWindow().size(w,h,scale);CinematicFrameFixture.publish(.13f,0,0,0,0,false);
                SkijaHost.beginFrame(0);
                try{
                    runtime.draw();
                    try(var paint=new Paint().setColor(0xFF00FF00)){
                        SkijaHost.canvas().drawRect(Rect.makeXYWH(10,SkijaHost.guiHeight()/2f,8,8),paint);
                    }
                }finally{SkijaHost.endFrame();}
                var output=Path.of("../stage-captures/cinematic-bars-"+w+".png");Files.createDirectories(output.getParent());SkijaHost.savePng(output,true);
                var image=ImageIO.read(output.toFile());
                for(int x:new int[]{1,w/2,w-2}){
                    assertEquals(0xFF000000,image.getRGB(x,8),"Top bar must span client width "+w);
                    assertEquals(0xFF000000,image.getRGB(x,h-9),"Bottom bar must stay at client bottom "+w);
                    assertEquals(0,image.getRGB(x,h/2)>>>24,"Center remains transparent "+w);
                }
                assertEquals(0xFF00FF00,image.getRGB((int)(12*scale),(int)((SkijaHost.guiHeight()/2f+2)*scale)),"Cinematic transform must restore before other HUD draws");
                config.addProperty("showCinematicBars",false);CineSettings.applyJson(config);

                // Use a long window so the real frame cannot expire while GL initializes.
                CinematicFrameFixture.publish(0,1,System.nanoTime()-99_000_000_000L,System.nanoTime()+1_000_000_000L,System.nanoTime()+2_000_000_000L,false);
                SkijaHost.beginFrame(0);try{runtime.draw();}finally{SkijaHost.endFrame();}
                SkijaHost.savePng(output,true);image=ImageIO.read(output.toFile());
                assertTrue((image.getRGB(w-2,h-2)>>>24)>240,"Fade must cover bottom-right client pixel "+w);
                config.addProperty("showCinematicBars",true);CineSettings.applyJson(config);
            }
        }finally{
            CinematicFrameFixture.clear();CineSettings.applyJson(settings);io.setBoolean(null,oldIo);
            if(presenter!=null)presenter.close();SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();
        }
    }
}
