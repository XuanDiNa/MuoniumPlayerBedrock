package com.muoniumplayer.bedrock;
import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.mc.GuiButton;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.render.skija.SkijaHost;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class HostToolsRenderTest {
    @SuppressWarnings("unchecked") static GuiButton button(GuiScreen screen,int id)throws Exception{
        var field=GuiScreen.class.getDeclaredField("buttonList");field.setAccessible(true);
        return ((List<GuiButton>)field.get(screen)).stream().filter(b->b.id==id).findFirst().orElseThrow();
    }
    static void click(GuiScreen screen,int id)throws Exception {var b=button(screen,id);screen.clickEvent(b.xPosition+b.width/2,b.yPosition+b.height/2,0);}
    @Test void directButtonsRenderAndReachPagesWithoutApplyOrAutomaticOperations()throws Exception{
        org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
        assertTrue(GLFW.glfwInit());long window=0;
        try{
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(960,600,"Owned Configure button fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();var client=BedrockClient.getInstance();client.bindWindow(window);
            client.getWindow().size(960,600,1);MuoniumPlayerClient.getInstance().init();
            var decoder=com.muoniumplayer.core.ncm.music.CloudMusic.player;var queue=com.muoniumplayer.core.ncm.music.CloudMusic.playList;
            var settings=new AtomicInteger();HostToolsScreen.ordinarySettings(()->{settings.incrementAndGet();client.gui.setScreen(null);});
            for(int action:new int[]{0,1,2,3,5}){
                var page=HostToolsScreen.entry();client.gui.setScreen(page);
                SkijaHost.beginFrame(0);try{page.drawHostFrame(0);}finally{SkijaHost.endFrame();}
                assertSame(page,client.gui.screen());assertEquals(0,settings.get());
                assertSame(decoder,com.muoniumplayer.core.ncm.music.CloudMusic.player);assertSame(queue,com.muoniumplayer.core.ncm.music.CloudMusic.playList);
                if(action==0){var output=Path.of("../stage-captures/configure-buttons.png");Files.createDirectories(output.getParent());SkijaHost.savePng(output,true);}
                click(page,action);
                if(action==0)assertInstanceOf(com.muoniumplayer.core.screens.ncm.NCMScreen.class,client.gui.screen());
                if(action==1)assertInstanceOf(HostHudEditor.class,client.gui.screen());
                if(action==2)assertInstanceOf(com.muoniumplayer.core.screens.hud.GuiPresetSettings.class,client.gui.screen());
                if(action==3)assertInstanceOf(HostMiniPlayer.class,client.gui.screen());
                if(action==5){assertEquals(1,settings.get());assertNull(client.gui.screen());}
            }
            var page=HostToolsScreen.entry();client.gui.setScreen(page);page.keyEvent('\0',Keyboard.KEY_ESCAPE);assertNull(client.gui.screen());
            var returns=new AtomicInteger();
            HostBackNavigation.bind(returns::incrementAndGet);
            var tools=new HostToolsScreen();client.gui.setScreen(tools);
            SkijaHost.beginFrame(0);try{tools.drawHostFrame(0);}finally{SkijaHost.endFrame();}
            assertThrows(java.util.NoSuchElementException.class,()->button(tools,7));assertEquals(0,returns.get());
            tools.keyEvent('\0',Keyboard.KEY_ESCAPE);assertEquals(0,returns.get(),"Child Esc first returns to the player");
            assertInstanceOf(com.muoniumplayer.core.screens.ncm.NCMScreen.class,client.gui.screen());
            var mini=new HostMiniPlayer();client.gui.setScreen(mini);mini.keyEvent('\0',Keyboard.KEY_ESCAPE);
            assertEquals(1,returns.get());assertNull(client.gui.screen());
        }finally{HostBackNavigation.clear();HostToolsScreen.ordinarySettings(null);BedrockClient.getInstance().gui.setScreen(null);SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();}
    }
}
