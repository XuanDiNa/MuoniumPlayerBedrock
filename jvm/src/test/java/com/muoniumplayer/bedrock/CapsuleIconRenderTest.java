package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.MusicCapsuleCard;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** Actual upstream card glyphs on the owned GPU renderer; no game/audio/network. */
class CapsuleIconRenderTest {
    @Test void transportInkStaysCenteredAndAllPlaybackModesHaveVisibleIconsAtEveryScale() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        var oldMode=CloudMusic.playMode;
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());
            client.getWindow().size(800,600,1);presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());
            MuoniumPlayerClient.getInstance().init();
            Class<?> icons=Class.forName("com.muoniumplayer.core.rendering.MusicCardEffects$Icon");
            var transport=MusicCapsuleCard.class.getDeclaredMethod("drawTransportIcon",io.github.humbleui.skija.Canvas.class,icons,
                double.class,double.class,double.class,double.class,int.class);
            var mode=MusicCapsuleCard.class.getDeclaredMethod("drawMode",double.class,double.class,float.class,CloudMusic.PlayMode.class);
            transport.setAccessible(true);mode.setAccessible(true);
            for(double scale:new double[]{.75,1,1.5,2}) {
                for(String glyph:new String[]{"PREVIOUS","PLAY","PAUSE","NEXT"}) {
                    double size=glyph.equals("PLAY")||glyph.equals("PAUSE")?20:16.5;
                    Object icon=java.lang.Enum.valueOf((Class)icons,glyph.equals("PLAY")||glyph.equals("PAUSE")?"PLAY_PAUSE":glyph);
                    double pause=glyph.equals("PAUSE")?1:0;
                    SkijaHost.beginFrame(0);
                    try {
                        var gl=com.muoniumplayer.api.OpenAPI.getInstance().getGLStateManager();
                        gl.pushMatrix();try{gl.translate(40,30,0);gl.scale(scale,scale,1);
                            transport.invoke(null,SkijaHost.canvas(),icon,100d,100d,size,pause,0xFFFFFFFF);
                        }finally{gl.popMatrix();}
                    }finally{SkijaHost.endFrame();}
                    var image=capture("transport-"+(int)glyph.charAt(0)+"-"+scale);
                    int[] b=ink(image);int count=b[4];
                    double cx=(b[0]+b[2]+1)*.5,cy=(b[1]+b[3]+1)*.5;
                    System.out.println("CAPSULE_INK glyph="+(int)glyph.charAt(0)+" scale="+scale+" center="+cx+","+cy+" pixels="+count);
                    assertTrue(count>=4,"Every transport control needs a visible glyph");
                    assertEquals(40+(100+(glyph.equals("PLAY")?size*16/120:0))*scale,cx,1.6,"Scaled icon ink must center on its actual click target: "+glyph);
                    assertEquals(30+100*scale,cy,1.6,"Icon ink must align with the control row: "+glyph);
                    assertTrue(b[2]-b[0]<size*scale+3&&b[3]-b[1]<size*scale+3,"Icon must fit its allocated box");
                }
                for(var value:CloudMusic.PlayMode.values()) {
                    CloudMusic.playMode=value;
                    SkijaHost.beginFrame(0);
                    try {
                        var gl=com.muoniumplayer.api.OpenAPI.getInstance().getGLStateManager();
                        gl.pushMatrix();try{gl.translate(40,30,0);gl.scale(scale,scale,1);
                            mode.invoke(MusicCapsuleCard.INSTANCE,100d,100d,1f,value);
                        }finally{gl.popMatrix();}
                    }finally{SkijaHost.endFrame();}
                    var image=capture("mode-"+value+"-"+scale);int[] b=ink(image);
                    assertTrue(b[4]>=4,"Playback mode must not be blank: "+value);
                    assertEquals(40+100*scale,(b[0]+b[2]+1)*.5,1.6,"Mode ink center X: "+value);
                    assertEquals(30+100*scale,(b[1]+b[3]+1)*.5,1.6,"Mode ink center Y: "+value);
                }
            }
          }finally{SkijaHost.shutdown();}
        }finally{CloudMusic.playMode=oldMode;GLFW.glfwTerminate();SessionMedia.close();}
    }
    private static BufferedImage capture(String name)throws Exception {
        Path p=Path.of("../stage-captures/capsule-"+name+".png");Files.createDirectories(p.getParent());
        SkijaHost.savePng(p,true);return ImageIO.read(p.toFile());
    }
    private static int[] ink(BufferedImage image) {
        int minX=image.getWidth(),minY=image.getHeight(),maxX=-1,maxY=-1,count=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int c=image.getRGB(x,y);
            if((c>>>24)>100&&((c>>>16)&255)>200&&((c>>>8)&255)>200&&(c&255)>200) {
                minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);count++;
            }
        }
        return new int[]{minX,minY,maxX,maxY,count};
    }
}
