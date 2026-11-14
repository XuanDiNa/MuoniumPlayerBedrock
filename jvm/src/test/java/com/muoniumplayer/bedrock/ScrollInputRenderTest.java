package com.muoniumplayer.bedrock;

import com.muoniumplayer.MuoniumPlayerClient;
import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.core.rendering.ui.AbstractWidget;
import com.muoniumplayer.core.rendering.ui.container.ScrollPanel;
import com.muoniumplayer.render.skija.SkijaHost;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import static org.junit.jupiter.api.Assertions.*;

class ScrollInputRenderTest {
    static final class Row extends AbstractWidget<Row> { public void onRender(double x, double y) {} }
    static final class ListScreen extends GuiScreen {
        final ScrollPanel list = new ScrollPanel();
        ListScreen() {
            list.setBounds(20,20,100,90);
            for (int i=0;i<25;i++) { var row=new Row();row.setBounds(0,0,100,26);list.addChild(row); }
        }
        @Override public void drawScreen(int x,int y,float partial) { list.renderWidget(x,y,com.muoniumplayer.compat.lwjgl2.Mouse.getDWheel()); }
    }
    @Test void windowsWheelReachesOriginalListAtEveryHostScale() throws Exception {
        System.setProperty("muonium.bedrock.offlineTest", "true");
        assertTrue(GLFW.glfwInit()); long window=0;
        try {
            GLFW.glfwDefaultWindowHints(); GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS,8);
            window=GLFW.glfwCreateWindow(640,480,"Disposable wheel fixture",0,0);assertNotEquals(0,window);
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            var client=BedrockClient.getInstance();client.bindWindow(window);MuoniumPlayerClient.getInstance().init();
            var install=BedrockOverlay.class.getDeclaredMethod("installInput",long.class);install.setAccessible(true);install.invoke(null,window);
            var hwnd=new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
            for(double scale:new double[]{1,1.5,2,3}) {
                client.getWindow().size(640,480,scale);
                var screen=new ListScreen();client.gui.setScreen(screen);
                frame(screen);
                wheel(hwnd,scale,70,50,-120);frame(screen);
                assertTrue(screen.list.targetScrollOffset>0,"Down wheel must scroll original list at scale "+scale);
                for(int i=0;i<18;i++){Thread.sleep(16);frame(screen);}
                assertTrue(screen.list.actualScrollOffset>10,"Received wheel must move visible rows, not only an internal target, at scale "+scale);
                assertTrue(screen.list.getChildren().getFirst().getY()<screen.list.getY()-10,
                        "Original list row geometry must actually move upward");
                wheel(hwnd,scale,70,50,120);frame(screen);
                assertEquals(0,screen.list.targetScrollOffset,.01,"Up wheel must return to top");
                for(int i=0;i<18;i++){Thread.sleep(16);frame(screen);}
                assertEquals(0,screen.list.actualScrollOffset,.25,"Visible rows must return to the top");
                wheel(hwnd,scale,180,130,-120);frame(screen);
                assertEquals(0,screen.list.targetScrollOffset,.01,"Outside wheel must not scroll list");
            }
        } finally {
            SkijaHost.shutdown();
            if(window!=0) {org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);GLFW.glfwDestroyWindow(window);}
            GLFW.glfwTerminate();SessionMedia.close();
        }
    }
    static void frame(GuiScreen screen) { SkijaHost.beginFrame();try{screen.drawHostFrame(0);}finally{SkijaHost.endFrame();} }
    static void wheel(WinDef.HWND hwnd,double scale,int x,int y,int delta) {
        int px=(int)(x*scale),py=(int)(y*scale);
        User32.INSTANCE.PostMessage(hwnd,0x0200,new WinDef.WPARAM(0),new WinDef.LPARAM((py<<16)|(px&65535)));
        User32.INSTANCE.PostMessage(hwnd,0x020A,new WinDef.WPARAM((long)(delta&65535)<<16),new WinDef.LPARAM(0));
        GLFW.glfwPollEvents();
    }
}
