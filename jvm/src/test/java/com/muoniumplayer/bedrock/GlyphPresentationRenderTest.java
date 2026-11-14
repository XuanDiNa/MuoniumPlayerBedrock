package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.lyrics3d.LyricGlyphAtlas;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.*;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owned GPU glyph pixels, with no audio or game process. */
class GlyphPresentationRenderTest {
    static long window;
    @BeforeAll static void initialize() {
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
        window=GLFW.glfwCreateWindow(640,360,"Owned glyph refinement fixture",0,0);
        assertNotEquals(0,window);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
        var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(640,360,1);
        if(!MuoniumPlayerClient.getInstance().isReady())MuoniumPlayerClient.getInstance().init();
    }
    @AfterAll static void shutdown() {
        SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();
    }
    static java.awt.image.BufferedImage capture(String name) throws Exception {
        Path p=Path.of("../stage-captures/"+name+".png");Files.createDirectories(p.getParent());
        SkijaHost.savePng(p,true);return ImageIO.read(p.toFile());
    }
    @Test void unavailableAtlasRetainsVisibleFallbackText() throws Exception {
        try(var stage=new StageLyrics()) {
            LyricGlyphAtlas.setHost(null);assertFalse(LyricGlyphAtlas.isFunctional());
            SkijaHost.beginFrame(0);
            try{stage.drawSmoothGlyph('Z',100,100,34,0xFFFFFF,1,0,0,0,1,0,false,0,0xFFFFFF);}
            finally{SkijaHost.endFrame();}
            var image=capture("glyph-unavailable-fallback");int visible=0;
            for(int y=0;y<360;y++)for(int x=0;x<640;x++)if((image.getRGB(x,y)>>>24)>0)visible++;
            assertTrue(visible>10,"A genuinely unavailable atlas must retain readable fallback");
        }
    }
    @Test void modernInkReusesItsAtlasForASubtleBlackShadowAndRestoresTheCanvas() throws Exception {
        try(var stage=new StageLyrics()) {
            LyricGlyphAtlas.beginFrame();
            SkijaHost.beginFrame(0);
            try {
                stage.drawSmoothGlyph('M',80,80,34,0xFFFFFF,1,17,5,2,1.2f,0,false,0,0xFFFFFF);
                try(var paint=new io.github.humbleui.skija.Paint().setColor(0xFFFF0000)) {
                    SkijaHost.canvas().drawRect(io.github.humbleui.types.Rect.makeXYWH(10,10,10,10),paint);
                }
            }finally{SkijaHost.endFrame();}
            var image=capture("modern-ink-shadow");int shadow=0,ink=0,maxShadow=0;
            for(int y=40;y<180;y++)for(int x=40;x<180;x++) {
                int pixel=image.getRGB(x,y),a=pixel>>>24,rgb=pixel&0xFFFFFF;
                if(a>0&&rgb==0){shadow++;maxShadow=Math.max(maxShadow,a);}
                if(a>120&&rgb==0xFFFFFF)ink++;
            }
            assertTrue(ink>10,"Real modern glyph ink must be visible");
            assertTrue(shadow>4,"Modern glyph needs upstream's subtle black shadow");
            assertTrue(maxShadow<=57,"Shadow must stay within upstream's 0.22 alpha strength");
            assertEquals(0xFFFF0000,image.getRGB(14,14),"Glyph transforms must restore the canvas");
        }
    }
    @Test void budgetMissWaitsForAtlasInsteadOfFlashingPixelTextAndNextFrameRecovers() throws Exception {
        try(var stage=new StageLyrics()) {
            LyricGlyphAtlas.beginFrame();assertTrue(LyricGlyphAtlas.isFunctional());
            var budget=LyricGlyphAtlas.class.getDeclaredField("inkBakesThisFrame");budget.setAccessible(true);
            budget.setInt(null,8);
            SkijaHost.beginFrame(0);
            try{stage.drawSmoothGlyph('Q',100,100,34,0xFFFFFF,1,0,0,0,1,0,false,0,0xFFFFFF);}
            finally{SkijaHost.endFrame();}
            var deferred=capture("glyph-budget-deferred");int occupied=0;
            for(int y=0;y<360;y++)for(int x=0;x<640;x++)if((deferred.getRGB(x,y)>>>24)>0)occupied++;
            assertEquals(0,occupied,"A budget miss must not flash the unrelated pixel font");
            LyricGlyphAtlas.beginFrame();
            SkijaHost.beginFrame(0);
            try{stage.drawSmoothGlyph('Q',100,100,34,0xFFFFFF,1,0,0,0,1,0,false,0,0xFFFFFF);}
            finally{SkijaHost.endFrame();}
            var recovered=capture("glyph-budget-recovered");occupied=0;
            for(int y=0;y<360;y++)for(int x=0;x<640;x++)if((recovered.getRGB(x,y)>>>24)>0)occupied++;
            assertTrue(occupied>10,"A fresh frame must recover the original atlas glyph");
        }
    }
}
