package com.muoniumplayer.bedrock;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;

/** A permanently hidden GL context and a separate, input-capable NO_API window. */
final class OverlayWindows implements AutoCloseable {
    private long render,display;
    static OverlayWindows create() {
        var pair=new OverlayWindows();
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_DECORATED,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY,GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
            pair.render=GLFW.glfwCreateWindow(16,16,"Muonium hidden renderer",0,0);
            if(pair.render==0)throw new IllegalStateException("Hidden GL context unavailable");
            GLFW.glfwMakeContextCurrent(pair.render);GL.createCapabilities();GLFW.glfwSwapInterval(0);
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API,GLFW.GLFW_NO_API);
            pair.display=GLFW.glfwCreateWindow(1280,720,"MuoniumPlayer Bedrock — upstream player",0,0);
            if(pair.display==0)throw new IllegalStateException("Layered presentation window unavailable");
            return pair;
        } catch(Throwable failure) {pair.close();throw failure;}
    }
    long render(){return render;}
    long display(){return display;}
    WinDef.HWND handle(){return new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(display)));}
    public void close() {
        if(display!=0){Callbacks.glfwFreeCallbacks(display);GLFW.glfwDestroyWindow(display);display=0;}
        if(render!=0){GLFW.glfwDestroyWindow(render);render=0;}
    }
}
