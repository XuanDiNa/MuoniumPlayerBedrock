package com.muoniumplayer.render.skija;

import com.muoniumplayer.bedrock.BedrockClient;
import io.github.humbleui.skija.*;
import org.lwjgl.opengl.GL11;
import java.nio.file.Files;
import java.nio.file.Path;

/** Own GL context boundary. Does not assume Bedrock provides an OpenGL FBO. */
public final class SkijaHost {
    private static DirectContext context;
    private static BackendRenderTarget target;
    private static Surface surface;
    private static Canvas canvas;
    private static int width, height, baseSave,framebuffer;
    private static String failure;
    private static Runnable after;
    public static Canvas beginFrame() {
        return beginFrame(0xFF101015);
    }
    /** Stage-only frames must preserve alpha for composition over the game. */
    public static Canvas beginFrame(int clearColor) {
        if (canvas != null) throw new IllegalStateException("Nested Skija host frame");
        var client = BedrockClient.getInstance();
        if (!client.isSameThread()) throw new IllegalStateException("Skija render thread required");
        // Java MC normally drives this from its HUD event before NCMScreen.
        // The owned Bedrock host has no MC HUD event: without this, every
        // original fadeIn cover stays at alpha zero even after a good upload.
        com.muoniumplayer.core.rendering.animation.Interpolations.calcFrameDelta();
        var win = client.getWindow();
        try {
            if (context == null) context = DirectContext.makeGL();
            if (surface == null || width != win.getWidth() || height != win.getHeight()) {
                if (surface != null) surface.close();
                if (target != null) target.close();
                width = win.getWidth(); height = win.getHeight();
                target = BackendRenderTarget.makeGL(width, height, 0, 8, framebuffer, FramebufferFormat.GR_GL_RGBA8);
                surface = Surface.makeFromBackendRenderTarget(context, target, SurfaceOrigin.BOTTOM_LEFT,
                        ColorType.RGBA_8888, ColorSpace.getSRGB());
                if (surface == null) throw new IllegalStateException("Skija surface unavailable");
            }
            context.resetGLAll();
            canvas = surface.getCanvas(); canvas.clear(clearColor);
            baseSave = canvas.save(); canvas.scale((float) guiScale(), (float) guiScale());
            SkijaGfx.beginFrame(); failure = null;
            return canvas;
        } catch (Throwable e) {
            failure = e.toString(); canvas = null; throw e;
        }
    }
    public static void endFrame() {
        if (canvas == null) return;
        try {
            if (after != null) { Runnable draw = after; after = null; draw.run(); }
            canvas.restoreToCount(baseSave); context.flush();
        } finally { SkijaGfx.endFrame(); canvas = null; }
    }
    public static void compositeFrame() { if (context != null) context.flush(); }
    /** Select the owned offscreen FBO; release wrappers before its storage dies. */
    public static void renderTarget(int next) {
        if(canvas!=null)throw new IllegalStateException("Cannot change target inside a frame");
        if(framebuffer==next)return;
        if(surface!=null){surface.close();surface=null;}
        if(target!=null){target.close();target=null;}
        framebuffer=next;
    }
    public static boolean deferPostCompositeOverlay(Runnable draw) {
        if (canvas == null || after != null) return false;
        after = draw; return true;
    }
    public static Canvas canvas() { return canvas; }
    /** Scoped lyric picture recording; the caller restores before ending a row. */
    public static Canvas redirectCanvas(Canvas next) {
        if(canvas==null||next==null||!BedrockClient.getInstance().isSameThread())throw new IllegalStateException("Canvas redirect outside render frame");
        Canvas previous=canvas;canvas=next;return previous;
    }
    public static DirectContext context() { return context; }
    public static boolean inFrame() { return canvas != null; }
    public static boolean isDisabled() { return failure != null; }
    public static boolean isSuspended() { return failure != null; }
    public static boolean isAvailable() { return failure == null; }
    public static String suspendReason() { return failure; }
    public static SkijaNatives.Status nativeStatus() { return SkijaNatives.status(); }
    public static int guiWidth() { return BedrockClient.getInstance().getWindow().getGuiScaledWidth(); }
    public static int guiHeight() { return BedrockClient.getInstance().getWindow().getGuiScaledHeight(); }
    public static double guiScale() { return BedrockClient.getInstance().getWindow().getGuiScale(); }
    public static void savePng(Path file) throws java.io.IOException {
        savePng(file, false);
    }
    public static void savePng(Path file, boolean preserveAlpha) throws java.io.IOException {
        if (surface == null) throw new IllegalStateException("No frame available");
        context.flush();
        // A default-window framebuffer is not texture backed. Read the explicit
        // capture once; never use this CPU path as the normal UI compositor.
        GL11.glFinish();
        var rgba = org.lwjgl.BufferUtils.createByteBuffer(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("GL capture error " + error);
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int at = (y * width + x) * 4;
            int alpha = preserveAlpha ? rgba.get(at + 3) & 255 : 255;
            // GL/Skia framebuffer colors are premultiplied. PNG stores straight
            // alpha; retain edge colors rather than darkening them a second time.
            int red = rgba.get(at) & 255, green = rgba.get(at + 1) & 255, blue = rgba.get(at + 2) & 255;
            if (preserveAlpha && alpha > 0 && alpha < 255) {
                red = Math.min(255, (red * 255 + alpha / 2) / alpha);
                green = Math.min(255, (green * 255 + alpha / 2) / alpha);
                blue = Math.min(255, (blue * 255 + alpha / 2) / alpha);
            }
            int color = alpha << 24 | red << 16 | green << 8 | blue;
            image.setRGB(x, height - y - 1, color);
        }
        if (!javax.imageio.ImageIO.write(image, "PNG", file.toFile())) throw new java.io.IOException("No PNG encoder");
    }
    public static void shutdown() {
        if (canvas != null) endFrame();
        com.muoniumplayer.bedrock.CapsuleIcons.close();
        if (surface != null) { surface.close(); surface = null; }
        if (target != null) { target.close(); target = null; }
        if (context != null) { context.close(); context = null; }
    }
}
