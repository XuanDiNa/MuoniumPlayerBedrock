package com.muoniumplayer.bedrock;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Win32 styles on an owned disposable GLFW window, without Minecraft. */
class OverlayWindowModeTest {
    @Test void ulwModeKeepsLayeredStyleWithoutConstantAlphaAndChangesOnlyAtTransitions() {
        assertTrue(GLFW.glfwInit());long window=0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API,GLFW.GLFW_NO_API);
            window=GLFW.glfwCreateWindow(320,200,"Disposable Win32 overlay fixture",0,0);
            assertNotEquals(0,window);
            var mode=new OverlayWindowMode(window);
            for(int cycle=0;cycle<3;cycle++) {
                assertTrue(mode.update(false));
                var passive=mode.state();
                System.out.println("WINDOW_MODE passive="+passive);
                assertEquals(0x80020,passive.style()&0x80020,"Passive window must be layered and click-through");
                assertFalse(passive.attributes(),"ULW must never be mixed with SetLayeredWindowAttributes");
                for(int frame=0;frame<50;frame++)assertFalse(mode.update(false),"Unchanged mode must not mutate Win32 styles");
                assertTrue(mode.update(true));
                var active=mode.state();
                assertEquals(0x80000,active.style()&0x80020,"Interactive ULW must stay layered and receive clicks");
                assertFalse(active.attributes(),"Interactive ULW must not acquire a constant alpha");
                assertEquals(GLFW.GLFW_NO_API,GLFW.glfwGetWindowAttrib(window,GLFW.GLFW_CLIENT_API));
                assertFalse(mode.update(true));
            }
        } finally {
            if(window!=0)GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
