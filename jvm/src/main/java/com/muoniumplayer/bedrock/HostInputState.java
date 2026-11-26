package com.muoniumplayer.bedrock;

import org.lwjgl.glfw.GLFW;
import java.util.Arrays;
/** Input ownership only; no playback, network, task or page lifetime changes. */
public final class HostInputState {
    private static boolean allowed=true,waiting,textAllowed=true;
    private static final boolean[] heldKeys=new boolean[GLFW.GLFW_KEY_LAST+1];
    private static final boolean[] heldButtons=new boolean[GLFW.GLFW_MOUSE_BUTTON_LAST+1];
    static void bind(){allowed=true;waiting=false;textAllowed=true;Arrays.fill(heldKeys,false);Arrays.fill(heldButtons,false);}
    public static boolean allowed(){return allowed;}
    record Snapshot(boolean focused,int heldKeys,int heldButtons){}
    static Snapshot snapshot(){
        int keys=0,buttons=0;for(boolean held:heldKeys)if(held)keys++;for(boolean held:heldButtons)if(held)buttons++;
        return new Snapshot(allowed,keys,buttons);
    }
    public static boolean keyAllowed(int key){return allowed&&(key<0||key>=heldKeys.length||!heldKeys[key]);}
    public static boolean buttonAllowed(int button){return allowed&&button>=0&&button<heldButtons.length&&!heldButtons[button];}
    static boolean textAllowed(){return allowed&&textAllowed;}
    static boolean keyEvent(int key,int action){
        if(action==GLFW.GLFW_RELEASE){if(key>=0&&key<heldKeys.length)heldKeys[key]=false;textAllowed=allowed;return false;}
        textAllowed=keyAllowed(key);return textAllowed;
    }
    static boolean buttonEvent(int button,int action){
        boolean accepted=buttonAllowed(button);
        if(action==GLFW.GLFW_RELEASE&&button>=0&&button<heldButtons.length)heldButtons[button]=false;
        return accepted;
    }
    public static void suspend(){allowed=false;waiting=true;com.muoniumplayer.compat.lwjgl2.Mouse.getDWheel();}
    static void update(long window,boolean focused){
        if(!focused){suspend();return;}
        // Quarantine only the controls carried across the focus handoff. One
        // logically stuck modifier/button must not lock the whole player page.
        // An inherited hold cannot start a drag, type, or trigger a shortcut.
        for(int b=0;b<heldButtons.length;b++)
            if(waiting||heldButtons[b])heldButtons[b]=GLFW.glfwGetMouseButton(window,b)==GLFW.GLFW_PRESS;
        for(int k=GLFW.GLFW_KEY_SPACE;k<heldKeys.length;k++)
            if(waiting||heldKeys[k])heldKeys[k]=GLFW.glfwGetKey(window,k)==GLFW.GLFW_PRESS;
        if(waiting)textAllowed=true;
        waiting=false;allowed=true;
    }
    private HostInputState(){}
}
