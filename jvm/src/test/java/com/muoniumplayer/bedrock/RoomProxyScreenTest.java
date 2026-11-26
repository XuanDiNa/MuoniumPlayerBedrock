package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.render.skija.SkijaHost;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;

class RoomProxyScreenTest {
    @TempDir Path root;
    @Test void explicitButtonsSaveWithoutConnectingAndEscFocusReturnPreserveWork() throws Exception {
        assertTrue(GLFW.glfwInit());long window=0;
        var client=BedrockClient.getInstance();
        try {
            GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,0);GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(1100,720,"Owned room proxy UI fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();client.bindWindow(window);client.getWindow().size(1100,720,1);MuoniumPlayerClient.getInstance().init();
            var parent=new GuiScreen(){};var policy=new RoomProxySettings(root);var screen=new RoomProxyScreen(parent,policy);client.gui.setScreen(screen);
            await(()->screen.panel.loaded);render(screen);
            assertFalse(Files.exists(root.resolve("config")));assertFalse(RoomTunnel.busy());assertFalse(RoomTunnel.ready());
            var originalQueue=com.muoniumplayer.core.ncm.music.CloudMusic.playList;
            click(screen,screen.panel.modes.get(RoomProxySettings.Mode.SOCKS5));render(screen);
            assertEquals(RoomProxySettings.Mode.SOCKS5,screen.panel.mode);
            screen.panel.host.setText("127.0.0.1");screen.panel.port.setText("1080");screen.panel.host.setFocused(true);
            screen.suspendHostInput();assertFalse(screen.panel.host.isFocused());assertSame(screen,client.gui.screen());
            click(screen,screen.panel.save);screen.keyEvent('\0',Keyboard.KEY_ESCAPE);assertSame(parent,client.gui.screen());
            await(()->!screen.panel.busy);
            assertEquals(RoomProxySettings.Mode.SOCKS5,new RoomProxySettings(root).get().mode());
            assertSame(originalQueue,com.muoniumplayer.core.ncm.music.CloudMusic.playList);assertFalse(RoomTunnel.busy());
            var reopened=new RoomProxyScreen(parent,new RoomProxySettings(root));client.gui.setScreen(reopened);await(()->reopened.panel.loaded);render(reopened);
            assertEquals("1080",reopened.panel.port.getText());
            var path=Path.of("../stage-captures/room-proxy-settings.png");Files.createDirectories(path.getParent());SkijaHost.savePng(path,true);
            // Short viewports retain scrolling, input and the return action.
            client.getWindow().size(800,420,1);reopened.resize(800,420);render(reopened);
            double old=reopened.panel.content.actualScrollOffset;reopened.panel.content.forwardWheel(-8);render(reopened);
            assertTrue(reopened.panel.content.actualScrollOffset>old,"Short proxy pages must really scroll");
            var scroll=reopened.panel.content;var save=reopened.panel.save;
            scroll.scrollToOffsetImmediately(scroll.actualScrollOffset+save.getY()+save.getHeight()/2-scroll.getY()-scroll.getHeight()/2);render(reopened);
            assertTrue(save.getY()>=scroll.getY()&&save.getY()+save.getHeight()<=scroll.getY()+scroll.getHeight(),"Save remains reachable inside the scroll viewport");
            reopened.keyEvent('\0',Keyboard.KEY_ESCAPE);assertSame(parent,client.gui.screen());
        } finally {client.gui.setScreen(null);SkijaHost.shutdown();if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();SessionMedia.close();}
    }
    private static void render(RoomProxyScreen screen) throws Exception {
        for(int i=0;i<25;i++){SkijaHost.beginFrame();try{screen.drawScreen(-1,-1,0);}finally{SkijaHost.endFrame();}Thread.sleep(8);}
    }
    private static void click(RoomProxyScreen screen,AbstractWidget<?> widget){
        double x=widget.getX()+widget.getWidth()/2,y=widget.getY()+widget.getHeight()/2;
        SkijaHost.beginFrame();try{screen.panel.renderWidget(x,y,0);}finally{SkijaHost.endFrame();}
        screen.panel.onMouseClickReceived(x,y,0);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+3_000_000_000L;
        while(!condition.getAsBoolean()&&System.nanoTime()<end){BedrockClient.getInstance().drainTasks();Thread.sleep(10);}assertTrue(condition.getAsBoolean());
    }
}
