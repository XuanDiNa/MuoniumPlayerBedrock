package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.lyrics3d.WorldLyricsState;
import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import com.muoniumplayer.core.ncm.music.AudioPlayer;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.*;
import com.muoniumplayer.core.screens.ncm.LyricLine;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real upstream renderer and real GPU glyph pages. No audio,
 * credentials, network, game process, or injected DLL is used by this fixture. */
class StageRenderTest {
    private static void checkGpuPicturePresentation() throws Exception {
        try(var recorder=new io.github.humbleui.skija.PictureRecorder();var stage=new StageLyrics();
            var paint=new io.github.humbleui.skija.Paint().setColor(0x80FF0000)) {
            var canvas=recorder.beginRecording(io.github.humbleui.types.Rect.makeWH(64,32));
            canvas.drawRect(io.github.humbleui.types.Rect.makeWH(64,32),paint);
            try(var picture=recorder.finishRecordingAsPicture()) {
                SkijaHost.beginFrame(0);
                try {assertTrue(stage.supportsGpuTrailCanvas());
                    assertTrue(stage.drawGpuTrailCanvas(picture,64,32,16,16,128,64,1,0));}
                finally {SkijaHost.endFrame();}
                assertEquals(1,stage.gpuTrailPictures());
                Path file=Path.of("../stage-captures/gpu-trail.png");Files.createDirectories(file.getParent());
                SkijaHost.savePng(file,true);var image=ImageIO.read(file.toFile());
                assertEquals(128,image.getRGB(32,32)>>>24,2);
                assertEquals(255,(image.getRGB(32,32)>>>16)&255,2);
                assertEquals(0,image.getRGB(200,100)>>>24);
            }
        }
    }
    @Test void upstreamGlyphsRenderIntoTransparentPerspectiveLayer() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest", "true");
        assertTrue(GLFW.glfwInit());
        long window = 0;
        var oldMusic = CloudMusic.currentlyPlaying;
        var oldPlayer = CloudMusic.player;
        var oldKey = CloudMusic.lyricsTrackKey;
        var oldLyrics = List.copyOf(CloudMusic.lyrics);
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
            window = GLFW.glfwCreateWindow(1280, 720, "Muonium stage offline fixture", 0, 0);
            assertNotEquals(0, window);
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            BedrockClient.getInstance().bindWindow(window);
            BedrockClient.getInstance().getWindow().size(1280, 720, 1);
            if (!MuoniumPlayerClient.getInstance().isReady()) MuoniumPlayerClient.getInstance().init();
            checkGpuPicturePresentation();
            checkSettingsReload();

            Music track = new Music("Offline render fixture", "", "", 918273L,
                    List.of(new Artist(1, "Fixture", List.of(), List.of())), List.of(),
                    new Album(1, "Fixture", "", List.of()), 30000L, 0L, 0L, List.of());
            CloudMusic.currentlyPlaying = track;
            CloudMusic.lyricsTrackKey = track.getStableKey();
            CloudMusic.lyrics.clear();
            CloudMusic.lyrics.add(new LyricLine(0L, "Music follows your view · 音樂隨視角綻放"));
            CloudMusic.lyrics.add(new LyricLine(10000L, "Perspective lyrics · 立體歌詞舞台"));
            CloudMusic.currentLyric=CloudMusic.lyrics.getFirst();
            Path wave = Path.of("stage-fixture.wav");
            var format = new javax.sound.sampled.AudioFormat(8000, 16, 1, true, false);
            try (var pcm = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(new byte[16000]), format, 8000)) {
                javax.sound.sampled.AudioSystem.write(pcm, javax.sound.sampled.AudioFileFormat.Type.WAVE, wave.toFile());
            }
            CloudMusic.player = new AudioPlayer(wave.toFile()) {
                public float getCurrentTimeMillis() { return 1600; }
                public float getTotalTimeMillis() { return 30000; }
                public boolean isFinished() { return false; }
                public boolean isPausing() { return false; }
            };
            checkCombinedPages();
            checkOriginalWidgetGeometry();
            checkDesktopWidgetColors();
            HudConfig.worldLyricsEnabled = true;
            HudConfig.worldLyricsModernStyle = true;
            HudConfig.worldLyricsSpreadDeg = 0;
            HudConfig.worldLyricsScale = 1.6f;
            HudConfig.worldLyricsGlowEnabled = true;
            WorldLyricsRenderer.clearBrokenLatch();
            checkHudTransforms();
            try (StageLyrics stage = new StageLyrics()) {
                for (int style = 0; style < 3; style++) {
                    HudConfig.worldLyricsGlowEnabled = style != 0;
                    HudConfig.worldLyricsCadenza = style == 1;
                    HudConfig.worldLyricsHuiguang = style == 2;
                    var reset = WorldLyricsState.class.getDeclaredMethod("resetForTest");
                    reset.setAccessible(true); reset.invoke(WorldLyricsState.get());
                    long start = System.nanoTime();
                    long before = stage.drawnGlyphs();
                    for (int frame = 0; frame < 70; frame++) {
                        SkijaHost.beginFrame(0);
                        try { stage.render(start + frame * 16_666_667L); }
                        finally { SkijaHost.endFrame(); }
                    }
                    assertTrue(stage.drawnGlyphs() > before, "Upstream style " + style + " must draw real glyphs");
                    Path output = Path.of("../stage-captures/stage-" + style + ".png").toAbsolutePath();
                    Files.createDirectories(output.getParent());
                    SkijaHost.savePng(output, true);
                    var image = ImageIO.read(output.toFile());
                    int visible = 0, clear = 0;
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                        int argb = image.getRGB(x, y);
                        if ((argb >>> 24) > 40 && (argb & 0xFFFFFF) != 0) visible++;
                        if ((argb >>> 24) == 0) clear++;
                    }
                    assertTrue(visible > 150, "Glyphs must appear in the captured viewport, style " + style);
                    assertTrue(clear > 1280 * 720 / 2, "Unoccupied pixels must remain transparent");
                }
            }
            try (LyricOverlay overlay = new LyricOverlay()) {
                SkijaHost.beginFrame(0);
                try { overlay.render(HostSettings.parse("{\"lyricsMode\":0}"), System.nanoTime()); }
                finally { SkijaHost.endFrame(); }
                Path output = Path.of("../stage-captures/ordinary-hud.png").toAbsolutePath();
                SkijaHost.savePng(output, true);
                var image = ImageIO.read(output.toFile()); int visible = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    if ((image.getRGB(x, y) >>> 24) > 40) visible++;
                assertTrue(visible > 150, "The ordinary HUD must work without a game camera");
                var world=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
                BedrockClient.getInstance().setScene(new BedrockClient.Scene(true,false,false,false,false,-1,-1));
                for(int style=0;style<3;style++) {
                    HudConfig.worldLyricsGlowEnabled=style!=0;
                    HudConfig.worldLyricsCadenza=style==1;HudConfig.worldLyricsHuiguang=style==2;
                    com.muoniumplayer.core.lyrics3d.BedrockWorldLifecycle.reset();
                    overlay.updateWorld(world);
                    long start=System.nanoTime();
                    long glyphs=overlay.drawnGlyphs();
                    for(int f=0;f<70;f++) {
                        SkijaHost.beginFrame(0);
                        long drawAt=start+f*16_666_667L;
                        try {BedrockOverlay.renderPassiveOverlays(HostSettings.parse("{\"lyricsMode\":2}"),true,true,
                            ()->overlay.render(HostSettings.parse("{\"lyricsMode\":2}"),drawAt,world),
                            ()->fail("Cinematic hidden HUD must not restore flat HUD widgets"));}
                        finally {SkijaHost.endFrame();}
                    }
                    assertTrue(overlay.drawnGlyphs()>glyphs,"World mode must draw original glyphs, not just HUD widgets");
                    output=Path.of("../stage-captures/world-"+style+".png").toAbsolutePath();SkijaHost.savePng(output,true);
                    image=ImageIO.read(output.toFile());visible=0;
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)
                        if((image.getRGB(x,y)>>>24)>40)visible++;
                    assertTrue(visible>150,"Original world style must draw into actual camera projection "+style);
                }
                checkSubtitleColors(overlay,world);
                overlay.updateWorld(null);
                SkijaHost.beginFrame(0);
                try {overlay.render(HostSettings.parse("{\"lyricsMode\":2}"),System.nanoTime(),null);}
                finally {SkijaHost.endFrame();}
                output=Path.of("../stage-captures/world-lost.png").toAbsolutePath();SkijaHost.savePng(output,true);
                image=ImageIO.read(output.toFile());
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)
                    assertEquals(0,image.getRGB(x,y)>>>24,"World loss must hide all anchored lyrics");
            }
        } finally {
            if (CloudMusic.player != null && CloudMusic.player != oldPlayer) CloudMusic.player.close();
            CloudMusic.player = oldPlayer; CloudMusic.currentlyPlaying = oldMusic;
            CloudMusic.lyricsTrackKey = oldKey; CloudMusic.lyrics.clear(); CloudMusic.lyrics.addAll(oldLyrics);
            com.muoniumplayer.core.ncm.music.ExternalMediaService.stop();
            SkijaHost.shutdown();
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
    private static void checkCombinedPages() throws Exception {
        var client=BedrockClient.getInstance();
        var pages=List.of(new HostMiniPlayer(),new HostHudEditor(),
            new com.muoniumplayer.core.screens.hud.GuiPresetSettings(()->client.gui.setScreen(null),
                com.muoniumplayer.core.screens.hud.PresetSettingsActions.Mode.MANAGE));
        for(var page:pages) {
            client.gui.setScreen(page);
            for(int i=0;i<40;i++) {
                SkijaHost.beginFrame(0);
                try{page.drawHostFrame(0);}finally{SkijaHost.endFrame();}
                Thread.sleep(16);
            }
            Path png=Path.of("../stage-captures/batch-"+page.getClass().getSimpleName()+".png");
            Files.createDirectories(png.getParent());SkijaHost.savePng(png,true);
            var image=ImageIO.read(png.toFile());int visible=0;
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)if((image.getRGB(x,y)>>>24)>40)visible++;
            assertTrue(visible>100,"Real UI must render "+page.getClass().getSimpleName());
            if(page.transparentHost())assertEquals(0,image.getRGB(0,0)>>>24);
            if(page instanceof HostMiniPlayer) {
                // Press coordinates use the same geometry as rendering, with
                // no prior hover frame. Mode cycling is real upstream state.
                var oldMode=CloudMusic.playMode;
                try {
                    var f=HostMiniPlayer.class.getDeclaredField("scale");f.setAccessible(true);float scale=f.getFloat(page);
                    var fx=HostMiniPlayer.class.getDeclaredField("x");fx.setAccessible(true);
                    var fy=HostMiniPlayer.class.getDeclaredField("y");fy.setAccessible(true);
                    page.clickEvent(Math.round(fx.getFloat(page)+(float)(com.muoniumplayer.core.rendering.MusicCapsuleCard.CARD_WIDTH-26)*scale),
                        Math.round(fy.getFloat(page)+(float)(com.muoniumplayer.core.rendering.MusicCapsuleCard.COVER_Y+12)*scale),0);
                    assertNotEquals(oldMode,CloudMusic.playMode,"Mini click must reach original capsule controls");
                }finally{CloudMusic.playMode=oldMode;}
                page.keyEvent('\0',com.muoniumplayer.compat.lwjgl2.Keyboard.KEY_ESCAPE);
                assertNull(client.gui.screen(),"Mini Escape closes interaction");
            }
            client.gui.setScreen(null);
        }
    }
    private static void checkSubtitleColors(LyricOverlay overlay,WorldFrame world) throws Exception {
        for(int mode=0;mode<3;mode++)for(int style=0;style<(mode==0?1:3);style++) {
            HudConfig.worldLyricsGlowEnabled=false;
            HudConfig.worldLyricsCadenza=style==1;HudConfig.worldLyricsHuiguang=style==2;
            com.muoniumplayer.core.lyrics3d.BedrockWorldLifecycle.reset();
            var custom=HostSettings.parse("{\"lyricsMode\":"+mode+",\"lyricColorCustom\":true,\"lyricColorRed\":32,\"lyricColorGreen\":240,\"lyricColorBlue\":64}");
            long start=System.nanoTime();
            for(int frame=0;frame<70;frame++) {
                SkijaHost.beginFrame(0);
                try {overlay.render(custom,start+frame*16_666_667L,mode==2?world:null);}
                finally {SkijaHost.endFrame();}
            }
            Path output=Path.of("../stage-captures/color-"+mode+"-"+style+".png").toAbsolutePath();SkijaHost.savePng(output,true);
            var image=ImageIO.read(output.toFile());int green=0;
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                int c=image.getRGB(x,y),r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                if((c>>>24)>60&&g>r*1.5&&g>b*1.5)green++;
            }
            assertTrue(green>60,"Custom color must reach real glyph pixels, mode="+mode+" style="+style+" count="+green);
        }
        SkijaHost.beginFrame(0);
        try {overlay.render(HostSettings.parse("{\"lyricsMode\":0}"),System.nanoTime());}
        finally {SkijaHost.endFrame();}
        Path output=Path.of("../stage-captures/color-original.png").toAbsolutePath();SkijaHost.savePng(output,true);
        var image=ImageIO.read(output.toFile());int white=0;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int c=image.getRGB(x,y);if((c>>>24)>160&&((c>>>16)&255)>220&&((c>>>8)&255)>220&&(c&255)>220)white++;
        }
        assertTrue(white>100,"Disabling custom color must restore original ordinary subtitle white");
    }
    private static void checkOriginalWidgetGeometry() {
        var original=HostSettings.parse("{}");
        var moved=HostSettings.parse("{\"infoXPercent\":75,\"infoYPercent\":20,\"infoScalePercent\":60,\"desktopLyricsXPercent\":20,\"desktopLyricsYPercent\":70,\"desktopLyricsScalePercent\":130}");
        HudElements.apply(moved,original);
        var extension=com.muoniumplayer.core.MuoniumPlayerExtension.getInstance();
        SkijaHost.beginFrame(0);
        try {
            extension.musicInfo.widget.render();extension.musicLyrics.widget.render();
            double width=com.muoniumplayer.core.rendering.rendersystem.RenderSystem.getLayoutWidth();
            double height=com.muoniumplayer.core.rendering.rendersystem.RenderSystem.getLayoutHeight();
            assertEquals(.75*(width-230*.6),extension.musicInfo.widget.getX(),.01);
            assertEquals(.2*(height-56*.6),extension.musicInfo.widget.getY(),.01);
            assertEquals(.2*(width-HudConfig.lyricWidth*1.3),extension.musicLyrics.widget.getX(),.01);
            assertEquals(.7*(height-HudConfig.lyricHeight*1.3),extension.musicLyrics.widget.getY(),.01);
        } finally {SkijaHost.endFrame();}
    }
    private static void checkDesktopWidgetColors() throws Exception {
        var custom=HostSettings.parse("{\"lyricColorCustom\":true,\"lyricColorRed\":32,\"lyricColorGreen\":240,\"lyricColorBlue\":64}");
        int original=HudConfig.lyricColorRgb,style=HudConfig.osdLyricStyle;
        var widget=com.muoniumplayer.core.MuoniumPlayerExtension.getInstance().musicLyrics;
        HudElements.apply(custom,null);
        try {
            for(int desktopStyle=0;desktopStyle<2;desktopStyle++) {
                HudConfig.osdLyricStyle=desktopStyle;
                for(int frame=0;frame<30;frame++) {
                    SkijaHost.beginFrame(0);
                    try {widget.widget.render();}finally{SkijaHost.endFrame();}
                }
                Path output=Path.of("../stage-captures/desktop-color-"+desktopStyle+".png").toAbsolutePath();SkijaHost.savePng(output,true);
                var image=ImageIO.read(output.toFile());int green=0;
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                    int c=image.getRGB(x,y),r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                    if((c>>>24)>60&&g>r*1.5&&g>b*1.5)green++;
                }
                assertTrue(green>60,"Desktop glyphs must honor custom RGB, style="+desktopStyle+" count="+green);
                assertEquals(original,HudConfig.lyricColorRgb,"Original preferences must survive the host override");
            }
        } finally {HudConfig.osdLyricStyle=style;HudElements.apply(HostSettings.parse("{}"),custom);}
    }
    private static void checkHudTransforms() throws Exception {
        var initial=HostSettings.parse("{}");
        var moved=HostSettings.parse("{\"islandOffsetX\":80,\"islandOffsetY\":60,\"spectrumOffsetX\":20,\"spectrumOffsetY\":-40,\"spectrumScalePercent\":50}");
        HudElements.apply(moved,initial);
        SkijaHost.beginFrame(0);
        try(var paint=new io.github.humbleui.skija.Paint().setColor(0xFFFF0000)) {
            assertTrue(SkijaHost.deferPostCompositeOverlay(()->HudElements.renderIsland(()->{
                try(var fill=new io.github.humbleui.skija.Paint().setColor(0xFF00FF00)) {
                    SkijaHost.canvas().drawRect(io.github.humbleui.types.Rect.makeXYWH(10,10,20,20),fill);
                }
            })));
            HudElements.renderSpectrum(()->SkijaHost.canvas().drawRect(io.github.humbleui.types.Rect.makeXYWH(600,600,40,40),paint));
            try(var fill=new io.github.humbleui.skija.Paint().setColor(0xFF0000FF)) {
                SkijaHost.canvas().drawRect(io.github.humbleui.types.Rect.makeXYWH(10,10,20,20),fill);
            }
        } finally {SkijaHost.endFrame();}
        Path output=Path.of("../stage-captures/hud-transforms.png").toAbsolutePath();SkijaHost.savePng(output,true);
        var image=ImageIO.read(output.toFile());
        assertEquals(0xFF00FF00,image.getRGB(95,75),"Island translation must survive deferred composition");
        assertEquals(0xFFFF0000,image.getRGB(645,625),"Spectrum scales around bottom center and moves independently");
        assertEquals(0,image.getRGB(665,645)>>>24,"Spectrum must shrink its geometry");
        assertEquals(0xFF0000FF,image.getRGB(15,15),"Widget transforms must restore the canvas for other draws");
        HudElements.apply(initial,moved);
    }
    private static void checkSettingsReload() throws Exception {
        var apply = BedrockOverlay.class.getDeclaredMethod("applySettings", HostSettings.class);
        apply.setAccessible(true);
        apply.invoke(null, HostSettings.parse("{}"));
        CloudMusic.setVolume(.25f, false);
        CloudMusic.playMode = CloudMusic.PlayMode.LoopSingle;
        apply.invoke(null, HostSettings.parse("{\"stageStyle\":2}"));
        assertEquals(.25f, CloudMusic.getVolume(), .001f, "Lyric edits must preserve UI volume");
        assertEquals(CloudMusic.PlayMode.LoopSingle, CloudMusic.playMode, "Lyric edits must preserve UI repeat mode");
        apply.invoke(null, HostSettings.parse("{\"stageStyle\":2,\"volume\":40,\"repeat\":1}"));
        assertEquals(.4f, CloudMusic.getVolume(), .001f);
        assertEquals(CloudMusic.PlayMode.LoopInList, CloudMusic.playMode);
    }
}
