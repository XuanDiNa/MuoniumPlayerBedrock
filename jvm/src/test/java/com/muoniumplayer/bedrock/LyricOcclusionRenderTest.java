package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** Real billboard projection/clipping at non-origin world coordinates. */
class LyricOcclusionRenderTest {
    private static boolean initialized;
    private static void initPlayer(){if(!initialized){MuoniumPlayerClient.getInstance().init();initialized=true;}}
    @Test void translatingAndTurningCameraReprojectsForegroundInsteadOfReleasingIt() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())){
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(800,600,1);
            presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            var raw=java.util.Arrays.copyOf(DepthFrameTest.packet(),360+256*128*4);
            var b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putInt(336,272).putInt(340,236).putInt(344,256).putInt(348,128);
            java.util.Arrays.fill(raw,360,raw.length,(byte)255);
            // Known foreground plane at two blocks, ending at world X = eye X.
            var original=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
            var front=original.projection().transform(new org.joml.Vector4f(0,0,-2,1));
            int packed=Math.round((front.z/front.w)*16777215);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++){
                int at=360+(y*256+x)*4;raw[at]=(byte)(packed>>16);raw[at+1]=(byte)(packed>>8);raw[at+2]=(byte)packed;
            }
            var depth=DepthFrame.decode(raw,1001);assertNotNull(depth);
            for(int scenario=0;scenario<4;scenario++){
                double dx=scenario==0?.2:0,dy=scenario==2?.2:0,angle=scenario==1?Math.toRadians(2):0,pitch=scenario==3?Math.toRadians(2):0;
                var currentRaw=WorldFrameTest.packet(1000);var currentBytes=java.nio.ByteBuffer.wrap(currentRaw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                double lx=Math.sin(angle)*Math.cos(pitch),ly=-Math.sin(pitch),lz=Math.cos(angle)*Math.cos(pitch);
                currentBytes.putDouble(40,100+dx).putDouble(48,70+dy).putDouble(192,lx).putDouble(200,ly).putDouble(208,lz);
                float[] view=new org.joml.Matrix4f().lookAt((float)(100+dx),(float)(70+dy),-20,(float)(100+dx+lx),(float)(70+dy+ly),(float)(-20+lz),0,1,0).get(new float[16]);
                for(int i=0;i<16;i++)currentBytes.putFloat(64+i*4,view[i]);
                var current=WorldFrame.decode(currentRaw,1001);assertNotNull(current);
                try(var stage=new StageLyrics()){
                    stage.configure(HostSettings.parse("{\"worldLyricsOcclusion\":true}"));stage.depth(depth,1001);
                    SkijaHost.beginFrame(0);
                    try{
                        stage.render(System.nanoTime(),current);stage.pushBillboard(-dx,-dy,4,0,0,.04f);
                        try(var paint=new Paint().setColor(0xFFFFFFFF)){
                            stage.drawHalo(0,0,30,0);SkijaHost.canvas().drawRect(Rect.makeXYWH(-10,-10,20,20),paint);
                        }finally{stage.popBillboard();}
                    }finally{SkijaHost.endFrame();}
                    var path=Path.of("../stage-captures/depth-camera-motion-"+scenario+".png");Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
                    var image=ImageIO.read(path.toFile());
                    var edge=WorldProjection.billboard(current,800,600,-dx,-dy,2,0,0,.04f).transformProject(new org.joml.Vector3f());
                    assertEquals(0,image.getRGB((int)edge.x-8,(int)edge.y)>>>24,"Moving camera retains the reprojected foreground: "+scenario);
                    assertTrue((image.getRGB((int)edge.x+8,(int)edge.y)>>>24)>200,"Disoccluded side stays visible: "+scenario);
                    assertEquals(1,stage.depthApplied());
                    assertEquals(1,stage.depthReprojected());
                    // New moving-entity depth replaces the old silhouette; then
                    // clear pixels release it without carrying a temporal mask.
                    for(boolean movedForeground:new boolean[]{true,false}){
                        var nextRaw=raw.clone();var nextHeader=java.nio.ByteBuffer.wrap(nextRaw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        nextHeader.putLong(56,movedForeground?2:3);java.util.Arrays.fill(nextRaw,360,nextRaw.length,(byte)255);
                        if(movedForeground)for(int y=0;y<128;y++)for(int x=0;x<176;x++){
                            int at=360+(y*256+x)*4;nextRaw[at]=(byte)(packed>>16);nextRaw[at+1]=(byte)(packed>>8);nextRaw[at+2]=(byte)packed;
                        }
                        stage.depth(DepthFrame.decode(nextRaw,1001),1001);
                        SkijaHost.beginFrame(0);
                        try{
                            stage.render(System.nanoTime(),current);stage.pushBillboard(-dx,-dy,4,0,0,.04f);
                            try(var paint=new Paint().setColor(0xFFFFFFFF)){
                                stage.drawHalo(0,0,30,0);SkijaHost.canvas().drawRect(Rect.makeXYWH(-10,-10,20,20),paint);
                            }finally{stage.popBillboard();}
                            try(var paint=new Paint().setColor(0xFF00FF00)){SkijaHost.canvas().drawRect(Rect.makeXYWH(20,20,2,2),paint);}
                        }finally{SkijaHost.endFrame();}
                        var nextPath=Path.of("../stage-captures/depth-camera-motion-"+scenario+"-entity-"+movedForeground+".png");SkijaHost.savePng(nextPath,true);
                        var nextImage=ImageIO.read(nextPath.toFile());int alpha=nextImage.getRGB((int)edge.x+8,(int)edge.y)>>>24;
                        if(movedForeground)assertEquals(0,alpha,"Updated entity covers newly reached pixels");
                        else assertTrue(alpha>200,"Departed entity releases its previously covered pixels");
                        assertEquals(0xFF00FF00,nextImage.getRGB(21,21),"Motion pass cannot alter later HUD draws");
                    }
                }
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
    @Test void reducedDepthMapsToFullResolutionAndReleasesDepartedSilhouettes() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())){
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(1600,1200,1);
            presenter.resize(1600,1200);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            var raw=java.util.Arrays.copyOf(DepthFrameTest.packet(),360+800*600*4);
            var b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putInt(328,1600).putInt(332,1200).putInt(336,0).putInt(340,0).putInt(344,1600).putInt(348,1200).putInt(356,2);
            var camera=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
            try(var stage=new StageLyrics()){
                stage.configure(HostSettings.parse("{\"worldLyricsOcclusion\":true}"));
                for(boolean foreground:new boolean[]{true,false}){
                    java.util.Arrays.fill(raw,360,raw.length,(byte)255);b.putLong(56,foreground?1:2);
                    if(foreground)for(int y=0;y<600;y++)for(int x=0;x<400;x++){
                        int at=360+(y*800+x)*4;raw[at]=(byte)128;raw[at+1]=raw[at+2]=0;
                    }
                    var depth=DepthFrame.decode(raw,1001);assertNotNull(depth);stage.depth(depth,1001);
                    SkijaHost.beginFrame(0);
                    try{
                        stage.render(System.nanoTime(),camera);stage.pushBillboard(0,0,4,0,0,.04f);
                        try{stage.drawPixelText("MMMM",-stage.pixelWidth("MMMM")/2,-7,0xFFFFFFFF,false);}
                        finally{stage.popBillboard();}
                    }finally{SkijaHost.endFrame();}
                    var path=Path.of("../stage-captures/reduced-depth-"+foreground+".png");Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
                    var image=ImageIO.read(path.toFile());int left=0,right=0;
                    for(int y=530;y<650;y++)for(int x=730;x<870;x++)if((image.getRGB(x,y)>>>24)>100){if(x<800)left++;else right++;}
                    if(foreground)assertEquals(0,left,"Reduced depth masks the same logical pixels");
                    else assertTrue(left>20,"A departed silhouette cannot linger in later frames");
                    assertTrue(right>20,"Exposed glyphs remain visible at full display resolution");
                }
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
    @Test void animatedBoundsCannotReleaseTheEntityMaskOnTheSameGlyph() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())){
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(800,600,1);
            presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            var raw=java.util.Arrays.copyOf(DepthFrameTest.packet(),360+256*128*4);
            var header=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.putInt(336,272).putInt(340,236).putInt(344,256).putInt(348,128);
            java.util.Arrays.fill(raw,360,raw.length,(byte)255);
            for(int yy=0;yy<128;yy++)for(int xx=0;xx<128;xx++){
                int at=360+(yy*256+xx)*4;raw[at]=(byte)128;raw[at+1]=raw[at+2]=0;
            }
            var depth=DepthFrame.decode(raw,1001);assertNotNull(depth);
            var camera=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
            try(var stage=new StageLyrics()){
                stage.configure(HostSettings.parse("{\"worldLyricsOcclusion\":true}"));stage.depth(depth,1001);
                int count=0;
                for(float radius:new float[]{10,90,300,10}){
                    SkijaHost.beginFrame(0);
                    try{
                        stage.render(System.nanoTime(),camera);stage.pushBillboard(0,0,4,0,0,.04f);
                        try{
                            stage.drawPixelText("MMMM",-stage.pixelWidth("MMMM")/2,-7,0xFFFFFFFF,false);
                            // Animation/glow bounds can grow beyond the previous capture,
                            // even when this particular effect is transparent at this instant.
                            stage.drawHalo(0,0,radius*2,0);
                        }finally{stage.popBillboard();}
                    }finally{SkijaHost.endFrame();}
                    var path=Path.of("../stage-captures/depth-growing-bounds-"+(count++)+".png");
                    Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
                    var image=ImageIO.read(path.toFile());int left=0,right=0;
                    for(int y=268;y<325;y++)for(int x=368;x<432;x++)if((image.getRGB(x,y)>>>24)>100){if(x<400)left++;else right++;}
                    assertEquals(0,left,"Growing bounds must not release already captured foreground pixels: "+radius);
                    assertTrue(right>20,"Exposed text remains visible: "+radius);
                }
                assertEquals(4,stage.depthApplied(),"All four frames must use their fresh overlapping depth");
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
    @Test void actualGameDepthClipsForegroundPixelsAndRestoresTheLaterHud() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())){
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(800,600,1);
            presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            var raw=java.util.Arrays.copyOf(DepthFrameTest.packet(),360+256*128*4);
            var header=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.putInt(336,272).putInt(340,236).putInt(344,256).putInt(348,128);
            java.util.Arrays.fill(raw,360,raw.length,(byte)255);
            // A foreground model-like silhouette covers only the left half.
            for(int yy=0;yy<128;yy++)for(int xx=0;xx<128;xx++){
                int at=360+(yy*256+xx)*4;raw[at]=(byte)128;raw[at+1]=raw[at+2]=0;
            }
            var depth=DepthFrame.decode(raw,1001);assertNotNull(depth);
            var camera=WorldFrame.decode(WorldFrameTest.packet(1000),1001);
            try(var stage=new StageLyrics()){
                stage.configure(HostSettings.parse("{\"worldLyricsOcclusion\":true}"));stage.depth(depth,1001);
                SkijaHost.beginFrame(0);
                try{
                    stage.render(System.nanoTime(),camera);stage.pushBillboard(0,0,4,0,0,.04f);
                    try{stage.drawPixelText("MMMM",-stage.pixelWidth("MMMM")/2,-7,0xFFFFFFFF,false);}
                    finally{stage.popBillboard();}
                    assertEquals(1,stage.depthApplied(),"Use the actual depth shader, not voxel fallback");
                    try(var paint=new Paint().setColor(0xFF00FF00)){SkijaHost.canvas().drawRect(Rect.makeXYWH(380,330,2,2),paint);}
                }finally{SkijaHost.endFrame();}
                var path=Path.of("../stage-captures/game-depth-lyric.png");Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
                var image=ImageIO.read(path.toFile());int left=0,right=0;
                for(int yy=268;yy<332;yy++)for(int xx=368;xx<432;xx++)if((image.getRGB(xx,yy)>>>24)>100&&yy<325){if(xx<400)left++;else right++;}
                assertEquals(0,left,"Only foreground pixels must mask the original glyphs");assertTrue(right>20,"Exposed half stays visible");
                assertEquals(0xFF00FF00,image.getRGB(381,331),"Depth mask cannot leak into the later HUD");
                assertNotNull(stage.depthCrop(),"Production glyph bounds request the next asynchronous ROI");
                // Expired depth must release the glyphs, retaining the old voxel path.
                stage.depth(depth,1200);
                for(boolean wall:new boolean[]{false,true}){
                    byte[] cells=LyricVisibilityTest.environment();WorldSpeakerTest.cell(cells,0,0,2,wall?1:0);
                    stage.environment(EnvironmentFrame.decode(cells,1001,7));
                    SkijaHost.beginFrame(0);
                    try{
                        stage.render(System.nanoTime(),LyricVisibilityTest.camera());stage.pushBillboard(0,0,4,0,0,.04f);
                        try{stage.drawPixelText("MMMM",-stage.pixelWidth("MMMM")/2,-7,0xFFFFFFFF,false);}
                        finally{stage.popBillboard();}
                        assertEquals(1,stage.depthApplied(),"Expired capture must use voxel fallback");
                    }finally{SkijaHost.endFrame();}
                    var fallbackPath=Path.of("../stage-captures/game-depth-expired-"+wall+".png");SkijaHost.savePng(fallbackPath,true);
                    var fallbackImage=ImageIO.read(fallbackPath.toFile());int ink=0;
                    for(int yy=240;yy<360;yy++)for(int xx=280;xx<520;xx++)if((fallbackImage.getRGB(xx,yy)>>>24)>100)ink++;
                    if(wall)assertEquals(0,ink,"Voxel wall still occludes when game depth expires");
                    else assertTrue(ink>20,"Clear fallback must not keep old entity silhouette");
                }
                // Turning the setting off restores all original ink even with a fresh mask.
                stage.configure(HostSettings.parse("{}"));stage.depth(depth,1001);
                SkijaHost.beginFrame(0);
                try{
                    stage.render(System.nanoTime(),camera);stage.pushBillboard(0,0,4,0,0,.04f);
                    try{stage.drawPixelText("MMMM",-stage.pixelWidth("MMMM")/2,-7,0xFFFFFFFF,false);}
                    finally{stage.popBillboard();}
                    assertEquals(1,stage.depthApplied());
                }finally{SkijaHost.endFrame();}
                var offPath=Path.of("../stage-captures/game-depth-off.png");SkijaHost.savePng(offPath,true);
                var offImage=ImageIO.read(offPath.toFile());int leftInk=0;
                for(int yy=268;yy<325;yy++)for(int xx=368;xx<400;xx++)if((offImage.getRGB(xx,yy)>>>24)>100)leftInk++;
                assertTrue(leftInk>20,"Off releases native depth and voxel masking together");
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
    @Test void nativeHalfSlabMaskLeavesTheUpperHalfOfTheSameGlyphVisible() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(800,600,1);
            presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            byte[] raw=EnvironmentShapesTest.packet();var b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putInt(40,84).putInt(44,54).putInt(48,-36).putDouble(56,100.5).putDouble(64,70.5).putDouble(72,-19.5);
            int old=((16)*33+16)*33+16,cell=(16*33+18)*33+16,n=33*33*33;
            raw[256+old]=0;raw[256+n+old]=0;raw[256+cell]=1;raw[256+n+cell]=2;
            try(var stage=new StageLyrics()) {
                stage.configure(HostSettings.parse("{\"worldLyricsOcclusion\":true}"));
                stage.environment(EnvironmentFrame.decode(raw,1001,7));
                SkijaHost.beginFrame(0);
                try {
                    stage.render(System.nanoTime(),LyricVisibilityTest.camera());
                    stage.pushBillboard(0,0,4,0,0,.04f);
                    try(var paint=new Paint().setColor(0xFFFFFFFF)){SkijaHost.canvas().drawRect(Rect.makeXYWH(-4,-4,8,8),paint);}
                    finally{stage.popBillboard();}
                }finally{SkijaHost.endFrame();}
                Path path=Path.of("../stage-captures/half-slab-lyric.png");Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
                var image=ImageIO.read(path.toFile());
                assertTrue((image.getRGB(400,293)>>>24)>200,"Upper glyph ink is above the slab");
                assertEquals(0,image.getRGB(400,307)>>>24,"Lower glyph ink is behind the slab");
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
    @Test void foregroundBlockClipsHalfAGlyphInsteadOfErasingTheWholeRow() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(800,600,1);
            presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            // Eye sits exactly on the right edge of a known cube. Its shadow
            // covers half the central glyph, while half and other glyphs remain.
            var raw=WorldFrameTest.packet(1000);var b=java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putDouble(40,101).putDouble(48,70.5).putDouble(56,-19.5);
            float[] view=new org.joml.Matrix4f().lookAt(101,70.5f,-19.5f,101,70.5f,-18.5f,0,1,0).get(new float[16]);
            for(int i=0;i<16;i++)b.putFloat(64+4*i,view[i]);
            var camera=WorldFrame.decode(raw,1001);assertNotNull(camera);
            byte[] cells=LyricVisibilityTest.environment();WorldSpeakerTest.cell(cells,0,0,2,1);
            try(var stage=new StageLyrics()) {
                var enabled=HostSettings.parse("{\"worldLyricsOcclusion\":true}");
                stage.configure(enabled);stage.environment(EnvironmentFrame.decode(cells,1001,7));
                for(boolean occlude:new boolean[]{false,true}) {
                    stage.configure(occlude?enabled:HostSettings.parse("{}"));
                    SkijaHost.beginFrame(0);
                    try {
                        stage.render(System.nanoTime(),camera);
                        stage.pushBillboard(0,0,4,0,0,.04f);
                        try(var paint=new Paint().setColor(0xFFFFFFFF)) {
                            // One solid glyph-like stroke spans the cube edge.
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(-4,-4,8,8),paint);
                        }finally{stage.popBillboard();}
                    }finally{SkijaHost.endFrame();}
                    Path path=Path.of("../stage-captures/partial-lyric-"+occlude+".png");
                    Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
                    var image=ImageIO.read(path.toFile());
                    assertTrue((image.getRGB(393,300)>>>24)>200,"Exposed half of the same glyph must stay visible");
                    if(occlude)assertEquals(0,image.getRGB(407,300)>>>24,"Only the covered half of the glyph must disappear");
                    else assertTrue((image.getRGB(407,300)>>>24)>200);
                }
                int clearInk=0;
                for(boolean occlude:new boolean[]{false,true}) {
                    stage.configure(occlude?enabled:HostSettings.parse("{}"));
                    SkijaHost.beginFrame(0);
                    try {
                        stage.pushBillboard(0,0,4,0,0,.04f);
                        try{stage.drawPixelText("MMMMMMMM",-stage.pixelWidth("MMMMMMMM")/2,-7,0xFFFFFFFF,false);}
                        finally{stage.popBillboard();}
                    }finally{SkijaHost.endFrame();}
                    Path path=Path.of("../stage-captures/partial-text-"+occlude+".png");SkijaHost.savePng(path,true);
                    var image=ImageIO.read(path.toFile());int leftInk=0,rightInk=0;
                    for(int yy=0;yy<600;yy++)for(int xx=0;xx<800;xx++)if((image.getRGB(xx,yy)>>>24)>100) {
                        if(xx<400)leftInk++;else rightInk++;
                    }
                    assertTrue(leftInk>50,"Exposed original font glyphs remain drawn");
                    if(occlude){assertEquals(0,rightInk,"Covered original font ink must be clipped");assertTrue(leftInk<clearInk);}
                    else{clearInk=leftInk+rightInk;assertTrue(rightInk>50);}
                }
                // Difference clips must be scoped to the row, not the rest of HUD.
                SkijaHost.beginFrame(0);
                try {
                    stage.pushBillboard(0,0,4,0,0,.04f);stage.popBillboard();
                    try(var paint=new Paint().setColor(0xFFFFFFFF)){SkijaHost.canvas().drawRect(Rect.makeXYWH(406,299,2,2),paint);}
                }finally{SkijaHost.endFrame();}
                Path path=Path.of("../stage-captures/partial-lyric-restored.png");SkijaHost.savePng(path,true);
                assertEquals(255,ImageIO.read(path.toFile()).getRGB(407,300)>>>24);
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
    @Test void worldBillboardIsDrawnInClearSpaceAndClippedOnlyByAKnownForegroundWall() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(800,600,1);
            presenter.resize(800,600);SkijaHost.renderTarget(presenter.framebuffer());initPlayer();
            var camera=LyricVisibilityTest.camera();assertNotNull(camera);
            try(var stage=new StageLyrics()) {
                stage.configure(HostSettings.parse("{\"worldLyricsOcclusion\":true}"));
                for(int scene=0;scene<4;scene++) {
                    byte[] raw=LyricVisibilityTest.environment();
                    if(scene==1)WorldSpeakerTest.cell(raw,0,0,2,1);
                    if(scene==3)WorldSpeakerTest.cell(raw,0,0,2,255);
                    stage.environment(scene==2?null:EnvironmentFrame.decode(raw,1001,7));
                    SkijaHost.beginFrame(0);
                    try {
                        stage.render(System.nanoTime(),camera);
                        stage.pushBillboard(0,0,4,camera.yawDeg(),camera.pitchDeg(),.04f);
                        try(var paint=new Paint().setColor(0xFFFFFFFF)) {
                            SkijaHost.canvas().drawRect(Rect.makeXYWH(-4,-4,8,8),paint);
                        }finally{stage.popBillboard();}
                    }finally{SkijaHost.endFrame();}
                    Path p=Path.of("../stage-captures/occlusion-"+scene+".png");Files.createDirectories(p.getParent());SkijaHost.savePng(p,true);
                    var image=ImageIO.read(p.toFile());int pixels=0;
                    for(int y=0;y<600;y++)for(int x=0;x<800;x++)if((image.getRGB(x,y)>>>24)>100)pixels++;
                    System.out.println("LYRIC_OCCLUSION scene="+scene+" visiblePixels="+pixels);
                    if(scene==1)assertEquals(0,pixels,"A known wall must actually clip the billboard");
                    else assertTrue(pixels>100,"Clear/pending/unknown geometry must not erase projected lyrics: "+scene);
                }
            }
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
}
