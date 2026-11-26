package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.*;

import static org.junit.jupiter.api.Assertions.*;

/** HWND messages target only our hidden fixture. No global input or focus changes. */
class OverlayFocusInputTest {
    private static final class Page extends GuiScreen {
        int clicks, escapes, characters;
        @Override protected void mouseClicked(int x,int y,int button){clicks++;}
        @Override protected void keyTyped(char ch,int key){if(key==1)escapes++;if(ch!='\0')characters++;}
    }

    @Test void returningWithHeldModifierMustNotLockEveryOtherControl() throws Exception {
        assertTrue(GLFW.glfwInit());long window=0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API,GLFW.GLFW_NO_API);
            window=GLFW.glfwCreateWindow(320,200,"Owned focus recovery fixture",0,0);
            assertNotEquals(0,window);
            var hwnd=new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
            var client=BedrockClient.getInstance();client.bindWindow(window);client.getWindow().size(320,200,1);
            var page=new Page();client.gui.setScreen(page);
            var install=BedrockOverlay.class.getDeclaredMethod("installInput",long.class);
            install.setAccessible(true);install.invoke(null,window);
            // A modifier remains logically down across the browser handoff.
            // Use GLFW's real input state, not a mock of the production gate.
            HostInputState.update(window,false);
            post(hwnd,0x100,0x10,0x002A0001L); // WM_KEYDOWN VK_SHIFT / left scan code
            GLFW.glfwPollEvents();
            assertEquals(GLFW.GLFW_PRESS,GLFW.glfwGetKey(window,GLFW.GLFW_KEY_LEFT_SHIFT));
            HostInputState.update(window,true);
            post(hwnd,0x201,1,30|(40<<16));post(hwnd,0x202,0,30|(40<<16));
            post(hwnd,0x100,27,1);post(hwnd,0x101,27,0xC0000001L);
            post(hwnd,0x102,'a',1);
            GLFW.glfwPollEvents();
            assertEquals(1,page.clicks,"A held modifier after focus return must not disable mouse clicks");
            assertEquals(1,page.escapes,"Escape must remain usable while a different key is held");
            assertEquals(1,page.characters,"Unrelated text input must recover without releasing every key");
            assertFalse(Keyboard.isKeyDown(Keyboard.KEY_LSHIFT),"Inherited modifier cannot affect shortcuts/polling");
            post(hwnd,0x100,0x10,0x402A0001L);post(hwnd,0x102,'X',1);GLFW.glfwPollEvents();
            assertEquals(1,page.characters,"An inherited key repeat must not type into the restored page");
            post(hwnd,0x101,0x10,0xC02A0001L);GLFW.glfwPollEvents();
            post(hwnd,0x100,0x10,0x002A0001L);GLFW.glfwPollEvents();
            assertTrue(Keyboard.isKeyDown(Keyboard.KEY_LSHIFT),"Release and a new press must rearm within the same event batch");
            post(hwnd,0x101,0x10,0xC02A0001L);GLFW.glfwPollEvents();

            // A carried mouse hold is also local to that button: it cannot
            // start a seek/drag or block Escape and the other mouse button.
            HostInputState.update(window,false);
            post(hwnd,0x201,1,30|(40<<16));GLFW.glfwPollEvents();HostInputState.update(window,true);
            assertFalse(Mouse.isButtonDown(0),"Focus handoff must not start a drag from a carried hold");
            post(hwnd,0x204,2,30|(40<<16));post(hwnd,0x205,0,30|(40<<16));
            post(hwnd,0x100,27,1);post(hwnd,0x101,27,0xC0000001L);GLFW.glfwPollEvents();
            assertEquals(2,page.clicks,"One held mouse button cannot disable the other button");
            assertEquals(2,page.escapes,"Escape must work while a carried mouse button remains held");
            post(hwnd,0x202,0,30|(40<<16));post(hwnd,0x201,1,30|(40<<16));
            post(hwnd,0x202,0,30|(40<<16));GLFW.glfwPollEvents();
            assertEquals(3,page.clicks,"A released carried button must accept the next click without reopening");

            HostInputState.update(window,false);
            post(hwnd,0x201,1,0);post(hwnd,0x202,0,0);
            post(hwnd,0x100,27,1);post(hwnd,0x101,27,0xC0000001L);post(hwnd,0x102,'z',1);
            post(hwnd,0x20A,120L<<16,0);GLFW.glfwPollEvents();
            assertEquals(3,page.clicks);assertEquals(2,page.escapes);assertEquals(1,page.characters);
            assertEquals(0,Mouse.getDWheel(),"Unfocused scroll must not be replayed when focus returns");
            HostInputState.update(window,true);assertTrue(HostInputState.allowed());
        } finally {
            BedrockClient.getInstance().gui.setScreen(null);
            if(window!=0){Callbacks.glfwFreeCallbacks(window);GLFW.glfwDestroyWindow(window);}
            HostInputState.bind();GLFW.glfwTerminate();
        }
    }
    private static void post(WinDef.HWND hwnd,int message,long key,long data){
        User32.INSTANCE.PostMessage(hwnd,message,new WinDef.WPARAM(key),new WinDef.LPARAM(data));
    }
}
