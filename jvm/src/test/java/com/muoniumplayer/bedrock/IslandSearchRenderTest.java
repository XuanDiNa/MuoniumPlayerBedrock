package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.settings.HudConfig;
import com.muoniumplayer.core.screens.ncm.*;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

class IslandSearchRenderTest {
    @Test void explicitHostEntryOwnsTransparentInputAndEscapeClosesOriginalSearch() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());
        try(var windows=OverlayWindows.create();var presenter=new LayeredPresenter(windows.handle())) {
          try {
            var client=BedrockClient.getInstance();client.bindWindow(windows.display());client.getWindow().size(1000,650,1);
            presenter.resize(1000,650);SkijaHost.renderTarget(presenter.framebuffer());MuoniumPlayerClient.getInstance().init();
            for(var category:com.muoniumplayer.core.screens.hud.HudSettingsModel.categories()) {
                assertFalse(category.title.contains("公告"));
                for(var group:category.groups)assertFalse(group.title.contains("公告"));
            }
            var count=com.muoniumplayer.core.screens.ncm.panels.NavigateBar.class.getDeclaredMethod("railSlotCount",double.class);
            count.setAccessible(true);assertEquals(4,count.invoke(null,320d));assertEquals(4,count.invoke(null,112d));
            HudConfig.islandSearchEnabled=true;
            var session=new OverlaySession(client,NCMScreen::getInstance);
            HostIslandSearch.open(session);
            var page=client.gui.screen();assertInstanceOf(IslandSearchOverlay.class,page);
            assertTrue(session.isOpen());assertTrue(page.transparentHost());
            for(int i=0;i<35;i++){BedrockOverlay.drawFrame(session,()->{});Thread.sleep(16);}
            var capture=java.nio.file.Path.of("../stage-captures/island-search.png");SkijaHost.savePng(capture,true);
            var pixels=javax.imageio.ImageIO.read(capture.toFile());
            int minX=1000,maxX=-1,minY=650,maxY=-1;
            for(int y=0;y<650;y++)for(int x=0;x<1000;x++)if((pixels.getRGB(x,y)>>>24)>80) {
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            }
            assertTrue(maxX>minX&&maxY>minY,"Search capsule must be visible");
            assertEquals(500,(minX+maxX+1)*.5,12,"Capsule must be horizontally centered, not double transformed");
            assertTrue(minY>=50&&maxY<400,"Search capsule must fit near the top of the viewport");
            var field=IslandSearchOverlay.class.getDeclaredField("searchField");field.setAccessible(true);
            page.keyEvent('x',0);
            assertEquals("x",((com.muoniumplayer.core.rendering.entities.impl.TextField)field.get(page)).getText());
            ((com.muoniumplayer.core.rendering.entities.impl.TextField)field.get(page)).setText("");
            var begin=IslandSearchModel.class.getDeclaredMethod("beginRequest");begin.setAccessible(true);
            var deliver=IslandSearchModel.class.getDeclaredMethod("deliverResults",long.class,java.util.List.class);deliver.setAccessible(true);
            var songs=new java.util.ArrayList<com.muoniumplayer.core.ncm.music.dto.Music>();
            for(int i=0;i<12;i++)songs.add(new com.muoniumplayer.core.ncm.music.dto.Music("Search fixture "+i,"","",100+i,
                java.util.List.of(new com.muoniumplayer.core.ncm.music.dto.Artist(1,"Fixture",java.util.List.of(),java.util.List.of())),
                java.util.List.of(),new com.muoniumplayer.core.ncm.music.dto.Album(1,"Fixture","",java.util.List.of()),30000,0,0,java.util.List.of()));
            deliver.invoke(null,(long)begin.invoke(null),songs);
            for(int i=0;i<15;i++){BedrockOverlay.drawFrame(session,()->{});Thread.sleep(16);}
            var rows=IslandSearchOverlay.class.getDeclaredField("resultRows");rows.setAccessible(true);
            assertEquals(12,((java.util.List<?>)rows.get(page)).size(),"Actual original search rows must attach to the widget tree");
            assertEquals(12,IslandSearchModel.resultPlaylist().musics.size());
            var panelField=IslandSearchOverlay.class.getDeclaredField("resultsPanel");panelField.setAccessible(true);
            var panel=(com.muoniumplayer.core.rendering.ui.container.ScrollPanel)panelField.get(page);
            assertTrue(panel.getWidth()>200&&panel.getHeight()>50,"Search result viewport must have real dimensions");
            var first=(com.muoniumplayer.core.rendering.ui.AbstractWidget<?>)((java.util.List<?>)rows.get(page)).getFirst();
            assertTrue(first.getWidth()>200&&first.getHeight()>1,"Result rows must occupy the visible viewport");
            SkijaHost.savePng(java.nio.file.Path.of("../stage-captures/island-search.png"),true);
            var x=IslandSearchOverlay.class.getDeclaredField("capsuleX");x.setAccessible(true);
            var y=IslandSearchOverlay.class.getDeclaredField("capsuleY");y.setAccessible(true);
            double before=first.getY();
            // GUI/layout correction is 2 at this framebuffer size; use the same
            // production Mouse -> drawHostFrame -> original ScrollPanel path.
            com.muoniumplayer.compat.lwjgl2.Mouse.position(((double)x.get(page)+100)*2,((double)y.get(page)+70)*2);
            com.muoniumplayer.compat.lwjgl2.Mouse.pushScrollNotches(-1);
            BedrockOverlay.drawFrame(session,()->{});
            assertTrue(panel.targetScrollOffset>0,"Wheel input over the original result list must scroll");
            for(int i=0;i<15;i++){BedrockOverlay.drawFrame(session,()->{});Thread.sleep(16);}
            assertTrue(first.getY()<before,"Actual result rows must move");
            page.keyEvent('\0',Keyboard.KEY_ESCAPE);
            for(int i=0;i<35&&client.gui.screen()!=null;i++){BedrockOverlay.drawFrame(session,()->{});Thread.sleep(16);}
            assertNull(client.gui.screen());assertTrue(session.finishScreenClose());
            var active=IslandSearchTransition.class.getDeclaredMethod("isActive");active.setAccessible(true);
            assertFalse((boolean)active.invoke(null));
          }finally{SkijaHost.shutdown();}
        }finally{GLFW.glfwTerminate();SessionMedia.close();}
    }
}
