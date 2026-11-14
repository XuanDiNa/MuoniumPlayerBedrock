package com.muoniumplayer.compat.lwjgl2;
import org.lwjgl.glfw.GLFW;
public final class Mouse {
    private static int wheel;
    private static double rawX, rawY;
    public static boolean isButtonDown(int button) {
        return Display.getWindowHandle() != 0 && button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
                && GLFW.glfwGetMouseButton(Display.getWindowHandle(), button) == GLFW.GLFW_PRESS;
    }
    public static void position(double x, double y) { rawX = x; rawY = y; }
    public static int getX() { return (int) rawX; }
    public static int getY() { return (int) (Display.getHeight() - rawY - 1); }
    public static synchronized int getDWheel() { int value = wheel; wheel = 0; return value; }
    public static synchronized void pushScrollNotches(double value) { wheel += (int) Math.round(value * 120); }
}
