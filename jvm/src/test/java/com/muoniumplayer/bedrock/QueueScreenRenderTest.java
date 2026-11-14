package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.*;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.rendering.PlayerQueueIcons;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.IconWidget;
import com.muoniumplayer.core.screens.ncm.*;
import com.muoniumplayer.core.screens.ncm.panels.*;
import com.muoniumplayer.core.settings.TouchSettings;
import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real original screen -> modal routing -> queue drawer, through host GLFW input. */
class QueueScreenRenderTest {
    static Object field(Object o,String key)throws Exception {var f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    static AbstractWidget<?> icon(AbstractWidget<?> root,String glyph) {
        if(root instanceof IconWidget i && glyph.equals(i.getIcon()))return root;
        for(var child:root.getChildren()){var found=icon(child,glyph);if(found!=null)return found;}
        return null;
    }
    static void frame(NCMScreen screen) {SkijaHost.beginFrame();try{screen.drawHostFrame(0);}finally{SkijaHost.endFrame();}}
    static void settle(NCMScreen screen)throws Exception {for(int i=0;i<45;i++){frame(screen);Thread.sleep(16);}}
    static void click(NCMScreen screen,WinDef.HWND hwnd,AbstractWidget<?> target,double scale)throws Exception {
        RenderSystem.refreshResolution();WindowAdaptiveScale.begin();
        double cx=WindowAdaptiveScale.correctionX(),cy=WindowAdaptiveScale.correctionY();WindowAdaptiveScale.end();
        int x=(int)Math.round((target.getX()+target.getWidth()/2)*cx*scale);
        int y=(int)Math.round((target.getY()+target.getHeight()/2)*cy*scale);
        var point=new WinDef.LPARAM((y<<16)|(x&65535));
        User32.INSTANCE.PostMessage(hwnd,0x0200,new WinDef.WPARAM(0),point);
        User32.INSTANCE.PostMessage(hwnd,0x0201,new WinDef.WPARAM(1),point);
        GLFW.glfwPollEvents();frame(screen);
        User32.INSTANCE.PostMessage(hwnd,0x0202,new WinDef.WPARAM(0),point);
        GLFW.glfwPollEvents();frame(screen);
    }
    @Test void playlistDrawerRemovesOnlyClickedRowAcrossHostScaleAndTouchModes()throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());long window=0;
        boolean oldTouch=TouchSettings.ENABLED;
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Offline full queue screen",0,0);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);MuoniumPlayerClient.getInstance().init();FoliaModelNotice.markShownThisSession();
            var install=BedrockOverlay.class.getDeclaredMethod("installInput",long.class);install.setAccessible(true);install.invoke(null,window);
            var hwnd=new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
            for(double scale:new double[]{1,2})for(boolean touch:new boolean[]{false,true}) {
                TouchSettings.ENABLED=touch;client.getWindow().size(1280,720,scale);
                CloudMusic.clearQueuedNextSongs();
                var source=new PlayList(700,"Offline fixture","",0,0,null,"",0);source.musicsQueried=true;source.musicsLoaded=true;
                source.musics=new java.util.concurrent.CopyOnWriteArrayList<>(List.of(QueueInputRenderTest.song(10),QueueInputRenderTest.song(20),QueueInputRenderTest.song(30),QueueInputRenderTest.song(40)));
                CloudMusic.playList=new java.util.concurrent.CopyOnWriteArrayList<>(source.musics);CloudMusic.currentlyPlaying=CloudMusic.playList.getFirst();CloudMusic.curIdx=0;
                var screen=NCMScreen.getInstance();client.gui.setScreen(screen);screen.setCurrentPanel(new PlaylistPanel(source));settle(screen);
                var controls=(AbstractWidget<?>)field(screen,"controlsBar");var button=icon(controls,PlayerQueueIcons.PLAY_QUEUE);assertNotNull(button);
                var queue=(PlayQueuePanel)field(screen,"playQueuePanel");if(queue.isOpen()){queue.setOpen(false);settle(screen);}
                click(screen,hwnd,button,scale);settle(screen);assertTrue(queue.isOpen(),"Bottom button through full host input");
                var list=(AbstractWidget<?>)field(queue,"list");assertEquals(3,((List<?>)field(queue,"rows")).size(),"Playlist tail must populate full screen drawer");
                // Child insertion order is deliberately retained for animation;
                // select the first visible queue row, not the oldest child.
                var first=(AbstractWidget<?>)((List<?>)field(queue,"rows")).getFirst();var remove=first.getChildren().stream().filter(w->w.getClass().getSimpleName().equals("RoundedRectWidget")).findFirst().orElseThrow();
                click(screen,hwnd,remove,scale);settle(screen);
                assertEquals(List.of(30L,40L),CloudMusic.getQueuedNextSongs().stream().map(Music::getId).toList());
                assertEquals(2,((List<?>)field(queue,"rows")).size());assertEquals(4,source.musics.size());assertEquals(10,CloudMusic.currentlyPlaying.getId());
            }
        } finally {TouchSettings.ENABLED=oldTouch;CloudMusic.clearQueuedNextSongs();SkijaHost.shutdown();if(window!=0){Callbacks.glfwFreeCallbacks(window);GLFW.glfwDestroyWindow(window);}GLFW.glfwTerminate();SessionMedia.close();}
    }
}


