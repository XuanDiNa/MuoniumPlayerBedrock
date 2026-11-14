package com.muoniumplayer.bedrock;

import com.sun.jna.Pointer;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Own-window input/composition boundary; never changes the game window. */
final class OverlayWindowMode {
    private final WinDef.HWND handle;
    private Boolean interactive;
    OverlayWindowMode(long window) {
        handle=new WinDef.HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
    }
    boolean update(boolean interactive) {
        if(this.interactive!=null&&this.interactive==interactive)return false;
        // ULW and SetLayeredWindowAttributes must not be mixed. GLFW's opacity
        // and passthrough APIs invoke that function on Windows, so set only the
        // input style here, retaining WS_EX_LAYERED in both modes.
        int style=User32.INSTANCE.GetWindowLong(handle,-20)|0x80000;
        style=interactive?style&~(0x20|0x08000000):style|0x20|0x08000000;
        Native.setLastError(0);
        if(User32.INSTANCE.SetWindowLong(handle,-20,style)==0&&Native.getLastError()!=0)
            throw new IllegalStateException("Overlay input style update failed win32="+Native.getLastError());
        this.interactive=interactive;
        return true;
    }
    record State(int style,boolean attributes,int alpha,int flags) {}
    State state() {
        var key=new IntByReference();var alpha=new ByteByReference();var flags=new IntByReference();
        boolean valid=User32.INSTANCE.GetLayeredWindowAttributes(handle,key,alpha,flags);
        return new State(User32.INSTANCE.GetWindowLong(handle,-20),valid,alpha.getValue()&255,flags.getValue());
    }
}
