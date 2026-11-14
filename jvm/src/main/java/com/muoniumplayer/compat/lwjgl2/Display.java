package com.muoniumplayer.compat.lwjgl2;
import com.muoniumplayer.bedrock.BedrockClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
public final class Display {
    public static long getWindowHandle() { return BedrockClient.getInstance().getWindow().handle(); }
    public static long getHwnd() { return getWindowHandle() == 0 ? 0 : GLFWNativeWin32.glfwGetWin32Window(getWindowHandle()); }
    public static int getWidth() { return BedrockClient.getInstance().getWindow().getWidth(); }
    public static int getHeight() { return BedrockClient.getInstance().getWindow().getHeight(); }
    public static boolean isVisible() { return getWindowHandle() != 0 && GLFW.glfwGetWindowAttrib(getWindowHandle(), GLFW.GLFW_VISIBLE) != 0; }
}
