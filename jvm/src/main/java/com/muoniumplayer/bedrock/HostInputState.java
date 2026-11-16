package com.muoniumplayer.bedrock;

import org.lwjgl.glfw.GLFW;
/** Input ownership only; no playback, network, task or page lifetime changes. */
public final class HostInputState {
    private static boolean allowed=true,waiting;
    static void bind(){allowed=true;waiting=false;}
    public static boolean allowed(){return allowed;}
    public static void suspend(){allowed=false;waiting=true;com.muoniumplayer.compat.lwjgl2.Mouse.getDWheel();}
    static void update(long window,boolean focused){
        if(!focused){suspend();return;}
        if(waiting){
            for(int b=0;b<=GLFW.GLFW_MOUSE_BUTTON_LAST;b++)if(GLFW.glfwGetMouseButton(window,b)==GLFW.GLFW_PRESS)return;
            for(int k=GLFW.GLFW_KEY_SPACE;k<=GLFW.GLFW_KEY_LAST;k++)if(GLFW.glfwGetKey(window,k)==GLFW.GLFW_PRESS)return;
        }
        waiting=false;allowed=true;
    }
    private HostInputState(){}
}
