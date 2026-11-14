package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.ncm.music.FoliaModelNotice;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheManagerRenderTest {
    @TempDir Path root;
    static List<AbstractWidget<?>> all(AbstractWidget<?> root){var out=new ArrayList<AbstractWidget<?>>();out.add(root);for(var child:root.getChildren())out.addAll(all(child));return out;}
    static void frame(CacheManagerScreen screen)throws Exception {
        BedrockClient.getInstance().drainTasks();SkijaHost.beginFrame();try{screen.drawHostFrame(0);}finally{SkijaHost.endFrame();}Thread.sleep(16);
    }
    static void settle(CacheManagerScreen screen)throws Exception{for(int i=0;i<35;i++)frame(screen);}
    static AbstractWidget<?> button(CacheManagerScreen s,String text){
        return all(s.panel).stream().filter(w->w instanceof LabelWidget l&&l.getLabel().equals(text))
            .map(AbstractWidget::getParent).filter(w->w instanceof RoundedButtonWidget).findFirst().orElseThrow();
    }
    static void activate(CacheManagerScreen s,AbstractWidget<?> b){
        double x=b.getX()+b.getWidth()/2,y=b.getY()+b.getHeight()/2;
        SkijaHost.beginFrame();try{s.panel.renderWidget(x,y,0);}finally{SkijaHost.endFrame();}
        s.panel.onMouseClickReceived(x,y,0);
    }
    static void reveal(CacheManagerScreen s,AbstractWidget<?> b)throws Exception {
        double top=s.panel.content.getY(),bottom=top+s.panel.content.getHeight();
        if(b.getY()<top||b.getY()+b.getHeight()>bottom){
            double center=b.getY()+b.getHeight()/2;
            s.panel.content.scrollToOffsetImmediately(s.panel.content.actualScrollOffset+center-(top+bottom)/2);settle(s);
        }
        assertTrue(b.getY()>=top&&b.getY()+b.getHeight()<=bottom,"Click target must be inside scroller");
    }
    @Test void actualSheetScrollsEditsDeletesAndReturnsAcrossWindowSizes()throws Exception {
        System.setProperty("muonium.bedrock.offlineTest","true");assertTrue(GLFW.glfwInit());long window=0;
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1280,720,"Offline cache management",0,0);assertNotEquals(0,window);GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);MuoniumPlayerClient.getInstance().init();FoliaModelNotice.markShownThisSession();
            var policy=new PlaybackFiles(root);policy.setRetain(true);Path audio=root.resolve("source.wav");Files.write(audio,PlaybackFilesTest.wav());
            for(int i=0;i<18;i++){policy.rememberLabel("s"+i,"测试曲目 "+i,"歌手");policy.retain("s"+i,audio.toFile());}
            for(int[] size:new int[][]{{1280,720},{640,480}}){
                GLFW.glfwSetWindowSize(window,size[0],size[1]);client.getWindow().size(size[0],size[1],1);
                var parent=new GuiScreen(){};client.gui.setScreen(parent);
                var screen=new CacheManagerScreen(parent,policy);client.gui.setScreen(screen);settle(screen);
                for(String text:List.of("选择存储文件夹","打开文件夹","恢复默认位置","保存容量","刷新列表")){
                    var b=button(screen,text);assertTrue(b.getX()>=0&&b.getX()+b.getWidth()<=screen.panel.getWidth(),text+" must stay inside viewport");
                }
                double gib=size[0]==1280?2.75:3.5;
                screen.panel.capacity.getTextField().setText(Double.toString(gib));var save=button(screen,"保存容量");reveal(screen,save);activate(screen,save);settle(screen);
                assertSame(screen,client.gui.screen());assertEquals((long)(gib*1073741824L),policy.limitBytes());
                screen.panel.content.scrollToOffsetImmediately(0);settle(screen);
                double old=screen.panel.content.actualScrollOffset;screen.panel.content.forwardWheel(-6);settle(screen);
                assertTrue(screen.panel.content.actualScrollOffset>old,"Actual original scroller must move");
                var first=screen.panel.items.getFirst();var remove=button(screen,"删除");
                reveal(screen,remove);activate(screen,remove);settle(screen);
                assertFalse(policy.entries().stream().anyMatch(e->e.id().equals(first.id())));assertEquals(screen.panel.items.size(),policy.entries().size());
                Path png=Path.of("../stage-captures/cache-"+size[0]+".png");Files.createDirectories(png.getParent());
                SkijaHost.beginFrame();try{screen.drawHostFrame(0);SkijaHost.savePng(png);}finally{SkijaHost.endFrame();}
                screen.keyEvent('\0',Keyboard.KEY_ESCAPE);assertSame(parent,client.gui.screen());
            }
        }finally{SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();}
    }
}
