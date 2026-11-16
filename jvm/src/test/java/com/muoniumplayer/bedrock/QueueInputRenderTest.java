package com.muoniumplayer.bedrock;
import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.*;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.screens.ncm.NCMScreen;
import com.muoniumplayer.core.screens.ncm.panels.*;
import com.muoniumplayer.render.skija.SkijaHost;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueueInputRenderTest {
    static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Music song(int id){return new com.google.gson.Gson().fromJson("{\"id\":"+id+",\"name\":\"Offline "+id+"\",\"dt\":180000}",Music.class);}
    static void frame(Runnable render){SkijaHost.beginFrame();try{render.run();}finally{SkijaHost.endFrame();}}
    @Test void queueButtonsUsePressCoordinatesRatherThanPreviousRenderedHover()throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());long window=0;
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Owned queue fixture",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var c=BedrockClient.getInstance();c.bindWindow(window);c.getWindow().size(1280,720,1);MuoniumPlayerClient.getInstance().init();
            var screen=NCMScreen.getInstance();c.gui.setScreen(screen);
            Music current=song(111);CloudMusic.playList=new java.util.concurrent.CopyOnWriteArrayList<>(List.of(current));
            CloudMusic.currentlyPlaying=current;CloudMusic.curIdx=0;
            var playlist=new PlayList(700,"Owned","",0,0,null,"",0);playlist.musicsQueried=true;playlist.musicsLoaded=true;
            playlist.musics=new java.util.concurrent.CopyOnWriteArrayList<>(List.of(song(222)));
            var row=new MusicWidget(playlist.musics.getFirst(),playlist,0);row.setBounds(10,10,450,30);
            for(int i=0;i<4;i++)frame(()->row.renderWidget(0,0,0));
            // Child 0/0 was never hovered. Find the original play-next icon by tooltip.
            AbstractWidget<?> add=null;
            for(var child:row.getChildren())if(child instanceof com.muoniumplayer.core.rendering.ui.widgets.IconWidget icon
                && "\uE309".equals(icon.getIcon()))add=child;
            assertNotNull(add,"Original music row exposes play-next action");
            row.onMouseClickReceived(add.getX()+add.getWidth()/2,add.getY()+add.getHeight()/2,0);
            assertEquals(1,CloudMusic.getQueuedNextSongs().size(),"Press after cursor move in same poll must add, without a hover render");
            for(int i=0;i<2;i++)CloudMusic.playNext(song(300+i));
            // Requeue the same source-row song: move its existing pending entry,
            // rather than inserting a second copy sharing one ledger identity.
            row.onMouseClickReceived(add.getX()+add.getWidth()/2,add.getY()+add.getHeight()/2,0);
            assertEquals(List.of(300L,301L,222L),CloudMusic.getQueuedNextSongs().stream().map(Music::getId).toList(),
                    "Requeue moves the existing song to the end of the priority segment");
            var q=new PlayQueuePanel();q.onInit();q.setBounds(100,20,500,300);q.setOpen(true);
            for(int i=0;i<70;i++){frame(()->q.renderWidget(0,0,0));Thread.sleep(16);}
            var list=(AbstractWidget<?>)field(q,"list");assertEquals(3,list.getChildren().size());
            var first=list.getChildren().getFirst();
            AbstractWidget<?> remove=first.getChildren().stream().filter(w->w.getClass().getSimpleName().equals("RoundedRectWidget")).findFirst().orElseThrow();
            assertTrue(q.consumeClick(remove.getX()+remove.getWidth()/2,remove.getY()+remove.getHeight()/2,0));
            assertEquals(2,CloudMusic.getQueuedNextSongs().size(),"Remove must not require a previous hover frame");
            CloudMusic.clearQueuedNextSongs();for(int i=0;i<4;i++)frame(()->q.renderWidget(0,0,0));
            CloudMusic.playNext(song(999));for(int i=0;i<15;i++){frame(()->q.renderWidget(0,0,0));Thread.sleep(16);}
            assertFalse(((AbstractWidget<?>)field(q,"drawer")).isHidden());
            assertEquals(1,((List<?>)field(q,"rows")).size(),"Open empty drawer must populate when a song is added");
            var drawer=(AbstractWidget<?>)field(q,"drawer");
            var clear=drawer.getChildren().get(3);
            assertFalse(clear.isHidden(),"Clear control must return after an empty queue is refilled");
            assertTrue(q.consumeClick(clear.getX()+clear.getWidth()/2,clear.getY()+clear.getHeight()/2,0));
            assertTrue(CloudMusic.getQueuedNextSongs().isEmpty(),"Visible clear button must clear the actual queue");
            for(int i=0;i<16;i++)CloudMusic.playNext(song(500+i));
            for(int i=0;i<10;i++)frame(()->q.renderWidget(0,0,0));
            assertEquals(16,((List<?>)field(q,"rows")).size());
            assertTrue(CloudMusic.moveQueuedNext(0,3));
            assertEquals(500,CloudMusic.getQueuedNextSongs().get(3).getId());
            CloudMusic.playList.add(song(700));CloudMusic.playList.add(song(701));
            CloudMusic.playNext(song(700));
            assertEquals(18,CloudMusic.getQueuedNextSongs().size(),"Queueing an existing playlist track moves it without making a copy");
            assertEquals(700,CloudMusic.getQueuedNextSongs().get(16).getId());
            assertEquals(701,CloudMusic.getQueuedNextSongs().get(17).getId(),"Ordinary playlist tail remains after explicit priority entries");
            CloudMusic.playNext(song(500));
            assertEquals(18,CloudMusic.getQueuedNextSongs().size());
            assertEquals(500,CloudMusic.getQueuedNextSongs().get(16).getId());
            assertEquals(1,CloudMusic.getQueuedNextSongs().stream().filter(s->s.getId()==500).count());
            assertEquals(current,CloudMusic.currentlyPlaying,"Queue edits must not replace the current song");
            // Alt-Tab must cancel the original drawer's independent drag,
            // rather than presenting a release/room reorder when focus returns.
            var queueField=NCMScreen.class.getDeclaredField("playQueuePanel");queueField.setAccessible(true);queueField.set(screen,q);
            var dragging=field(q,"rows");var dragRow=((List<?>)dragging).getFirst();
            var begin=Arrays.stream(PlayQueuePanel.class.getDeclaredMethods()).filter(m->m.getName().equals("beginDrag")).findFirst().orElseThrow();
            begin.setAccessible(true);begin.invoke(q,dragRow,0d);assertNotNull(field(q,"draggingRow"));
            var pending=NCMScreen.class.getDeclaredField("pendingClickActive");pending.setAccessible(true);pending.setBoolean(screen,true);
            screen.suspendHostInput();
            assertNull(field(q,"draggingRow"),"Focus loss must cancel the queue drag before its next polling frame");
            assertFalse(pending.getBoolean(screen),"Focus loss cannot release a deferred action");
            screen.openAnnouncements();
            assertEquals("HostToolsScreen",c.gui.screen().getClass().getSimpleName(),"The player toolbar must expose the migrated Workspace actions");
            assertEquals(current,CloudMusic.currentlyPlaying,"Opening tools cannot change playback");
            assertEquals(18,CloudMusic.getQueuedNextSongs().size(),"Opening tools cannot change the queue");
            var tools=c.gui.screen();
            for(int i=0;i<20;i++){frame(()->tools.drawScreen(0,0,0));Thread.sleep(16);}
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("../stage-captures"));
            SkijaHost.savePng(java.nio.file.Path.of("../stage-captures/modmenube-player-tools.png"),true);
            var toolsImage=javax.imageio.ImageIO.read(java.nio.file.Path.of("../stage-captures/modmenube-player-tools.png").toFile());
            long textPixels=0;
            for(int yy=0;yy<toolsImage.getHeight();yy++)for(int xx=0;xx<toolsImage.getWidth();xx++){
                int pixel=toolsImage.getRGB(xx,yy);
                if((pixel&0xFF0000)>0xA00000&&(pixel&0xFF00)>0xA000&&(pixel&255)>160)textPixels++;
            }
            assertTrue(textPixels>100,"Tool labels must render readable glyphs, not blank button rectangles");
            var buttonsField=com.muoniumplayer.compat.mc.GuiScreen.class.getDeclaredField("buttonList");buttonsField.setAccessible(true);
            var buttons=(List<?>)buttonsField.get(tools);assertEquals(7,buttons.size(),"All old Workspace operations need an explicit local entry");
            var hud=(com.muoniumplayer.compat.mc.GuiButton)buttons.stream().filter(b->((com.muoniumplayer.compat.mc.GuiButton)b).displayString.contains("HUD")).findFirst().orElseThrow();tools.clickEvent(hud.xPosition+2,hud.yPosition+2,0);
            assertInstanceOf(HostHudEditor.class,c.gui.screen(),"HUD editor must be reachable without assigning a default hotkey");
        }finally{CloudMusic.clearQueuedNextSongs();SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();}
    }
}
